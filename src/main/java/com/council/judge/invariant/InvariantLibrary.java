package com.council.judge.invariant;

import java.util.List;

/**
 * Stable invariant IDs and definitions used by score calibration and tests.
 */
public final class InvariantLibrary {

    public static final String PAYMENT_IDEMPOTENCY_BODY_MISMATCH =
            "payment.idempotency.body_mismatch";
    public static final String PAYMENT_NO_DOUBLE_MOVEMENT =
            "payment.no_double_movement";
    public static final String PAYMENT_ATOMIC_LEDGER =
            "payment.atomic_ledger";
    public static final String PAYMENT_TRANSACTIONAL_OUTBOX =
            "payment.transactional_outbox";
    public static final String PAYMENT_CRASH_RECOVERY =
            "payment.crash_recovery";

    public static final String URL_DELETED_ALIAS_MUST_NOT_REDIRECT =
            "url.deleted_alias_must_not_redirect";
    public static final String URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE =
            "url.tombstone_precedes_active_cache";
    public static final String URL_REPLICA_LAG_UNSAFE_AFTER_DELETE =
            "url.replica_lag_unsafe_after_delete";
    public static final String URL_ANALYTICS_NOT_REDIRECT_TRUTH =
            "url.analytics_not_redirect_truth";
    public static final String URL_STAMPEDE_SINGLEFLIGHT =
            "url.stampede_singleflight";
    public static final String URL_NO_MAYBE_STALE_REDIRECT =
            "url.no_maybe_stale_redirect";

    public static final String RESEARCH_CITES_EVIDENCE =
            "research.cites_evidence";
    public static final String RESEARCH_VALID_SOURCE_IDS =
            "research.valid_source_ids";
    public static final String RESEARCH_CONFLICT_HANDLING =
            "research.conflict_handling";
    public static final String RESEARCH_RECENCY =
            "research.recency";
    public static final String RESEARCH_NO_UNSUPPORTED_CURRENT_CLAIMS =
            "research.no_unsupported_current_claims";

    private static final List<InvariantDefinition> DEFINITIONS = List.of(
            new InvariantDefinition(
                    InvariantDomain.PAYMENT_TRANSFER,
                    PAYMENT_IDEMPOTENCY_BODY_MISMATCH,
                    "Same idempotency key with a different body must be rejected as a conflict",
                    "A payment answer must compare request payload/hash for reused idempotency keys and reject "
                            + "different bodies instead of replaying or executing a different transfer.",
                    InvariantSeverity.CRITICAL,
                    0.70),
            new InvariantDefinition(
                    InvariantDomain.PAYMENT_TRANSFER,
                    PAYMENT_NO_DOUBLE_MOVEMENT,
                    "Retries and timeouts must not double-move money",
                    "Payment retries, duplicate requests, and provider timeouts must be idempotent and must not "
                            + "double debit, double credit, or double capture.",
                    InvariantSeverity.CRITICAL,
                    0.72),
            new InvariantDefinition(
                    InvariantDomain.PAYMENT_TRANSFER,
                    PAYMENT_ATOMIC_LEDGER,
                    "Debit and credit must be atomic ledger changes",
                    "A transfer answer must keep debit, credit, transfer state, and idempotency outcome in an "
                            + "all-or-nothing transaction or equivalent ledger invariant.",
                    InvariantSeverity.CRITICAL,
                    0.65),
            new InvariantDefinition(
                    InvariantDomain.PAYMENT_TRANSFER,
                    PAYMENT_TRANSACTIONAL_OUTBOX,
                    "Kafka/payment events require transactional outbox safety",
                    "A payment answer with Kafka or external eventing must avoid pre-commit emits and persist "
                            + "events through an outbox or equivalent after-commit reliability mechanism.",
                    InvariantSeverity.MAJOR,
                    0.72),
            new InvariantDefinition(
                    InvariantDomain.PAYMENT_TRANSFER,
                    PAYMENT_CRASH_RECOVERY,
                    "Crash timing must be recoverable without double execution",
                    "A payment answer must explain recovery for crashes between idempotency creation, ledger "
                            + "mutation, event publication, and final response.",
                    InvariantSeverity.MAJOR,
                    0.70),

            new InvariantDefinition(
                    InvariantDomain.URL_SHORTENER,
                    URL_DELETED_ALIAS_MUST_NOT_REDIRECT,
                    "A recently deleted alias must not redirect",
                    "When the prompt states the alias was deleted, the redirect endpoint must return 404/410 "
                            + "rather than 301/302 to the long URL.",
                    InvariantSeverity.CRITICAL,
                    0.55),
            new InvariantDefinition(
                    InvariantDomain.URL_SHORTENER,
                    URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE,
                    "Deletion tombstone must win over active cache",
                    "The redirect path must check or honor a deleted tombstone/negative cache before serving "
                            + "an active cached redirect.",
                    InvariantSeverity.CRITICAL,
                    0.75),
            new InvariantDefinition(
                    InvariantDomain.URL_SHORTENER,
                    URL_REPLICA_LAG_UNSAFE_AFTER_DELETE,
                    "Lagging replicas are unsafe during recent deletion",
                    "During a known replica lag window, the answer must not trust replicas for a recently "
                            + "deleted alias; it needs a tombstone, primary read, or safe delete-version check.",
                    InvariantSeverity.CRITICAL,
                    0.72),
            new InvariantDefinition(
                    InvariantDomain.URL_SHORTENER,
                    URL_ANALYTICS_NOT_REDIRECT_TRUTH,
                    "Analytics lag is not redirect correctness",
                    "Kafka/Redpanda lag and dashboards are asynchronous observability signals, not the source "
                            + "of truth for synchronous redirect correctness.",
                    InvariantSeverity.MAJOR,
                    0.78),
            new InvariantDefinition(
                    InvariantDomain.URL_SHORTENER,
                    URL_STAMPEDE_SINGLEFLIGHT,
                    "Redis degradation requires request coalescing",
                    "Cache miss or timeout storms should use singleflight, per-key locks, or request coalescing "
                            + "so many app instances do not stampede PostgreSQL.",
                    InvariantSeverity.MAJOR,
                    0.80),
            new InvariantDefinition(
                    InvariantDomain.URL_SHORTENER,
                    URL_NO_MAYBE_STALE_REDIRECT,
                    "Redirect endpoint cannot return a maybe-stale lease",
                    "A browser redirect path must make a deterministic redirect/not-redirect decision and must "
                            + "not serve a possibly stale redirect as a lease response.",
                    InvariantSeverity.CRITICAL,
                    0.55),

            new InvariantDefinition(
                    InvariantDomain.RESEARCH_EVIDENCE,
                    RESEARCH_CITES_EVIDENCE,
                    "Research-required answers must cite the evidence pack",
                    "When external research is required and sources are available, the answer must cite the "
                            + "provided source IDs or otherwise ground factual claims in supplied evidence.",
                    InvariantSeverity.MAJOR,
                    0.72),
            new InvariantDefinition(
                    InvariantDomain.RESEARCH_EVIDENCE,
                    RESEARCH_VALID_SOURCE_IDS,
                    "Citations must reference existing source IDs",
                    "Citations such as [S1] must refer to sources that exist in the evidence pack.",
                    InvariantSeverity.MAJOR,
                    0.62),
            new InvariantDefinition(
                    InvariantDomain.RESEARCH_EVIDENCE,
                    RESEARCH_CONFLICT_HANDLING,
                    "Conflicting sources must be reconciled",
                    "When sources conflict, the answer must acknowledge the conflict and explain which claim is "
                            + "better supported or more recent.",
                    InvariantSeverity.MAJOR,
                    0.78),
            new InvariantDefinition(
                    InvariantDomain.RESEARCH_EVIDENCE,
                    RESEARCH_RECENCY,
                    "Time-sensitive claims need recency handling",
                    "Latest/current/today questions must state the relevant date context or cite recent sources.",
                    InvariantSeverity.WARNING,
                    0.82),
            new InvariantDefinition(
                    InvariantDomain.RESEARCH_EVIDENCE,
                    RESEARCH_NO_UNSUPPORTED_CURRENT_CLAIMS,
                    "Current claims must not be unsupported",
                    "Answers with current prices, releases, legal rules, or company facts must not make "
                            + "unsupported current claims when research was required.",
                    InvariantSeverity.MAJOR,
                    0.70)
    );

    private InvariantLibrary() {}

    public static List<InvariantDefinition> definitions() {
        return DEFINITIONS;
    }

    public static List<InvariantDefinition> definitionsFor(InvariantDomain domain) {
        return DEFINITIONS.stream()
                .filter(definition -> definition.domain() == domain)
                .toList();
    }

    public static InvariantDefinition definition(String id) {
        return DEFINITIONS.stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown invariant id: " + id));
    }
}
