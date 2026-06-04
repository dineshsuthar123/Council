package com.council.judge;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Heuristic specificity / realism scorer for engineering answers.
 * <p>
 * Scores how many concrete, high-signal technical concepts an answer contains.
 * The score is normalised to [0.0, 1.0] relative to the domain's expected depth.
 * <p>
 * This is NOT an ML classifier – it is a deterministic keyword-density + numerical
 * estimation signal that rewards answers mentioning real-world engineering primitives
 * and concrete numbers (TPS, QPS, latency ms, partition counts) instead of hand-wavy
 * buzzwords.
 *
 * <h3>Numerical estimation bonus (BACKEND_ARCHITECTURE / SYSTEM_DESIGN)</h3>
 * For architecture tasks we explicitly look for quantitative patterns such as:
 * <ul>
 *   <li>Throughput: "10 000 TPS", "50k QPS", "300 msg/s"</li>
 *   <li>Latency: "p99 150ms", "< 200 ms", "500ms budget"</li>
 *   <li>Storage: "2 TB/day", "500 GB"</li>
 *   <li>Partitions / replicas: "34 partitions", "replication factor 3"</li>
 *   <li>Node / instance counts: "6 nodes", "3 replicas"</li>
 * </ul>
 * Each distinct numerical pattern found adds a bonus of +0.08 (capped at +0.40).
 * BACKEND_ARCHITECTURE receives the full bonus; SYSTEM_DESIGN receives a reduced bonus.
 * This explicitly rewards the "show the math" behavior for backend architecture answers.
 */
@Component
public class SpecificityScorer {

    /* ── Numerical estimation patterns ───────────────────────────────── */

    /**
     * Hard cap on the input length subjected to regex scanning.
     * Prevents catastrophic-backtracking and worst-case linear-scan cost on
     * adversarial LLM output. Drafts longer than this are truncated to the
     * prefix before regex matching; keyword {@code contains(...)} checks
     * still run on the full text.
     */
    private static final int MAX_REGEX_INPUT_CHARS = 16_384;

    /**
     * Patterns that detect concrete quantitative estimations.
     * <p>
     * All quantifiers are <b>possessive</b> ({@code *+}, {@code ++}, {@code {n,m}+})
     * to eliminate backtracking. Character classes that used {@code [^.]} are
     * restricted to {@code [^.\n]} so a pathological single-line input cannot
     * drive quadratic scan cost across line boundaries. Nested {@code (...)*}
     * shapes have been flattened.
     */
    private static final List<Pattern> NUMERICAL_PATTERNS = List.of(
            // Throughput: "10 000 TPS", "50k QPS", "300 RPS", "1M req/s", "120 msg/s"
            Pattern.compile("\\b\\d[\\d ,]*+\\s*+(?:k|m|b)?+\\s*+(?:tps|qps|rps|req/s|msg/s|ops/s|events/s|writes/s|reads/s)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Latency with leading context: "p99 150ms", "latency of 100ms" (bounded, possessive)
            Pattern.compile("\\b(?:p99|p95|p50|p999|latency|timeout|sla)[^.\\n]{0,30}+\\d++\\s*+ms\\b",
                    Pattern.CASE_INSENSITIVE),
            // Bare "<n> ms" with optional trailing context (bounded, possessive)
            Pattern.compile("\\b\\d++\\s*+ms\\b(?:[^.\\n]{0,20}+(?:latency|timeout|response|budget|slo|sla))?+",
                    Pattern.CASE_INSENSITIVE),
            // Storage: "2 TB/day", "500 GB", "10 GB/s"
            Pattern.compile("\\b\\d[\\d .]*+\\s*+(?:k|m|g|t)?+b(?:/(?:s|day|hour|month))?+\\b",
                    Pattern.CASE_INSENSITIVE),
            // Kafka / queue partitions: "34 partitions", "partition count: 12"
            Pattern.compile("\\b\\d++\\s*+partition", Pattern.CASE_INSENSITIVE),
            // Replication factor: "replication factor 3", "3 replicas"
            Pattern.compile("\\b(?:replication factor|\\d++\\s*+replica)", Pattern.CASE_INSENSITIVE),
            // Node / instance counts: "6 nodes", "3 instances", "5 shards"
            Pattern.compile("\\b\\d++\\s*+(?:node|instance|shard|server|pod|container)s?+\\b",
                    Pattern.CASE_INSENSITIVE),
            // User / request scale: "1M users", "100k concurrent", "10 000 users"
            Pattern.compile("\\b\\d[\\d ,]*+\\s*+(?:k|m|b)?+\\s*+(?:user|request|connection|session|client)s?+\\b",
                    Pattern.CASE_INSENSITIVE),
            // Retry / backoff numbers: "150ms backoff", "retry 3 times", "3 retries"
            Pattern.compile("\\b\\d++\\s*+(?:x|times?+)?+\\s*+retr(?:y|ies)\\b|\\b\\d++\\s*+ms\\s*+(?:exponential\\s*+)?+backoff\\b",
                    Pattern.CASE_INSENSITIVE),
            // Percentage / error budget: "99.9%", "0.1% error", "budget of 1%"
            Pattern.compile("\\b\\d++(?:\\.\\d++)?+\\s*+%\\s*+(?:availability|uptime|error|budget|slo|sla)?+\\b",
                    Pattern.CASE_INSENSITIVE),
            // Explicit arithmetic estimation: "10k * 5 / 60 = 833".
            // Flattened (no nested `*`): bounded repetition {0,8}+ on the "operator operand"
            // group eliminates the catastrophic-backtracking shape and still matches
            // realistic expressions up to 9 operands.
            Pattern.compile("\\b\\d[\\d ,]*+(?:\\.\\d++)?+(?:\\s*+[x*+/-]\\s*+\\d[\\d ,]*+(?:\\.\\d++)?+){0,8}+\\s*+=\\s*+\\d[\\d ,]*+(?:\\.\\d++)?+\\b",
                    Pattern.CASE_INSENSITIVE)
    );

    /** Bonus per unique numerical pattern matched (capped at MAX_NUMERIC_BONUS). */
    private static final double NUMERIC_BONUS_PER_MATCH = 0.08;
    private static final double MAX_NUMERIC_BONUS = 0.40;
    private static final double BACKEND_ARCH_NUMERIC_MULTIPLIER = 1.0;
    private static final double SYSTEM_DESIGN_NUMERIC_MULTIPLIER = 0.75;

    /* ── Domain keyword sets ──────────────────────────────────────────── */

    private static final Map<String, List<String>> DOMAIN_KEYWORDS = new LinkedHashMap<>();

    static {
        DOMAIN_KEYWORDS.put("payments", List.of(
                "idempotency key", "payment attempt", "authorization",
                "capture", "ledger", "reconciliation",
                "double charge", "double-charge", "charge prevention",
                "settlement", "refund", "chargeback",
                "payment intent", "psp", "payment service provider",
                "tokenization", "pci", "3ds", "sca",
                "webhook", "callback", "payment state machine",
                "partial capture", "void", "payment gateway",
                "acquirer", "issuer", "card network"
        ));

        DOMAIN_KEYWORDS.put("distributed_systems", List.of(
                "exactly-once", "at-least-once", "at-most-once",
                "outbox", "inbox", "dead letter queue", "dlq",
                "circuit breaker", "bulkhead", "backpressure",
                "kafka", "partition", "consumer lag", "consumer group",
                "offset commit", "rebalance",
                "leader election", "raft", "paxos", "zab",
                "split brain", "quorum", "consensus",
                "crdt", "vector clock", "lamport",
                "eventual consistency", "strong consistency", "linearizability",
                "causal consistency", "read-your-writes",
                "saga", "compensation", "two-phase commit", "2pc",
                "optimistic locking", "pessimistic locking", "cas",
                "fencing token", "lease", "heartbeat",
                "shard", "consistent hashing", "replication lag",
                "write-ahead log", "wal", "binlog",
                "idempotent consumer", "deduplication"
        ));

        DOMAIN_KEYWORDS.put("debugging", List.of(
                "root cause", "root-cause", "stack trace",
                "reproduce", "minimal reproduction",
                "race condition", "data race", "thread safety",
                "deadlock", "livelock", "starvation",
                "memory leak", "gc pressure", "heap dump",
                "thread dump", "flame graph", "profiler",
                "bisect", "git bisect", "log correlation",
                "metrics", "p99 latency", "error rate",
                "canary", "rollback", "feature flag",
                "circuit breaker state", "connection exhaustion",
                "file descriptor leak", "dns resolution",
                "tcp retransmit", "kernel tuning"
        ));

        DOMAIN_KEYWORDS.put("backend_architecture", List.of(
                "api gateway", "service mesh", "sidecar",
                "rate limiter", "token bucket", "sliding window",
                "connection pool", "thread pool", "event loop",
                "cqrs", "event sourcing", "materialized view",
                "read replica", "write-through cache", "write-behind",
                "cache invalidation", "ttl", "cdn",
                "blue-green", "canary deployment", "rolling update",
                "health check", "readiness probe", "liveness probe",
                "graceful shutdown", "drain", "back-off",
                "retry budget", "timeout budget", "deadline propagation",
                "tracing", "span", "correlation id",
                "schema migration", "backward compatible", "versioned api",
                "database per service", "shared database",
                "strangler fig", "anti-corruption layer",
                // Added: concrete operational primitives rewarded by new prompt
                "circuit breaker", "bulkhead", "backpressure",
                "outbox pattern", "saga", "idempotency key",
                "exponential backoff", "jitter", "dead letter queue"
        ));
    }

    /**
     * Fluff phrases that should NOT count positively.
     */
    private static final List<Pattern> FLUFF_PATTERNS = List.of(
            Pattern.compile("\\bbest practices\\b",         Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bindustry standard\\b",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brobust and scalable\\b",    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bensure reliability\\b",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bleverage\\b",               Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bseamless\\b",               Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bholistic approach\\b",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bworld-class\\b",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstate-of-the-art\\b",       Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcutting-edge\\b",           Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bproperly handle\\b",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bimportant to consider\\b",  Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bit depends\\b",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bvarious factors\\b",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcomprehensive solution\\b", Pattern.CASE_INSENSITIVE)
    );

    /* ── Public API ───────────────────────────────────────────────────── */

    /**
     * Score the specificity of an answer given the detected task type.
     *
     * <p>For {@code BACKEND_ARCHITECTURE} and {@code SYSTEM_DESIGN} tasks, answers that
     * include concrete numerical estimations (TPS, QPS, latency ms, partition counts, etc.)
     * receive a significant bonus — rewarding the "Show the Math" drafter constraint.
     *
     * @param answer   the draft answer text
     * @param taskType the classified task type (used to select relevant domains)
     * @return a score in [0.0, 1.0] where higher = more specific
     */
    public double score(String answer, TaskType taskType) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        String lower = answer.toLowerCase(Locale.ROOT);
        List<String> relevantDomains = getRelevantDomains(taskType);

        if (relevantDomains.isEmpty()) {
            return 0.5; // GENERAL_REASONING / CODING — no specificity penalty
        }

        // ── Keyword density score ──────────────────────────────────────
        int totalHits     = 0;
        int totalKeywords = 0;
        for (String domain : relevantDomains) {
            List<String> keywords = DOMAIN_KEYWORDS.getOrDefault(domain, List.of());
            totalKeywords += keywords.size();
            for (String kw : keywords) {
                if (lower.contains(kw)) totalHits++;
            }
        }

        if (totalKeywords == 0) return 0.5;

        double density  = (double) totalHits / totalKeywords;
        double hitBonus = Math.min(1.0, totalHits / 8.0);
        double rawScore = 0.4 * density + 0.6 * hitBonus;

        // ── Fluff penalty ──────────────────────────────────────────────
        int    fluffCount   = countFluff(lower);
        double fluffPenalty = Math.min(0.3, fluffCount * 0.05);

        // ── Numerical estimation bonus (architecture tasks only) ───────
        double numericBonus = numericalEstimationBonus(answer, taskType);

        return clamp01(rawScore - fluffPenalty + numericBonus);
    }

    /**
     * Count how many distinct numerical estimation patterns appear in the answer.
     * Each pattern is counted once (presence, not frequency) to avoid gaming.
     * Input longer than {@link #MAX_REGEX_INPUT_CHARS} is truncated before
     * scanning to bound worst-case runtime on adversarial LLM output.
     */
    public int countNumericalMatches(String answer) {
        if (answer == null || answer.isBlank()) return 0;
        CharSequence bounded = boundForRegex(answer);
        int count = 0;
        for (Pattern p : NUMERICAL_PATTERNS) {
            if (p.matcher(bounded).find()) count++;
        }
        return count;
    }

    /**
     * Count how many fluff phrases appear in the answer.
     * Input longer than {@link #MAX_REGEX_INPUT_CHARS} is truncated before
     * scanning to bound worst-case runtime on adversarial LLM output.
     */
    public int countFluff(String lowerAnswer) {
        if (lowerAnswer == null || lowerAnswer.isEmpty()) return 0;
        CharSequence bounded = boundForRegex(lowerAnswer);
        int count = 0;
        for (Pattern p : FLUFF_PATTERNS) {
            if (p.matcher(bounded).find()) count++;
        }
        return count;
    }

    /**
     * Truncate the given text to {@link #MAX_REGEX_INPUT_CHARS} characters for
     * regex scanning. The truncation is deterministic (prefix) and does not
     * affect keyword {@code contains(...)} checks, which still run on the full
     * text in {@link #score(String, TaskType)}.
     */
    private static CharSequence boundForRegex(String text) {
        if (text.length() <= MAX_REGEX_INPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_REGEX_INPUT_CHARS);
    }

    /**
     * Score result with breakdown for observability.
     */
    public ScoringBreakdown scoreWithBreakdown(String answer, TaskType taskType) {
        if (answer == null || answer.isBlank()) {
            return new ScoringBreakdown(0.0, 0, 0, 0, 0, List.of());
        }

        String lower = answer.toLowerCase(Locale.ROOT);
        List<String> relevantDomains = getRelevantDomains(taskType);

        List<String> matchedKeywords = new ArrayList<>();
        int totalKeywords = 0;
        for (String domain : relevantDomains) {
            List<String> keywords = DOMAIN_KEYWORDS.getOrDefault(domain, List.of());
            totalKeywords += keywords.size();
            for (String kw : keywords) {
                if (lower.contains(kw)) matchedKeywords.add(kw);
            }
        }

        int    fluffCount      = countFluff(lower);
        int    numericMatches  = countNumericalMatches(answer);
        double finalScore      = score(answer, taskType);

        return new ScoringBreakdown(finalScore, matchedKeywords.size(),
                totalKeywords, fluffCount, numericMatches, matchedKeywords);
    }

    /* ── Helpers ──────────────────────────────────────────────────────── */

    private List<String> getRelevantDomains(TaskType taskType) {
        return switch (taskType) {
            case SYSTEM_DESIGN          -> List.of("distributed_systems", "backend_architecture", "payments");
            case BACKEND_ARCHITECTURE   -> List.of("backend_architecture", "distributed_systems");
            case DEBUGGING              -> List.of("debugging", "distributed_systems");
            case CODING                 -> List.of();
            case GENERAL_REASONING      -> List.of();
        };
    }

    private double numericalEstimationBonus(String answer, TaskType taskType) {
        if (taskType != TaskType.BACKEND_ARCHITECTURE && taskType != TaskType.SYSTEM_DESIGN) {
            return 0.0;
        }

        int numericMatches = countNumericalMatches(answer);
        if (numericMatches == 0) {
            return 0.0;
        }

        double multiplier = taskType == TaskType.BACKEND_ARCHITECTURE
                ? BACKEND_ARCH_NUMERIC_MULTIPLIER
                : SYSTEM_DESIGN_NUMERIC_MULTIPLIER;

        return Math.min(MAX_NUMERIC_BONUS, numericMatches * NUMERIC_BONUS_PER_MATCH * multiplier);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /* ── Result record ────────────────────────────────────────────────── */

    /**
     * Detailed breakdown of a specificity score for observability / debugging.
     */
    public record ScoringBreakdown(
            double score,
            int keywordHits,
            int totalKeywordsChecked,
            int fluffCount,
            /** Number of distinct numerical estimation patterns matched. */
            int numericalPatternMatches,
            List<String> matchedKeywords
    ) {}
}
