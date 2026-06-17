package com.council.judge.mutation;

import com.council.judge.invariant.InvariantDomain;
import com.council.judge.invariant.InvariantLibrary;

import java.util.List;

/**
 * Deterministic prompt mutation harness for adversarial evaluator tests.
 */
public class ScenarioMutationHarness {

    public List<ScenarioMutation> allHardMutations() {
        return List.of(
                paymentIdempotencyBodyMismatch(),
                paymentCrashAfterDebitBeforeCredit(),
                paymentProviderTimeoutRetry(),
                paymentKafkaProducerFailure(),
                urlCacheStaleDeletedAlias(),
                urlCacheDownReplicaLag(),
                urlReplicaLagVariation(),
                urlKafkaLagAndProducerFailure(),
                urlRecentlyDeletedAliasVariant(),
                researchConflictingSources(),
                researchCurrentClaimWithoutCitations());
    }

    public ScenarioMutation paymentIdempotencyBodyMismatch() {
        return new ScenarioMutation(
                "payment.same-idempotency-key-different-body",
                InvariantDomain.PAYMENT_TRANSFER,
                "same idempotency key with different body",
                """
                        A wallet transfer API receives two POST /transfers requests with the same
                        idempotency key. The first body transfers 100 USD from A to B. The second body
                        transfers 900 USD from A to C. PostgreSQL stores balances and idempotency records,
                        Redis caches hot idempotency lookups, and Kafka publishes transfer events.
                        Explain the correct production behavior and algorithm.
                        """,
                0.70,
                List.of(InvariantLibrary.PAYMENT_IDEMPOTENCY_BODY_MISMATCH));
    }

    public ScenarioMutation paymentCrashAfterDebitBeforeCredit() {
        return new ScenarioMutation(
                "payment.crash-after-debit-before-credit",
                InvariantDomain.PAYMENT_TRANSFER,
                "app crash timing shift",
                """
                        A payment service debits wallet A, credits wallet B, stores idempotency state in
                        PostgreSQL, and emits Kafka events. The app crashes after debit but before credit,
                        then the client retries with the same idempotency key. Give a deterministic
                        production-grade recovery algorithm.
                        """,
                0.65,
                List.of(InvariantLibrary.PAYMENT_ATOMIC_LEDGER,
                        InvariantLibrary.PAYMENT_CRASH_RECOVERY,
                        InvariantLibrary.PAYMENT_NO_DOUBLE_MOVEMENT));
    }

    public ScenarioMutation paymentProviderTimeoutRetry() {
        return new ScenarioMutation(
                "payment.external-provider-timeout-retry",
                InvariantDomain.PAYMENT_TRANSFER,
                "provider timeout retry",
                """
                        A card payment provider times out after possibly capturing funds. The user retries
                        the same checkout payment with the same idempotency key. PostgreSQL is source of truth
                        and Kafka records payment lifecycle events. Explain how to avoid double capture and
                        what the endpoint should return while the provider state is unknown.
                        """,
                0.72,
                List.of(InvariantLibrary.PAYMENT_NO_DOUBLE_MOVEMENT,
                        InvariantLibrary.PAYMENT_CRASH_RECOVERY));
    }

    public ScenarioMutation paymentKafkaProducerFailure() {
        return new ScenarioMutation(
                "payment.kafka-producer-failure-after-commit",
                InvariantDomain.PAYMENT_TRANSFER,
                "Kafka producer failure",
                """
                        A transfer commits successfully in PostgreSQL but the Kafka producer fails before
                        publishing the transfer_succeeded event. Consumers drive notifications and settlement.
                        Explain a production-safe design that preserves ledger correctness and event delivery.
                        """,
                0.72,
                List.of(InvariantLibrary.PAYMENT_TRANSACTIONAL_OUTBOX));
    }

    public ScenarioMutation urlCacheStaleDeletedAlias() {
        return new ScenarioMutation(
                "url.cache-stale-deleted-alias",
                InvariantDomain.URL_SHORTENER,
                "cache stale vs deleted alias",
                """
                        A URL shortener uses Redis cache, PostgreSQL primary/replicas, and Kafka analytics.
                        Alias abc123 was deleted 1 second ago, but Redis still has an active redirect value
                        with five minutes of TTL. What must GET /abc123 return and how is stale redirect
                        prevented?
                        """,
                0.75,
                List.of(InvariantLibrary.URL_DELETED_ALIAS_MUST_NOT_REDIRECT,
                        InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE));
    }

    public ScenarioMutation urlCacheDownReplicaLag() {
        return new ScenarioMutation(
                "url.cache-down-replica-lag",
                InvariantDomain.URL_SHORTENER,
                "cache down plus replica lag",
                """
                        Redis is down for a URL shortener redirect path. PostgreSQL read replicas are
                        2 seconds behind primary. Alias abc123 was deleted one second ago. Two app instances
                        receive the same cache miss. Explain the correct redirect result and database path.
                        """,
                0.72,
                List.of(InvariantLibrary.URL_REPLICA_LAG_UNSAFE_AFTER_DELETE,
                        InvariantLibrary.URL_STAMPEDE_SINGLEFLIGHT));
    }

    public ScenarioMutation urlReplicaLagVariation() {
        return new ScenarioMutation(
                "url.replica-lag-five-seconds-delete-one-second",
                InvariantDomain.URL_SHORTENER,
                "replica lag variation",
                """
                        PostgreSQL replicas are 5 seconds stale and a short URL alias was deleted 1 second
                        ago. Redis may have either a stale active cache value or no value at all. Give
                        deterministic redirect logic, including status code, primary/replica choice, and
                        cache write behavior.
                        """,
                0.72,
                List.of(InvariantLibrary.URL_REPLICA_LAG_UNSAFE_AFTER_DELETE,
                        InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE));
    }

    public ScenarioMutation urlKafkaLagAndProducerFailure() {
        return new ScenarioMutation(
                "url.kafka-lag-producer-failure-dashboard",
                InvariantDomain.URL_SHORTENER,
                "Kafka lag / producer failure",
                """
                        A URL-shortener dashboard shows successful redirects for abc123 because Kafka
                        analytics consumers are 10 minutes behind and one producer batch failed. The owner
                        deleted abc123 1 second ago. Explain redirect correctness, dashboard freshness,
                        metrics, and what the endpoint must return.
                        """,
                0.78,
                List.of(InvariantLibrary.URL_ANALYTICS_NOT_REDIRECT_TRUTH,
                        InvariantLibrary.URL_DELETED_ALIAS_MUST_NOT_REDIRECT));
    }

    public ScenarioMutation urlRecentlyDeletedAliasVariant() {
        return new ScenarioMutation(
                "url.deleted-alias-404-vs-410",
                InvariantDomain.URL_SHORTENER,
                "recently deleted alias variant",
                """
                        A public short URL was intentionally deleted by its owner. The product may choose
                        either 404 or 410 depending on existence-leak policy. Redis is degraded and replicas
                        lag. Explain the exact endpoint contract and algorithm.
                        """,
                0.75,
                List.of(InvariantLibrary.URL_DELETED_ALIAS_MUST_NOT_REDIRECT,
                        InvariantLibrary.URL_REPLICA_LAG_UNSAFE_AFTER_DELETE));
    }

    public ScenarioMutation researchConflictingSources() {
        return new ScenarioMutation(
                "research.conflicting-sources",
                InvariantDomain.RESEARCH_EVIDENCE,
                "research prompt with conflicting sources",
                """
                        Use the provided research pack to answer a current market question. Source S1 says
                        the feature launched in March, while source S2 says the launch was delayed until May.
                        The answer must cite evidence and handle the source conflict.
                        """,
                0.78,
                List.of(InvariantLibrary.RESEARCH_CITES_EVIDENCE,
                        InvariantLibrary.RESEARCH_CONFLICT_HANDLING));
    }

    public ScenarioMutation researchCurrentClaimWithoutCitations() {
        return new ScenarioMutation(
                "research.current-claim-without-citations",
                InvariantDomain.RESEARCH_EVIDENCE,
                "current claim without citations",
                """
                        What is the latest status of a fast-moving AI product announcement today?
                        Answer using the supplied evidence pack and include citations.
                        """,
                0.70,
                List.of(InvariantLibrary.RESEARCH_CITES_EVIDENCE,
                        InvariantLibrary.RESEARCH_NO_UNSUPPORTED_CURRENT_CLAIMS));
    }
}
