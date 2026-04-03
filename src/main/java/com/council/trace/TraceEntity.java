package com.council.trace;

import com.council.common.TraceStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code reasoning_traces} table.
 * Complex fields are stored as JSONB strings.
 */
@Entity
@Table(name = "reasoning_traces")
public class TraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trace_id", nullable = false, unique = true)
    private UUID traceId;

    @Column(name = "user_query", nullable = false)
    private String userQuery;

    @Column(name = "draft_results", length = 100_000)
    private String draftResults;

    @Column(name = "raw_responses", length = 100_000)
    private String rawResponses;

    @Column(name = "critic_result", length = 100_000)
    private String criticResult;

    @Column(name = "judge_result", length = 50_000)
    private String judgeResult;

    @Column(name = "final_answer", length = 50_000)
    private String finalAnswer;

    @Column(name = "final_confidence")
    private Double finalConfidence;

    @Column(name = "judge_reason", length = 10_000)
    private String judgeReason;

    @Column(name = "used_providers", length = 2000)
    private String usedProviders;

    @Column(name = "failed_providers", length = 2000)
    private String failedProviders;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TraceStatus status = TraceStatus.PENDING;

    @Column(name = "error_message", length = 10_000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TraceEntity() {}

    public TraceEntity(UUID traceId, String userQuery) {
        this.traceId = traceId;
        this.userQuery = userQuery;
        this.createdAt = Instant.now();
    }

    /* ── getters & setters ─────────────────────────────────────────── */

    public UUID getId() { return id; }
    public UUID getTraceId() { return traceId; }
    public String getUserQuery() { return userQuery; }
    public String getDraftResults() { return draftResults; }
    public void setDraftResults(String draftResults) { this.draftResults = draftResults; }
    public String getRawResponses() { return rawResponses; }
    public void setRawResponses(String rawResponses) { this.rawResponses = rawResponses; }
    public String getCriticResult() { return criticResult; }
    public void setCriticResult(String criticResult) { this.criticResult = criticResult; }
    public String getJudgeResult() { return judgeResult; }
    public void setJudgeResult(String judgeResult) { this.judgeResult = judgeResult; }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
    public Double getFinalConfidence() { return finalConfidence; }
    public void setFinalConfidence(Double finalConfidence) { this.finalConfidence = finalConfidence; }
    public String getJudgeReason() { return judgeReason; }
    public void setJudgeReason(String judgeReason) { this.judgeReason = judgeReason; }
    public String getUsedProviders() { return usedProviders; }
    public void setUsedProviders(String usedProviders) { this.usedProviders = usedProviders; }
    public String getFailedProviders() { return failedProviders; }
    public void setFailedProviders(String failedProviders) { this.failedProviders = failedProviders; }
    public Long getTotalLatencyMs() { return totalLatencyMs; }
    public void setTotalLatencyMs(Long totalLatencyMs) { this.totalLatencyMs = totalLatencyMs; }
    public TraceStatus getStatus() { return status; }
    public void setStatus(TraceStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}

