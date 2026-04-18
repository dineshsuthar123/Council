package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.common.CouncilConstants;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.judge.DeterministicJudge;
import com.council.judge.PromptClassifier;
import com.council.judge.TaskType;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.provider.routing.ProviderDescriptor;
import com.council.provider.routing.ProviderSelectionStrategy;
import com.council.trace.TraceService;
import com.council.verifier.VerifierEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Top-level orchestrator that drives the full reasoning pipeline.
 * <p>
 * When routing is enabled, provider selection is delegated to
 * {@link ProviderSelectionStrategy} with <b>task-aware routing</b>:
 * the prompt is classified and different providers / judge weights
 * are used depending on the task type.
 * <p>
 * Each phase is an independently testable method:
 * {@link #runDraftPhase}, {@link #runCriticPhase}, {@link #runJudgePhase}.
 */
@Service
public class ReasoningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReasoningOrchestrator.class);
    private static final long PHASE_TIMEOUT_SECONDS = 60L;

    private final ProviderRegistry registry;
    private final CriticEngine criticEngine;
    private final VerifierEngine verifierEngine;
    private final DeterministicJudge judge;
    private final PromptClassifier promptClassifier;
    private final TraceService traceService;
    private final OrchestrationMetrics metrics;
    private final ProviderSelectionStrategy selectionStrategy;
    private final ProviderConcurrencyLimiter concurrencyLimiter;

    public ReasoningOrchestrator(ProviderRegistry registry,
                                  CriticEngine criticEngine,
                                  VerifierEngine verifierEngine,
                                  DeterministicJudge judge,
                                  PromptClassifier promptClassifier,
                                  TraceService traceService,
                                  OrchestrationMetrics metrics,
                                  CouncilProperties properties,
                                  ProviderSelectionStrategy selectionStrategy,
                                  ProviderConcurrencyLimiter concurrencyLimiter) {
        this.registry = registry;
        this.criticEngine = criticEngine;
        this.verifierEngine = verifierEngine;
        this.judge = judge;
        this.promptClassifier = promptClassifier;
        this.traceService = traceService;
        this.metrics = metrics;
        this.selectionStrategy = selectionStrategy;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /**
     * Execute the full reasoning pipeline for the given user query.
     *
     * @return a {@link FinalResponse} (never null — graceful errors on total failure)
     */
    public FinalResponse reason(String userQuery) {
        String traceId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        MDC.put(CouncilConstants.MDC_TRACE_ID, traceId);
        metrics.recordRequest();

        log.info("[orchestrator] Starting reasoning pipeline, traceId={}", traceId);

        try {
            // 0. Classify the prompt
            TaskType taskType = promptClassifier.classify(userQuery);
            log.info("[orchestrator] Prompt classified as: {}", taskType);

            // 1. Select providers (routing-aware or legacy), with task type
            List<LlmAdapter> providers = selectDraftProviders(traceId, taskType);
            if (providers.isEmpty()) {
                log.warn("[orchestrator] No providers available");
                return errorResponse(traceId, "No LLM providers are currently available");
            }
            log.info("[orchestrator] {} providers selected: {}", providers.size(),
                    providers.stream().map(LlmAdapter::providerName).toList());

            // 2. Draft phase
            DraftRequest draftRequest = DraftRequest.of(traceId, userQuery);
            List<DraftResult> allDrafts = runDraftPhase(providers, draftRequest);

            List<DraftResult> successfulDrafts = allDrafts.stream()
                    .filter(DraftResult::isSuccess).toList();
            List<String> failedProviders = allDrafts.stream()
                    .filter(d -> !d.isSuccess())
                    .map(DraftResult::provider).toList();

            log.info("[orchestrator] Drafts: {} successful, {} failed",
                    successfulDrafts.size(), failedProviders.size());

            if (successfulDrafts.isEmpty()) {
                String msg = "All providers failed: " + failedProviders;
                log.error("[orchestrator] {}", msg);
                persistTrace(traceId, userQuery, allDrafts, null, null, null, startTime);
                return errorResponse(traceId, msg, failedProviders);
            }

            // 3. Review phases (Critic + Verifier in parallel)
            ReviewPhaseResult review = runReviewPhases(traceId, userQuery, successfulDrafts);
            CriticResult criticResult = review.criticResult();
            Map<String, VerifierResult> verifierResults = review.verifierResults();

            // 4. Judge phase (task-aware + verifier guillotine)
            JudgeResult judgeResult = runJudgePhase(successfulDrafts, criticResult, verifierResults, taskType);

            // 5. Premium escalation (only when routing is enabled)
            List<DraftResult> finalDrafts = successfulDrafts;
            List<String> finalFailed = failedProviders;
            JudgeResult finalJudge = judgeResult;
            CriticResult finalCritic = criticResult;

            if (registry.isRoutingEnabled()) {
                List<LlmAdapter> escalationProviders = selectEscalationProviders(
                        traceId, judgeResult.winnerScore(),
                        criticResult.contradictionSeverity(),
                        successfulDrafts.stream().map(DraftResult::provider).toList());

                if (!escalationProviders.isEmpty()) {
                    log.info("[orchestrator] Escalation triggered with {} premium providers",
                            escalationProviders.size());
                    metrics.recordEscalation();

                    List<DraftResult> escalationDrafts = runDraftPhase(escalationProviders, draftRequest);
                    List<DraftResult> successfulEscalation = escalationDrafts.stream()
                            .filter(DraftResult::isSuccess).toList();

                    if (!successfulEscalation.isEmpty()) {
                        // Combine and re-judge
                        List<DraftResult> combined = new ArrayList<>(successfulDrafts);
                        combined.addAll(successfulEscalation);

                        ReviewPhaseResult escalatedReview = runReviewPhases(traceId, userQuery, combined);
                        finalCritic = escalatedReview.criticResult();
                        finalJudge = runJudgePhase(combined, finalCritic,
                            escalatedReview.verifierResults(), taskType);
                        finalDrafts = combined;
                    }

                    // Update failed providers with escalation failures
                    List<String> escalationFailed = escalationDrafts.stream()
                            .filter(d -> !d.isSuccess())
                            .map(DraftResult::provider).toList();
                    if (!escalationFailed.isEmpty()) {
                        List<String> allFailed = new ArrayList<>(failedProviders);
                        allFailed.addAll(escalationFailed);
                        finalFailed = allFailed;
                    }
                }
            }

            // 6. Build final response
            String winnerProvider = finalJudge.winnerProvider();
            DraftResult winner = finalDrafts.stream()
                    .filter(d -> d.provider().equals(winnerProvider))
                    .findFirst()
                    .orElse(finalDrafts.getFirst());

            FinalResponse response = new FinalResponse(
                    traceId,
                    winner.answer(),
                    finalJudge.reason(),
                    finalDrafts.stream().map(DraftResult::provider).toList(),
                    finalFailed,
                    winner.confidence()
            );

            long totalLatency = System.currentTimeMillis() - startTime;
            metrics.recordTotalLatency(totalLatency);
            log.info("[orchestrator] Pipeline complete in {}ms, winner={}, taskType={}",
                    totalLatency, winner.provider(), taskType);

            // 7. Async trace persistence
            persistTrace(traceId, userQuery, allDrafts, finalCritic, finalJudge, response, startTime);

            return response;

        } catch (Exception e) {
            long totalLatency = System.currentTimeMillis() - startTime;
            log.error("[orchestrator] Unexpected error after {}ms: {}", totalLatency, e.getMessage(), e);
            return errorResponse(traceId, "Internal orchestration error: " + e.getMessage());
        } finally {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
        }
    }

    /* ── Provider selection (routing-aware) ─────────────────────────── */

    private List<LlmAdapter> selectDraftProviders(String traceId, TaskType taskType) {
        if (registry.isRoutingEnabled()) {
            List<ProviderDescriptor> descriptors = registry.buildDescriptors();
            return selectionStrategy.selectDraftProviders(
                    descriptors, registry.getAllAdapters(), traceId, taskType);
        }
        return registry.getAvailableDraftProviders();
    }

    private List<LlmAdapter> selectEscalationProviders(String traceId,
                                                       double bestConfidence,
                                                       double contradictionSeverity,
                                                       List<String> alreadyUsed) {
        List<ProviderDescriptor> descriptors = registry.buildDescriptors();
        return selectionStrategy.selectEscalationProviders(
                descriptors, registry.getAllAdapters(),
                bestConfidence, contradictionSeverity, alreadyUsed, traceId);
    }

    /* ── Phase 1: Draft generation (parallel via virtual threads) ── */

    List<DraftResult> runDraftPhase(List<LlmAdapter> providers, DraftRequest request) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<DraftResult>> futures = providers.stream()
                    .map(provider -> CompletableFuture.supplyAsync(() -> {
                        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
                        String name = provider.providerName();
                        boolean acquired = concurrencyLimiter.tryAcquire(name);
                        if (!acquired) {
                            log.warn("[orchestrator] Provider '{}' at max concurrency, skipping", name);
                            metrics.recordConcurrencyRejection(name);
                            return DraftResult.failure(name, provider.modelName(),
                                    "Max concurrency reached", 0);
                        }
                        try {
                            return provider.generateDraft(request);
                        } finally {
                            concurrencyLimiter.release(name);
                            MDC.remove(CouncilConstants.MDC_TRACE_ID);
                        }
                    }, executor)
                    .orTimeout(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        String providerName = provider.providerName();
                        String modelName = provider.modelName();
                        if (isTimeoutException(ex)) {
                            log.warn("[orchestrator] Draft provider '{}' timed out after {}s",
                                    providerName, PHASE_TIMEOUT_SECONDS);
                            return DraftResult.failure(providerName, modelName,
                                    "Draft generation timed out", 0);
                        }

                        String message = rootMessage(ex);
                        log.warn("[orchestrator] Draft provider '{}' failed: {}", providerName, message);
                        return DraftResult.failure(providerName, modelName,
                                "Draft generation failed: " + message, 0);
                    }))
                    .toList();

            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    /* ── Phase 2: Review (Critic + Verifier) ───────────────────── */

    private ReviewPhaseResult runReviewPhases(String traceId,
                                              String userQuery,
                                              List<DraftResult> successfulDrafts) {
        CriticRequest criticRequest = new CriticRequest(traceId, userQuery, successfulDrafts);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<CriticResult> criticFuture = CompletableFuture
                    .supplyAsync(() -> runCriticPhase(criticRequest), executor)
                    .orTimeout(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (isTimeoutException(ex)) {
                            log.warn("[orchestrator] Critic timed out after {}s", PHASE_TIMEOUT_SECONDS);
                            return CriticResult.failure("unknown", "unknown", "Critic timed out", 0);
                        }
                        String message = rootMessage(ex);
                        log.warn("[orchestrator] Critic execution failed: {}", message);
                        return CriticResult.failure("unknown", "unknown", message, 0);
                    });

            CompletableFuture<Map<String, VerifierResult>> verifierFuture = CompletableFuture
                    .supplyAsync(() -> runVerifierPhase(traceId, userQuery, successfulDrafts), executor)
                    .orTimeout(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (isTimeoutException(ex)) {
                            log.warn("[orchestrator] Verifier phase timed out after {}s", PHASE_TIMEOUT_SECONDS);
                        } else {
                            log.warn("[orchestrator] Verifier phase failed: {}", rootMessage(ex));
                        }
                        return Map.of();
                    });

            return new ReviewPhaseResult(criticFuture.join(), verifierFuture.join());
        }
    }

    CriticResult runCriticPhase(CriticRequest request) {
        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
        try {
            return criticEngine.critique(request);
        } catch (Exception e) {
            log.warn("[orchestrator] Critic execution failed: {}", e.getMessage());
            return CriticResult.failure("unknown", "unknown", e.getMessage(), 0);
        } finally {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
        }
    }

    Map<String, VerifierResult> runVerifierPhase(String traceId,
                                                 String userQuery,
                                                 List<DraftResult> successfulDrafts) {
        if (successfulDrafts == null || successfulDrafts.isEmpty()) {
            return Map.of();
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<AbstractMap.SimpleEntry<String, VerifierResult>>> futures = successfulDrafts.stream()
                    .map(draft -> CompletableFuture
                            .supplyAsync(() -> {
                                MDC.put(CouncilConstants.MDC_TRACE_ID, traceId);
                                try {
                                    VerifierRequest request = new VerifierRequest(traceId, userQuery, draft);
                                    VerifierResult result = verifierEngine.verifyDraft(request);
                                    return new AbstractMap.SimpleEntry<>(draft.provider(), result);
                                } finally {
                                    MDC.remove(CouncilConstants.MDC_TRACE_ID);
                                }
                            }, executor)
                            .orTimeout(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                if (isTimeoutException(ex)) {
                                    log.warn("[orchestrator] Verifier timed out for draft '{}' after {}s",
                                            draft.provider(), PHASE_TIMEOUT_SECONDS);
                                } else {
                                    log.warn("[orchestrator] Verifier failed for draft '{}': {}",
                                            draft.provider(), rootMessage(ex));
                                }
                                return new AbstractMap.SimpleEntry<>(draft.provider(), null);
                            }))
                    .toList();

            Map<String, VerifierResult> results = new LinkedHashMap<>();
            for (CompletableFuture<AbstractMap.SimpleEntry<String, VerifierResult>> future : futures) {
                AbstractMap.SimpleEntry<String, VerifierResult> entry = future.join();
                if (entry.getValue() != null) {
                    results.put(entry.getKey(), entry.getValue());
                }
            }
            return results;
        }
    }

    /* ── Phase 3: Judge scoring (task-aware + verifier) ─────────── */

    JudgeResult runJudgePhase(List<DraftResult> drafts,
                              CriticResult criticResult,
                              Map<String, VerifierResult> verifierResults,
                              TaskType taskType) {
        JudgeResult judgeResult = judge.evaluate(drafts, criticResult, verifierResults, taskType);
        if (judgeResult.winnerProvider() != null) {
            metrics.recordJudgeDecision(judgeResult.winnerProvider());
        }
        return judgeResult;
    }

    JudgeResult runJudgePhase(List<DraftResult> drafts, CriticResult criticResult, TaskType taskType) {
        return runJudgePhase(drafts, criticResult, Map.of(), taskType);
    }

    /** Legacy overload for backward compatibility. */
    JudgeResult runJudgePhase(List<DraftResult> drafts, CriticResult criticResult) {
        return runJudgePhase(drafts, criticResult, Map.of(), TaskType.GENERAL_REASONING);
    }

    /* ── helpers ────────────────────────────────────────────────── */

    private boolean isTimeoutException(Throwable throwable) {
        return unwrap(throwable) instanceof TimeoutException;
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = unwrap(throwable);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record ReviewPhaseResult(CriticResult criticResult,
                                     Map<String, VerifierResult> verifierResults) {}

    private void persistTrace(String traceId, String userQuery,
                              List<DraftResult> allDrafts,
                              CriticResult criticResult,
                              JudgeResult judgeResult,
                              FinalResponse response,
                              long startTime) {
        long totalLatency = System.currentTimeMillis() - startTime;
        traceService.persistAsync(traceId, userQuery, allDrafts, criticResult,
                judgeResult, response, totalLatency);
    }

    private FinalResponse errorResponse(String traceId, String error) {
        return new FinalResponse(traceId, null, error, List.of(), List.of(), 0.0);
    }

    private FinalResponse errorResponse(String traceId, String error, List<String> failedProviders) {
        return new FinalResponse(traceId, null, error, List.of(), failedProviders, 0.0);
    }
}

