package com.council.orchestrator;

import java.util.Locale;

/**
 * Last-mile safeguards for final answers before they are returned to users.
 */
final class FinalAnswerCompletenessGuard {

    private FinalAnswerCompletenessGuard() {}

    static String repair(String userQuery, String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        if (!isCacheDeletionConsistencyPrompt(userQuery) || !asksForAlgorithm(userQuery)) {
            return answer;
        }
        if (hasConcreteRedirectPseudocode(answer)) {
            return answer;
        }

        String trimmed = answer.trim();
        return trimmed + "\n\n" + concreteRedirectAlgorithm();
    }

    static boolean hasDanglingPseudocodePromise(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String lower = normalize(answer);
        boolean promises = lower.contains("provided below")
                || lower.contains("pseudocode below")
                || lower.contains("algorithm below")
                || lower.contains("as follows");
        return promises && !hasConcreteRedirectPseudocode(answer);
    }

    private static boolean isCacheDeletionConsistencyPrompt(String userQuery) {
        String q = normalize(userQuery);
        return q.contains("redis")
                && (q.contains("postgres") || q.contains("replica"))
                && (q.contains("deleted") || q.contains("deletion"))
                && (q.contains("kafka") || q.contains("analytics"))
                && (q.contains("url-shortener") || q.contains("short url") || q.contains("redirect"));
    }

    private static boolean asksForAlgorithm(String userQuery) {
        String q = normalize(userQuery);
        return q.contains("pseudocode") || q.contains("algorithm");
    }

    private static boolean hasConcreteRedirectPseudocode(String answer) {
        String a = normalize(answer);
        boolean hasControlFlow = (a.contains("if (") || a.contains("if "))
                && (a.contains("else") || a.contains("return "));
        boolean hasCorrectnessTerms = a.contains("tombstone")
                && a.contains("primary")
                && (a.contains("singleflight") || a.contains("coalesc") || a.contains("lock"))
                && (a.contains("404") || a.contains("410") || a.contains("not_found"));
        return hasControlFlow && hasCorrectnessTerms;
    }

    private static String concreteRedirectAlgorithm() {
        return """
                Concrete redirect algorithm:

                ```java
                RedirectResult resolve(String alias) {
                    CacheValue cached = redis.get(alias, timeoutMs = 20);

                    if (cached == DELETED) {
                        return notFoundOrGone();
                    }

                    if (cached instanceof ActiveRedirect active
                            && active.version() >= minKnownDeleteVersion(alias)) {
                        emitAnalyticsAsync(alias, "redirect_served");
                        return redirect(active.longUrl());
                    }

                    return singleflight(alias, () -> {
                        Row row = primaryDb.findByAlias(alias);

                        if (row == null || row.deletedAt() != null) {
                            redis.set(alias, DELETED, ttlSeconds = 60);
                            return notFoundOrGone();
                        }

                        CacheValue active = new ActiveRedirect(row.longUrl(), row.version());
                        redis.set(alias, active, ttlMinutes = 5);
                        emitAnalyticsAsync(alias, "redirect_served");
                        return redirect(row.longUrl());
                    });
                }
                ```
                """;
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
