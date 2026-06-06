package com.council.judge;

import com.council.common.CouncilUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public static double capConfidence(String answer, String summary, double confidence) {
        return Math.min(CouncilUtils.clamp01(confidence), evaluate(answer, summary).cap());
    }

    public static Calibration evaluate(String answer, String summary) {
        String text = normalize(answer + " " + summary);
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

        return new Calibration(cap, List.copyOf(reasons));
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
        return text.contains("lease")
                && text.contains("stale")
                && containsAny(text, "return", "response", "serve");
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
        if (!text.contains("pseudocode") && !text.contains("resolve(") && !text.contains("redirectresult")) {
            return false;
        }
        return containsAny(text, "if (", "if ", "else", "return ", "{", "}", "redis.get", "db.find");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
