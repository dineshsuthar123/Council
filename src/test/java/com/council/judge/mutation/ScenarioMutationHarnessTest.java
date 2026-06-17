package com.council.judge.mutation;

import com.council.judge.invariant.InvariantCriticResult;
import com.council.judge.invariant.InvariantDomain;
import com.council.judge.invariant.InvariantLibrary;
import com.council.judge.invariant.InvariantViolation;
import com.council.judge.invariant.InvariantViolationCritic;
import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioMutationHarnessTest {

    private final ScenarioMutationHarness harness = new ScenarioMutationHarness();
    private final InvariantViolationCritic critic = new InvariantViolationCritic();

    @Test
    @DisplayName("Harness exposes at least ten unique hard mutations")
    void harnessExposesTenUniqueMutations() {
        List<ScenarioMutation> mutations = harness.allHardMutations();

        assertTrue(mutations.size() >= 10, "Expected at least 10 mutation-based test cases");
        assertEquals(mutations.size(), mutations.stream().map(ScenarioMutation::id).collect(Collectors.toSet()).size(),
                "Mutation ids must be unique");
        assertTrue(mutations.stream().anyMatch(m -> m.domain() == InvariantDomain.PAYMENT_TRANSFER));
        assertTrue(mutations.stream().anyMatch(m -> m.domain() == InvariantDomain.URL_SHORTENER));
        assertTrue(mutations.stream().anyMatch(m -> m.domain() == InvariantDomain.RESEARCH_EVIDENCE));
    }

    @Test
    @DisplayName("Unsafe answers trigger expected invariant ids and stay below golden caps")
    void unsafeAnswersTriggerExpectedInvariantsAndCaps() {
        for (ScenarioMutation mutation : harness.allHardMutations()) {
            InvariantCriticResult result = critic.evaluate(
                    mutation.prompt(),
                    unsafeAnswerFor(mutation),
                    researchPackFor(mutation));

            Set<String> violatedIds = result.violations().stream()
                    .map(InvariantViolation::invariantId)
                    .collect(Collectors.toCollection(HashSet::new));

            assertTrue(result.evaluated(), "Invariant critic should evaluate " + mutation.id());
            assertTrue(result.overallCap() <= mutation.goldenMaxScore() + 0.0001,
                    () -> mutation.id() + " expected cap <= " + mutation.goldenMaxScore()
                            + " but was " + result.overallCap()
                            + " violations=" + violatedIds);
            assertTrue(violatedIds.containsAll(mutation.expectedInvariantIds()),
                    () -> mutation.id() + " missing expected ids. expected="
                            + mutation.expectedInvariantIds() + " actual=" + violatedIds);
        }
    }

    @Test
    @DisplayName("Strong mutated answers avoid the critical invariant caps")
    void strongMutatedAnswersAvoidCriticalCaps() {
        ScenarioMutation payment = harness.paymentIdempotencyBodyMismatch();
        InvariantCriticResult paymentResult = critic.evaluate(payment.prompt(), strongPaymentAnswer());
        assertFalse(paymentResult.hasViolations(), () -> "Unexpected payment violations: " + paymentResult.violations());

        ScenarioMutation url = harness.urlCacheDownReplicaLag();
        InvariantCriticResult urlResult = critic.evaluate(url.prompt(), strongUrlAnswer());
        assertFalse(urlResult.hasViolations(), () -> "Unexpected URL violations: " + urlResult.violations());

        ScenarioMutation research = harness.researchConflictingSources();
        InvariantCriticResult researchResult = critic.evaluate(
                research.prompt(), strongResearchAnswer(), researchPackFor(research));
        assertFalse(researchResult.hasViolations(),
                () -> "Unexpected research violations: " + researchResult.violations());
    }

    private String unsafeAnswerFor(ScenarioMutation mutation) {
        return switch (mutation.domain()) {
            case PAYMENT_TRANSFER -> unsafePaymentAnswer(mutation);
            case URL_SHORTENER -> unsafeUrlAnswer(mutation);
            case RESEARCH_EVIDENCE -> unsafeResearchAnswer();
        };
    }

    private String unsafePaymentAnswer(ScenarioMutation mutation) {
        if (mutation.expectedInvariantIds().contains(InvariantLibrary.PAYMENT_IDEMPOTENCY_BODY_MISMATCH)) {
            return """
                    If an idempotency key already exists, return the stored result for speed. Then debit the
                    source wallet, credit the destination wallet, and publish a Kafka event.
                    """;
        }
        if (mutation.expectedInvariantIds().contains(InvariantLibrary.PAYMENT_TRANSACTIONAL_OUTBOX)) {
            return """
                    Commit the PostgreSQL transfer and publish directly to Kafka. If the producer fails, log
                    the error and let consumers catch up later.
                    """;
        }
        return """
                On retry or crash, start the transfer from the beginning. Debit wallet A, then credit wallet B
                in separate steps. If the provider timed out, call capture again until it succeeds.
                """;
    }

    private String unsafeUrlAnswer(ScenarioMutation mutation) {
        String base = """
                Return the active Redis cached redirect when present for low latency. Otherwise use a read
                replica, since replicas are cheaper than primary reads. Redis TTL will eventually remove stale
                data and analytics dashboards can confirm whether users were redirected.
                """;
        if (mutation.expectedInvariantIds().contains(InvariantLibrary.URL_STAMPEDE_SINGLEFLIGHT)) {
            return base + " During spikes, add retries with exponential backoff.";
        }
        return base;
    }

    private String unsafeResearchAnswer() {
        return """
                The latest product status is that the March launch happened and the feature is currently
                available to everyone.
                """;
    }

    private String strongPaymentAnswer() {
        return """
                Lock or insert the idempotency record before money movement and store a normalized request
                hash. A reused key with a different payload hash returns 409 idempotency conflict. The same
                request returns PROCESSING/current status or replays the stored success response. Debit, credit,
                transfer state, and a transactional outbox event are committed atomically in one PostgreSQL
                transaction with wallet rows locked in deterministic order. An outbox worker publishes Kafka
                after commit. Crash recovery resumes from stored status and reconciliation checks ledger
                invariants, so retries never double debit or double capture.
                """;
    }

    private String strongUrlAnswer() {
        return """
                Return 404 Not Found or 410 Gone for deleted aliases. The delete path writes deleted_at/version
                in primary PostgreSQL and writes a Redis DELETED tombstone; tombstone wins before active cache.
                Do not trust lagging replicas in the recent deletion window. Use primary reads or safe
                delete-version checks. Use singleflight/per-key locks for cache misses under Redis degradation.
                Kafka analytics and dashboards are asynchronous observability, not redirect truth.
                Pseudocode:
                if (cached == DELETED) return 404;
                if (cached == ACTIVE && cached.version >= minKnownDeleteVersion(alias)) return redirect(cached.url);
                row = primaryDb.findByAlias(alias);
                if (row == null || row.deleted_at != null) { redis.set(alias, DELETED, 60); return 404; }
                redis.set(alias, ACTIVE(row.url, row.version), 300);
                return redirect(row.url);
                """;
    }

    private String strongResearchAnswer() {
        return """
                The sources conflict: S1 reports the feature launched in March, while S2 says the launch was
                delayed until May. Because S2 is the more recent dated source, I would present May as the
                better-supported current status while noting that the March report is superseded context [S1][S2].
                """;
    }

    private ResearchPack researchPackFor(ScenarioMutation mutation) {
        if (mutation.domain() != InvariantDomain.RESEARCH_EVIDENCE) {
            return ResearchPack.notRequired();
        }
        return ResearchPack.withSources(
                "Prompt asks for current information with conflicting sources.",
                List.of("current product launch status"),
                List.of(
                        new ResearchSource("S1", "March launch report", "https://example.com/march",
                                "example.com", "Feature launched in March.", "2026-03-20", 0.88),
                        new ResearchSource("S2", "May launch delay", "https://example.com/may",
                                "example.com", "Conflicting report: launch delayed until May.", "2026-05-05", 0.94)
                ));
    }
}
