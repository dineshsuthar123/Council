package com.council.judge;

import com.council.config.CouncilProperties;
import com.council.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicJudgeTest {

    private DeterministicJudge judge;

    @BeforeEach
    void setUp() {
        CouncilProperties props = new CouncilProperties();
        var gemini = new CouncilProperties.ProviderConfig();
        gemini.setReliability(0.85);
        var deepseek = new CouncilProperties.ProviderConfig();
        deepseek.setReliability(0.75);
        var claude = new CouncilProperties.ProviderConfig();
        claude.setReliability(0.90);
        props.setProviders(Map.of("gemini", gemini, "deepseek", deepseek, "claude", claude));
        judge = new DeterministicJudge(props, new SpecificityScorer());
    }

    @Test
    @DisplayName("No valid drafts returns no-winner result")
    void noValidDrafts() {
        JudgeResult result = judge.evaluate(List.of(), null);
        assertNull(result.winnerProvider());
        assertEquals(0.0, result.winnerScore());
        assertTrue(result.reason().contains("No valid drafts"));
    }

    @Test
    @DisplayName("Single draft is auto-selected")
    void singleDraft() {
        DraftResult only = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer", "summary", List.of(), List.of(), 0.80, 1000, "raw");

        JudgeResult result = judge.evaluate(List.of(only), null);
        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.winnerScore() > 0);
        assertTrue(result.reason().toLowerCase().contains("only one")
                || result.reason().toLowerCase().contains("single"));
    }

    @Test
    @DisplayName("Higher confidence wins when critic is unavailable")
    void higherConfidenceWinsWithoutCritic() {
        DraftResult high = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer-a", "summary-a", List.of(), List.of(), 0.95, 1000, "raw");
        DraftResult low = DraftResult.success("deepseek", "deepseek-chat",
                "answer-b", "summary-b", List.of(), List.of(), 0.50, 1200, "raw");

        JudgeResult result = judge.evaluate(List.of(high, low), null);
        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.winnerScore() > result.rankings().getLast().score());
    }

    @Test
    @DisplayName("With critic available, higher confidence wins under shared critic envelope")
    void higherConfidenceWinsWithSharedCriticEnvelope() {
        DraftResult a = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer-a", "summary-a", List.of(), List.of(), 0.90, 1000, "raw");
        DraftResult b = DraftResult.success("deepseek", "deepseek-chat",
                "answer-b", "summary-b", List.of(), List.of(), 0.82, 1200, "raw");

        CriticResult critic = CriticResult.successFull("claude", "claude-test",
                "summary", 0.6,
                Map.of("gemini", 4, "deepseek", 0),
                List.of(), List.of(), List.of(),
                0.8, 0.75, 0.70,
                0.2, List.of(), false, false,
                "gemini has better operational confidence in this pairwise comparison",
                500, "raw");

        JudgeResult result = judge.evaluate(List.of(a, b), critic);
        assertEquals("gemini", result.winnerProvider());
    }

    @Test
    @DisplayName("Critic failure means no contradiction penalty applied")
    void criticFailureMeansNoPenalty() {
        DraftResult a = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer-a", "summary-a", List.of(), List.of(), 0.90, 1000, "raw");
        DraftResult b = DraftResult.success("deepseek", "deepseek-chat",
                "answer-b", "summary-b", List.of(), List.of(), 0.80, 1200, "raw");

        CriticResult failed = CriticResult.failure("claude", "claude-test", "timeout", 0);

        JudgeResult result = judge.evaluate(List.of(a, b), failed);
        // Gemini should win because it has higher confidence and no penalty
        assertEquals("gemini", result.winnerProvider());
                assertTrue(result.reason().contains("Critic unavailable"));
    }

    @Test
    @DisplayName("Rankings are sorted descending by score")
    void rankingsAreSorted() {
        DraftResult a = DraftResult.success("claude", "claude-test",
                "a", "s", List.of(), List.of(), 0.70, 100, "raw");
        DraftResult b = DraftResult.success("gemini", "gemini-test",
                "b", "s", List.of(), List.of(), 0.90, 100, "raw");
        DraftResult c = DraftResult.success("deepseek", "deepseek-test",
                "c", "s", List.of(), List.of(), 0.80, 100, "raw");

        JudgeResult result = judge.evaluate(List.of(a, b, c), null);

        List<JudgeRanking> rankings = result.rankings();
        assertEquals(3, rankings.size());
        for (int i = 1; i < rankings.size(); i++) {
            assertTrue(rankings.get(i - 1).score() >= rankings.get(i).score(),
                    "Rankings must be sorted descending");
        }
    }

    @Test
    @DisplayName("Verifier fatal throughput contradiction disqualifies draft to zero")
    void verifierThroughputDisqualifiesDraft() {
        DraftResult fatal = DraftResult.success("deepseek", "deepseek-chat",
                "bad math", "summary", List.of(), List.of(), 0.99, 100, "raw");
        DraftResult safe = DraftResult.success("gemini", "gemini-2.5-pro",
                "good math", "summary", List.of(), List.of(), 0.70, 100, "raw");

        CriticResult critic = CriticResult.successFull("claude", "claude-test",
                "summary", 0.2,
                Map.of("deepseek", 0, "gemini", 0),
                List.of(), List.of(), List.of(),
                0.9, 0.9, 0.9,
                0.1, List.of(), false, false,
                "none",
                100, "raw");

        JudgeResult result = judge.evaluate(
                List.of(fatal, safe),
                critic,
                VerifierBatchResult.success(Map.of(
                        "deepseek", VerifierVerdict.disqualifiedThroughput(
                                "Input rate exceeds processing capacity by 25x")
                )),
                TaskType.BACKEND_ARCHITECTURE
        );

        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.reason().contains("Verifier disqualifications"));
        assertTrue(result.reason().contains("processing capacity"));
    }

    @Test
    @DisplayName("Weak stale deletion answer is capped even when provider confidence is high")
    void weakStaleDeletionAnswerIsCappedWithoutCritic() {
        DraftResult weak = DraftResult.success("gemini", "gemini-2.5-pro",
                weakUrlShortenerAnswer(),
                "Return 404, use Redis expiration, fallback to PostgreSQL replicas, and monitor Kafka.",
                List.of(), List.of(), 0.95, 1000, "raw");

        JudgeResult result = judge.evaluate(List.of(weak), null);

        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.winnerScore() <= 0.72,
                "A high-confidence answer missing tombstones, primary reads, and singleflight must not score elite");
    }

    @Test
    @DisplayName("Weak stale deletion answer cannot score elite even with lenient critic scores")
    void weakStaleDeletionAnswerIsCappedWithLenientCritic() {
        DraftResult weak = DraftResult.success("gemini", "gemini-2.5-pro",
                weakUrlShortenerAnswer(),
                "Return 404, use Redis expiration, fallback to PostgreSQL replicas, and monitor Kafka.",
                List.of(), List.of(), 0.95, 1000, "raw");

        CriticResult overlyLenient = CriticResult.successFull("critic", "critic-model",
                "Too generous critic", 0.0,
                Map.of("gemini", 0),
                List.of(), List.of(), List.of(),
                0.95, 0.95, 0.95,
                0.0, List.of(), false, false,
                "gemini sounds complete",
                100, "raw");

        JudgeResult result = judge.evaluate(List.of(weak), overlyLenient);

        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.winnerScore() <= 0.72,
                "Deterministic calibration should cap stale-delete answers that only sound production-grade");
    }

    @Test
    @DisplayName("Maybe-stale lease response is hard capped for redirect correctness")
    void maybeStaleLeaseResponseIsHardCapped() {
        DraftResult dangerous = DraftResult.success("gemini", "gemini-2.5-pro",
                """
                For the deleted URL redirect, Redis may be degraded, PostgreSQL replicas may lag,
                and Kafka analytics consumers may be behind. Use a cache-aside lease mechanism:
                if the lease holder is loading, return a cached lease response indicating the data
                might be stale. Query PostgreSQL primary or a read replica depending on configured
                consistency level. Send an invalidation request to Redis on deletion.
                """,
                "Uses Redis leases, PostgreSQL primary or replicas, and Kafka analytics monitoring.",
                List.of(), List.of(), 0.95, 1000, "raw");

        JudgeResult result = judge.evaluate(List.of(dangerous), null);

        assertTrue(result.winnerScore() <= 0.55,
                "A redirect path must never score highly if it can return maybe-stale content");
        assertTrue(result.reason().contains("Production consistency cap applied"));
    }

    @Test
    @DisplayName("Primary-or-replica consistency hedging is hard capped under known replica lag")
    void primaryOrReplicaHedgingIsHardCapped() {
        DraftResult dangerous = DraftResult.success("gemini", "gemini-2.5-pro",
                """
                The redirect should return 404 if deletion is observed. On Redis miss, query
                PostgreSQL primary or a read replica depending on the configured consistency level.
                Kafka analytics lag should be monitored separately from redirects. Use cache-aside
                lease mechanism for Redis stampede and send an invalidation request to Redis when deleted.
                """,
                "Redis cache-aside, PostgreSQL primary or read replica, Kafka analytics, deleted URL redirect.",
                List.of(), List.of(), 0.95, 1000, "raw");

        JudgeResult result = judge.evaluate(List.of(dangerous), null);

        assertTrue(result.winnerScore() <= 0.55,
                "Known 2 second replica lag with 1 second old deletion must not allow replica hedging");
    }

    @Test
    @DisplayName("Strong stale deletion answer keeps elite confidence")
    void strongStaleDeletionAnswerKeepsHighConfidence() {
        DraftResult strong = DraftResult.success("claude", "claude-test",
                """
                Return 404 Not Found, or 410 Gone if the product intentionally reveals deletion history.
                Do not trust a lagging PostgreSQL replica during the 2 second replica lag window after a
                deletion 1 second ago. On delete, write deleted_at and a monotonic version to the primary,
                overwrite Redis alias:abc123 with a short-lived DELETED tombstone/negative-cache value,
                and invalidate the active redirect key. Redirect reads treat the tombstone as authoritative;
                if Redis times out, they read the primary or verify the deletion version before redirecting.
                Cache stampede is handled with singleflight/request coalescing or a per-key distributed lock
                so only one request loads the alias while others wait briefly. Kafka analytics consumer lag
                and stale dashboards do not affect redirect correctness. Metrics include Redis p99,
                replica lag, primary fallback rate, tombstone hits, lock wait time, and Kafka consumer lag.
                Pseudocode: if tombstone return 404; if cache hit redirect; acquire per-key lock;
                read primary when recent delete is possible; cache DELETED or active version; emit analytics async.
                """,
                "Uses tombstone, primary read, version check, singleflight, and analytics separation.",
                List.of(), List.of(), 0.95, 1000, "raw");

        JudgeResult result = judge.evaluate(List.of(strong), null);

        assertEquals("claude", result.winnerProvider());
        assertEquals(0.95, result.winnerScore(), 0.001);
    }

    private String weakUrlShortenerAnswer() {
        return """
                The redirect endpoint should return 404 because the short URL was deleted.
                Redis degradation means the app should use Redis built-in expiration or a cache
                invalidation queue to prevent stale redirects. If Redis misses or times out,
                fallback to PostgreSQL read replicas with exponential backoff. Kafka analytics
                consumers are lagging, so monitoring dashboards may still show successful redirects.
                For a cache stampede, retry Redis and PostgreSQL with exponential backoff.
                Pseudocode: check Redis, fallback to PostgreSQL, if found check deletion timestamp,
                redirect if active, otherwise return 404, then write analytics to Kafka.
                """;
    }
}


