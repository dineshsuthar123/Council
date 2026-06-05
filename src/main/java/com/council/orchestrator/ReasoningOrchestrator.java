package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.common.CouncilConstants;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.events.PipelineEventBroadcaster;
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
import com.council.synthesizer.SynthesizerEngine;
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
    private static final long DEFAULT_DRAFT_TIMEOUT_SECONDS = 90L;
    private static final long DEFAULT_CRITIC_TIMEOUT_SECONDS = 120L;
    private static final long DEFAULT_VERIFIER_TIMEOUT_SECONDS = 30L;
    private static final long DEFAULT_SYNTHESIS_TIMEOUT_SECONDS = 60L;

    private final ProviderRegistry registry;
    private final CriticEngine criticEngine;
    private final VerifierEngine verifierEngine;
    private final SynthesizerEngine synthesizerEngine;
    private final DeterministicJudge judge;
    private final PromptClassifier promptClassifier;
    private final TraceService traceService;
    private final OrchestrationMetrics metrics;
    private final ProviderSelectionStrategy selectionStrategy;
    private final ProviderConcurrencyLimiter concurrencyLimiter;
    private final PipelineEventBroadcaster eventBroadcaster;
    private final long draftTimeoutSeconds;
    private final long criticTimeoutSeconds;
    private final long verifierTimeoutSeconds;
    private final long synthesisTimeoutSeconds;

    public ReasoningOrchestrator(ProviderRegistry registry,
                                  CriticEngine criticEngine,
                                  VerifierEngine verifierEngine,
                                  SynthesizerEngine synthesizerEngine,
                                  DeterministicJudge judge,
                                  PromptClassifier promptClassifier,
                                  TraceService traceService,
                                  OrchestrationMetrics metrics,
                                  CouncilProperties properties,
                                  ProviderSelectionStrategy selectionStrategy,
                                  ProviderConcurrencyLimiter concurrencyLimiter,
                                  PipelineEventBroadcaster eventBroadcaster) {
        this.registry = registry;
        this.criticEngine = criticEngine;
        this.verifierEngine = verifierEngine;
        this.synthesizerEngine = synthesizerEngine;
        this.judge = judge;
        this.promptClassifier = promptClassifier;
        this.traceService = traceService;
        this.metrics = metrics;
        this.selectionStrategy = selectionStrategy;
        this.concurrencyLimiter = concurrencyLimiter;
        this.eventBroadcaster = eventBroadcaster;
        CouncilProperties.OrchestratorConfig orchestratorConfig = properties.getOrchestrator();
        this.draftTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getDraftTimeoutSeconds(), DEFAULT_DRAFT_TIMEOUT_SECONDS);
        this.criticTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getCriticTimeoutSeconds(), DEFAULT_CRITIC_TIMEOUT_SECONDS);
        this.verifierTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getVerifierTimeoutSeconds(), DEFAULT_VERIFIER_TIMEOUT_SECONDS);
        this.synthesisTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getSynthesisTimeoutSeconds(), DEFAULT_SYNTHESIS_TIMEOUT_SECONDS);
    }

    /**
     * Execute the full reasoning pipeline for the given user query.
     *
     * @return a {@link FinalResponse} (never null — graceful errors on total failure)
     */
    public FinalResponse reason(String userQuery) {
        String traceId = UUID.randomUUID().toString();
        return reason(userQuery, traceId);
    }

    /**
     * Execute the full reasoning pipeline using a caller-provided trace ID.
     * This is used by the async run API so clients can subscribe to events
     * before the final response exists.
     */
    public FinalResponse reason(String userQuery, String traceId) {
        long startTime = System.currentTimeMillis();
        MDC.put(CouncilConstants.MDC_TRACE_ID, traceId);
        metrics.recordRequest();

        log.info("[orchestrator] Starting reasoning pipeline, traceId={}", traceId);
        emit(traceId, "START", "running", "Reasoning pipeline started", startTime,
                Map.of("queryLength", userQuery == null ? 0 : userQuery.length()));

        try {
            // 0. Classify the prompt
            TaskType taskType = promptClassifier.classify(userQuery);
            log.info("[orchestrator] Prompt classified as: {}", taskType);
            emit(traceId, "CLASSIFY", "done", "Prompt classified as " + taskType, startTime,
                    Map.of("taskType", taskType.name()));

            // 1. Select providers (routing-aware or legacy), with task type
            List<LlmAdapter> providers = selectDraftProviders(traceId, taskType);
            if (providers.isEmpty()) {
                log.warn("[orchestrator] No providers available");
                FinalResponse response = errorResponse(traceId, "No LLM providers are currently available");
                emitFailure(traceId, "ERROR", response.judgeReason(), startTime, response);
                return response;
            }
            log.info("[orchestrator] {} providers selected: {}", providers.size(),
                    providers.stream().map(LlmAdapter::providerName).toList());
            emit(traceId, "ROUTE", "done", providers.size() + " draft providers selected", startTime,
                    Map.of("providers", providers.stream().map(LlmAdapter::providerName).toList(),
                            "routingEnabled", registry.isRoutingEnabled()));

            // 2. Draft phase
            emit(traceId, "DRAFT", "running", "Draft phase started", startTime,
                    Map.of("providerCount", providers.size()));
            DraftRequest draftRequest = DraftRequest.of(traceId, userQuery);
            List<DraftResult> allDrafts = runDraftPhase(providers, draftRequest);
            List<DraftResult> traceDrafts = new ArrayList<>(allDrafts);

            List<DraftResult> successfulDrafts = allDrafts.stream()
                    .filter(DraftResult::isSuccess).toList();
            List<String> failedProviders = allDrafts.stream()
                    .filter(d -> !d.isSuccess())
                    .map(DraftResult::provider).toList();

            log.info("[orchestrator] Drafts: {} successful, {} failed",
                    successfulDrafts.size(), failedProviders.size());
            emit(traceId, "DRAFT", "done", "Draft phase completed", startTime,
                    Map.of("successfulDrafts", successfulDrafts.size(),
                            "failedDrafts", failedProviders.size(),
                            "failedProviders", failedProviders));

            if (successfulDrafts.isEmpty()) {
                String msg = "All providers failed: " + failedProviders;
                log.error("[orchestrator] {}", msg);
                persistTrace(traceId, userQuery, allDrafts, null, null, null, startTime);
                FinalResponse response = errorResponse(traceId, msg, failedProviders);
                emitFailure(traceId, "ERROR", msg, startTime, response);
                return response;
            }

            // 3. Review phases (Critic + Verifier in parallel)
            emit(traceId, "REVIEW", "running", "Critic and verifier phases started", startTime,
                    Map.of("drafts", successfulDrafts.size()));
            ReviewPhaseResult review = runReviewPhases(traceId, userQuery, successfulDrafts);
            CriticResult criticResult = review.criticResult();
            VerifierBatchResult verifierBatchResult = review.verifierBatchResult();
            boolean enforceConstraintVerifier = shouldEnforceConstraintVerifier(userQuery, taskType);
            VerifierBatchResult effectiveVerifierBatchResult = enforceConstraintVerifier
                    ? verifierBatchResult
                    : VerifierBatchResult.passedFor(successfulDrafts);
            if (!enforceConstraintVerifier) {
                log.info("[orchestrator] Verifier completed in advisory mode for taskType={}", taskType);
            }
            emit(traceId, "REVIEW", "done", "Review phases completed", startTime,
                    Map.of("criticSuccess", criticResult != null && criticResult.isSuccess(),
                            "verifierMode", enforceConstraintVerifier ? "enforced" : "advisory"));

            // 3a. Failure handler: constraint/verifier authority gates all downstream phases.
            DraftFilterResult filteredInitial = filterConstraintValidDrafts(
                    successfulDrafts, effectiveVerifierBatchResult);
            List<DraftResult> constraintValidDrafts = filteredInitial.validDrafts();
            List<String> failedAndRejected = mergeProviderLists(failedProviders, filteredInitial.invalidProviders());

            if (constraintValidDrafts.isEmpty()) {
                log.warn("[orchestrator] No valid drafts after verifier/constraint filtering");
                FinalResponse failure = constraintFailureResponse(traceId, failedAndRejected);
                persistTrace(traceId, userQuery, allDrafts, criticResult, null, failure, startTime);
                emitFailure(traceId, "ERROR", failure.message(), startTime, failure);
                return failure;
            }
            emit(traceId, "VERIFY", "done", "Constraint filtering completed", startTime,
                    Map.of("validDrafts", constraintValidDrafts.size(),
                            "rejectedProviders", filteredInitial.invalidProviders(),
                            "verifierMode", enforceConstraintVerifier ? "enforced" : "advisory"));

            // 4. Judge phase (task-aware + verifier guillotine)
            emit(traceId, "JUDGE", "running", "Judge phase started", startTime,
                    Map.of("candidateDrafts", constraintValidDrafts.size()));
            JudgeResult judgeResult = runJudgePhase(
                    constraintValidDrafts, criticResult, effectiveVerifierBatchResult, taskType);
            emit(traceId, "JUDGE", "done", "Judge selected " + judgeResult.winnerProvider(), startTime,
                    Map.of("winnerProvider", judgeResult.winnerProvider(),
                            "winnerScore", judgeResult.winnerScore()));

            // 5. Premium escalation (only when routing is enabled)
            List<DraftResult> finalDrafts = constraintValidDrafts;
            List<String> finalFailed = failedAndRejected;
            JudgeResult finalJudge = judgeResult;
            CriticResult finalCritic = criticResult;
            VerifierBatchResult finalVerifier = effectiveVerifierBatchResult;

            if (registry.isRoutingEnabled()) {
                List<LlmAdapter> escalationProviders = selectEscalationProviders(
                        traceId, judgeResult.winnerScore(),
                        criticResult.contradictionSeverity(),
                    constraintValidDrafts.stream().map(DraftResult::provider).toList());

                if (!escalationProviders.isEmpty()) {
                    log.info("[orchestrator] Escalation triggered with {} premium providers",
                            escalationProviders.size());
                    metrics.recordEscalation();
                    emit(traceId, "ESCALATE", "running", "Premium escalation triggered", startTime,
                            Map.of("providers", escalationProviders.stream().map(LlmAdapter::providerName).toList()));

                    List<DraftResult> escalationDrafts = runDraftPhase(escalationProviders, draftRequest);
                    traceDrafts.addAll(escalationDrafts);
                    List<DraftResult> successfulEscalation = escalationDrafts.stream()
                            .filter(DraftResult::isSuccess).toList();

                    List<String> escalationFailed = escalationDrafts.stream()
                            .filter(d -> !d.isSuccess())
                            .map(DraftResult::provider).toList();
                    if (!escalationFailed.isEmpty()) {
                        finalFailed = mergeProviderLists(finalFailed, escalationFailed);
                    }

                    if (!successfulEscalation.isEmpty()) {
                        // Combine and re-judge
                        List<DraftResult> combined = new ArrayList<>(constraintValidDrafts);
                        combined.addAll(successfulEscalation);

                        ReviewPhaseResult escalatedReview = runReviewPhases(traceId, userQuery, combined);
                        finalCritic = escalatedReview.criticResult();
                        VerifierBatchResult effectiveEscalatedVerifier = enforceConstraintVerifier
                                ? escalatedReview.verifierBatchResult()
                                : VerifierBatchResult.passedFor(combined);
                        finalVerifier = effectiveEscalatedVerifier;

                        DraftFilterResult filteredEscalated =
                                filterConstraintValidDrafts(combined, effectiveEscalatedVerifier);
                        finalFailed = mergeProviderLists(finalFailed, filteredEscalated.invalidProviders());

                        if (filteredEscalated.validDrafts().isEmpty()) {
                            log.warn("[orchestrator] No valid drafts after escalation verifier/constraint filtering");
                            FinalResponse failure = constraintFailureResponse(traceId, finalFailed);
                            persistTrace(traceId, userQuery, traceDrafts, finalCritic, null, failure, startTime);
                            emitFailure(traceId, "ERROR", failure.message(), startTime, failure);
                            return failure;
                        }

                        finalJudge = runJudgePhase(filteredEscalated.validDrafts(), finalCritic,
                                effectiveEscalatedVerifier, taskType);
                        finalDrafts = filteredEscalated.validDrafts();
                    }
                    emit(traceId, "ESCALATE", "done", "Premium escalation completed", startTime,
                            Map.of("successfulDrafts", successfulEscalation.size()));
                }
            }

            // 6. Build final response
            String winnerProvider = finalJudge.winnerProvider();
            DraftResult winner = finalDrafts.stream()
                    .filter(d -> d.provider().equals(winnerProvider))
                    .findFirst()
                    .orElse(finalDrafts.getFirst());

            SynthesisResult synthesisResult = runSynthesisPhase(new SynthesisRequest(
                    traceId,
                    userQuery,
                    finalDrafts,
                    finalVerifier,
                    finalCritic
            ));
            emit(traceId, "SYNTHESIS", synthesisResult != null && synthesisResult.isSuccess() ? "done" : "degraded",
                    synthesisResult != null && synthesisResult.isSuccess()
                            ? "Synthesis completed"
                            : "Synthesis unavailable; using judge winner", startTime,
                    Map.of("provider", synthesisResult == null ? "none" : synthesisResult.provider(),
                            "success", synthesisResult != null && synthesisResult.isSuccess()));

            String finalAnswer = winner.answer();
            double finalConfidence = winner.confidence();

            if (synthesisResult != null
                    && synthesisResult.isSuccess()
                    && synthesisResult.synthesizedAnswer() != null
                    && !synthesisResult.synthesizedAnswer().isBlank()) {
                finalAnswer = synthesisResult.synthesizedAnswer();
                if (synthesisResult.confidence() > 0.0) {
                    finalConfidence = synthesisResult.confidence();
                }
            } else {
                String synthesisError = synthesisResult == null
                        ? "null synthesis result"
                        : synthesisResult.errorMessage();
                log.warn("[orchestrator] Synthesis unavailable, falling back to winner draft answer: {}",
                        synthesisError);
            }

            FinalResponse response = new FinalResponse(
                    traceId,
                    finalAnswer,
                    finalJudge.reason(),
                    finalDrafts.stream().map(DraftResult::provider).toList(),
                    finalFailed,
                    finalConfidence
            );

            long totalLatency = System.currentTimeMillis() - startTime;
            metrics.recordTotalLatency(totalLatency);
            log.info("[orchestrator] Pipeline complete in {}ms, winner={}, taskType={}",
                    totalLatency, winner.provider(), taskType);

            // 7. Async trace persistence
            persistTrace(traceId, userQuery, traceDrafts, finalCritic, finalJudge, response, startTime);
            emit(traceId, "TRACE", "queued", "Trace persistence queued", startTime,
                    Map.of("totalDrafts", traceDrafts.size()));
            emit(traceId, "COMPLETE", "done", "Reasoning pipeline completed", startTime,
                    Map.of("response", response,
                            "winnerProvider", winner.provider(),
                            "taskType", taskType.name(),
                            "totalLatencyMs", totalLatency));

            return response;

        } catch (Exception e) {
            long totalLatency = System.currentTimeMillis() - startTime;
            log.error("[orchestrator] Unexpected error after {}ms: {}", totalLatency, e.getMessage(), e);
            FinalResponse response = errorResponse(traceId, "Internal orchestration error: " + e.getMessage());
            emitFailure(traceId, "ERROR", response.judgeReason(), startTime, response);
            return response;
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
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Callable<DraftResult>> tasks = providers.stream()
                    .<Callable<DraftResult>>map(provider -> () -> executeDraftProvider(provider, request))
                    .toList();

            List<Future<DraftResult>> futures =
                    executor.invokeAll(tasks, draftTimeoutSeconds, TimeUnit.SECONDS);
            List<DraftResult> results = new ArrayList<>(futures.size());

            for (int i = 0; i < futures.size(); i++) {
                results.add(resolveDraftFuture(providers.get(i), futures.get(i)));
            }

            return List.copyOf(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Draft phase interrupted");
            return providers.stream()
                    .map(provider -> DraftResult.failure(provider.providerName(), provider.modelName(),
                            "Draft generation interrupted", 0))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private DraftResult executeDraftProvider(LlmAdapter provider, DraftRequest request) {
        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
        String name = provider.providerName();
        boolean acquired = concurrencyLimiter.tryAcquire(name);
        if (!acquired) {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
            log.warn("[orchestrator] Provider '{}' at max concurrency, skipping", name);
            metrics.recordConcurrencyRejection(name);
            return DraftResult.failure(name, provider.modelName(), "Max concurrency reached", 0);
        }
        try {
            return provider.generateDraft(request);
        } finally {
            concurrencyLimiter.release(name);
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
        }
    }

    private DraftResult resolveDraftFuture(LlmAdapter provider, Future<DraftResult> future) {
        String providerName = provider.providerName();
        String modelName = provider.modelName();

        if (future.isCancelled()) {
            log.warn("[orchestrator] Draft provider '{}' timed out after {}s",
                    providerName, draftTimeoutSeconds);
            return DraftResult.failure(providerName, modelName, "Draft generation timed out", 0);
        }

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Draft provider '{}' interrupted", providerName);
            return DraftResult.failure(providerName, modelName, "Draft generation interrupted", 0);
        } catch (ExecutionException e) {
            String message = rootMessage(e);
            log.warn("[orchestrator] Draft provider '{}' failed: {}", providerName, message);
            return DraftResult.failure(providerName, modelName,
                    "Draft generation failed: " + message, 0);
        }
    }

    /* ── Phase 2: Review (Critic + Verifier) ───────────────────── */

    private ReviewPhaseResult runReviewPhases(String traceId,
                                              String userQuery,
                                              List<DraftResult> successfulDrafts) {
        CriticRequest criticRequest = new CriticRequest(traceId, userQuery, successfulDrafts);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long startNanos = System.nanoTime();
            Future<CriticResult> criticFuture = executor.submit(() -> runCriticPhase(criticRequest));
            Future<VerifierBatchResult> verifierFuture =
                    executor.submit(() -> runVerifierPhase(traceId, userQuery, successfulDrafts));

            CriticResult critic = resolveCriticFuture(
                    criticFuture, deadlineNanos(startNanos, criticTimeoutSeconds));
            VerifierBatchResult verifier = resolveVerifierFuture(
                    verifierFuture, deadlineNanos(startNanos, verifierTimeoutSeconds), successfulDrafts);

            return new ReviewPhaseResult(critic, verifier);
        } finally {
            executor.shutdownNow();
        }
    }

    private CriticResult resolveCriticFuture(Future<CriticResult> future, long deadlineNanos) {
        try {
            return future.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[orchestrator] Critic timed out after {}s", criticTimeoutSeconds);
            return CriticResult.failure("unknown", "unknown", "Critic timed out", 0);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Critic interrupted");
            return CriticResult.failure("unknown", "unknown", "Critic interrupted", 0);
        } catch (ExecutionException e) {
            String message = rootMessage(e);
            log.warn("[orchestrator] Critic execution failed: {}", message);
            return CriticResult.failure("unknown", "unknown", message, 0);
        }
    }

    private VerifierBatchResult resolveVerifierFuture(Future<VerifierBatchResult> future,
                                                      long deadlineNanos,
                                                      List<DraftResult> successfulDrafts) {
        try {
            return future.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[orchestrator] Verifier phase timed out after {}s", verifierTimeoutSeconds);
            return VerifierBatchResult.passedFor(successfulDrafts);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Verifier phase interrupted");
            return VerifierBatchResult.passedFor(successfulDrafts);
        } catch (ExecutionException e) {
            log.warn("[orchestrator] Verifier phase failed: {}", rootMessage(e));
            return VerifierBatchResult.passedFor(successfulDrafts);
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

    VerifierBatchResult runVerifierPhase(String traceId,
                                         String userQuery,
                                         List<DraftResult> successfulDrafts) {
        if (successfulDrafts == null || successfulDrafts.isEmpty()) {
            return VerifierBatchResult.success(Map.of());
        }

        MDC.put(CouncilConstants.MDC_TRACE_ID, traceId);
        try {
            return verifierEngine.verify(traceId, userQuery, successfulDrafts);
        } catch (Exception e) {
            log.warn("[orchestrator] Verifier batch execution failed: {}", e.getMessage());
            return VerifierBatchResult.passedFor(successfulDrafts);
        } finally {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
        }
    }

    SynthesisResult runSynthesisPhase(SynthesisRequest request) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<SynthesisResult> future = executor.submit(() -> executeSynthesisPhase(request));
        try {
            return future.get(synthesisTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[orchestrator] Synthesis timed out after {}s", synthesisTimeoutSeconds);
            return SynthesisResult.failure("none", "none", "Synthesis timed out", 0);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Synthesis interrupted");
            return SynthesisResult.failure("none", "none", "Synthesis interrupted", 0);
        } catch (ExecutionException e) {
            String message = rootMessage(e);
            log.warn("[orchestrator] Synthesis execution failed: {}", message);
            return SynthesisResult.failure("none", "none", message, 0);
        } finally {
            executor.shutdownNow();
        }
    }

    private SynthesisResult executeSynthesisPhase(SynthesisRequest request) {
        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
        try {
            return synthesizerEngine.synthesize(request);
        } catch (Exception e) {
            log.warn("[orchestrator] Synthesis execution failed: {}", e.getMessage());
            return SynthesisResult.failure("none", "none", e.getMessage(), 0);
        } finally {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
        }
    }

    /* ── Phase 3: Judge scoring (task-aware + verifier) ─────────── */

    JudgeResult runJudgePhase(List<DraftResult> drafts,
                              CriticResult criticResult,
                              VerifierBatchResult verifierBatchResult,
                              TaskType taskType) {
        JudgeResult judgeResult = judge.evaluate(drafts, criticResult, verifierBatchResult, taskType);
        if (judgeResult.winnerProvider() != null) {
            metrics.recordJudgeDecision(judgeResult.winnerProvider());
        }
        return judgeResult;
    }

    JudgeResult runJudgePhase(List<DraftResult> drafts, CriticResult criticResult, TaskType taskType) {
        return runJudgePhase(drafts, criticResult, VerifierBatchResult.passedFor(drafts), taskType);
    }

    /** Legacy overload for backward compatibility. */
    JudgeResult runJudgePhase(List<DraftResult> drafts, CriticResult criticResult) {
        return runJudgePhase(drafts, criticResult, VerifierBatchResult.passedFor(drafts), TaskType.GENERAL_REASONING);
    }

    /* ── helpers ────────────────────────────────────────────────── */

    private long positiveOrDefault(int value, long defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private long deadlineNanos(long startNanos, long timeoutSeconds) {
        return startNanos + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    private long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 1L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = unwrap(throwable);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private boolean shouldEnforceConstraintVerifier(String userQuery, TaskType taskType) {
        if (taskType == TaskType.SYSTEM_DESIGN) {
            return true;
        }
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }

        String lower = userQuery.toLowerCase(Locale.ROOT);
        if (!lower.contains("design")) {
            return false;
        }

        return containsAny(lower,
                "architecture",
                "capacity",
                "database",
                "event pipeline",
                "kafka",
                "ledger",
                "message queue",
                "partition",
                "payment",
                "queue",
                "replica",
                "scale",
                "shard",
                "system",
                "throughput",
                "tps");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record ReviewPhaseResult(CriticResult criticResult,
                                     VerifierBatchResult verifierBatchResult) {}

    private record DraftFilterResult(List<DraftResult> validDrafts,
                                     List<String> invalidProviders) {}

    private DraftFilterResult filterConstraintValidDrafts(List<DraftResult> drafts,
                                                          VerifierBatchResult verifierBatchResult) {
        if (drafts == null || drafts.isEmpty()) {
            return new DraftFilterResult(List.of(), List.of());
        }

        List<DraftResult> validDrafts = new ArrayList<>();
        List<String> invalidProviders = new ArrayList<>();

        for (DraftResult draft : drafts) {
            VerifierVerdict verdict = verifierBatchResult == null
                    ? VerifierVerdict.passed()
                    : verifierBatchResult.verdictForProvider(draft.provider());

            if (verdict != null && verdict.isDisqualified()) {
                invalidProviders.add(draft.provider());
            } else {
                validDrafts.add(draft);
            }
        }

        return new DraftFilterResult(List.copyOf(validDrafts), List.copyOf(invalidProviders));
    }

    private List<String> mergeProviderLists(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return List.copyOf(merged);
    }

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

    private void emit(String traceId,
                      String phase,
                      String status,
                      String message,
                      long startTime,
                      Map<String, Object> details) {
        if (eventBroadcaster == null) {
            return;
        }
        eventBroadcaster.publish(traceId, phase, status, message,
                System.currentTimeMillis() - startTime, details);
    }

    private void emitFailure(String traceId,
                             String phase,
                             String message,
                             long startTime,
                             FinalResponse response) {
        emit(traceId, phase, "failed", message, startTime, Map.of("response", response));
    }

    private FinalResponse errorResponse(String traceId, String error) {
        return new FinalResponse(traceId, null, error, List.of(), List.of(), 0.0);
    }

    private FinalResponse errorResponse(String traceId, String error, List<String> failedProviders) {
        return new FinalResponse(traceId, null, error, List.of(), failedProviders, 0.0);
    }

    private FinalResponse constraintFailureResponse(String traceId, List<String> failedProviders) {
        String message = "All generated designs violate system constraints. Regeneration required.";
        return new FinalResponse(
                traceId,
                null,
                message,
                List.of(),
                failedProviders == null ? List.of() : failedProviders,
                0.0,
                "NO_VALID_DESIGN",
                message
        );
    }
}

