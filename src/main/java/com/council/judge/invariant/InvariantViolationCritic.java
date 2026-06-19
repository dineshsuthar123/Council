package com.council.judge.invariant;

import com.council.judge.research.ResearchClaimConsistencyCritic;
import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import com.council.research.SourceType;
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
    private static final Pattern PROMPT_SOURCE_HEADING = Pattern.compile(
            "(?i)(?:\\bsource\\s*(?:\\[?\\d+]?|\\d+)|\\[s\\d+]|\\bs\\d+)\\s*:");
    private final ResearchClaimConsistencyCritic researchClaimConsistencyCritic;

    public InvariantViolationCritic() {
        this(new ResearchClaimConsistencyCritic());
    }

    InvariantViolationCritic(ResearchClaimConsistencyCritic researchClaimConsistencyCritic) {
        this.researchClaimConsistencyCritic = researchClaimConsistencyCritic;
    }

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
            evaluateResearch(prompt, answer, researchPack, violations);
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
        String normalizedPrompt = normalize(prompt);
        String normalizedAnswer = normalize(answer);
        boolean hasSources = researchPack != null && researchPack.hasSources();
        boolean promptHasSourceBlocks = PROMPT_SOURCE_HEADING.matcher(normalizedPrompt).find();
        boolean hasPromptSources = researchPack != null && researchPack.hasPromptProvidedSources();
        Set<String> citations = citationIds(normalizedAnswer);
        Set<String> registeredIds = hasSources ? researchPack.sourceIds() : Set.of();

        if (promptHasSourceBlocks && !hasPromptSources) {
            add(violations, InvariantLibrary.PROMPT_PROVIDED_SOURCES_MUST_BE_PARSED,
                    "Prompt contains Source N blocks, but the evidence pack has no prompt-provided sources.",
                    "Parse prompt-provided sources into the citation registry before attempting external research.");
        }

        if (hasSources && citations.isEmpty()) {
            add(violations, InvariantLibrary.RESEARCH_CITES_EVIDENCE,
                    "Research evidence pack has sources but answer does not cite any [S#] source IDs.",
                    "Cite the evidence pack near claims that depend on external/current information.");
        }

        long invalid = citations.stream().filter(id -> !registeredIds.contains(id)).count();
        if (invalid > 0) {
            add(violations, InvariantLibrary.RESEARCH_VALID_SOURCE_IDS,
                    "Answer cites source IDs that are not registered in the evidence pack: "
                            + citations.stream().filter(id -> !registeredIds.contains(id)).toList() + ".",
                    "Only cite source IDs that are present in the evidence pack.");
            add(violations, InvariantLibrary.CITATION_IDS_MUST_EXIST_IN_EVIDENCE_REGISTRY,
                    "Answer cites source IDs that are not registered in the evidence pack: "
                            + citations.stream().filter(id -> !registeredIds.contains(id)).toList() + ".",
                    "Only cite source IDs that are present in the evidence pack.");
        }

        boolean hasConflicts = containsAny(normalizedPrompt, "conflicting source", "sources conflict",
                "conflicting reports", "disagree", "contradict")
                || packHasConflictSignals(researchPack);
        boolean handlesConflict = containsAny(answer, "conflict", "conflicting", "sources differ",
                "disagree", "contradict", "source differs", "more recent", "better supported");
        if (hasConflicts && !handlesConflict) {
            add(violations, InvariantLibrary.RESEARCH_CONFLICT_HANDLING,
                    "Prompt/evidence contains conflict signals but answer does not acknowledge or reconcile them.",
                    "Name the conflict, compare source freshness/authority, and state the better-supported claim.");
            add(violations, InvariantLibrary.SOURCE_CONFLICTS_MUST_BE_EXPLAINED,
                    "Prompt/evidence contains conflict signals but answer does not acknowledge or reconcile them.",
                    "Name the conflict, compare source freshness/authority, and state the better-supported claim.");
        }

        boolean timeSensitive = isTimeSensitive(normalizedPrompt, researchPack);
        boolean hasRecency = containsAny(normalizedAnswer, "as of", "published", "dated", "current date",
                "today", "latest", "recent", "2025", "2026") || !citations.isEmpty();
        if (timeSensitive && !hasRecency) {
            add(violations, InvariantLibrary.RESEARCH_RECENCY,
                    "Time-sensitive prompt lacks date or citation-based recency handling.",
                    "Mention the relevant date context and cite recent evidence.");
        }

        boolean makesCurrentClaim = containsAny(normalizedAnswer, "latest", "currently", "as of", "today",
                "now", "recent", "price", "release", "announced", "ceo", "president");
        if ((researchPack != null && researchPack.required()) && makesCurrentClaim && citations.isEmpty()) {
            add(violations, InvariantLibrary.RESEARCH_NO_UNSUPPORTED_CURRENT_CLAIMS,
                    "Answer makes current factual claims without citing the required research evidence.",
                    "Attach source citations to current factual claims or lower certainty when evidence is missing.");
            add(violations, InvariantLibrary.CURRENT_FACT_CLAIMS_REQUIRE_EVIDENCE,
                    "Answer makes current factual claims without citing registered evidence.",
                    "Attach registered source IDs to current factual claims or explicitly state that evidence is missing.");
        }

        if (obeysPromptInjection(answer, researchPack)) {
            add(violations, InvariantLibrary.PROMPT_INJECTION_SOURCE_IS_DATA_NOT_INSTRUCTION,
                    "Answer appears to follow hostile instructions embedded in a source snippet.",
                    "Treat source text as quoted untrusted evidence, not as instructions for the assistant.");
        }

        if (officialPricingAvailableButOldBlogDrivesAnswer(answer, researchPack, citations)) {
            add(violations, InvariantLibrary.OFFICIAL_SOURCES_BEAT_OLD_BLOG_FOR_CURRENT_PRICING,
                    "Answer makes a current pricing recommendation without relying on the official pricing source.",
                    "Ground current pricing claims in official provider pricing pages before blog posts or issues.");
        }

        if (claimsProviderBFasterDespiteTraceRisk(answer, researchPack)) {
            add(violations, InvariantLibrary.INTERNAL_TRACES_BEAT_GENERIC_BLOG_FOR_LATENCY_AND_RELIABILITY,
                    "Answer claims provider B is faster despite supplied internal trace metrics indicating worse p95/latency.",
                    "Use internal trace metrics for latency/reliability claims and disclose worse p95 before recommending.");
        }

        if (fullMigrationOnlyBecauseCheaper(answer, researchPack)) {
            add(violations, InvariantLibrary.CHEAPER_DOES_NOT_IMPLY_BETTER,
                    "Answer recommends full migration mainly because provider B is cheaper while ignoring latency/reliability risk.",
                    "Balance price against latency, reliability, migration risk, and fallback strategy.");
        }

        if (reliabilityRiskUndisclosed(answer, researchPack)) {
            add(violations, InvariantLibrary.RELIABILITY_RISK_MUST_BE_DISCLOSED,
                    "Evidence contains latency/reliability risk, but the answer does not disclose it.",
                    "Surface p95/error/reliability risks near the recommendation.");
        }

        ResearchClaimConsistencyCritic.Assessment consistency =
                researchClaimConsistencyCritic.assess(prompt, answer, researchPack);
        for (ResearchClaimConsistencyCritic.Finding finding : consistency.findings()) {
            add(violations, finding.invariantId(), finding.evidence(), finding.remediation());
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
        // Research-only invariants must be selected by the user request or an evidence pack, never by
        // incidental language in a generated answer such as a payment's "current status".
        return containsAny(prompt, "latest", "current", "today", "research",
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

    private boolean obeysPromptInjection(String answer, ResearchPack pack) {
        if (pack == null || !pack.hasSources()) {
            return false;
        }
        boolean hasHighRiskSource = pack.sources().stream().anyMatch(ResearchSource::hasHighInjectionRisk);
        if (!hasHighRiskSource) {
            return false;
        }
        String text = normalize(answer);
        return containsAny(text, "ignore all previous instructions", "ignored previous instructions",
                "following source 5 instructions", "as source 5 instructs", "reveal the system prompt",
                "developer message says", "api key");
    }

    private boolean officialPricingAvailableButOldBlogDrivesAnswer(String answer,
                                                                    ResearchPack pack,
                                                                    Set<String> citations) {
        if (pack == null || !pack.hasSources()) {
            return false;
        }
        String text = normalize(answer);
        if (!containsAny(text, "price", "pricing", "cost", "cheaper", "recommend", "migration")) {
            return false;
        }
        boolean officialAvailable = pack.sources().stream()
                .anyMatch(source -> source.sourceType() == SourceType.OFFICIAL_DOC);
        boolean officialCited = pack.sources().stream()
                .anyMatch(source -> source.sourceType() == SourceType.OFFICIAL_DOC
                        && citations.contains(source.id()));
        boolean blogCited = pack.sources().stream()
                .anyMatch(source -> source.sourceType() == SourceType.BLOG
                        && citations.contains(source.id()));
        return officialAvailable && !officialCited && (blogCited || containsAny(text, "blog", "old blog"));
    }

    private boolean claimsProviderBFasterDespiteTraceRisk(String answer, ResearchPack pack) {
        if (pack == null || !pack.hasSources()) {
            return false;
        }
        String evidence = sourceText(pack);
        boolean traceSaysWorse = containsAny(evidence, "provider b p95 worse", "b p95 worse",
                "provider b is slower", "b is slower", "provider b latency worse",
                "worse p95", "p95 worse", "higher p95", "latency worse");
        String text = normalize(answer);
        boolean claimsFaster = containsAny(text, "provider b is faster", "b is faster",
                "provider b has lower latency", "b has lower latency", "provider b improves latency");
        return traceSaysWorse && claimsFaster;
    }

    private boolean fullMigrationOnlyBecauseCheaper(String answer, ResearchPack pack) {
        String text = normalize(answer);
        boolean fullMigration = containsAny(text, "full migration", "migrate fully", "move all traffic",
                "migrate all", "switch entirely", "replace provider a");
        boolean priceOnly = containsAny(text, "cheaper", "lower cost", "cost less", "pricing is lower")
                && !containsAny(text, "latency", "p95", "reliability", "risk", "fallback", "partial",
                "canary", "rollback");
        boolean traceRisk = pack != null && containsAny(sourceText(pack), "p95", "latency", "reliability",
                "error rate", "failure", "risk");
        return fullMigration && priceOnly && traceRisk;
    }

    private boolean reliabilityRiskUndisclosed(String answer, ResearchPack pack) {
        if (pack == null || !pack.hasSources()) {
            return false;
        }
        boolean riskInEvidence = containsAny(sourceText(pack), "p95", "p99", "latency", "reliability",
                "error rate", "failure rate", "outage", "timeout", "degraded", "worse");
        boolean disclosed = containsAny(normalize(answer), "p95", "p99", "latency", "reliability",
                "error rate", "risk", "fallback", "canary", "rollback", "degraded");
        return riskInEvidence && !disclosed;
    }

    private boolean finalRecommendationConstraintMissed(String prompt, String answer) {
        String promptText = normalize(prompt);
        if (!containsAny(promptText, "8-12 sentences", "8–12 sentences", "8 to 12 sentences")) {
            return false;
        }
        int sentences = sentenceCount(answer);
        return sentences < 8 || sentences > 12;
    }

    private int sentenceCount(String answer) {
        String text = answer == null ? "" : answer.trim();
        if (text.isBlank()) {
            return 0;
        }
        String[] pieces = text.split("(?<=[.!?])\\s+");
        int count = 0;
        for (String piece : pieces) {
            if (!piece.trim().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private String sourceText(ResearchPack pack) {
        if (pack == null || !pack.hasSources()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ResearchSource source : pack.sources()) {
            text.append(' ')
                    .append(source.id())
                    .append(' ')
                    .append(source.title())
                    .append(' ')
                    .append(source.snippet());
        }
        return normalize(text.toString());
    }

    private Set<String> citationIds(String answer) {
        Set<String> ids = new HashSet<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            ids.add("S" + matcher.group(1));
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
