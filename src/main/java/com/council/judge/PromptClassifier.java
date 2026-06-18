package com.council.judge;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight rule-based classifier that identifies the task type of a user prompt.
 * <p>
 * No ML – just keyword/phrase matching with weighted scoring.
 * The highest-scoring category wins; ties default to {@link TaskType#GENERAL_REASONING}.
 */
@Component
public class PromptClassifier {

    /**
     * Keyword groups per task type.
     * Each keyword hit adds 1 point; multi-word phrases are treated as single units.
     */
    private static final Map<TaskType, List<String>> KEYWORD_MAP = Map.of(

            TaskType.SYSTEM_DESIGN, List.of(
                    "design a system", "system design", "design a service",
                    "architecture for", "design an api", "design the backend",
                    "scalable", "high availability", "distributed system",
                    "microservice", "event-driven", "message queue",
                    "load balancer", "caching layer", "sharding",
                    "replication", "failover", "cap theorem",
                    "consistency model", "partition tolerance",
                    "rate limiter", "notification system",
                    "url shortener", "chat system", "payment system",
                    "booking system", "feed system", "search engine",
                    "file storage", "video streaming", "ride sharing"
            ),

            TaskType.BACKEND_ARCHITECTURE, List.of(
                    "database schema", "api gateway", "service mesh",
                    "circuit breaker", "retry strategy", "idempotency",
                    "outbox pattern", "saga pattern", "cqrs", "event sourcing",
                    "dead letter queue", "webhook", "kafka", "rabbitmq",
                    "grpc", "rest api", "graphql", "connection pool",
                    "thread pool", "backpressure", "rate limiting",
                    "optimistic locking", "pessimistic locking",
                    "distributed lock", "consensus", "raft", "paxos",
                    "leader election", "exactly-once", "at-least-once",
                    "eventual consistency", "strong consistency",
                        "reconciliation", "ledger", "double-write",
                        "transactions per second", "high-throughput", "high throughput",
                        "orchestration", "payment"
            ),

            TaskType.DEBUGGING, List.of(
                    "debug", "debugging", "root cause", "root-cause",
                    "why does", "why is", "what causes", "what caused",
                    "stack trace", "stacktrace", "exception", "error message",
                    "failing", "fails", "broken", "crash", "crashing",
                    "memory leak", "deadlock", "race condition",
                    "null pointer", "segfault", "out of memory",
                    "timeout", "connection refused", "500 error",
                    "log analysis", "troubleshoot", "investigate",
                    "flaky test", "intermittent", "reproduce"
            ),

            TaskType.CODING, List.of(
                    "implement", "write a function", "write code",
                    "code snippet", "algorithm", "data structure",
                    "write a class", "write a method", "refactor",
                    "unit test", "leetcode", "hackerrank",
                    "time complexity", "space complexity", "big o",
                    "sort", "binary search", "dynamic programming",
                    "recursion", "bfs", "dfs", "linked list",
                    "hash map", "tree", "graph algorithm",
                    "regex", "parse", "serialize", "deserialize",
                    "write a script", "implement a", "code review"
            ),

            TaskType.RESEARCH_REQUIRED, List.of(
                    "which sources should be trusted", "how should citations be attached",
                    "official pricing page", "source 1", "source 2", "source [1]",
                    "[s1]", "prompt-injection text found inside source", "prompt injection",
                    "current pricing", "latency implications", "evidence pack",
                    "sources disagree", "source ranking", "source-ranking",
                    "citation correctness", "citations", "cite", "source quality",
                    "recommendation", "official provider", "old blog", "github issue"
            )
    );

            private static final List<Pattern> BACKEND_ARCHITECTURE_PATTERNS = List.of(
                Pattern.compile("\\btps\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\btransactions\\s+per\\s+second\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bhigh-throughput\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bhigh\\s+throughput\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\borchestration\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bpayment\\b", Pattern.CASE_INSENSITIVE)
            );

    /**
     * Classify the given user prompt into a {@link TaskType}.
     *
     * @param userQuery the raw prompt text (never null)
     * @return the detected task type
     */
    public TaskType classify(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return TaskType.GENERAL_REASONING;
        }

        String lower = userQuery.toLowerCase(Locale.ROOT);
        if (isResearchEvidencePrompt(lower)) {
            return TaskType.RESEARCH_REQUIRED;
        }

        TaskType best = TaskType.GENERAL_REASONING;
        int bestScore = 0;

        for (var entry : KEYWORD_MAP.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    score++;
                }
            }

            if (entry.getKey() == TaskType.BACKEND_ARCHITECTURE) {
                for (Pattern pattern : BACKEND_ARCHITECTURE_PATTERNS) {
                    if (pattern.matcher(lower).find()) {
                        score++;
                    }
                }
            }

            if (score > bestScore || (score == bestScore && score > 0
                    && tieBreakPriority(entry.getKey()) < tieBreakPriority(best))) {
                bestScore = score;
                best = entry.getKey();
            }
        }

        // Require at least 2 keyword hits to beat GENERAL_REASONING,
        // unless the prompt is very short (< 60 chars) where 1 strong hit suffices.
        if (bestScore == 1 && lower.length() >= 60) {
            return TaskType.GENERAL_REASONING;
        }

        return best;
    }

    private int tieBreakPriority(TaskType taskType) {
        return switch (taskType) {
            case SYSTEM_DESIGN -> 0;
            case RESEARCH_REQUIRED -> 1;
            case BACKEND_ARCHITECTURE -> 2;
            case DEBUGGING -> 3;
            case CODING -> 4;
            case GENERAL_REASONING -> 100;
        };
    }

    private boolean isResearchEvidencePrompt(String lower) {
        boolean hasSourceBlocks = Pattern.compile("(?im)^\\s*(source\\s*\\[?\\d+]?|\\[s\\d+]|s\\d+)\\s*:")
                .matcher(lower)
                .find();
        return hasSourceBlocks
                || (lower.contains("evidence pack") && lower.contains("citation"))
                || lower.contains("which sources should be trusted")
                || lower.contains("citation correctness")
                || lower.contains("sources disagree")
                || lower.contains("prompt-injection text found inside source")
                || (lower.contains("official pricing page") && lower.contains("recommendation"));
    }
}

