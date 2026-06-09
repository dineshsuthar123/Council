package com.council.judge;

import com.council.common.CouncilUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Domain-specific guardrails for wallet/payment transfer answers.
 */
final class PaymentTransferCalibrator {

    private PaymentTransferCalibrator() {}

    static ProductionConsistencyCalibrator.QualityScore qualityScore(String answer,
                                                                     String summary,
                                                                     double fallbackConfidence) {
        String text = normalize(answer + " " + summary);
        ProductionConsistencyCalibrator.Calibration calibration = evaluate(answer, summary);
        if (!looksLikePaymentTransferAnswer(text)) {
            return new ProductionConsistencyCalibrator.QualityScore(
                    Math.min(CouncilUtils.clamp01(fallbackConfidence), calibration.cap()),
                    Map.of(),
                    calibration.reasons());
        }

        Map<String, Double> dimensions = dimensionScores(text);
        double weighted = weightedScore(dimensions);
        double score = Math.min(weighted, calibration.cap());
        return new ProductionConsistencyCalibrator.QualityScore(
                CouncilUtils.clamp01(score), dimensions, calibration.reasons());
    }

    static ProductionConsistencyCalibrator.Calibration evaluate(String answer, String summary) {
        String text = normalize(answer + " " + summary);
        if (!looksLikePaymentTransferAnswer(text)) {
            return new ProductionConsistencyCalibrator.Calibration(1.0, List.of());
        }

        List<String> reasons = new ArrayList<>();
        double cap = 1.0;

        if (sameIdempotencyKeyReturnsFailure(text)) {
            cap = Math.min(cap, 0.50);
            reasons.add("same idempotency key returns failure instead of stored result/status");
        }
        if (createsIdempotencyAfterMoneyMovement(text)) {
            cap = Math.min(cap, 0.55);
            reasons.add("idempotency record is created after debit/credit");
        }
        if (emitsKafkaBeforeCommitWithoutOutbox(text)) {
            cap = Math.min(cap, 0.60);
            reasons.add("Kafka event is emitted before database commit without transactional outbox");
        }
        if (movesMoneyNonAtomically(text)) {
            cap = Math.min(cap, 0.45);
            reasons.add("debit and credit are not clearly atomic");
        }
        if (trustsRedisAsSourceOfTruth(text)) {
            cap = Math.min(cap, 0.60);
            reasons.add("Redis is treated as source of truth for idempotency or balances");
        }
        if (kafkaEventsWithoutOutbox(text)) {
            cap = Math.min(cap, 0.72);
            reasons.add("Kafka publishing lacks transactional outbox safety");
        }
        if (fraudNeverBlocksWithoutConditions(text)) {
            cap = Math.min(cap, 0.70);
            reasons.add("fraud path incorrectly says synchronous blocking is never allowed");
        }

        int missing = 0;
        if (scoreIdempotencySafety(text) < 0.60) {
            missing++;
            reasons.add("weak idempotency correctness");
        }
        if (scoreKafkaOutboxSafety(text) < 0.60) {
            missing++;
            reasons.add("weak Kafka/outbox safety");
        }
        if (scoreAtomicity(text) < 0.60) {
            missing++;
            reasons.add("weak atomic debit/credit handling");
        }
        if (scoreCrashRecovery(text) < 0.55) {
            missing++;
            reasons.add("weak crash recovery");
        }
        if (scorePseudocode(text) < 0.45 && containsAny(text, "pseudocode", "algorithm")) {
            missing++;
            reasons.add("payment pseudocode is not concrete enough");
        }

        if (missing >= 4) {
            cap = Math.min(cap, 0.62);
        } else if (missing >= 3) {
            cap = Math.min(cap, 0.70);
        } else if (missing >= 2) {
            cap = Math.min(cap, 0.78);
        }

        return new ProductionConsistencyCalibrator.Calibration(cap, List.copyOf(reasons));
    }

    static boolean looksLikePaymentTransferAnswer(String text) {
        return containsAny(text, "wallet", "payment", "transfer", "ledger", "money movement")
                && containsAny(text, "idempotency", "idempotent", "idempotency key", "idempotencykey")
                && containsAny(text, "debit", "credit", "balance", "postgres", "database", "transaction");
    }

    static Map<String, Double> dimensionScores(String answer, String summary) {
        String text = normalize(answer + " " + summary);
        if (!looksLikePaymentTransferAnswer(text)) {
            return Map.of();
        }
        return dimensionScores(text);
    }

    private static Map<String, Double> dimensionScores(String text) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("transfer_decision", scoreTransferDecision(text));
        scores.put("idempotency_safety", scoreIdempotencySafety(text));
        scores.put("atomicity", scoreAtomicity(text));
        scores.put("crash_recovery", scoreCrashRecovery(text));
        scores.put("locking_concurrency", scoreLockingConcurrency(text));
        scores.put("redis_role", scoreRedisRole(text));
        scores.put("kafka_outbox_safety", scoreKafkaOutboxSafety(text));
        scores.put("fraud_path", scoreFraudPath(text));
        scores.put("observability", scoreObservability(text));
        scores.put("pseudocode", scorePseudocode(text));
        scores.put("common_mistakes", scoreCommonMistakes(text));
        return Collections.unmodifiableMap(scores);
    }

    private static double weightedScore(Map<String, Double> scores) {
        return (scores.getOrDefault("transfer_decision", 0.0) * 0.08)
                + (scores.getOrDefault("idempotency_safety", 0.0) * 0.18)
                + (scores.getOrDefault("atomicity", 0.0) * 0.18)
                + (scores.getOrDefault("crash_recovery", 0.0) * 0.12)
                + (scores.getOrDefault("locking_concurrency", 0.0) * 0.10)
                + (scores.getOrDefault("redis_role", 0.0) * 0.08)
                + (scores.getOrDefault("kafka_outbox_safety", 0.0) * 0.12)
                + (scores.getOrDefault("fraud_path", 0.0) * 0.05)
                + (scores.getOrDefault("observability", 0.0) * 0.04)
                + (scores.getOrDefault("pseudocode", 0.0) * 0.03)
                + (scores.getOrDefault("common_mistakes", 0.0) * 0.02);
    }

    private static double scoreTransferDecision(String text) {
        boolean exactlyOnce = containsAny(text, "exactly once", "at most once", "succeed exactly once");
        boolean noDoubleDebit = containsAny(text, "never be debited twice", "no double debit",
                "must not debit twice", "duplicate debit");
        boolean pending = containsAny(text, "processing", "pending", "pending_review", "pending review");
        if (exactlyOnce && noDoubleDebit && pending) {
            return 0.92;
        }
        if (exactlyOnce && (noDoubleDebit || pending)) {
            return 0.78;
        }
        if (containsAny(text, "succeed", "fail", "return")) {
            return 0.45;
        }
        return 0.30;
    }

    private static double scoreIdempotencySafety(String text) {
        if (sameIdempotencyKeyReturnsFailure(text)) {
            return 0.20;
        }
        boolean returnsStored = containsAny(text, "return stored result", "return the same result",
                "return same result", "stored response", "replay the response", "current status");
        boolean processing = containsAny(text, "processing", "in progress", "202", "pending");
        boolean lockedBefore = idempotencyBeforeMoneyMovement(text);
        boolean parameterCheck = containsAny(text, "compare incoming parameters", "parameter mismatch",
                "same request parameters", "payload hash", "request hash");
        if (returnsStored && processing && lockedBefore && parameterCheck) {
            return 0.94;
        }
        if (returnsStored && processing && lockedBefore) {
            return 0.88;
        }
        if (returnsStored && lockedBefore) {
            return 0.78;
        }
        if (lockedBefore || returnsStored) {
            return 0.58;
        }
        if (text.contains("idempotency")) {
            return 0.38;
        }
        return 0.20;
    }

    private static double scoreAtomicity(String text) {
        boolean transaction = containsAny(text, "one db transaction", "single database transaction",
                "single db transaction", "same transaction", "begin transaction", "inside one postgresql transaction");
        boolean debitCredit = text.contains("debit") && text.contains("credit");
        boolean atomic = containsAny(text, "atomic", "all-or-nothing", "commit", "rollback");
        boolean eventUnsafe = emitsKafkaBeforeCommitWithoutOutbox(text);
        if (transaction && debitCredit && atomic && !eventUnsafe) {
            return 0.92;
        }
        if (transaction && debitCredit && !eventUnsafe) {
            return 0.78;
        }
        if (transaction && debitCredit) {
            return 0.45;
        }
        if (debitCredit) {
            return 0.32;
        }
        return 0.24;
    }

    private static double scoreCrashRecovery(String text) {
        boolean mentionsCrash = containsAny(text, "crash", "restart", "recovery", "retry");
        boolean idempotencyFirst = idempotencyBeforeMoneyMovement(text);
        boolean outbox = text.contains("outbox");
        boolean reconcile = containsAny(text, "reconciliation", "balance invariant", "ledger invariant",
                "audit", "repair");
        if (mentionsCrash && idempotencyFirst && outbox && reconcile) {
            return 0.90;
        }
        if (mentionsCrash && idempotencyFirst && outbox) {
            return 0.82;
        }
        if ((mentionsCrash && idempotencyFirst) || outbox) {
            return 0.62;
        }
        return 0.30;
    }

    private static double scoreLockingConcurrency(String text) {
        boolean rowLocks = containsAny(text, "select for update", "row lock", "lock both wallet",
                "lock wallet rows", "pessimistic lock");
        boolean deterministicOrder = containsAny(text, "deterministic order", "sort account ids",
                "ordered by account id", "avoid deadlock", "deadlock");
        boolean uniqueKey = containsAny(text, "unique idempotency", "unique constraint", "primary key",
                "insert or lock idempotency");
        if (rowLocks && deterministicOrder && uniqueKey) {
            return 0.90;
        }
        if (rowLocks && (deterministicOrder || uniqueKey)) {
            return 0.78;
        }
        if (rowLocks || uniqueKey) {
            return 0.58;
        }
        return 0.28;
    }

    private static double scoreRedisRole(String text) {
        if (trustsRedisAsSourceOfTruth(text)) {
            return 0.25;
        }
        boolean cacheOnly = containsAny(text, "redis is only", "redis only", "fast-path cache",
                "redis is a cache", "redis as cache");
        boolean postgresTruth = containsAny(text, "postgresql is the source of truth",
                "postgres is the source of truth", "database is the source of truth",
                "db is the source of truth");
        if (cacheOnly && postgresTruth) {
            return 0.90;
        }
        if (cacheOnly || postgresTruth) {
            return 0.72;
        }
        if (text.contains("redis")) {
            return 0.42;
        }
        return 0.50;
    }

    private static double scoreKafkaOutboxSafety(String text) {
        if (emitsKafkaBeforeCommitWithoutOutbox(text)) {
            return 0.18;
        }
        boolean outbox = text.contains("outbox");
        boolean sameTx = containsAny(text, "same transaction", "inside the transaction",
                "inside one postgresql transaction", "transactional outbox");
        boolean worker = containsAny(text, "outbox worker", "publisher", "cdc", "debezium");
        boolean idempotentEvents = containsAny(text, "idempotent event", "event id", "dedupe event",
                "duplicate message");
        if (outbox && sameTx && worker && idempotentEvents) {
            return 0.94;
        }
        if (outbox && sameTx && worker) {
            return 0.88;
        }
        if (outbox && sameTx) {
            return 0.78;
        }
        if (outbox) {
            return 0.62;
        }
        if (text.contains("kafka")) {
            return 0.35;
        }
        return 0.45;
    }

    private static double scoreFraudPath(String text) {
        if (!text.contains("fraud")) {
            return 0.55;
        }
        if (fraudNeverBlocksWithoutConditions(text)) {
            return 0.35;
        }
        boolean conditional = containsAny(text, "advisory", "must block", "synchronous risk",
                "before committing", "pending_review", "pending review", "compensation");
        return conditional ? 0.86 : 0.58;
    }

    private static double scoreObservability(String text) {
        int signals = 0;
        signals += containsAny(text, "idempotency replay", "idempotency conflict", "duplicate key") ? 1 : 0;
        signals += containsAny(text, "lock wait", "deadlock", "row lock") ? 1 : 0;
        signals += containsAny(text, "outbox lag", "outbox backlog", "publisher lag") ? 1 : 0;
        signals += containsAny(text, "kafka lag", "publish failure", "consumer lag") ? 1 : 0;
        signals += containsAny(text, "balance invariant", "ledger invariant", "reconciliation") ? 1 : 0;
        signals += containsAny(text, "fraud", "pending_review", "pending review") ? 1 : 0;
        signals += containsAny(text, "trace id", "uetr", "correlation id", "transaction id") ? 1 : 0;
        if (signals >= 5) {
            return 0.88;
        }
        if (signals >= 3) {
            return 0.76;
        }
        if (signals >= 1) {
            return 0.55;
        }
        return 0.30;
    }

    private static double scorePseudocode(String text) {
        boolean algorithm = containsAny(text, "pseudocode", "algorithm", "transfer(", "begin transaction");
        if (!algorithm) {
            return 0.22;
        }
        boolean idempotencyFirst = idempotencyBeforeMoneyMovement(text);
        boolean transaction = containsAny(text, "begin transaction", "commit", "rollback");
        boolean locks = containsAny(text, "select for update", "lock");
        boolean outbox = text.contains("outbox");
        if (idempotencyFirst && transaction && locks && outbox) {
            return 0.90;
        }
        if (idempotencyFirst && transaction && outbox) {
            return 0.78;
        }
        if (sameIdempotencyKeyReturnsFailure(text)
                || createsIdempotencyAfterMoneyMovement(text)
                || emitsKafkaBeforeCommitWithoutOutbox(text)) {
            return 0.15;
        }
        return 0.45;
    }

    private static double scoreCommonMistakes(String text) {
        boolean section = containsAny(text, "weaker system", "mistake", "common mistake", "would make");
        if (!section) {
            return 0.48;
        }
        int mistakes = 0;
        mistakes += containsAny(text, "return failure", "same idempotency key") ? 1 : 0;
        mistakes += containsAny(text, "idempotency record after", "after debit") ? 1 : 0;
        mistakes += containsAny(text, "kafka before commit", "emit kafka") ? 1 : 0;
        mistakes += containsAny(text, "redis source of truth", "trust redis") ? 1 : 0;
        mistakes += containsAny(text, "non-atomic", "not atomic", "separate debit") ? 1 : 0;
        mistakes += containsAny(text, "fraud", "async only") ? 1 : 0;
        if (mistakes >= 4) {
            return 0.86;
        }
        if (mistakes >= 2) {
            return 0.70;
        }
        return 0.58;
    }

    private static boolean sameIdempotencyKeyReturnsFailure(String text) {
        if (containsAny(text, "should not automatically return failure", "not automatically return failure",
                "must not return failure", "do not return failure", "weaker system would return failure")) {
            return false;
        }
        return text.contains("idempotency")
                && containsAny(text, "if found, return failure", "if exists, return failure",
                        "existing idempotency key return failure", "same idempotency key returns failure",
                        "same idempotency key should return failure");
    }

    private static boolean createsIdempotencyAfterMoneyMovement(String text) {
        int store = firstIndexOf(text, "store idempotency record", "create idempotency record",
                "insert idempotency record");
        int debit = firstIndexOf(text, "debit");
        int credit = firstIndexOf(text, "credit");
        return store >= 0 && ((debit >= 0 && store > debit) || (credit >= 0 && store > credit));
    }

    private static boolean idempotencyBeforeMoneyMovement(String text) {
        String scoped = algorithmScope(text);
        int idempotency = firstIndexOf(scoped, "insert or lock idempotency", "create idempotency",
                "insert idempotency", "lock idempotency", "idempotency record");
        int debit = firstIndexOf(scoped, "debit");
        int credit = firstIndexOf(scoped, "credit");
        if (idempotency < 0) {
            return false;
        }
        int movement = Math.min(debit < 0 ? Integer.MAX_VALUE : debit, credit < 0 ? Integer.MAX_VALUE : credit);
        return movement == Integer.MAX_VALUE || idempotency < movement;
    }

    private static String algorithmScope(String text) {
        int start = firstIndexOf(text, "pseudocode:", "algorithm:", "begin transaction",
                "inside one postgresql transaction");
        return start >= 0 ? text.substring(start) : text;
    }

    private static boolean emitsKafkaBeforeCommitWithoutOutbox(String text) {
        if (text.contains("outbox")
                || containsAny(text, "do not emit kafka", "never emit kafka", "must not emit kafka",
                "should not emit kafka", "not emit kafka")) {
            return false;
        }
        int emit = firstIndexOf(text, "emit kafka", "publish kafka", "send kafka event",
                "emit event", "publish event");
        int commit = firstIndexOf(text, "commit");
        return emit >= 0 && commit >= 0 && emit < commit;
    }

    private static boolean kafkaEventsWithoutOutbox(String text) {
        return text.contains("kafka") && !text.contains("outbox");
    }

    private static boolean movesMoneyNonAtomically(String text) {
        return text.contains("debit")
                && text.contains("credit")
                && !containsAny(text, "transaction", "atomic", "all-or-nothing", "same db transaction");
    }

    private static boolean trustsRedisAsSourceOfTruth(String text) {
        if (containsAny(text, "postgres is the source of truth", "postgresql is the source of truth",
                "database is the source of truth", "db is the source of truth", "redis is only",
                "redis only", "redis is a cache", "fast-path cache")) {
            return false;
        }
        return text.contains("redis") && containsAny(text, "source of truth", "authoritative");
    }

    private static boolean fraudNeverBlocksWithoutConditions(String text) {
        return text.contains("fraud")
                && containsAny(text, "never block synchronously", "should never block synchronously",
                "must never block synchronously")
                && !containsAny(text, "advisory", "pending_review", "pending review",
                "synchronous risk", "before committing", "must block", "compensation");
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
