package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.common.CouncilConstants;
import com.council.common.CouncilUtils;
import com.council.common.exception.ProviderFailureCategory;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.events.PipelineEventBroadcaster;
import com.council.judge.DeterministicJudge;
import com.council.judge.FinalScoreBreakdown;
import com.council.judge.PromptClassifier;
import com.council.judge.ProductionConsistencyCalibrator;
import com.council.judge.ResearchQualityCalibrator;
import com.council.judge.TaskType;
import com.council.judge.invariant.InvariantCriticResult;
import com.council.judge.invariant.InvariantLibrary;
import com.council.judge.invariant.InvariantViolationCritic;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.provider.routing.ProviderDescriptor;
import com.council.provider.routing.ProviderSelectionStrategy;
import com.council.research.ResearchPack;
import com.council.research.ResearchPromptAugmenter;
import com.council.research.ResearchService;
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
    private static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 90L;
    private static final long DEFAULT_PER_PROVIDER_DEADLINE_SECONDS = 30L;
    private static final double DEFAULT_EARLY_STOP_QUALITY_THRESHOLD = 0.88;
    private static final double DEFAULT_EARLY_STOP_MIN_IMPROVEMENT = 0.06;

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
    private final ResearchService researchService;
    private final ResearchPromptAugmenter researchPromptAugmenter;
    private final InvariantViolationCritic invariantViolationCritic;
    private final EarlyStopPolicy earlyStopPolicy;
    private final CouncilProperties.OrchestratorConfig orchestratorConfig;
    private final Map<String, CouncilProperties.ProviderConfig> providerConfigs;
    private final long draftTimeoutSeconds;
    private final long criticTimeoutSeconds;
    private final long verifierTimeoutSeconds;
    private final long synthesisTimeoutSeconds;
    private final long requestTimeoutSeconds;
    private final long perProviderDeadlineSeconds;
    private final boolean earlyStopEnabled;
    private final double earlyStopQualityThreshold;
    private final double earlyStopMinImprovement;

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
                                  PipelineEventBroadcaster eventBroadcaster,
                                  ResearchService researchService,
                                  ResearchPromptAugmenter researchPromptAugmenter) {
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
        this.researchService = researchService;
        this.researchPromptAugmenter = researchPromptAugmenter;
        this.invariantViolationCritic = new InvariantViolationCritic();
        this.earlyStopPolicy = new EarlyStopPolicy();
        this.orchestratorConfig = properties.getOrchestrator();
        this.providerConfigs = properties.getProviders() == null ? Map.of() : Map.copyOf(properties.getProviders());
        this.draftTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getDraftTimeoutSeconds(), DEFAULT_DRAFT_TIMEOUT_SECONDS);
        this.criticTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getCriticTimeoutSeconds(), DEFAULT_CRITIC_TIMEOUT_SECONDS);
        this.verifierTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getVerifierTimeoutSeconds(), DEFAULT_VERIFIER_TIMEOUT_SECONDS);
        this.synthesisTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getSynthesisTimeoutSeconds(), DEFAULT_SYNTHESIS_TIMEOUT_SECONDS);
        this.requestTimeoutSeconds = positiveOrDefault(
                orchestratorConfig.getRequestTimeoutSeconds(), DEFAULT_REQUEST_TIMEOUT_SECONDS);
        this.perProviderDeadlineSeconds = positiveOrDefault(
                orchestratorConfig.getPerProviderDeadlineSeconds(), DEFAULT_PER_PROVIDER_DEADLINE_SECONDS);
        this.earlyStopEnabled = orchestratorConfig.isEarlyStopEnabled();
        this.earlyStopQualityThreshold = positiveDoubleOrDefault(
                orchestratorConfig.getEarlyStopQualityThreshold(), DEFAULT_EARLY_STOP_QUALITY_THRESHOLD);
        this.earlyStopMinImprovement = positiveDoubleOrDefault(
                orchestratorConfig.getEarlyStopMinImprovement(), DEFAULT_EARLY_STOP_MIN_IMPROVEMENT);
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
        long startNanos = System.nanoTime();
        MDC.put(CouncilConstants.MDC_TRACE_ID, traceId);
        metrics.recordRequest();
        ResearchPack researchPack = ResearchPack.notRequired();
        List<DraftResult> traceDraftsForFailure = List.of();

        log.info("[orchestrator] Starting reasoning pipeline, traceId={}", traceId);
        emit(traceId, "START", "running", "Reasoning pipeline started", startTime,
                Map.of("queryLength", userQuery == null ? 0 : userQuery.length()));

        try {
            // 0. Classify the prompt
            TaskType taskType = promptClassifier.classify(userQuery);
            ExecutionBudget budget = executionBudget(taskType, startNanos);
            log.info("[orchestrator] Prompt classified as: {}", taskType);
            emit(traceId, "CLASSIFY", "done", "Prompt classified as " + taskType, startTime,
                    Map.of("taskType", taskType.name(),
                            "requestTimeoutSeconds", budget.requestTimeoutSeconds(),
                            "draftTimeoutSeconds", budget.draftTimeoutSeconds(),
                            "perProviderDeadlineSeconds", budget.perProviderDeadlineSeconds(),
                            "earlyStopQualityThreshold", budget.earlyStopQualityThreshold()));

            researchPack = researchService == null
                    ? ResearchPack.notRequired()
                    : researchService.buildEvidencePack(userQuery, taskType);
            if (researchPack == null) {
                researchPack = ResearchPack.notRequired();
            }
            String modelQuery = researchPromptAugmenter == null
                    ? userQuery
                    : researchPromptAugmenter.augment(userQuery, researchPack);
            if (researchPack.required()) {
                emit(traceId, "CLASSIFY", researchPack.hasSources() ? "done" : "degraded",
                        researchPack.hasSources()
                                ? "Research evidence pack built"
                                : "Research required but evidence unavailable",
                        startTime,
                        Map.of("researchRequired", true,
                                "sourceCount", researchPack.sources().size(),
                                "originSummary", researchPack.originSummary(),
                                "warnings", researchPack.warnings(),
                                "queries", researchPack.queries()));
            }

            // 1. Select providers (routing-aware or legacy), with task type
            List<LlmAdapter> providers = selectDraftProviders(traceId, taskType);
            if (providers.isEmpty()) {
                log.warn("[orchestrator] No providers available");
                FinalResponse response = withResearch(
                        errorResponse(traceId, "No LLM providers are currently available"), researchPack);
                persistTrace(traceId, userQuery, List.of(), null, null, response, startTime);
                emitFailure(traceId, "ERROR", response.judgeReason(), startTime, response);
                return response;
            }
            log.info("[orchestrator] {} providers selected: {}", providers.size(),
                    providers.stream().map(LlmAdapter::providerName).toList());
            emit(traceId, "ROUTE", "done", providers.size() + " draft providers selected", startTime,
                    Map.of("providers", providers.stream().map(LlmAdapter::providerName).toList(),
                            "routingEnabled", registry.isRoutingEnabled(),
                            "requestBudgetRemainingMs", budget.remainingMillis()));

            // 2. Draft phase
            emit(traceId, "DRAFT", "running", "Draft phase started", startTime,
                    Map.of("providerCount", providers.size()));
            DraftRequest draftRequest = DraftRequest.of(traceId, modelQuery);
            DraftPhaseResult initialDraftPhase = runDraftPhaseDetailed(providers, draftRequest, budget, researchPack);
            List<DraftResult> allDrafts = initialDraftPhase.drafts();
            List<DraftResult> traceDrafts = new ArrayList<>(allDrafts);
            traceDraftsForFailure = traceDrafts;
            ProviderRunDiagnostics initialRunDiagnostics =
                    ProviderRunDiagnostics.from(allDrafts, initialDraftPhase.earlyStopDecision());

            List<DraftResult> successfulDrafts = allDrafts.stream()
                    .filter(DraftResult::isSuccess).toList();
            List<String> failedProviders = allDrafts.stream()
                    .filter(DraftResult::isFailure)
                    .map(DraftResult::provider).toList();

            log.info("[orchestrator] Drafts: {} successful, {} failed",
                    successfulDrafts.size(), failedProviders.size());
            emit(traceId, "DRAFT", "done", "Draft phase completed", startTime,
                    Map.of("successfulDrafts", successfulDrafts.size(),
                            "failedDrafts", failedProviders.size(),
                            "skippedDrafts", initialRunDiagnostics.skippedProviders(),
                            "failedProviders", failedProviders,
                            "earlyStopDecision", initialDraftPhase.earlyStopDecision()));

            if (successfulDrafts.isEmpty()) {
                String msg = failedProviders.isEmpty()
                        ? "No provider produced a valid draft. " + initialRunDiagnostics.degradedRunStatus()
                        : "All providers failed: " + failedProviders;
                log.error("[orchestrator] {}", msg);
                FinalResponse response = withResearch(errorResponse(traceId, msg, failedProviders), researchPack)
                        .withRunDiagnostics(initialRunDiagnostics)
                        .withProviderFailures(ProviderFailureDetails.fromDraftResults(allDrafts))
                        .withProviderOutcomes(ProviderOutcome.fromDraftResults(allDrafts));
                persistTrace(traceId, userQuery, allDrafts, null, null, response, startTime);
                emitFailure(traceId, "ERROR", msg, startTime, response);
                return response;
            }

            // 3. Review phases (Critic + Verifier in parallel)
            emit(traceId, "REVIEW", "running", "Critic and verifier phases started", startTime,
                    Map.of("drafts", successfulDrafts.size()));
            ReviewPhaseResult review = runReviewPhases(traceId, modelQuery, successfulDrafts, budget);
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

            if (constraintValidDrafts.isEmpty()) {
                log.warn("[orchestrator] No valid drafts after verifier/constraint filtering");
                FinalResponse failure = withResearch(
                        constraintFailureResponse(traceId, failedProviders), researchPack)
                        .withRunDiagnostics(initialRunDiagnostics)
                        .withProviderFailures(ProviderFailureDetails.fromDraftResults(allDrafts))
                        .withProviderOutcomes(ProviderOutcome.fromDraftResults(allDrafts));
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
            List<String> finalFailed = failedProviders;
            JudgeResult finalJudge = judgeResult;
            CriticResult finalCritic = criticResult;
            VerifierBatchResult finalVerifier = effectiveVerifierBatchResult;

            if (registry.isRoutingEnabled() && budget.hasRemaining()) {
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

                    DraftPhaseResult escalationPhase =
                            runDraftPhaseDetailed(escalationProviders, draftRequest, budget, researchPack);
                    List<DraftResult> escalationDrafts = escalationPhase.drafts();
                    traceDrafts.addAll(escalationDrafts);
                    List<DraftResult> successfulEscalation = escalationDrafts.stream()
                            .filter(DraftResult::isSuccess).toList();

                    List<String> escalationFailed = escalationDrafts.stream()
                            .filter(DraftResult::isFailure)
                            .map(DraftResult::provider).toList();
                    if (!escalationFailed.isEmpty()) {
                        finalFailed = mergeProviderLists(finalFailed, escalationFailed);
                    }

                    if (!successfulEscalation.isEmpty()) {
                        // Combine and re-judge
                        List<DraftResult> combined = new ArrayList<>(constraintValidDrafts);
                        combined.addAll(successfulEscalation);

                        ReviewPhaseResult escalatedReview = runReviewPhases(traceId, modelQuery, combined, budget);
                        finalCritic = escalatedReview.criticResult();
                        VerifierBatchResult effectiveEscalatedVerifier = enforceConstraintVerifier
                                ? escalatedReview.verifierBatchResult()
                                : VerifierBatchResult.passedFor(combined);
                        finalVerifier = effectiveEscalatedVerifier;

                        DraftFilterResult filteredEscalated =
                                filterConstraintValidDrafts(combined, effectiveEscalatedVerifier);

                        if (filteredEscalated.validDrafts().isEmpty()) {
                            log.warn("[orchestrator] No valid drafts after escalation verifier/constraint filtering");
                            FinalResponse failure = withResearch(
                                    constraintFailureResponse(traceId, finalFailed), researchPack)
                                    .withRunDiagnostics(ProviderRunDiagnostics.from(traceDrafts,
                                            initialDraftPhase.earlyStopDecision()))
                                    .withProviderFailures(ProviderFailureDetails.fromDraftResults(traceDrafts))
                                    .withProviderOutcomes(ProviderOutcome.fromDraftResults(traceDrafts));
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
            } else if (registry.isRoutingEnabled()) {
                metrics.recordBudgetStop("escalation_skipped_request_budget_exhausted", taskType.name());
                metrics.recordDegradedMode("escalation_skipped_request_budget_exhausted");
                emit(traceId, "ESCALATE", "degraded", "Escalation skipped because request budget was exhausted",
                        startTime, Map.of("requestBudgetRemainingMs", budget.remainingMillis()));
            }

            // 6. Build final response
            String winnerProvider = finalJudge.winnerProvider();
            DraftResult winner = finalDrafts.stream()
                    .filter(d -> d.provider().equals(winnerProvider))
                    .findFirst()
                    .orElse(finalDrafts.getFirst());

            SynthesisResult synthesisResult = runSynthesisPhase(new SynthesisRequest(
                    traceId,
                    modelQuery,
                    finalDrafts,
                    finalVerifier,
                    finalCritic
            ), budget);
            emit(traceId, "SYNTHESIS", synthesisResult != null && synthesisResult.isSuccess() ? "done" : "degraded",
                    synthesisResult != null && synthesisResult.isSuccess()
                            ? "Synthesis completed"
                            : "Synthesis unavailable; using judge winner", startTime,
                    Map.of("provider", synthesisResult == null ? "none" : synthesisResult.provider(),
                            "success", synthesisResult != null && synthesisResult.isSuccess()));

            String finalAnswer = winner.answer();
            double winnerConfidence = finalJudge.winnerScore();
            double finalConfidence = winnerConfidence;

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
            if (FinalAnswerCompletenessGuard.hasDanglingPseudocodePromise(finalAnswer)) {
                log.warn("[orchestrator] Final answer promised pseudocode but did not include concrete control flow");
            }
            finalAnswer = FinalAnswerCompletenessGuard.compose(userQuery, finalAnswer, researchPack);
            CalibratedFinalQuality finalQuality =
                    calibrateFinalQuality(userQuery, finalAnswer, null, finalConfidence, winnerConfidence, researchPack);
            finalConfidence = finalQuality.score();

            FinalResponse response = new FinalResponse(
                    traceId,
                    finalAnswer,
                    finalJudge.reason(),
                    finalDrafts.stream().map(DraftResult::provider).toList(),
                    finalFailed,
                    finalConfidence
            ).withScoreBreakdown(
                    finalConfidence,
                    winnerConfidence,
                    modelAgreement(finalJudge.rankings()),
                    finalQuality.dimensions(),
                    finalQuality.scoreBreakdown())
                    .withResearch(researchPack)
                    .withInvariants(finalQuality.invariants())
                    .withRunDiagnostics(ProviderRunDiagnostics.from(traceDrafts, initialDraftPhase.earlyStopDecision()))
                    .withProviderFailures(ProviderFailureDetails.fromDraftResults(traceDrafts))
                    .withProviderOutcomes(ProviderOutcome.fromDraftResults(traceDrafts));

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
            FinalResponse response = withResearch(
                    errorResponse(traceId, "Internal orchestration error: " + e.getMessage()), researchPack);
            try {
                persistTrace(traceId, userQuery, traceDraftsForFailure, null, null, response, startTime);
            } catch (Exception persistError) {
                log.warn("[orchestrator] Failed to persist internal-error trace: {}", persistError.getMessage());
            }
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
        return runDraftPhase(providers, request,
                executionBudget(TaskType.GENERAL_REASONING, System.nanoTime()));
    }

    List<DraftResult> runDraftPhase(List<LlmAdapter> providers,
                                     DraftRequest request,
                                     ExecutionBudget budget) {
        return runDraftPhaseDetailed(providers, request, budget, ResearchPack.notRequired()).drafts();
    }

    List<DraftResult> runDraftPhase(List<LlmAdapter> providers,
                                    DraftRequest request,
                                    TaskType taskType,
                                    ResearchPack researchPack) {
        return runDraftPhaseDetailed(providers, request,
                executionBudget(taskType, System.nanoTime()), researchPack).drafts();
    }

    private DraftPhaseResult runDraftPhaseDetailed(List<LlmAdapter> providers,
                                                   DraftRequest request,
                                                   ExecutionBudget budget,
                                                   ResearchPack researchPack) {
        if (providers == null || providers.isEmpty()) {
            return new DraftPhaseResult(List.of(), EarlyStopDecision.continueWaiting(
                    budget.taskType(), budget.earlyStopQualityThreshold(), 0, 0, List.of("no_selected_providers")));
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ExecutorCompletionService<DraftResult> completionService =
                    new ExecutorCompletionService<>(executor);
            Map<Future<DraftResult>, LlmAdapter> pending = new LinkedHashMap<>();
            Map<String, DraftResult> resultsByProvider = new LinkedHashMap<>();
            Map<String, Long> providerDeadlines = new HashMap<>();
            Deque<LlmAdapter> unsubmitted = new ArrayDeque<>(providers);
            long now = System.nanoTime();
            long phaseDeadlineNanos = Math.min(
                    budget.requestDeadlineNanos(),
                    now + TimeUnit.SECONDS.toNanos(budget.draftTimeoutSeconds()));
            EarlyStopPolicy.Requirements requirements = earlyStopPolicy.requirements(
                    budget.taskType(), request.userQuery(), researchPack, providers.size(),
                    budget.earlyStopQualityThreshold());
            EarlyStopDecision latestEarlyStop = EarlyStopDecision.continueWaiting(
                    budget.taskType(), requirements.confidenceThreshold(),
                    requirements.minValidDraftsBeforeEarlyStop(), 0, List.of("drafts_not_completed"));
            boolean schedulingStopped = false;

            submitProvidersUntil(pending, providerDeadlines, completionService, unsubmitted, request, budget,
                    phaseDeadlineNanos, requirements.minValidDraftsBeforeEarlyStop());

            while (!pending.isEmpty() || !unsubmitted.isEmpty()) {
                cancelExpiredDrafts(pending, resultsByProvider, providerDeadlines, budget);

                now = System.nanoTime();
                if (now >= phaseDeadlineNanos) {
                    cancelPendingDrafts(pending, resultsByProvider,
                            "Draft generation timed out before enough providers completed",
                            "draft_phase_deadline_exceeded", budget);
                    skipUnsubmittedProviders(unsubmitted, resultsByProvider,
                            ProviderOutcomeStatus.SKIPPED_TIMEOUT_BUDGET,
                            "Draft skipped: request timeout budget exhausted");
                    break;
                }

                if (!pending.isEmpty()) {
                    Future<DraftResult> completed = completionService.poll(
                            nextDraftPollMillis(pending, providerDeadlines, phaseDeadlineNanos),
                            TimeUnit.MILLISECONDS);
                    if (completed != null) {
                        LlmAdapter provider = pending.remove(completed);
                        if (provider != null) {
                            resultsByProvider.put(provider.providerName(), resolveDraftFuture(provider, completed));
                        }
                    }
                }

                latestEarlyStop = earlyStopPolicy.evaluate(
                        budget.taskType(), request.userQuery(), researchPack, providers.size(),
                        resultsByProvider.values(), budget.earlyStopEnabled(), budget.earlyStopQualityThreshold(),
                        budget.earlyStopMinImprovement(), expectedRemainingCeiling(unsubmitted),
                        budget.remainingMillis(), TimeUnit.SECONDS.toMillis(budget.requestTimeoutSeconds()));
                if (latestEarlyStop.allowed() && !schedulingStopped) {
                    log.info("[orchestrator] Draft early-stop: {}", latestEarlyStop.reason());
                    schedulingStopped = true;
                    skipUnsubmittedProviders(unsubmitted, resultsByProvider, ProviderOutcomeStatus.SKIPPED_EARLY_STOP,
                            "Draft skipped: early stop after valid draft confidence "
                                    + formatScore(bestDraftConfidence(resultsByProvider.values()))
                                    + " >= " + formatScore(latestEarlyStop.threshold()));
                }

                if (!schedulingStopped && !unsubmitted.isEmpty()) {
                    int validDrafts = (int) resultsByProvider.values().stream().filter(DraftResult::isSuccess).count();
                    int targetPending = validDrafts >= requirements.minValidDraftsBeforeEarlyStop()
                            ? pending.size() + unsubmitted.size()
                            : Math.max(1, requirements.minValidDraftsBeforeEarlyStop() - validDrafts);
                    submitProvidersUntil(pending, providerDeadlines, completionService, unsubmitted, request, budget,
                            phaseDeadlineNanos, targetPending);
                }
            }

            List<DraftResult> ordered = new ArrayList<>(providers.size());
            for (LlmAdapter provider : providers) {
                DraftResult result = resultsByProvider.get(provider.providerName());
                if (result != null) {
                    ordered.add(result);
                }
            }
            return new DraftPhaseResult(List.copyOf(ordered), latestEarlyStop);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Draft phase interrupted");
            List<DraftResult> interrupted = providers.stream()
                    .map(provider -> DraftResult.failure(provider.providerName(), provider.modelName(),
                            "Draft generation interrupted", 0,
                            ProviderFailureDetails.local(provider.providerName(), provider.modelName(),
                                    ProviderFailureCategory.UNKNOWN, "Draft generation interrupted", 0)))
                    .toList();
            return new DraftPhaseResult(interrupted, EarlyStopDecision.continueWaiting(
                    budget.taskType(), budget.earlyStopQualityThreshold(), providers.size(), 0,
                    List.of("draft_phase_interrupted")));
        } finally {
            executor.shutdownNow();
        }
    }

    private void submitProvidersUntil(Map<Future<DraftResult>, LlmAdapter> pending,
                                      Map<String, Long> providerDeadlines,
                                      ExecutorCompletionService<DraftResult> completionService,
                                      Deque<LlmAdapter> unsubmitted,
                                      DraftRequest request,
                                      ExecutionBudget budget,
                                      long phaseDeadlineNanos,
                                      int targetPending) {
        while (pending.size() < targetPending && !unsubmitted.isEmpty()) {
            LlmAdapter provider = unsubmitted.removeFirst();
            Future<DraftResult> future = completionService.submit(() -> executeDraftProvider(provider, request));
            pending.put(future, provider);
            long providerDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(providerDeadlineSeconds(provider, budget));
            providerDeadlines.put(provider.providerName(), Math.min(providerDeadline, phaseDeadlineNanos));
        }
    }

    private void skipUnsubmittedProviders(Deque<LlmAdapter> unsubmitted,
                                          Map<String, DraftResult> resultsByProvider,
                                          ProviderOutcomeStatus outcomeStatus,
                                          String reason) {
        while (!unsubmitted.isEmpty()) {
            LlmAdapter provider = unsubmitted.removeFirst();
            resultsByProvider.put(provider.providerName(), DraftResult.skipped(
                    provider.providerName(), provider.modelName(), outcomeStatus, reason));
        }
    }

    private void cancelExpiredDrafts(Map<Future<DraftResult>, LlmAdapter> pending,
                                     Map<String, DraftResult> resultsByProvider,
                                     Map<String, Long> providerDeadlines,
                                     ExecutionBudget budget) {
        long now = System.nanoTime();
        Iterator<Map.Entry<Future<DraftResult>, LlmAdapter>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Future<DraftResult>, LlmAdapter> entry = iterator.next();
            Future<DraftResult> future = entry.getKey();
            LlmAdapter provider = entry.getValue();
            Long deadline = providerDeadlines.get(provider.providerName());
            if (!future.isDone() && deadline != null && now >= deadline) {
                future.cancel(true);
                iterator.remove();
                resultsByProvider.put(provider.providerName(), DraftResult.failure(
                        provider.providerName(),
                        provider.modelName(),
                        "Draft generation timed out after per-provider deadline",
                        TimeUnit.SECONDS.toMillis(providerDeadlineSeconds(provider, budget)),
                        ProviderFailureDetails.local(provider.providerName(), provider.modelName(),
                                ProviderFailureCategory.TIMEOUT,
                                "Draft generation timed out after per-provider deadline",
                                TimeUnit.SECONDS.toMillis(providerDeadlineSeconds(provider, budget)))));
                metrics.recordBudgetStop("provider_deadline_exceeded", budget.taskType().name());
                metrics.recordDegradedMode("provider_deadline_exceeded");
            }
        }
    }

    private void cancelPendingDrafts(Map<Future<DraftResult>, LlmAdapter> pending,
                                     Map<String, DraftResult> resultsByProvider,
                                     String reason,
                                     String metricReason,
                                     ExecutionBudget budget) {
        if (!pending.isEmpty()) {
            metrics.recordBudgetStop(metricReason, budget.taskType().name());
        }
        Iterator<Map.Entry<Future<DraftResult>, LlmAdapter>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Future<DraftResult>, LlmAdapter> entry = iterator.next();
            Future<DraftResult> future = entry.getKey();
            LlmAdapter provider = entry.getValue();
            future.cancel(true);
            iterator.remove();
            resultsByProvider.putIfAbsent(provider.providerName(), DraftResult.failure(
                    provider.providerName(), provider.modelName(), reason, 0,
                    ProviderFailureDetails.local(provider.providerName(), provider.modelName(),
                            metricReason.contains("deadline") ? ProviderFailureCategory.TIMEOUT
                                    : ProviderFailureCategory.UNKNOWN,
                            reason, 0)));
            metrics.recordDegradedMode(metricReason);
        }
    }

    private long nextDraftPollMillis(Map<Future<DraftResult>, LlmAdapter> pending,
                                     Map<String, Long> providerDeadlines,
                                     long phaseDeadlineNanos) {
        long nextDeadline = phaseDeadlineNanos;
        for (LlmAdapter provider : pending.values()) {
            Long providerDeadline = providerDeadlines.get(provider.providerName());
            if (providerDeadline != null) {
                nextDeadline = Math.min(nextDeadline, providerDeadline);
            }
        }
        long remaining = remainingMillisZero(nextDeadline);
        return Math.max(1L, Math.min(250L, remaining));
    }

    private double expectedRemainingCeiling(Collection<LlmAdapter> pendingProviders) {
        return pendingProviders.stream()
                .mapToDouble(provider -> expectedProviderCeiling(provider.providerName()))
                .max()
                .orElse(0.0);
    }

    private double bestDraftConfidence(Collection<DraftResult> results) {
        return results.stream()
                .filter(DraftResult::isSuccess)
                .mapToDouble(DraftResult::confidence)
                .max()
                .orElse(0.0);
    }

    private long providerDeadlineSeconds(LlmAdapter provider, ExecutionBudget budget) {
        int configuredProviderTimeout = providerConfigs
                .getOrDefault(provider.providerName(), new CouncilProperties.ProviderConfig())
                .getTimeoutSeconds();
        long configured = positiveOrDefault(configuredProviderTimeout, budget.perProviderDeadlineSeconds());
        return Math.max(1L, Math.min(budget.perProviderDeadlineSeconds(), configured));
    }

    private double expectedProviderCeiling(String providerName) {
        CouncilProperties.ProviderConfig config = providerConfigs
                .getOrDefault(providerName, new CouncilProperties.ProviderConfig());
        return Math.min(0.98, Math.max(0.50, config.getReliability() + 0.08));
    }

    private DraftResult executeDraftProvider(LlmAdapter provider, DraftRequest request) {
        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
        String name = provider.providerName();
        boolean acquired = concurrencyLimiter.tryAcquire(name);
        if (!acquired) {
            MDC.remove(CouncilConstants.MDC_TRACE_ID);
            log.warn("[orchestrator] Provider '{}' at max concurrency, skipping", name);
            metrics.recordConcurrencyRejection(name);
            return DraftResult.skipped(name, provider.modelName(), ProviderOutcomeStatus.SKIPPED_BUDGET_LIMIT,
                    "Draft skipped: local concurrency limit reached before provider invocation");
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
            return DraftResult.failure(providerName, modelName, "Draft generation timed out", 0,
                    ProviderFailureDetails.local(providerName, modelName, ProviderFailureCategory.TIMEOUT,
                            "Draft generation timed out", 0));
        }

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[orchestrator] Draft provider '{}' interrupted", providerName);
            return DraftResult.failure(providerName, modelName, "Draft generation interrupted", 0,
                    ProviderFailureDetails.local(providerName, modelName, ProviderFailureCategory.UNKNOWN,
                            "Draft generation interrupted", 0));
        } catch (ExecutionException e) {
            String message = rootMessage(e);
            log.warn("[orchestrator] Draft provider '{}' failed: {}", providerName, message);
            return DraftResult.failure(providerName, modelName,
                    "Draft generation failed: " + message, 0,
                    ProviderFailureDetails.local(providerName, modelName, ProviderFailureCategory.UNKNOWN,
                            "Draft generation failed", 0));
        }
    }

    /* ── Phase 2: Review (Critic + Verifier) ───────────────────── */

    private ReviewPhaseResult runReviewPhases(String traceId,
                                              String userQuery,
                                              List<DraftResult> successfulDrafts) {
        return runReviewPhases(traceId, userQuery, successfulDrafts,
                executionBudget(TaskType.GENERAL_REASONING, System.nanoTime()));
    }

    private ReviewPhaseResult runReviewPhases(String traceId,
                                              String userQuery,
                                              List<DraftResult> successfulDrafts,
                                              ExecutionBudget budget) {
        if (!budget.hasRemaining()) {
            metrics.recordBudgetStop("review_skipped_request_budget_exhausted", budget.taskType().name());
            metrics.recordDegradedMode("review_skipped_request_budget_exhausted");
            return new ReviewPhaseResult(
                    CriticResult.failure("unknown", "unknown", "Critic skipped: request budget exhausted", 0),
                    VerifierBatchResult.passedFor(successfulDrafts));
        }

        CriticRequest criticRequest = new CriticRequest(traceId, userQuery, successfulDrafts);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long startNanos = System.nanoTime();
            Future<CriticResult> criticFuture = executor.submit(() -> runCriticPhase(criticRequest));
            Future<VerifierBatchResult> verifierFuture =
                    executor.submit(() -> runVerifierPhase(traceId, userQuery, successfulDrafts));

            CriticResult critic = resolveCriticFuture(
                    criticFuture, Math.min(deadlineNanos(startNanos, criticTimeoutSeconds),
                            budget.requestDeadlineNanos()));
            VerifierBatchResult verifier = resolveVerifierFuture(
                    verifierFuture, Math.min(deadlineNanos(startNanos, verifierTimeoutSeconds),
                            budget.requestDeadlineNanos()), successfulDrafts);

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
        return runSynthesisPhase(request,
                executionBudget(TaskType.GENERAL_REASONING, System.nanoTime()));
    }

    SynthesisResult runSynthesisPhase(SynthesisRequest request, ExecutionBudget budget) {
        if (!budget.hasRemaining()) {
            metrics.recordBudgetStop("synthesis_skipped_request_budget_exhausted", budget.taskType().name());
            metrics.recordDegradedMode("synthesis_skipped_request_budget_exhausted");
            return SynthesisResult.failure("none", "none",
                    "Synthesis skipped: request budget exhausted", 0);
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<SynthesisResult> future = executor.submit(() -> executeSynthesisPhase(request));
        try {
            long timeoutMillis = Math.min(
                    TimeUnit.SECONDS.toMillis(synthesisTimeoutSeconds),
                    budget.remainingMillis());
            return future.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[orchestrator] Synthesis timed out after {}ms", budget.remainingMillis());
            metrics.recordBudgetStop("synthesis_deadline_exceeded", budget.taskType().name());
            metrics.recordDegradedMode("synthesis_deadline_exceeded");
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

    private double positiveDoubleOrDefault(double value, double defaultValue) {
        return value > 0.0 ? value : defaultValue;
    }

    private ExecutionBudget executionBudget(TaskType taskType, long startNanos) {
        TaskType effectiveTaskType = taskType == null ? TaskType.GENERAL_REASONING : taskType;
        CouncilProperties.TaskBudgetConfig taskBudget = taskBudget(effectiveTaskType);

        long requestSeconds = positiveOrDefault(
                taskBudget == null ? 0 : taskBudget.getRequestTimeoutSeconds(),
                requestTimeoutSeconds);
        long draftSeconds = positiveOrDefault(
                taskBudget == null ? 0 : taskBudget.getDraftTimeoutSeconds(),
                draftTimeoutSeconds);
        long providerSeconds = positiveOrDefault(
                taskBudget == null ? 0 : taskBudget.getPerProviderDeadlineSeconds(),
                perProviderDeadlineSeconds);
        double threshold = positiveDoubleOrDefault(
                taskBudget == null ? 0.0 : taskBudget.getEarlyStopQualityThreshold(),
                earlyStopQualityThreshold);

        return new ExecutionBudget(
                effectiveTaskType,
                startNanos + TimeUnit.SECONDS.toNanos(requestSeconds),
                requestSeconds,
                draftSeconds,
                providerSeconds,
                CouncilUtils.clamp01(threshold),
                earlyStopEnabled,
                earlyStopMinImprovement);
    }

    private CouncilProperties.TaskBudgetConfig taskBudget(TaskType taskType) {
        Map<String, CouncilProperties.TaskBudgetConfig> budgets = orchestratorConfig.getTaskBudgets();
        if (budgets == null || budgets.isEmpty()) {
            return null;
        }
        return budgets.get(taskKey(taskType));
    }

    private String taskKey(TaskType taskType) {
        TaskType effectiveTaskType = taskType == null ? TaskType.GENERAL_REASONING : taskType;
        return effectiveTaskType.name().toLowerCase(Locale.ROOT).replace('_', '-');
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

    private long remainingMillisZero(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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

    private CalibratedFinalQuality calibrateFinalQuality(String userQuery,
                                                          String answer,
                                                          String summary,
                                                          double confidence,
                                                          double draftJudgeScore,
                                                          ResearchPack researchPack) {
        InvariantCriticResult invariantResult =
                invariantViolationCritic.evaluate(userQuery, answer, researchPack);
        double productionConsistencyCap = ProductionConsistencyCalibrator.evaluate(answer, summary).cap();
        ProductionConsistencyCalibrator.QualityScore productionScore =
                ProductionConsistencyCalibrator.qualityScore(answer, summary, confidence, invariantResult);
        double score = productionScore.score();
        Map<String, Double> dimensions = new LinkedHashMap<>(productionScore.dimensions());
        List<String> reasons = new ArrayList<>(productionScore.reasons());

        ResearchQualityCalibrator.QualityScore researchScore =
                ResearchQualityCalibrator.qualityScore(userQuery, answer, researchPack, score, invariantResult);
        Map<String, String> unavailableReasons = new LinkedHashMap<>();
        Double researchCalibratedScore = null;
        if (researchScore.applied()) {
            researchCalibratedScore = researchScore.score();
            dimensions.putAll(researchScore.dimensions());
            reasons.addAll(researchScore.reasons());
            score = Math.min(score, researchScore.score());
        } else {
            unavailableReasons.put("researchCalibratedScore", "Not a research-required task.");
        }

        Double invariantCap = null;
        Double finalCompletenessCap = null;
        if (invariantResult.evaluated()) {
            invariantCap = invariantResult.overallCap();
            finalCompletenessCap = invariantResult.capForInvariant(
                    InvariantLibrary.FINAL_RECOMMENDATION_CONSTRAINT_MUST_BE_FOLLOWED);
            dimensions.put("invariant_overall_cap", invariantResult.overallCap());
            score = Math.min(score, invariantResult.overallCap());
        } else {
            unavailableReasons.put("invariantCap", "No invariant domain applied to this answer.");
        }
        if (finalCompletenessCap == null) {
            unavailableReasons.put("finalCompletenessCap", "No final-answer sentence-count cap was applied.");
        }

        if (productionScore.applied() || researchScore.applied() || invariantResult.hasViolations()) {
            log.info("[orchestrator] Final quality calibrated from {} to {} by quality rubrics: dimensions={}, reasons={}, invariantViolations={}",
                    confidence, score, dimensions, reasons, invariantResult.violations().size());
        }
        double finalAnswerQuality = CouncilUtils.clamp01(score);
        FinalScoreBreakdown scoreBreakdown = new FinalScoreBreakdown(
                draftJudgeScore,
                confidence,
                productionScore.score(),
                researchCalibratedScore,
                invariantCap,
                finalCompletenessCap,
                productionConsistencyCap,
                finalAnswerQuality,
                "finalAnswerQuality = min(baseRubricScore, researchCalibratedScore when available, "
                        + "invariantCap when evaluated); baseRubricScore already includes productionConsistencyCap.",
                reasons,
                unavailableReasons);
        return new CalibratedFinalQuality(
                finalAnswerQuality,
                Map.copyOf(dimensions),
                List.copyOf(reasons),
                invariantResult,
                scoreBreakdown);
    }

    private Double modelAgreement(List<JudgeRanking> rankings) {
        if (rankings == null || rankings.size() <= 1) {
            return null;
        }
        double max = rankings.stream().mapToDouble(JudgeRanking::score).max().orElse(0.0);
        double min = rankings.stream().mapToDouble(JudgeRanking::score).min().orElse(0.0);
        double spread = Math.max(0.0, max - min);
        return Math.max(0.0, Math.min(0.95, 1.0 - (spread / 0.50)));
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

    private record ExecutionBudget(TaskType taskType,
                                   long requestDeadlineNanos,
                                   long requestTimeoutSeconds,
                                   long draftTimeoutSeconds,
                                   long perProviderDeadlineSeconds,
                                   double earlyStopQualityThreshold,
                                   boolean earlyStopEnabled,
                                   double earlyStopMinImprovement) {
        boolean hasRemaining() {
            return requestDeadlineNanos - System.nanoTime() > 0L;
        }

        long remainingMillis() {
            long remainingNanos = requestDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return 0L;
            }
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        }
    }

    private record DraftPhaseResult(List<DraftResult> drafts, EarlyStopDecision earlyStopDecision) {}

    private record CalibratedFinalQuality(double score,
                                           Map<String, Double> dimensions,
                                           List<String> reasons,
                                           InvariantCriticResult invariants,
                                           FinalScoreBreakdown scoreBreakdown) {}

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

    private FinalResponse withResearch(FinalResponse response, ResearchPack researchPack) {
        return response.withResearch(researchPack == null ? ResearchPack.notRequired() : researchPack);
    }
}

