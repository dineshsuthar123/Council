package com.council.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionConsistencyCalibratorTest {

    @Test
    @DisplayName("Strong consistency answer with vague algorithm scores high but not elite")
    void strongConsistencyAnswerWithVagueAlgorithmScoresHighButNotElite() {
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore("""
                        Use deleted_at/version/tombstone in the primary database and write a Redis
                        DELETED negative-cache tombstone so stale redirects are suppressed even when Redis
                        is degraded. The redirect path must not trust a PostgreSQL replica that is 2 seconds
                        behind during a 1-second-old deletion window; use a primary read or safe version check.
                        Cache stampede should be controlled with singleflight/request coalescing/per-key lock.
                        Redirect correctness is independent from Kafka analytics freshness and dashboards.
                        Monitor Redis p95/p99 latency, replica lag, Kafka consumer lag, tombstone hits,
                        primary fallback rate, lock wait, dashboard freshness, and stale-cache prevention.
                        The tradeoff is choosing correctness over stale availability on the redirect path,
                        while analytics can remain eventually consistent with lower latency pressure.
                        The redirect algorithm is checking Redis cache for a valid redirect, falling back to
                        a primary database read, and writing a tombstone or active redirect value.
                        A weaker system would trust Redis TTL, trust lagging replicas, serve maybe-stale leases,
                        omit tombstones, skip coalescing, and confuse analytics dashboards with redirect truth.
                        """, null, 0.55);

        assertTrue(quality.score() >= 0.74 && quality.score() <= 0.82,
                "The answer is strong but should lose points for vague algorithm and weak direct endpoint decision");
        assertDimensionAtLeast(quality.dimensions(), "deletion_safety", 0.90);
        assertDimensionAtLeast(quality.dimensions(), "replica_lag_awareness", 0.85);
        assertDimensionAtLeast(quality.dimensions(), "stampede_control", 0.85);
        assertTrue(quality.dimensions().get("pseudocode") <= 0.45);
    }

    @Test
    @DisplayName("Dangerous maybe-stale redirect response remains hard capped")
    void dangerousMaybeStaleRedirectResponseRemainsHardCapped() {
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore("""
                        The deleted short URL redirect uses Redis, PostgreSQL replicas, and Kafka analytics.
                        During Redis degradation, use a cache-aside lease mechanism and return a cached lease
                        response indicating the redirect data might be stale. Query PostgreSQL primary or a
                        read replica depending on configured consistency level and send an invalidation request
                        to Redis when deleted.
                        """, null, 0.95);

        assertTrue(quality.score() <= 0.55,
                "Returning maybe-stale content from the synchronous redirect path is a hard correctness bug");
    }

    @Test
    @DisplayName("Concrete tombstone-first pseudocode lifts algorithm dimension")
    void concreteTombstoneFirstPseudocodeLiftsAlgorithmDimension() {
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore("""
                        Return 404 Not Found or 410 Gone because the URL was deleted 1 second ago.
                        Write deleted_at/version/tombstone to the primary database and a Redis DELETED
                        negative-cache tombstone. Do not trust a PostgreSQL replica during the 2 second lag
                        window; read primary or check delete version. Use singleflight/request coalescing/per-key
                        lock for stampede control. Kafka analytics lag and dashboards are not redirect truth.
                        Monitor Redis p99 latency, replica lag, Kafka consumer lag, tombstone hits, primary
                        fallback rate, lock wait, dashboard freshness, and stale-cache prevention. The tradeoff
                        is correctness and consistency over low latency/stale availability for deleted aliases,
                        while analytics remains eventually consistent. A weaker system would trust TTLs,
                        replicas, maybe-stale leases, and delayed analytics.
                        Pseudocode:
                        if (cached == DELETED) return 404;
                        if (cached == ACTIVE && cached.version >= minKnownDeleteVersion(alias)) return redirect;
                        return singleflight(alias, () -> {
                            row = primaryDb.findByAlias(alias);
                            if (row == null || row.deleted_at != null) {
                                redis.set(alias, DELETED, 60);
                                return 404;
                            }
                            redis.set(alias, ACTIVE(row.url, row.version), 300);
                            return redirect(row.url);
                        });
                        """, null, 0.55);

        assertTrue(quality.score() >= 0.84);
        assertDimensionAtLeast(quality.dimensions(), "pseudocode", 0.85);
        assertDimensionAtLeast(quality.dimensions(), "correct_endpoint_decision", 0.90);
    }

    private void assertDimensionAtLeast(Map<String, Double> dimensions, String key, double min) {
        assertTrue(dimensions.containsKey(key), "Missing dimension " + key);
        assertTrue(dimensions.get(key) >= min, key + " should be at least " + min);
    }
}
