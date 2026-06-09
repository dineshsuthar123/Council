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

    @Test
    @DisplayName("Cached redirect before tombstone is penalized in pseudocode")
    void cachedRedirectBeforeTombstoneIsPenalizedInPseudocode() {
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore("""
                        Return 404 Not Found or 410 Gone because the URL was deleted 1 second ago.
                        Write deleted_at/version/tombstone to the primary database and a Redis DELETED
                        negative-cache tombstone. Do not trust a PostgreSQL replica during the 2 second lag
                        window; use primary reads or a delete version check. Use singleflight/request coalescing
                        for stampede control. Kafka analytics lag and dashboards are not redirect truth.
                        Monitor Redis p99 latency, replica lag, Kafka consumer lag, tombstone hits, primary
                        fallback rate, lock wait, dashboard freshness, and stale-cache prevention. The tradeoff
                        is correctness over stale availability for deleted aliases, while analytics remains
                        eventually consistent. A weaker system would trust TTLs, replicas, stale leases, and analytics.
                        Pseudocode:
                        cachedRedirect = redis.get(alias);
                        if (cachedRedirect && !isTombstoned(redis, alias)) {
                            return redirect(cachedRedirect.url);
                        }
                        if (redis.get(alias) == DELETED) return 404;
                        return singleflight(alias, () -> primaryDb.findByAlias(alias));
                        """, null, 0.95);

        assertTrue(quality.dimensions().get("pseudocode") <= 0.75,
                "Tombstone must have precedence over active cached redirects");
        assertTrue(quality.score() < 0.86,
                "The overall answer can remain strong, but unsafe precedence should keep it below elite");
    }

    @Test
    @DisplayName("Unsafe wallet transfer algorithm is hard capped by payment rubric")
    void unsafeWalletTransferAlgorithmIsHardCappedByPaymentRubric() {
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore("""
                        For a wallet payment transfer from U1 to U2 with Redis, PostgreSQL, and Kafka:
                        Pseudocode:
                        1. Receive transfer request with idempotency key.
                        2. Check Redis for existing idempotency key; if found, return failure.
                        3. Begin PostgreSQL transaction.
                        4. Debit U1.
                        5. Emit Kafka event for debit.
                        6. Credit U2.
                        7. Emit Kafka event for credit.
                        8. Commit PostgreSQL transaction.
                        9. Store idempotency record in PostgreSQL.
                        Fraud detection should never block synchronously; handle it async later.
                        """, null, 0.95);

        assertTrue(quality.score() <= 0.50,
                "Dangerous payment ordering must not be scored as production-grade");
        assertTrue(quality.dimensions().containsKey("idempotency_safety"));
        assertFalse(quality.dimensions().containsKey("deletion_safety"),
                "Wallet transfers must not reuse URL-shortener dimension labels");
        assertTrue(quality.dimensions().get("idempotency_safety") <= 0.25);
        assertTrue(quality.dimensions().get("kafka_outbox_safety") <= 0.25);
        assertTrue(quality.dimensions().get("pseudocode") <= 0.20);
        assertTrue(quality.reasons().stream().anyMatch(reason -> reason.contains("same idempotency key")));
        assertTrue(quality.reasons().stream().anyMatch(reason -> reason.contains("outbox")));
    }

    @Test
    @DisplayName("Safe wallet transfer algorithm receives payment-specific dimensions")
    void safeWalletTransferAlgorithmReceivesPaymentSpecificDimensions() {
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore("""
                        The wallet transfer must succeed exactly once or remain PROCESSING/PENDING_REVIEW;
                        U1 must never be debited twice and U2 must never be missed after U1 is debited.
                        Redis is only a fast-path cache. PostgreSQL is the source of truth.
                        Pseudocode:
                        begin transaction;
                        idem = insert or lock idempotency record by idempotency key and request hash;
                        if idem.status == SUCCEEDED return stored response;
                        if idem.status == PROCESSING return 202 current status;
                        lock both wallet rows with SELECT FOR UPDATE in deterministic order by account id;
                        verify U1 has sufficient balance;
                        insert transfer row status PROCESSING;
                        debit U1 and credit U2 atomically in the same transaction;
                        mark transfer SUCCEEDED and store final response in the idempotency record;
                        insert transactional outbox event with unique event id in the same transaction;
                        commit;
                        an outbox worker or Debezium publisher sends Kafka events idempotently after commit.
                        If fraud is advisory, run it async; if policy must block risky transfers, run synchronous
                        risk checks before committing or mark the transfer PENDING_REVIEW with compensation rules.
                        Observe idempotency replay/conflict rate, row lock wait/deadlocks, outbox lag, Kafka publish
                        failures, balance invariant violations, reconciliation drift, pending_review counts,
                        transaction id/correlation id logs, and duplicate event IDs.
                        A weaker system would return failure for the same idempotency key, trust Redis as source
                        of truth, emit Kafka before commit, create idempotency after debit, split debit and credit,
                        and make fraud async-only without policy conditions.
                        """, null, 0.80);

        assertTrue(quality.score() >= 0.84,
                () -> "Safe payment ordering should score strongly, score=" + quality.score()
                        + ", dimensions=" + quality.dimensions()
                        + ", reasons=" + quality.reasons());
        assertDimensionAtLeast(quality.dimensions(), "idempotency_safety", 0.88);
        assertDimensionAtLeast(quality.dimensions(), "atomicity", 0.90);
        assertDimensionAtLeast(quality.dimensions(), "kafka_outbox_safety", 0.88);
        assertDimensionAtLeast(quality.dimensions(), "redis_role", 0.88);
    }

    private void assertDimensionAtLeast(Map<String, Double> dimensions, String key, double min) {
        assertTrue(dimensions.containsKey(key), "Missing dimension " + key);
        assertTrue(dimensions.get(key) >= min, key + " should be at least " + min);
    }
}
