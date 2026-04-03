package com.council.evaluation;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-prompt result within an evaluation run.
 */
@Entity
@Table(name = "evaluation_prompt_results")
public class EvaluationPromptResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluation_run_id", nullable = false)
    private EvaluationRunEntity evaluationRun;

    @Column(name = "prompt_index", nullable = false)
    private int promptIndex;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    @Column(name = "expected_keywords", length = 5000)
    private String expectedKeywords;

    /* ── council results ───────────────────────────────────────────── */

    @Column(name = "council_trace_id", length = 100)
    private String councilTraceId;

    @Column(name = "council_answer", columnDefinition = "TEXT")
    private String councilAnswer;

    @Column(name = "council_confidence")
    private Double councilConfidence;

    @Column(name = "council_latency_ms")
    private Long councilLatencyMs;

    @Column(name = "council_winner_provider", length = 200)
    private String councilWinnerProvider;

    @Column(name = "council_contradiction_severity")
    private Double councilContradictionSeverity;

    @Column(name = "council_used_providers", length = 2000)
    private String councilUsedProviders;

    @Column(name = "council_failed_providers", length = 2000)
    private String councilFailedProviders;

    @Column(name = "council_judge_reason", columnDefinition = "TEXT")
    private String councilJudgeReason;

    @Column(name = "council_answer_length")
    private Integer councilAnswerLength;

    /* ── baselines & scoring ───────────────────────────────────────── */

    @Column(name = "baseline_results", length = 100_000)
    private String baselineResults;

    @Column(name = "keyword_match_score")
    private Double keywordMatchScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private EvaluationStatus status = EvaluationStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EvaluationPromptResultEntity() {}

    public EvaluationPromptResultEntity(int promptIndex, String prompt) {
        this.promptIndex = promptIndex;
        this.prompt = prompt;
        this.createdAt = Instant.now();
    }

    /* ── getters & setters ─────────────────────────────────────────── */

    public UUID getId() { return id; }

    public EvaluationRunEntity getEvaluationRun() { return evaluationRun; }
    public void setEvaluationRun(EvaluationRunEntity evaluationRun) { this.evaluationRun = evaluationRun; }

    public int getPromptIndex() { return promptIndex; }
    public String getPrompt() { return prompt; }

    public String getExpectedAnswer() { return expectedAnswer; }
    public void setExpectedAnswer(String expectedAnswer) { this.expectedAnswer = expectedAnswer; }

    public String getExpectedKeywords() { return expectedKeywords; }
    public void setExpectedKeywords(String expectedKeywords) { this.expectedKeywords = expectedKeywords; }

    public String getCouncilTraceId() { return councilTraceId; }
    public void setCouncilTraceId(String councilTraceId) { this.councilTraceId = councilTraceId; }

    public String getCouncilAnswer() { return councilAnswer; }
    public void setCouncilAnswer(String councilAnswer) { this.councilAnswer = councilAnswer; }

    public Double getCouncilConfidence() { return councilConfidence; }
    public void setCouncilConfidence(Double councilConfidence) { this.councilConfidence = councilConfidence; }

    public Long getCouncilLatencyMs() { return councilLatencyMs; }
    public void setCouncilLatencyMs(Long councilLatencyMs) { this.councilLatencyMs = councilLatencyMs; }

    public String getCouncilWinnerProvider() { return councilWinnerProvider; }
    public void setCouncilWinnerProvider(String p) { this.councilWinnerProvider = p; }

    public Double getCouncilContradictionSeverity() { return councilContradictionSeverity; }
    public void setCouncilContradictionSeverity(Double s) { this.councilContradictionSeverity = s; }

    public String getCouncilUsedProviders() { return councilUsedProviders; }
    public void setCouncilUsedProviders(String p) { this.councilUsedProviders = p; }

    public String getCouncilFailedProviders() { return councilFailedProviders; }
    public void setCouncilFailedProviders(String p) { this.councilFailedProviders = p; }

    public String getCouncilJudgeReason() { return councilJudgeReason; }
    public void setCouncilJudgeReason(String r) { this.councilJudgeReason = r; }

    public Integer getCouncilAnswerLength() { return councilAnswerLength; }
    public void setCouncilAnswerLength(Integer len) { this.councilAnswerLength = len; }

    public String getBaselineResults() { return baselineResults; }
    public void setBaselineResults(String baselineResults) { this.baselineResults = baselineResults; }

    public Double getKeywordMatchScore() { return keywordMatchScore; }
    public void setKeywordMatchScore(Double keywordMatchScore) { this.keywordMatchScore = keywordMatchScore; }

    public EvaluationStatus getStatus() { return status; }
    public void setStatus(EvaluationStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
}

