package com.council.research;

import com.council.judge.TaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchSourceRelevanceScorerTest {

    private final ResearchSourceRelevanceScorer scorer = new ResearchSourceRelevanceScorer();
    private static final String PROVIDER_MIGRATION_QUERY =
            "Compare Provider A and Provider B API pricing, reliability, latency, and rate limits for a migration.";

    @Test
    void genericAiTrendsAreLowRelevanceForProviderMigration() {
        ResearchSource source = new ResearchSource("S7", "Microsoft AI trends 2026",
                "https://example.com/ai-trends", "example.com",
                "A broad overview of the future of AI.", "2026-06-01", 0.92);

        ResearchSourceRelevanceScorer.Assessment assessment = scorer.assess(
                PROVIDER_MIGRATION_QUERY, TaskType.RESEARCH_REQUIRED, source);

        assertTrue(assessment.relevanceScore() < 0.45);
        assertNotNull(assessment.excludedReason());
    }

    @Test
    void officialProviderPricingIsHighRelevance() {
        ResearchSource source = new ResearchSource("S1", "Provider A API pricing",
                "https://docs.provider-a.example/pricing", "docs.provider-a.example",
                "Official Provider A API pricing and token costs.", "2026-06-01", 0.95,
                SourceType.OFFICIAL_DOC, EvidenceOrigin.EXTERNAL_RESEARCH, null, "2026-06-01",
                0.95, 0.90, InjectionRisk.LOW, true, java.util.Map.of());

        ResearchSourceRelevanceScorer.Assessment assessment = scorer.assess(
                PROVIDER_MIGRATION_QUERY, TaskType.RESEARCH_REQUIRED, source);

        assertTrue(assessment.relevanceScore() >= 0.45);
        assertTrue(assessment.isIncluded());
    }

    @Test
    void providerRateLimitIssueIsHighRelevance() {
        ResearchSource source = new ResearchSource("S4", "Provider B 429 rate-limit issue",
                "https://github.com/provider-b/api/issues/42", "github.com",
                "Provider B API requests returned HTTP 429 during traffic spikes.", "2026-06-01", 0.78,
                SourceType.ISSUE, EvidenceOrigin.EXTERNAL_RESEARCH, null, "2026-06-01",
                0.72, 0.86, InjectionRisk.LOW, true, java.util.Map.of());

        ResearchSourceRelevanceScorer.Assessment assessment = scorer.assess(
                PROVIDER_MIGRATION_QUERY, TaskType.RESEARCH_REQUIRED, source);

        assertTrue(assessment.relevanceScore() >= 0.45);
        assertTrue(assessment.isIncluded());
    }
}
