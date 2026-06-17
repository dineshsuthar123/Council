package com.council.judge.invariant;

import com.council.judge.ProductionConsistencyCalibrator;
import com.council.judge.ResearchQualityCalibrator;
import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class InvariantViolationCriticTest {

    private final InvariantViolationCritic critic = new InvariantViolationCritic();

    @Test
    @DisplayName("URL shortener unsafe answer is capped by deletion, replica, and stampede invariants")
    void unsafeUrlShortenerAnswerIsCapped() {
        String prompt = """
                A URL-shortener uses Redis, PostgreSQL replicas, Kafka analytics, and Spring services.
                Redis has stale cache. Replicas are 2 seconds behind. Alias abc123 was deleted 1 second ago.
                Two app instances receive the same cache miss.
                """;
        String answer = """
                Return the cached redirect if Redis has it. Otherwise read from the PostgreSQL replica
                for low latency. Redis TTL will eventually expire the old value, and analytics dashboards
                can be checked later.
                """;

        InvariantCriticResult result = critic.evaluate(prompt, answer);

        assertTrue(result.hasViolations());
        assertTrue(result.overallCap() <= 0.55);
        assertViolated(result, InvariantLibrary.URL_DELETED_ALIAS_MUST_NOT_REDIRECT);
        assertViolated(result, InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE);
        assertViolated(result, InvariantLibrary.URL_REPLICA_LAG_UNSAFE_AFTER_DELETE);
        assertViolated(result, InvariantLibrary.URL_STAMPEDE_SINGLEFLIGHT);
    }

    @Test
    @DisplayName("Strong tombstone-first URL answer keeps high score")
    void strongUrlShortenerAnswerScoresHigh() {
        String prompt = """
                A URL-shortener uses Redis, PostgreSQL replicas, Kafka analytics, and Spring services.
                Replicas are 2 seconds behind and alias abc123 was deleted 1 second ago during a traffic spike.
                """;
        String answer = """
                Return 404 Not Found or 410 Gone because the alias was deleted 1 second ago.
                The delete path writes deleted_at/version in the primary database, deletes the old active
                cache value, and writes Redis tombstone alias:abc123=DELETED with a short TTL. Redirect
                correctness is independent from Kafka analytics freshness; dashboards can be stale.
                Do not trust lagging replicas in the deletion window; read primary or use delete-version checks.
                Use singleflight/request coalescing/per-key lock for cache misses.
                Monitor Redis p95/p99 latency and timeouts, replica lag, Kafka consumer lag, tombstone hits,
                primary fallback rate, lock wait, dashboard freshness, and stale-cache prevention. The tradeoff
                is correctness and consistency over stale availability for deleted aliases, while analytics stays
                eventually consistent. A weaker system would trust TTLs, lagging replicas, stale leases,
                missing tombstones, no coalescing, and delayed analytics dashboards.
                Pseudocode:
                if (cached == DELETED) return 404;
                if (cached == ACTIVE && cached.version >= minKnownDeleteVersion(alias)) return redirect(cached.url);
                return singleflight(alias, () -> {
                    row = primaryDb.findByAlias(alias);
                    if (row == null || row.deleted_at != null) {
                        redis.set(alias, DELETED, 60);
                        return 404;
                    }
                    redis.set(alias, ACTIVE(row.url, row.version), 300);
                    return redirect(row.url);
                });
                """;

        InvariantCriticResult result = critic.evaluate(prompt, answer);
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore(answer, null, 0.95, result);

        assertFalse(result.hasViolations(), () -> "Unexpected violations: " + result.violations());
        assertTrue(quality.score() >= 0.84, () -> "score=" + quality.score());
        assertEquals(1.0, quality.dimensions().get("invariant_url_shortener"));
    }

    @Test
    @DisplayName("Payment idempotency body mismatch is a first-class invariant")
    void paymentBodyMismatchViolationIsDetected() {
        String prompt = """
                Two payment transfer requests reuse the same idempotency key with different request body:
                first transfers 100 USD from A to B, second transfers 900 USD from A to C.
                PostgreSQL stores balances, Redis caches idempotency status, and Kafka publishes events.
                """;
        String answer = """
                If the idempotency key exists, return the stored result. Otherwise debit the source,
                credit the destination, and publish a Kafka event.
                """;

        InvariantCriticResult result = critic.evaluate(prompt, answer);

        assertTrue(result.overallCap() <= 0.70);
        assertViolated(result, InvariantLibrary.PAYMENT_IDEMPOTENCY_BODY_MISMATCH);
        assertViolated(result, InvariantLibrary.PAYMENT_TRANSACTIONAL_OUTBOX);
    }

    @Test
    @DisplayName("Safe payment answer satisfies idempotency, atomicity, outbox, and recovery invariants")
    void safePaymentAnswerAvoidsInvariantViolations() {
        String prompt = """
                A transfer service can crash during processing, clients retry with the same idempotency key,
                and Kafka events drive downstream settlement.
                """;
        String answer = """
                The wallet transfer must succeed exactly once or remain PROCESSING/PENDING_REVIEW; U1 must
                never be debited twice and U2 must never be missed after U1 is debited. Redis is only a
                fast-path cache and PostgreSQL is the source of truth.
                Pseudocode:
                begin transaction;
                idem = insert or lock idempotency record by idempotency key and request hash;
                if same key has different payload hash return 409 idempotency conflict;
                if idem.status == SUCCEEDED return stored response;
                if idem.status == PROCESSING return 202 current status;
                lock both wallet rows with SELECT FOR UPDATE in deterministic order by account id;
                verify source has sufficient balance;
                debit U1 and credit U2 atomically in the same transaction;
                mark transfer SUCCEEDED and store final response in the idempotency record;
                insert transactional outbox event with unique event id in the same transaction;
                commit;
                an outbox worker publishes Kafka events idempotently after commit. Crash recovery resumes from
                stored status and reconciliation checks balance invariant / ledger invariant drift, so retries
                never double debit or double capture. Observe idempotency replay/conflict rate, row lock wait,
                outbox lag, Kafka publish failures, reconciliation drift, pending_review counts, and transaction
                id/correlation id logs. A weaker system would return failure for the same key, trust Redis as
                source of truth, emit Kafka before commit, create idempotency after debit, and split debit/credit.
                """;

        InvariantCriticResult result = critic.evaluate(prompt, answer);
        ProductionConsistencyCalibrator.QualityScore quality =
                ProductionConsistencyCalibrator.qualityScore(answer, null, 0.90, result);

        assertFalse(result.hasViolations(), () -> "Unexpected violations: " + result.violations());
        assertTrue(quality.score() >= 0.84, () -> "score=" + quality.score());
        assertEquals(1.0, quality.dimensions().get("invariant_payment_transfer"));
    }

    @Test
    @DisplayName("Research answer without citations or conflict handling is capped")
    void researchEvidenceViolationsCapAnswer() {
        ResearchPack pack = conflictingResearchPack();
        String answer = """
                The latest product launch happened in March and is currently fully available.
                """;

        InvariantCriticResult result = critic.evaluate(
                "Answer the current question using conflicting sources.", answer, pack);
        ResearchQualityCalibrator.QualityScore quality =
                ResearchQualityCalibrator.qualityScore(answer, pack, 0.92, result);

        assertTrue(result.overallCap() <= 0.70);
        assertTrue(quality.score() <= 0.70);
        assertViolated(result, InvariantLibrary.RESEARCH_CITES_EVIDENCE);
        assertViolated(result, InvariantLibrary.RESEARCH_CONFLICT_HANDLING);
        assertViolated(result, InvariantLibrary.RESEARCH_NO_UNSUPPORTED_CURRENT_CLAIMS);
    }

    @Test
    @DisplayName("Research answer with valid citations and conflict handling remains high")
    void citedResearchAnswerAvoidsViolations() {
        ResearchPack pack = conflictingResearchPack();
        String answer = """
                As of the cited evidence, the sources conflict: S1 reports a March launch, while S2 says
                the launch was delayed until May. I would treat the May claim as better supported for the
                final status because it is the more recent dated source [S2], while noting the older March
                report as superseded context [S1].
                """;

        InvariantCriticResult result = critic.evaluate(
                "Answer the latest status using conflicting sources.", answer, pack);
        ResearchQualityCalibrator.QualityScore quality =
                ResearchQualityCalibrator.qualityScore(answer, pack, 0.92, result);

        assertFalse(result.hasViolations(), () -> "Unexpected violations: " + result.violations());
        assertTrue(quality.score() >= 0.78, () -> "score=" + quality.score());
        assertEquals(1.0, quality.dimensions().get("invariant_research_evidence"));
    }

    private ResearchPack conflictingResearchPack() {
        return ResearchPack.withSources(
                "Prompt asks for current information with conflicting sources.",
                List.of("latest product launch status"),
                List.of(
                        new ResearchSource("S1", "March launch report", "https://example.com/march",
                                "example.com", "Feature launched in March.", "2026-03-20", 0.88),
                        new ResearchSource("S2", "May delay update", "https://example.com/may",
                                "example.com", "Conflicting report: launch delayed until May.", "2026-05-05", 0.94)
                ));
    }

    private void assertViolated(InvariantCriticResult result, String id) {
        Set<String> ids = result.violations().stream()
                .map(InvariantViolation::invariantId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains(id), () -> "Expected violation " + id + " in " + ids);
    }
}
