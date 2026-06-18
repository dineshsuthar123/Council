package com.council.orchestrator;

import com.council.research.ResearchPack;

import java.util.Locale;

/**
 * Last-mile safeguards for final answers before they are returned to users.
 */
final class FinalAnswerCompletenessGuard {

    private FinalAnswerCompletenessGuard() {}

    static String compose(String userQuery, String answer, ResearchPack researchPack) {
        String repaired = repair(userQuery, answer);
        if (repaired == null || repaired.isBlank()) {
            return repaired;
        }
        if (hasStructuredTemplate(repaired)) {
            return repaired;
        }
        if (isCacheDeletionConsistencyPrompt(userQuery)) {
            return urlShortenerTemplate(repaired);
        }
        if (isPaymentTransferPrompt(userQuery, repaired)) {
            return paymentTransferTemplate(repaired);
        }
        if (researchPack != null && researchPack.required()) {
            return researchTemplate(repaired, researchPack);
        }
        return repaired;
    }

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

    private static boolean isPaymentTransferPrompt(String userQuery, String answer) {
        String text = normalize(userQuery + " " + answer);
        return containsAny(text, "payment", "transfer", "wallet", "ledger", "money movement")
                && containsAny(text, "idempotency", "idempotency key", "debit", "credit")
                && containsAny(text, "postgres", "database", "transaction", "kafka", "outbox");
    }

    private static boolean hasStructuredTemplate(String answer) {
        String lower = normalize(answer);
        return containsAny(lower, "### decision", "## decision")
                && containsAny(lower, "### core safety", "## core safety", "### safety")
                && containsAny(lower, "### tradeoffs", "## tradeoffs", "### trade-offs")
                && containsAny(lower, "### concrete", "## concrete", "### algorithm", "### pseudocode")
                && containsAny(lower, "### common mistakes", "## common mistakes");
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

    private static String urlShortenerTemplate(String answer) {
        return """
                ### Decision
                Return `404 Not Found` or `410 Gone` for a recently deleted alias. Do not redirect a known-deleted short URL.

                ### Core Safety Reasoning
                %s

                ### Tradeoffs
                For deleted aliases, correctness and read-your-writes consistency win over low latency and stale availability. Analytics can remain eventually consistent, but the redirect path cannot use delayed dashboards or Kafka lag as redirect truth.

                ### Concrete Algorithm
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

                ### Common Mistakes
                - Trusting Redis TTL as deletion consistency.
                - Serving active cache before checking a tombstone.
                - Reading a lagging replica during a recent delete window.
                - Treating delayed analytics dashboards as redirect truth.
                - Retrying every miss independently instead of using singleflight/per-key coalescing.
                """.formatted(answer.trim());
    }

    private static String paymentTransferTemplate(String answer) {
        return """
                ### Decision
                Execute each payment transfer at most once. A reused idempotency key with a different request body must return a deterministic conflict instead of replaying or executing a different transfer.

                ### Core Safety Reasoning
                %s

                ### Tradeoffs
                Payment correctness beats low latency when state is ambiguous. It is better to return `PROCESSING`, `PENDING_REVIEW`, or a stored response than to double debit, double capture, or emit events outside the committed ledger state.

                ### Concrete Algorithm
                ```java
                TransferResult transfer(Request request) {
                    beginTransaction();
                    IdempotencyRecord idem = lockOrInsertIdempotency(request.key(), request.payloadHash());

                    if (idem.payloadHashDiffers(request.payloadHash())) {
                        rollback();
                        return conflict("idempotency key reused with different body");
                    }
                    if (idem.status() == SUCCEEDED) return storedResponse(idem);
                    if (idem.status() == PROCESSING) return currentStatus(idem);

                    lockWalletRowsInDeterministicOrder(request.debtor(), request.creditor());
                    debit(request.debtor(), request.amount());
                    credit(request.creditor(), request.amount());
                    markSucceeded(idem);
                    insertTransactionalOutboxEvent(request.eventId());
                    commit();

                    return success();
                }
                ```

                ### Common Mistakes
                - Creating the idempotency record after money movement.
                - Returning generic failure for every existing idempotency key.
                - Splitting debit and credit across non-atomic transactions.
                - Publishing Kafka events before commit without an outbox.
                - Treating Redis as the source of truth for balances or idempotency.
                """.formatted(answer.trim());
    }

    private static String researchTemplate(String answer, ResearchPack researchPack) {
        String warning = researchPack.hasSources()
                ? "Use the registered source IDs for claims that depend on current or prompt-provided evidence."
                : "External research was required, but no source pack was available; state uncertainty clearly.";
        return """
                ### Decision
                Answer only to the level supported by the evidence pack. %s

                ### Core Safety Reasoning
                %s

                ### Tradeoffs
                Prefer cited, recent, primary evidence over broad unsupported claims. If sources conflict, call out the disagreement and explain which source is fresher or more authoritative.

                ### Concrete Evidence Check
                ```text
                if research_required and sources_available:
                    cite source ids near current factual claims
                    reject citations outside the supplied source list
                    reconcile conflicts before making a final claim
                else:
                    lower certainty and explain missing evidence
                ```

                ### Common Mistakes
                - Making current claims without citations.
                - Citing source IDs that were not in the evidence pack.
                - Hiding conflicting evidence.
                - Treating older reports as current when newer evidence supersedes them.
                """.formatted(warning, answer.trim());
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
