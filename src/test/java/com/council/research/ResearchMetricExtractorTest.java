package com.council.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResearchMetricExtractorTest {

    private final ResearchMetricExtractor extractor = new ResearchMetricExtractor();

    @Test
    void extractsProviderComparisonMetricsFromRegisteredEvidence() {
        ResearchPack pack = ResearchPack.withEvidence(
                "Prompt-provided provider comparison evidence.",
                List.of(),
                List.of(
                        source("S4", "GitHub issue: provider B returned repeated 429 errors and rate limits."),
                        source("S6", """
                                Internal trace metrics:
                                provider A success rate: 99.2%
                                provider A p95 latency: 2.8s
                                provider A average cost per 1K requests: $0.42
                                provider A had no major degraded windows
                                provider B success rate: 96.4%
                                provider B p95 latency: 4.9s
                                provider B average cost per 1K requests: $0.21
                                provider B had two 30-minute degraded windows
                                provider B batch API is cheaper but has delayed completion.
                                """)
                ),
                null,
                List.of());

        ResearchMetricExtractor.ProviderEvidenceComparison comparison = extractor.extract(pack);

        assertEquals(99.2, comparison.providerA().successRate());
        assertEquals(96.4, comparison.providerB().successRate());
        assertEquals(2.8, comparison.providerA().p95LatencySeconds());
        assertEquals(4.9, comparison.providerB().p95LatencySeconds());
        assertEquals(0.42, comparison.providerA().averageCostPer1KRequests());
        assertEquals(0.21, comparison.providerB().averageCostPer1KRequests());
        assertEquals(0, comparison.providerA().degradedWindowCount());
        assertEquals(2, comparison.providerB().degradedWindowCount());
        assertEquals(30, comparison.providerB().degradedWindowMinutes());
        assertEquals("B", comparison.cheaperProvider());
        assertEquals("A", comparison.fasterProvider());
        assertEquals("A", comparison.moreReliableProvider());
        assertEquals("B", comparison.rateLimitRiskProvider());
        assertTrue(comparison.providerB().rateLimitRisk());
        assertTrue(comparison.providerB().batchDelayCaveat());
        assertTrue(comparison.providerB().sourceIds().contains("S6"));
        assertTrue(comparison.evidenceSummary().contains("cheaper=provider B"));
    }

    private ResearchSource source(String id, String snippet) {
        return new ResearchSource(id, id + " evidence", null, null, snippet, "recent", 0.9,
                SourceType.INTERNAL_TRACE,
                EvidenceOrigin.INTERNAL_TRACE,
                "2026-06-19T00:00:00Z",
                "recent",
                0.9,
                0.9,
                InjectionRisk.LOW,
                true,
                java.util.Map.of("parser", "prompt-provided"));
    }
}
