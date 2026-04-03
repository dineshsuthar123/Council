package com.council.evaluation;

import com.council.api.dto.FinalResponse;
import com.council.evaluation.dto.*;
import com.council.orchestrator.ReasoningOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Core evaluation service. Runs batches of prompts through the Council pipeline
 * and optionally through single-model baselines, collecting metrics for comparison.
 * <p>
 * Batch execution is async with bounded concurrency via a {@link Semaphore}
 * to avoid overloading providers.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** Max concurrent prompt evaluations to avoid provider overload. */
    private static final int MAX_CONCURRENCY = 3;

    private final ReasoningOrchestrator orchestrator;
    private final BaselineRunner baselineRunner;
    private final KeywordMatcher keywordMatcher;
    private final EvaluationMetricsCalculator metricsCalculator;
    private final EvaluationMapper evaluationMapper;
    private final EvaluationRunRepository runRepository;
    private final EvaluationPromptResultRepository promptResultRepository;

    public EvaluationService(ReasoningOrchestrator orchestrator,
                             BaselineRunner baselineRunner,
                             KeywordMatcher keywordMatcher,
                             EvaluationMetricsCalculator metricsCalculator,
                             EvaluationMapper evaluationMapper,
                             EvaluationRunRepository runRepository,
                             EvaluationPromptResultRepository promptResultRepository) {
        this.orchestrator = orchestrator;
        this.baselineRunner = baselineRunner;
        this.keywordMatcher = keywordMatcher;
        this.metricsCalculator = metricsCalculator;
        this.evaluationMapper = evaluationMapper;
        this.runRepository = runRepository;
        this.promptResultRepository = promptResultRepository;
    }

    /**
     * Create an evaluation run and kick off async processing.
     *
     * @return the run ID for polling
     */
    @Transactional
    public String startEvaluation(EvaluationRequest request) {
        UUID runId = UUID.randomUUID();
        EvaluationRunEntity run = new EvaluationRunEntity(
                runId, request.name(), request.prompts().size());

        if (request.tags() != null && !request.tags().isEmpty()) {
            run.setTags(String.join(",", request.tags()));
        }
        if (request.providerSubset() != null && !request.providerSubset().isEmpty()) {
            run.setProviderSubset(String.join(",", request.providerSubset()));
        }
        run.setRunBaselines(request.runBaselines());
        run.setStatus(EvaluationStatus.RUNNING);

        // Create placeholder prompt results
        for (int i = 0; i < request.prompts().size(); i++) {
            EvaluationPromptInput input = request.prompts().get(i);
            EvaluationPromptResultEntity result = new EvaluationPromptResultEntity(i, input.prompt());
            result.setExpectedAnswer(input.expectedAnswer());
            if (input.expectedKeywords() != null && !input.expectedKeywords().isEmpty()) {
                result.setExpectedKeywords(String.join(",", input.expectedKeywords()));
            }
            result.setStatus(EvaluationStatus.PENDING);
            run.addPromptResult(result);
        }

        runRepository.save(run);
        log.info("[eval] Created evaluation run {} with {} prompts", runId, request.prompts().size());

        // Fire async execution
        executeRunAsync(runId, request);

        return runId.toString();
    }

    /**
     * Async execution of all prompts in a run.
     */
    @Async("traceExecutor")
    public void executeRunAsync(UUID runId, EvaluationRequest request) {
        try {
            executeRun(runId, request);
        } catch (Exception e) {
            log.error("[eval] Fatal error in run {}: {}", runId, e.getMessage(), e);
            markRunFailed(runId, e.getMessage());
        }
    }

    /**
     * Synchronous execution of all prompts (also used directly in tests).
     */
    public void executeRun(UUID runId, EvaluationRequest request) {
        log.info("[eval] Starting execution of run {}", runId);
        Semaphore semaphore = new Semaphore(MAX_CONCURRENCY);

        List<EvaluationPromptInput> prompts = request.prompts();
        List<String> providerSubset = request.providerSubset();
        boolean runBaselines = request.runBaselines();

        int completed = 0;
        int failed = 0;

        for (int i = 0; i < prompts.size(); i++) {
            EvaluationPromptInput input = prompts.get(i);
            try {
                semaphore.acquire();
                try {
                    evaluateSinglePrompt(runId, i, input, providerSubset, runBaselines);
                    completed++;
                } finally {
                    semaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[eval] Run {} interrupted at prompt {}", runId, i);
                failed++;
                break;
            } catch (Exception e) {
                log.warn("[eval] Prompt {} failed in run {}: {}", i, runId, e.getMessage());
                failed++;
            }

            // Update progress
            updateRunProgress(runId, completed, failed);
        }

        // Finalize run
        finalizeRun(runId, completed, failed);
    }

    /* ── single prompt evaluation ──────────────────────────────────── */

    private void evaluateSinglePrompt(UUID runId, int index, EvaluationPromptInput input,
                                       List<String> providerSubset, boolean runBaselines) {
        log.debug("[eval] Evaluating prompt {} in run {}", index, runId);

        EvaluationPromptResultEntity entity = findPromptResult(runId, index);
        if (entity == null) {
            log.error("[eval] Prompt result entity not found: run={}, index={}", runId, index);
            return;
        }

        try {
            // 1. Run Council pipeline
            long start = System.currentTimeMillis();
            FinalResponse response = orchestrator.reason(input.prompt());
            long councilLatency = System.currentTimeMillis() - start;

            entity.setCouncilTraceId(response.traceId());
            entity.setCouncilAnswer(response.finalAnswer());
            entity.setCouncilConfidence(response.confidence());
            entity.setCouncilLatencyMs(councilLatency);
            entity.setCouncilJudgeReason(response.judgeReason());
            entity.setCouncilAnswerLength(
                    response.finalAnswer() != null ? response.finalAnswer().length() : 0);

            if (response.usedProviders() != null && !response.usedProviders().isEmpty()) {
                entity.setCouncilUsedProviders(String.join(",", response.usedProviders()));
                // Winner is the first used provider (the judge's pick)
                entity.setCouncilWinnerProvider(response.usedProviders().getFirst());
            }
            if (response.failedProviders() != null && !response.failedProviders().isEmpty()) {
                entity.setCouncilFailedProviders(String.join(",", response.failedProviders()));
            }

            // Extract contradiction severity from judge reason (heuristic)
            entity.setCouncilContradictionSeverity(
                    extractContradictionSeverity(response.judgeReason()));

            // 2. Keyword matching
            List<String> keywords = input.expectedKeywords();
            Double kwScore = keywordMatcher.combinedScore(
                    response.finalAnswer(), input.expectedAnswer(), keywords);
            entity.setKeywordMatchScore(kwScore);

            // 3. Baselines (if requested)
            if (runBaselines) {
                Map<String, BaselineResultResponse> baselines = baselineRunner.runBaselines(
                        input.prompt(), providerSubset,
                        input.expectedAnswer(), keywords);
                entity.setBaselineResults(evaluationMapper.toJson(baselines));
            }

            boolean hasFailedProviders = response.failedProviders() != null
                    && !response.failedProviders().isEmpty();
            entity.setStatus(hasFailedProviders
                    ? EvaluationStatus.PARTIAL_FAILURE : EvaluationStatus.COMPLETED);

        } catch (Exception e) {
            log.warn("[eval] Prompt {} failed: {}", index, e.getMessage());
            entity.setStatus(EvaluationStatus.FAILED);
            entity.setErrorMessage(e.getMessage());
        }

        promptResultRepository.save(entity);
    }

    /* ── run lifecycle ─────────────────────────────────────────────── */

    @Transactional
    void updateRunProgress(UUID runId, int completed, int failed) {
        runRepository.findByRunId(runId).ifPresent(run -> {
            run.setCompletedPrompts(completed);
            run.setFailedPrompts(failed);
            runRepository.save(run);
        });
    }

    @Transactional
    void finalizeRun(UUID runId, int completed, int failed) {
        runRepository.findByRunId(runId).ifPresent(run -> {
            run.setCompletedPrompts(completed);
            run.setFailedPrompts(failed);
            run.setCompletedAt(Instant.now());

            // Compute aggregates
            List<EvaluationPromptResponse> results = run.getPromptResults().stream()
                    .map(evaluationMapper::toPromptResponse)
                    .toList();
            EvaluationAggregateResponse aggregate = metricsCalculator.calculate(results);
            run.setAggregateMetrics(evaluationMapper.toJson(aggregate));

            // Determine final status
            if (failed == run.getTotalPrompts()) {
                run.setStatus(EvaluationStatus.FAILED);
            } else if (failed > 0) {
                run.setStatus(EvaluationStatus.PARTIAL_FAILURE);
            } else {
                run.setStatus(EvaluationStatus.COMPLETED);
            }

            runRepository.save(run);
            log.info("[eval] Run {} finalized: status={}, completed={}, failed={}",
                    runId, run.getStatus(), completed, failed);
        });
    }

    @Transactional
    void markRunFailed(UUID runId, String error) {
        runRepository.findByRunId(runId).ifPresent(run -> {
            run.setStatus(EvaluationStatus.FAILED);
            run.setCompletedAt(Instant.now());
            runRepository.save(run);
        });
    }

    /* ── queries ───────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Optional<EvaluationResponse> findByRunId(String runId) {
        try {
            UUID uuid = UUID.fromString(runId);
            return runRepository.findByRunId(uuid).map(evaluationMapper::toResponse);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Page<EvaluationRunSummary> listRuns(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        return runRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize))
                .map(evaluationMapper::toSummary);
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    private EvaluationPromptResultEntity findPromptResult(UUID runId, int index) {
        return runRepository.findByRunId(runId)
                .map(run -> run.getPromptResults().stream()
                        .filter(r -> r.getPromptIndex() == index)
                        .findFirst().orElse(null))
                .orElse(null);
    }

    /**
     * Extract contradiction severity from judge reason text (heuristic parse).
     * Returns 0.0 if not found.
     */
    Double extractContradictionSeverity(String judgeReason) {
        if (judgeReason == null) return 0.0;
        // Pattern: "contradiction severity (0.XX)"
        var matcher = java.util.regex.Pattern.compile(
                "contradiction severity \\(([0-9.]+)\\)").matcher(judgeReason);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
}

