package com.council.judge;

import com.council.common.CouncilUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic quality guardrails for stale-delete/cache-consistency incidents.
 */
public final class ProductionConsistencyCalibrator {

    private ProductionConsistencyCalibrator() {}

    public record Calibration(double cap, List<String> reasons) {
        public boolean applied() {
            return cap < 1.0;
        }
    }

    public record QualityScore(double score, Map<String, Double> dimensions, List<String> reasons) {
        public boolean applied() {
            return !dimensions.isEmpty() || !reasons.isEmpty();
        }
    }

    public static double capConfidence(String answer, String summary, double confidence) {
        return Math.min(CouncilUtils.clamp01(confidence), evaluate(answer, summary).cap());
    }

    public static QualityScore qualityScore(String answer, String summary, double fallbackConfidence) {
        String text = normalize(answer + " " + summary);
        if (PaymentTransferCalibrator.looksLikePaymentTransferAnswer(text)) {
            return PaymentTransferCalibrator.qualityScore(answer, summary, fallbackConfidence);
        }

        Calibration calibration = evaluate(answer, summary);
        if (!looksLikeStaleDeletionConsistencyAnswer(text)) {
            return new QualityScore(
                    Math.min(CouncilUtils.clamp01(fallbackConfidence), calibration.cap()),
                    Map.of(),
                    calibration.reasons());
        }

        Map<String, Double> dimensions = dimensionScores(text);
        double weightedScore = weightedScore(dimensions);
        double score = Math.min(weightedScore, hardSafetyCap(text));
        return new QualityScore(CouncilUtils.clamp01(score), dimensions, calibration.reasons());
    }

    public static Calibration evaluate(String answer, String summary) {
        String text = normalize(answer + " " + summary);
        if (PaymentTransferCalibrator.looksLikePaymentTransferAnswer(text)) {
            return PaymentTransferCalibrator.evaluate(answer, summary);
        }

        if (!looksLikeStaleDeletionConsistencyAnswer(text)) {
            return new Calibration(1.0, List.of());
        }

        List<String> reasons = new ArrayList<>();
        int missing = 0;

        boolean hasTombstone = containsAny(text, "tombstone", "negative cache", "negative-cache",
                "deleted marker", "deletion marker", "cache delete marker", "deleted sentinel");
        boolean hasPrimarySafeRead = containsAny(text, "primary", "leader", "bypass replica",
                "avoid replica", "do not trust the replica", "do not trust a replica",
                "source of truth", "read-your-writes", "replica lag window");
        boolean hasStampedeCoalescing = containsAny(text, "singleflight", "single flight",
                "request coalescing", "request collapsing", "per-key lock", "per key lock",
                "distributed lock", "mutex", "dogpile", "cache-aside lease", "lease mechanism");
        boolean separatesAnalytics = containsAny(text, "analytics lag", "dashboard",
                "consumer lag", "redirect correctness", "correctness is independent",
                "does not change the redirect", "not a source of truth");
        boolean hasVersionOrDeletedAt = containsAny(text, "deleted_at", "deleted at",
                "version", "row_version", "generation", "delete version", "mutation version");
        boolean hasCorrectStatus = containsAny(text, "404", "410", "not found", "gone");
        boolean hasConcretePseudocode = hasConcretePseudocode(text);

        if (!hasTombstone) {
            missing++;
            reasons.add("missing tombstone/negative cache");
        }
        if (!hasPrimarySafeRead && !hasVersionOrDeletedAt) {
            missing++;
            reasons.add("missing primary-safe read or version/deleted_at check");
        }
        if (!hasStampedeCoalescing) {
            missing++;
            reasons.add("missing singleflight/request coalescing");
        }
        if (!separatesAnalytics) {
            missing++;
            reasons.add("missing analytics-vs-redirect separation");
        }
        if (!hasCorrectStatus) {
            missing++;
            reasons.add("missing deterministic 404/410 decision");
        }
        if (!hasConcretePseudocode) {
            missing++;
            reasons.add("missing concrete pseudocode/control flow");
        }

        double cap = 1.0;
        if (missing >= 5) {
            cap = 0.62;
        } else if (missing >= 4) {
            cap = 0.68;
        } else if (missing >= 3) {
            cap = 0.72;
        } else if (missing >= 2) {
            cap = 0.80;
        }

        if (treatsRedisTtlAsEnough(text) && !hasTombstone) {
            cap = Math.min(cap, 0.65);
            reasons.add("treats Redis TTL/expiration as sufficient deletion consistency");
        }
        if (allowsUnsafeReplicaChoice(text, hasPrimarySafeRead, hasVersionOrDeletedAt)) {
            cap = Math.min(cap, 0.55);
            reasons.add("allows unsafe read-replica choice during known replica lag");
        }
        if (returnsMaybeStaleLease(text)) {
            cap = Math.min(cap, 0.55);
            reasons.add("allows maybe-stale lease response on synchronous redirect path");
        }
        if (reliesOnBestEffortInvalidation(text, hasTombstone, hasPrimarySafeRead, hasVersionOrDeletedAt)) {
            cap = Math.min(cap, 0.70);
            reasons.add("relies on best-effort Redis invalidation without durable correctness fallback");
        }
        if (!hasConcretePseudocode && text.contains("pseudocode")) {
            cap = Math.min(cap, 0.70);
            reasons.add("pseudocode is a prose checklist");
        }
        if (activeCachePrecedesTombstoneInPseudocode(text)) {
            cap = Math.min(cap, 0.85);
            reasons.add("pseudocode checks active cache before deleted tombstone");
        }
        if (hasShallowTradeoffStatement(text)) {
            cap = Math.min(cap, 0.88);
            reasons.add("tradeoff explanation is directionally correct but shallow");
        }

        return new Calibration(cap, List.copyOf(reasons));
    }

    public static Map<String, Double> dimensionScores(String answer, String summary) {
        String text = normalize(answer + " " + summary);
        if (PaymentTransferCalibrator.looksLikePaymentTransferAnswer(text)) {
            return PaymentTransferCalibrator.dimensionScores(answer, summary);
        }

        if (!looksLikeStaleDeletionConsistencyAnswer(text)) {
            return Map.of();
        }
        return dimensionScores(text);
    }

    private static Map<String, Double> dimensionScores(String text) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("correct_endpoint_decision", scoreEndpointDecision(text));
        scores.put("deletion_safety", scoreDeletionSafety(text));
        scores.put("replica_lag_awareness", scoreReplicaLagAwareness(text));
        scores.put("stampede_control", scoreStampedeControl(text));
        scores.put("observability", scoreObservability(text));
        scores.put("tradeoffs", scoreTradeoffs(text));
        scores.put("pseudocode", scorePseudocode(text));
        scores.put("common_mistakes", scoreCommonMistakes(text));
        return Collections.unmodifiableMap(scores);
    }

    private static double weightedScore(Map<String, Double> scores) {
        return (scores.getOrDefault("correct_endpoint_decision", 0.0) * 0.15)
                + (scores.getOrDefault("deletion_safety", 0.0) * 0.20)
                + (scores.getOrDefault("replica_lag_awareness", 0.0) * 0.15)
                + (scores.getOrDefault("stampede_control", 0.0) * 0.15)
                + (scores.getOrDefault("observability", 0.0) * 0.10)
                + (scores.getOrDefault("tradeoffs", 0.0) * 0.10)
                + (scores.getOrDefault("pseudocode", 0.0) * 0.10)
                + (scores.getOrDefault("common_mistakes", 0.0) * 0.05);
    }

    private static double hardSafetyCap(String text) {
        boolean hasTombstone = containsAny(text, "tombstone", "negative cache", "negative-cache",
                "deleted marker", "deletion marker", "cache delete marker", "deleted sentinel");
        boolean hasPrimarySafeRead = containsAny(text, "primary", "leader", "bypass replica",
                "avoid replica", "do not trust the replica", "do not trust a replica",
                "source of truth", "read-your-writes", "replica lag window");
        boolean hasVersionOrDeletedAt = containsAny(text, "deleted_at", "deleted at",
                "version", "row_version", "generation", "delete version", "mutation version");

        double cap = 1.0;
        if (allowsUnsafeReplicaChoice(text, hasPrimarySafeRead, hasVersionOrDeletedAt)) {
            cap = Math.min(cap, 0.55);
        }
        if (returnsMaybeStaleLease(text)) {
            cap = Math.min(cap, 0.55);
        }
        if (treatsRedisTtlAsEnough(text) && !hasTombstone) {
            cap = Math.min(cap, 0.65);
        }
        if (reliesOnBestEffortInvalidation(text, hasTombstone, hasPrimarySafeRead, hasVersionOrDeletedAt)) {
            cap = Math.min(cap, 0.70);
        }
        if (activeCachePrecedesTombstoneInPseudocode(text)) {
            cap = Math.min(cap, 0.85);
        }
        return cap;
    }

    private static double scoreEndpointDecision(String text) {
        boolean status = containsAny(text, "404", "410", "not found", "gone");
        boolean deletedReason = containsAny(text, "deleted 1 second ago", "deleted one second ago",
                "was deleted", "has been deleted", "deleted url", "owner deleted");
        if (status && deletedReason && containsAny(text, "return", "endpoint should", "should return")) {
            return 0.92;
        }
        if (status && deletedReason) {
            return 0.82;
        }
        if (status) {
            return 0.72;
        }
        if (containsAny(text, "not redirect", "must not redirect", "should not redirect", "notfoundorgone")) {
            return 0.62;
        }
        return 0.35;
    }

    private static double scoreDeletionSafety(String text) {
        boolean hasTombstone = containsAny(text, "tombstone", "deleted marker", "deletion marker",
                "deleted sentinel");
        boolean hasNegativeCache = containsAny(text, "negative cache", "negative-cache",
                "negative caching", "deleted/negative");
        boolean hasVersion = containsAny(text, "deleted_at", "deleted at", "version",
                "row_version", "generation", "delete version", "mutation version");
        boolean writesPrimary = containsAny(text, "primary database", "primary db",
                "write deletion", "write deleted", "mark alias deleted", "source of truth");

        if (hasTombstone && hasNegativeCache && hasVersion && writesPrimary) {
            return 0.94;
        }
        if (hasTombstone && hasNegativeCache && hasVersion) {
            return 0.90;
        }
        if (hasTombstone && (hasNegativeCache || hasVersion)) {
            return 0.82;
        }
        if (reliesOnBestEffortInvalidation(text, false, false, false) || treatsRedisTtlAsEnough(text)) {
            return 0.42;
        }
        if (containsAny(text, "invalidate", "delete redis key", "cache invalidation")) {
            return 0.58;
        }
        return 0.35;
    }

    private static double scoreReplicaLagAwareness(String text) {
        boolean hasLag = containsAny(text, "2 seconds", "2-second", "two seconds", "replica lag",
                "lagging replica", "read replicas");
        boolean rejectsReplica = containsAny(text, "do not trust", "must not trust", "avoid replica",
                "bypass replica", "replica is unsafe", "not trust a postgresql replica",
                "not trust the replica");
        boolean hasPrimaryOrVersion = containsAny(text, "primary read", "primary database",
                "primary db", "read primary", "source of truth", "version check",
                "deleted_at", "delete version");

        if (allowsUnsafeReplicaChoice(text, hasPrimaryOrVersion, hasPrimaryOrVersion)) {
            return 0.25;
        }
        if (hasLag && rejectsReplica && hasPrimaryOrVersion) {
            return 0.90;
        }
        if (hasLag && hasPrimaryOrVersion) {
            return 0.78;
        }
        if (hasLag || hasPrimaryOrVersion) {
            return 0.58;
        }
        return 0.30;
    }

    private static double scoreStampedeControl(String text) {
        if (containsAny(text, "singleflight", "single flight", "request coalescing",
                "request collapsing", "per-key lock", "per key lock", "distributed lock",
                "mutex", "dogpile")) {
            return 0.88;
        }
        if (containsAny(text, "cache-aside lease", "lease mechanism") && !returnsMaybeStaleLease(text)) {
            return 0.72;
        }
        if (containsAny(text, "backoff", "retry", "rate limit", "jitter")) {
            return 0.45;
        }
        return 0.25;
    }

    private static double scoreObservability(String text) {
        int signals = 0;
        signals += containsAny(text, "redis p95", "redis p99", "redis latency", "redis timeout") ? 1 : 0;
        signals += containsAny(text, "replica lag", "replication lag") ? 1 : 0;
        signals += containsAny(text, "kafka lag", "consumer lag", "redpanda lag") ? 1 : 0;
        signals += containsAny(text, "tombstone hit", "tombstone hits", "negative-cache hit") ? 1 : 0;
        signals += containsAny(text, "primary fallback", "primary read fallback") ? 1 : 0;
        signals += containsAny(text, "lock wait", "singleflight wait", "coalesced load") ? 1 : 0;
        signals += containsAny(text, "dashboard freshness", "stale dashboard", "analytics freshness") ? 1 : 0;
        signals += containsAny(text, "stale-cache prevention", "stale redirect", "stale-cache") ? 1 : 0;

        if (signals >= 6) {
            return 0.90;
        }
        if (signals >= 4) {
            return 0.82;
        }
        if (signals >= 2) {
            return 0.65;
        }
        if (signals == 1) {
            return 0.48;
        }
        return 0.30;
    }

    private static double scoreTradeoffs(String text) {
        boolean mentionsTradeoff = containsAny(text, "tradeoff", "trade-off", "tradeoffs", "trade-offs",
                "availability", "consistency", "latency");
        boolean correctnessWins = containsAny(text, "correctness over", "prioritize redirect correctness",
                "correctness should win", "consistency over");
        boolean redirectVsAnalytics = containsAny(text, "analytics freshness", "eventual consistency",
                "analytics pipeline", "dashboard", "redirect correctness");
        boolean latencyAvailability = text.contains("latency") && text.contains("availability");

        if (mentionsTradeoff && correctnessWins && redirectVsAnalytics && latencyAvailability) {
            return 0.82;
        }
        if ((mentionsTradeoff && correctnessWins) || (correctnessWins && redirectVsAnalytics)) {
            return 0.66;
        }
        if (mentionsTradeoff) {
            return 0.50;
        }
        return 0.35;
    }

    private static double scorePseudocode(String text) {
        if (hasDanglingPseudocodePromise(text)) {
            return 0.32;
        }
        boolean hasConcrete = hasConcretePseudocode(text);
        if (hasConcrete && activeCachePrecedesTombstoneInPseudocode(text)) {
            return 0.72;
        }
        if (hasConcrete && containsAny(text, "singleflight", "lock", "primarydb.findbyalias",
                "redis.get", "redis.set", "cached == deleted")) {
            return 0.88;
        }
        if (hasConcrete) {
            return 0.72;
        }
        if (text.contains("pseudocode") || text.contains("algorithm")) {
            return 0.42;
        }
        return 0.22;
    }

    private static boolean hasDanglingPseudocodePromise(String text) {
        return containsAny(text, "provided below", "provided below.", "as follows", "shown below")
                && containsAny(text, "pseudocode", "algorithm")
                && !hasConcretePseudocode(text);
    }

    private static double scoreCommonMistakes(String text) {
        boolean hasMistakeSection = containsAny(text, "weaker system", "mistake", "common mistake",
                "would make");
        if (!hasMistakeSection) {
            return 0.48;
        }
        int mistakes = 0;
        mistakes += containsAny(text, "ttl", "expiration alone") ? 1 : 0;
        mistakes += containsAny(text, "trusting lagging replicas", "trust replica", "replica") ? 1 : 0;
        mistakes += containsAny(text, "maybe stale", "cached lease") ? 1 : 0;
        mistakes += containsAny(text, "missing tombstone", "no tombstone", "negative cache") ? 1 : 0;
        mistakes += containsAny(text, "singleflight", "coalescing", "stampede") ? 1 : 0;
        mistakes += containsAny(text, "analytics", "dashboard") ? 1 : 0;
        if (mistakes >= 4) {
            return 0.88;
        }
        if (mistakes >= 3) {
            return 0.78;
        }
        return 0.65;
    }

    private static boolean looksLikeStaleDeletionConsistencyAnswer(String text) {
        return text.contains("redis")
                && (text.contains("postgres") || text.contains("replica"))
                && (text.contains("deleted") || text.contains("deletion"))
                && (text.contains("kafka") || text.contains("analytics"))
                && (text.contains("redirect") || text.contains("url"));
    }

    private static boolean treatsRedisTtlAsEnough(String text) {
        return (text.contains("ttl") || text.contains("expiration") || text.contains("expire"))
                && containsAny(text, "prevent stale", "avoid stale", "stale redirects", "cache invalidation");
    }

    private static boolean allowsUnsafeReplicaChoice(String text,
                                                     boolean hasPrimarySafeRead,
                                                     boolean hasVersionOrDeletedAt) {
        if (containsAny(text, "primary or a read replica", "primary or read replica",
                "primary or replica", "depending on configured consistency",
                "depending on the configured consistency", "depending on consistency level")) {
            return true;
        }
        return text.contains("replica") && !hasPrimarySafeRead && !hasVersionOrDeletedAt;
    }

    private static boolean returnsMaybeStaleLease(String text) {
        if (containsAny(text, "do not return", "should not return", "must not return",
                "never return", "not return a cached", "not serve maybe", "do not serve maybe")) {
            return false;
        }
        if (containsAny(text, "weaker system would", "weaker systems would", "mistake",
                "common mistake", "would serve maybe", "would return maybe")) {
            return false;
        }
        return text.contains("lease")
                && text.contains("stale")
                && containsAny(text, "return", "response", "serve");
    }

    private static boolean activeCachePrecedesTombstoneInPseudocode(String text) {
        int algorithmStart = firstIndexOf(text, "pseudocode", "algorithm", "resolve(");
        if (algorithmStart < 0) {
            return false;
        }
        String snippet = text.substring(algorithmStart);
        int tombstone = firstIndexOf(snippet, "tombstone", "istombstoned", "is tombstoned",
                "deleted", "negative-cache", "negative cache");
        int active = firstIndexOf(snippet, "cachedredirect", "cached redirect", "valid redirect",
                "cache hit", "active redirect", "cached == active", "cached active", "return cached");
        return active >= 0 && tombstone >= 0 && active < tombstone;
    }

    private static boolean hasShallowTradeoffStatement(String text) {
        if (!text.contains("tradeoff") && !text.contains("trade-off")) {
            return false;
        }
        boolean hasDirection = text.contains("prioritize redirect correctness")
                || text.contains("prioritize correctness")
                || text.contains("consistency over");
        boolean hasSeparateBudgets = text.contains("latency")
                && text.contains("availability")
                && (text.contains("analytics") || text.contains("eventual consistency"));
        return hasDirection && !hasSeparateBudgets;
    }

    private static boolean reliesOnBestEffortInvalidation(String text,
                                                          boolean hasTombstone,
                                                          boolean hasPrimarySafeRead,
                                                          boolean hasVersionOrDeletedAt) {
        boolean mentionsInvalidationOnly = containsAny(text, "send an invalidation request",
                "invalidation request to redis", "invalidate redis", "delete redis key");
        return mentionsInvalidationOnly && !hasTombstone && !hasPrimarySafeRead && !hasVersionOrDeletedAt;
    }

    private static boolean hasConcretePseudocode(String text) {
        boolean introducesAlgorithm = containsAny(text, "pseudocode", "algorithm", "resolve(",
                "redirectresult", "redirect result", "```");
        if (!introducesAlgorithm) {
            return false;
        }
        boolean hasControlFlow = containsAny(text, "if (", "if ", "else if", "else", "{", "}")
                && containsAny(text, "return ", "then return");
        boolean hasRedirectState = containsAny(text, "tombstone", "deleted", "negative-cache")
                && containsAny(text, "primary", "primarydb.findbyalias", "db.find", "redis.get",
                        "redis.set", "singleflight", "lock");
        return hasControlFlow && hasRedirectState;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int firstIndexOf(String haystack, String... needles) {
        int result = -1;
        for (String needle : needles) {
            int index = haystack.indexOf(needle);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
