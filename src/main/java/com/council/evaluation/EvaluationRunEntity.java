package com.council.evaluation;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Top-level evaluation run. Each run contains multiple prompt results.
 */
@Entity
@Table(name = "evaluation_runs")
public class EvaluationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false, unique = true)
    private UUID runId;

    @Column(name = "name", length = 500)
    private String name;

    @Column(name = "tags", length = 2000)
    private String tags;

    @Column(name = "provider_subset", length = 2000)
    private String providerSubset;

    @Column(name = "run_baselines", nullable = false)
    private boolean runBaselines;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private EvaluationStatus status = EvaluationStatus.PENDING;

    @Column(name = "total_prompts", nullable = false)
    private int totalPrompts;

    @Column(name = "completed_prompts", nullable = false)
    private int completedPrompts;

    @Column(name = "failed_prompts", nullable = false)
    private int failedPrompts;

    @Column(name = "aggregate_metrics", length = 100_000)
    private String aggregateMetrics;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "evaluationRun", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("promptIndex ASC")
    private List<EvaluationPromptResultEntity> promptResults = new ArrayList<>();

    protected EvaluationRunEntity() {}

    public EvaluationRunEntity(UUID runId, String name, int totalPrompts) {
        this.runId = runId;
        this.name = name;
        this.totalPrompts = totalPrompts;
        this.createdAt = Instant.now();
    }

    /* ── getters & setters ─────────────────────────────────────────── */

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getProviderSubset() { return providerSubset; }
    public void setProviderSubset(String providerSubset) { this.providerSubset = providerSubset; }

    public boolean isRunBaselines() { return runBaselines; }
    public void setRunBaselines(boolean runBaselines) { this.runBaselines = runBaselines; }

    public EvaluationStatus getStatus() { return status; }
    public void setStatus(EvaluationStatus status) { this.status = status; }

    public int getTotalPrompts() { return totalPrompts; }

    public int getCompletedPrompts() { return completedPrompts; }
    public void setCompletedPrompts(int completedPrompts) { this.completedPrompts = completedPrompts; }

    public int getFailedPrompts() { return failedPrompts; }
    public void setFailedPrompts(int failedPrompts) { this.failedPrompts = failedPrompts; }

    public String getAggregateMetrics() { return aggregateMetrics; }
    public void setAggregateMetrics(String aggregateMetrics) { this.aggregateMetrics = aggregateMetrics; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public List<EvaluationPromptResultEntity> getPromptResults() { return promptResults; }

    public void addPromptResult(EvaluationPromptResultEntity result) {
        result.setEvaluationRun(this);
        this.promptResults.add(result);
    }
}

