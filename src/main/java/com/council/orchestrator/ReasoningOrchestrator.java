package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.common.CouncilConstants;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.judge.DeterministicJudge;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Top-level orchestrator that drives the full reasoning pipeline.
 * <p>
 * Each phase is an independently testable method:
 * {@link #runDraftPhase}, {@link #runCriticPhase}, {@link #runJudgePhase}.
 */
@Service
public class ReasoningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReasoningOrchestrator.class);

    private final ProviderRegistry registry;
    private final CriticEngine criticEngine;
    private final DeterministicJudge judge;
    private final TraceService traceService;
    private final OrchestrationMetrics metrics;
    private final int draftTimeoutSeconds;
    private final int criticTimeoutSeconds;

    public ReasoningOrchestrator(ProviderRegistry registry,
                                  CriticEngine criticEngine,
                                  DeterministicJudge judge,
                                  TraceService traceService,
                                  OrchestrationMetrics metrics,
                                  CouncilProperties properties) {
        this.registry = registry;
        this.criticEngine = criticEngine;
        this.judge = judge;
        this.traceService = traceService;
        this.metrics = metrics;
        this.draftTimeoutSeconds = properties.getOrchestrator().getDraftTimeoutSeconds();
        this.criticTimeoutSeconds = properties.getOrchestrator().getCriticTimeoutSeconds();
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
            // 1. Available providers
            List<LlmAdapter> providers = registry.getAvailableDraftProviders();
            if (providers.isEmpty()) {
                log.warn("[orchestrator] No providers available");
                return errorResponse(traceId, "No LLM providers are currently available");
            }
            log.info("[orchestrator] {} providers available: {}", providers.size(),
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
                return errorResponse(traceId, msg);
            }

            // 3. Critic phase
            CriticRequest criticRequest = new CriticRequest(traceId, userQuery, successfulDrafts);
            CriticResult criticResult = runCriticPhase(criticRequest);

            // 4. Judge phase
            JudgeResult judgeResult = runJudgePhase(successfulDrafts, criticResult);

            // 5. Build final response
            DraftResult winner = successfulDrafts.stream()
                    .filter(d -> d.provider().equals(judgeResult.winnerProvider()))
                    .findFirst()
                    .orElse(successfulDrafts.getFirst());

            FinalResponse response = new FinalResponse(
                    traceId,
                    winner.answer(),
                    judgeResult.reason(),
                    successfulDrafts.stream().map(DraftResult::provider).toList(),
                    failedProviders,
                    winner.confidence()
            );

            long totalLatency = System.currentTimeMillis() - startTime;
            metrics.recordTotalLatency(totalLatency);
            log.info("[orchestrator] Pipeline complete in {}ms, winner={}", totalLatency, winner.provider());

            // 6. Async trace persistence
            persistTrace(traceId, userQuery, allDrafts, criticResult, judgeResult, response, startTime);

            return response;

        } catch (Exception e) {
            long totalLatency = System.currentTimeMillis() - startTime;
            log.error("[orchestrator] Unexpected error after {}ms: {}", totalLatency, e.getMessage(), e);
            return errorResponse(traceId, "Internal orchestration error: " + e.getMessage());
        } finally {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
        }
    }

    /* ── Phase 1: Draft generation (parallel via virtual threads) ── */

    List<DraftResult> runDraftPhase(List<LlmAdapter> providers, DraftRequest request) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DraftResult>> futures = providers.stream()
                    .map(provider -> executor.submit(() -> {
                        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
                        try {
                            return provider.generateDraft(request);
                        } finally {
                            MDC.remove(CouncilConstants.MDC_TRACE_ID);
                        }
                    }))
                    .toList();

            return futures.stream()
                    .map(f -> collectDraftResult(f, draftTimeoutSeconds))
                    .toList();
        }
    }

    /* ── Phase 2: Critic review (timeout-wrapped) ──────────────── */

    CriticResult runCriticPhase(CriticRequest request) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CriticResult> future = executor.submit(() -> {
                MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
                try {
                    return criticEngine.critique(request);
                } finally {
                    MDC.remove(CouncilConstants.MDC_TRACE_ID);
                }
            });
            try {
                return future.get(criticTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("[orchestrator] Critic timed out after {}s", criticTimeoutSeconds);
                return CriticResult.failure("unknown", "unknown", "Critic timed out", 0);
            } catch (Exception e) {
                log.warn("[orchestrator] Critic execution failed: {}", e.getMessage());
                return CriticResult.failure("unknown", "unknown", e.getMessage(), 0);
            }
        }
    }

    /* ── Phase 3: Judge scoring ────────────────────────────────── */

    JudgeResult runJudgePhase(List<DraftResult> drafts, CriticResult criticResult) {
        JudgeResult judgeResult = judge.evaluate(drafts, criticResult);
        if (judgeResult.winnerProvider() != null) {
            metrics.recordJudgeDecision(judgeResult.winnerProvider());
        }
        return judgeResult;
    }

    /* ── helpers ────────────────────────────────────────────────── */

    private DraftResult collectDraftResult(Future<DraftResult> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return DraftResult.failure("unknown", "unknown", "Draft generation timed out", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DraftResult.failure("unknown", "unknown", "Interrupted", 0);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return DraftResult.failure("unknown", "unknown", cause.getMessage(), 0);
        }
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

    private FinalResponse errorResponse(String traceId, String error) {
        return new FinalResponse(traceId, null, error, List.of(), List.of(), 0.0);
    }
}

