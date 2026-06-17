package com.council.judge.invariant;

import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic invariant critic for high-risk reasoning domains.
 * <p>
 * This is intentionally local and bounded: no model calls, no network calls,
 * and no hidden retries. It is safe to run for every final answer.
 */
@Component
public class InvariantViolationCritic {

    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]", Pattern.CASE_INSENSITIVE);

    public InvariantCriticResult evaluate(String prompt, String answer) {
        return evaluate(prompt, answer, ResearchPack.notRequired());
    }

    public InvariantCriticResult evaluate(String prompt, String answer, ResearchPack researchPack) {
        String promptText = normalize(prompt);
        String answerText = normalize(answer);
        String combined = promptText + " " + answerText;

        List<InvariantDefinition> checked = new ArrayList<>();
        List<InvariantViolation> violations = new ArrayList<>();

        if (looksLikePaymentTransfer(combined)) {
            checked.addAll(InvariantLibrary.definitionsFor(InvariantDomain.PAYMENT_TRANSFER));
            evaluatePayment(promptText, answerText, violations);
        }
        if (looksLikeUrlShortener(combined)) {
            checked.addAll(InvariantLibrary.definitionsFor(InvariantDomain.URL_SHORTENER));
            evaluateUrlShortener(promptText, answerText, violations);
        }
        if (looksLikeResearch(promptText, answerText, researchPack)) {
            checked.addAll(InvariantLibrary.definitionsFor(InvariantDomain.RESEARCH_EVIDENCE));
            evaluateResearch(promptText, answerText, researchPack, violations);
        }

        if (checked.isEmpty()) {
            return InvariantCriticResult.notEvaluated();
        }
        return InvariantCriticResult.from(checked, violations);
    }

    private void evaluatePayment(String prompt, String answer, List<InvariantViolation> violations) {
        boolean asksBodyMismatch = containsAny(prompt, "same idempotency key with different body",
                "same idempotency key but different body", "different request body",
                "different payload", "payload hash", "request hash", "parameter mismatch")
                || (prompt.contains("idempotency key")
                && containsAny(prompt, "different body", "different request", "different payload",
                "first body", "second body"));
        boolean hasMismatchHandling = containsAny(answer, "parameter mismatch", "payload mismatch",
                "request hash", "payload hash", "same request parameters", "409", "conflict",
                "idempotency conflict", "reject", "unprocessable");
        if (asksBodyMismatch && !hasMismatchHandling) {
            add(violations, InvariantLibrary.PAYMENT_IDEMPOTENCY_BODY_MISMATCH,
                    "Prompt contains reused idempotency key with a changed request body, but answer does not "
                            + "require request-hash comparison or conflict rejection.",
                    "Compare normalized request hashes for reused idempotency keys and return a deterministic "
                            + "409/422-style conflict for different bodies.");
        }

        boolean retryRisk = containsAny(prompt, "retry", "timeout", "duplicate", "crash",
                "provider timeout", "same idempotency key") || containsAny(answer, "retry", "timeout");
        boolean preventsDoubleMovement = containsAny(answer, "never be debited twice",
                "no double debit", "must not debit twice", "double charge", "double capture",
                "at most once", "exactly once", "idempotent");
        if (retryRisk && !preventsDoubleMovement) {
            add(violations, InvariantLibrary.PAYMENT_NO_DOUBLE_MOVEMENT,
                    "Retry/duplicate/crash risk is present without a clear no-double-movement guarantee.",
                    "State the no-double-debit/no-double-capture invariant and enforce it with idempotency, "
                            + "unique constraints, and stored status/result replay.");
        }

        boolean hasDebitCredit = answer.contains("debit") && answer.contains("credit");
        boolean hasAtomicity = containsAny(answer, "single database transaction", "same transaction",
                "one db transaction", "inside one postgresql transaction", "atomic",
                "all-or-nothing", "commit", "rollback");
        if (hasDebitCredit && !hasAtomicity) {
            add(violations, InvariantLibrary.PAYMENT_ATOMIC_LEDGER,
                    "Answer discusses debit and credit without requiring an atomic transaction or equivalent "
                            + "ledger invariant.",
                    "Keep debit, credit, transfer state, and idempotency outcome in one transaction or a "
                            + "provably atomic ledger workflow.");
        }

        boolean promptHasEvents = containsAny(prompt + " " + answer, "kafka", "redpanda", "event",
                "producer", "consumer", "publish");
        boolean hasOutbox = containsAny(answer, "transactional outbox", "outbox", "cdc", "debezium",
                "after commit", "post-commit publisher");
        if (promptHasEvents && !hasOutbox) {
            add(violations, InvariantLibrary.PAYMENT_TRANSACTIONAL_OUTBOX,
                    "Payment/eventing scenario lacks transactional outbox or equivalent after-commit event safety.",
                    "Persist events in the same transaction and publish from an idempotent outbox/CDC worker.");
        }

        boolean promptHasCrash = containsAny(prompt, "crash", "restart", "power loss",
                "before response", "after commit", "after debit", "before credit",
                "timeout", "state is unknown", "provider state is unknown");
        boolean hasRecovery = containsAny(answer, "recovery", "reconciliation", "recover",
                "resume", "processing", "pending", "stored status", "outbox worker",
                "ledger invariant", "audit");
        if (promptHasCrash && !hasRecovery) {
            add(violations, InvariantLibrary.PAYMENT_CRASH_RECOVERY,
                    "Prompt shifts crash timing but answer does not explain recovery or reconciliation.",
                    "Describe stored in-progress status, idempotent resume/replay, outbox replay, and ledger "
                            + "reconciliation for each crash window.");
        }
    }

    private void evaluateUrlShortener(String prompt, String answer, List<InvariantViolation> violations) {
        boolean deletedAlias = containsAny(prompt, "deleted 1 second ago", "deleted one second ago",
                "recently deleted", "was deleted", "intentionally deleted",
                "was intentionally deleted", "deleted alias", "deleted url",
                "deleted abc", "deleted it");
        boolean returnsNotFound = containsAny(answer, "404", "410", "not found", "gone",
                "must not redirect", "should not redirect", "not redirect");
        if (deletedAlias && !returnsNotFound) {
            add(violations, InvariantLibrary.URL_DELETED_ALIAS_MUST_NOT_REDIRECT,
                    "Prompt states the alias was deleted, but answer does not make a deterministic 404/410 "
                            + "or no-redirect decision.",
                    "Return 404 or 410 for deleted aliases; never redirect a known-deleted short URL.");
        }

        boolean cacheRisk = containsAny(prompt, "redis", "cache", "stale", "degraded", "cache miss");
        boolean hasTombstone = containsAny(answer, "tombstone", "negative cache", "negative-cache",
                "deleted marker", "deleted sentinel", "deletion marker", "deleted tombstone");
        if (deletedAlias && cacheRisk && !hasTombstone) {
            add(violations, InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE,
                    "Deleted/cache scenario lacks a tombstone or negative-cache design.",
                    "Write a DELETED tombstone/negative cache and make it authoritative over active cache values.");
        } else if (activeCachePrecedesTombstoneInPseudocode(answer)) {
            add(violations, InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE,
                    "Algorithm appears to serve active cached redirect before checking tombstone/deleted state.",
                    "Check tombstone/deleted state first; only serve active cache when it is version-safe.");
        }

        boolean replicaLag = containsAny(prompt, "replica lag", "replicas are", "replicas lag",
                "read replicas", "2 seconds behind", "lagging replica", "replica is");
        boolean hasReplicaSafety = containsAny(answer, "do not trust", "must not trust",
                "avoid replica", "bypass replica", "replica is unsafe", "read primary",
                "primary read", "primary database", "source of truth", "deleted_at",
                "delete version", "mutation version", "read-your-writes");
        boolean unsafeReplicaChoice = containsAny(answer, "primary or a read replica",
                "primary or read replica", "primary or replica",
                "use a read replica", "use the read replica", "read from replica",
                "read from the replica", "use replica", "replica for low latency",
                "depending on configured consistency", "depending on the configured consistency",
                "depending on consistency level");
        if (replicaLag && deletedAlias && (unsafeReplicaChoice || !hasReplicaSafety)) {
            add(violations, InvariantLibrary.URL_REPLICA_LAG_UNSAFE_AFTER_DELETE,
                    unsafeReplicaChoice
                            ? "Answer permits a primary-or-replica choice despite a known deletion lag window."
                            : "Answer does not reject lagging replica reads for a recently deleted alias.",
                    "Use a Redis tombstone, primary read, or delete-version/deleted_at check for recent deletes.");
        }

        boolean analyticsLag = containsAny(prompt, "kafka", "redpanda", "consumer lag",
                "analytics", "dashboard");
        boolean separatesAnalytics = containsAny(answer, "not a source of truth",
                "not redirect truth", "not redirect correctness", "analytics freshness",
                "redirect correctness", "does not change the redirect", "independent from kafka",
                "dashboards are delayed", "asynchronous");
        if (analyticsLag && !separatesAnalytics) {
            add(violations, InvariantLibrary.URL_ANALYTICS_NOT_REDIRECT_TRUTH,
                    "Kafka/analytics/dashboard lag is present but answer does not separate it from redirect truth.",
                    "State that analytics are asynchronous observability and cannot decide synchronous redirect "
                            + "correctness.");
        }

        boolean stampedeRisk = containsAny(prompt, "traffic spike", "cache miss", "redis degraded",
                "redis is partially degraded", "two app instances", "same cache-miss",
                "stampede", "dogpile");
        boolean hasCoalescing = containsAny(answer, "singleflight", "single flight",
                "request coalescing", "request collapsing", "per-key lock", "per key lock",
                "distributed lock", "mutex", "dogpile", "lease holder");
        if (stampedeRisk && !hasCoalescing) {
            add(violations, InvariantLibrary.URL_STAMPEDE_SINGLEFLIGHT,
                    "Cache-miss/stampede risk is present without singleflight, per-key lock, or coalescing.",
                    "Allow one request per alias to load from the database while others wait briefly or take a "
                            + "bounded fallback.");
        }

        if (returnsMaybeStaleLease(answer)) {
            add(violations, InvariantLibrary.URL_NO_MAYBE_STALE_REDIRECT,
                    "Answer allows a maybe-stale lease response on the browser redirect path.",
                    "Return a deterministic redirect or 404/410; never serve a maybe-stale redirect for a "
                            + "known-deleted alias.");
        }
    }

    private void evaluateResearch(String prompt,
                                  String answer,
                                  ResearchPack researchPack,
                                  List<InvariantViolation> violations) {
        boolean hasSources = researchPack != null && researchPack.hasSources();
        Set<Integer> citations = citationIds(answer);
        int sourceCount = hasSources ? researchPack.sources().size() : 0;

        if (hasSources && citations.isEmpty()) {
            add(violations, InvariantLibrary.RESEARCH_CITES_EVIDENCE,
                    "Research evidence pack has sources but answer does not cite any [S#] source IDs.",
                    "Cite the evidence pack near claims that depend on external/current information.");
        }

        long invalid = citations.stream().filter(id -> id < 1 || id > sourceCount).count();
        if (hasSources && invalid > 0) {
            add(violations, InvariantLibrary.RESEARCH_VALID_SOURCE_IDS,
                    "Answer cites source IDs outside the evidence pack range 1.." + sourceCount + ".",
                    "Only cite source IDs that are present in the evidence pack.");
        }

        boolean hasConflicts = containsAny(prompt, "conflicting source", "sources conflict",
                "conflicting reports", "disagree", "contradict")
                || packHasConflictSignals(researchPack);
        boolean handlesConflict = containsAny(answer, "conflict", "conflicting", "sources differ",
                "disagree", "contradict", "source differs", "more recent", "better supported");
        if (hasConflicts && !handlesConflict) {
            add(violations, InvariantLibrary.RESEARCH_CONFLICT_HANDLING,
                    "Prompt/evidence contains conflict signals but answer does not acknowledge or reconcile them.",
                    "Name the conflict, compare source freshness/authority, and state the better-supported claim.");
        }

        boolean timeSensitive = isTimeSensitive(prompt, researchPack);
        boolean hasRecency = containsAny(answer, "as of", "published", "dated", "current date",
                "today", "latest", "recent", "2025", "2026") || !citations.isEmpty();
        if (timeSensitive && !hasRecency) {
            add(violations, InvariantLibrary.RESEARCH_RECENCY,
                    "Time-sensitive prompt lacks date or citation-based recency handling.",
                    "Mention the relevant date context and cite recent evidence.");
        }

        boolean makesCurrentClaim = containsAny(answer, "latest", "currently", "as of", "today",
                "now", "recent", "price", "release", "announced", "ceo", "president");
        if ((researchPack != null && researchPack.required()) && makesCurrentClaim && citations.isEmpty()) {
            add(violations, InvariantLibrary.RESEARCH_NO_UNSUPPORTED_CURRENT_CLAIMS,
                    "Answer makes current factual claims without citing the required research evidence.",
                    "Attach source citations to current factual claims or lower certainty when evidence is missing.");
        }
    }

    private void add(List<InvariantViolation> violations,
                     String id,
                     String evidence,
                     String remediation) {
        violations.add(InvariantViolation.of(InvariantLibrary.definition(id), evidence, remediation));
    }

    private boolean looksLikePaymentTransfer(String text) {
        return containsAny(text, "payment", "transfer", "wallet", "ledger", "money movement",
                "debit", "credit", "idempotency", "idempotency key")
                && containsAny(text, "postgres", "database", "transaction", "kafka",
                "balance", "provider", "request body");
    }

    private boolean looksLikeUrlShortener(String text) {
        return containsAny(text, "url-shortener", "short url", "shortener", "redirect", "sho.rt",
                "alias", "abc123")
                && containsAny(text, "redis", "postgres", "replica", "kafka", "redpanda",
                "deleted", "cache", "analytics");
    }

    private boolean looksLikeResearch(String prompt, String answer, ResearchPack pack) {
        if (pack != null && pack.required()) {
            return true;
        }
        return containsAny(prompt + " " + answer, "latest", "current", "today", "research",
                "cite", "citation", "source", "sources conflict", "conflicting sources");
    }

    private boolean packHasConflictSignals(ResearchPack pack) {
        if (pack == null || !pack.hasSources()) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        for (ResearchSource source : pack.sources()) {
            text.append(' ')
                    .append(source.title())
                    .append(' ')
                    .append(source.snippet());
        }
        return containsAny(normalize(text.toString()), "conflict", "conflicting", "disagree",
                "contradict", "contradiction", "disputed");
    }

    private boolean isTimeSensitive(String prompt, ResearchPack pack) {
        String reason = pack == null ? "" : normalize(pack.reason());
        return containsAny(prompt + " " + reason, "latest", "current", "today", "now",
                "recent", "2026", "price", "ceo", "president", "release", "law", "rule");
    }

    private Set<Integer> citationIds(String answer) {
        Set<Integer> ids = new HashSet<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }
        return ids;
    }

    private boolean returnsMaybeStaleLease(String text) {
        if (containsAny(text, "do not return", "should not return", "must not return",
                "never return", "not serve maybe", "do not serve maybe",
                "weaker system would", "mistake", "common mistake")) {
            return false;
        }
        return text.contains("lease")
                && text.contains("stale")
                && containsAny(text, "return", "response", "serve");
    }

    private boolean activeCachePrecedesTombstoneInPseudocode(String text) {
        int algorithmStart = firstIndexOf(text, "pseudocode", "algorithm", "resolve(",
                "redirectresult", "redis.get");
        if (algorithmStart < 0) {
            return false;
        }
        String snippet = text.substring(algorithmStart);
        int tombstone = firstIndexOf(snippet, "tombstone", "istombstoned", "is tombstoned",
                "deleted", "negative-cache", "negative cache", "cached == deleted");
        int active = firstIndexOf(snippet, "cachedredirect", "cached redirect", "valid redirect",
                "cache hit", "active redirect", "cached == active", "cached active", "return cached");
        return active >= 0 && tombstone >= 0 && active < tombstone;
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int firstIndexOf(String haystack, String... needles) {
        int result = -1;
        for (String needle : needles) {
            int index = haystack.indexOf(needle);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
