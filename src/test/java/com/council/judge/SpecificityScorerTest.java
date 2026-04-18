package com.council.judge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpecificityScorerTest {

    private SpecificityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new SpecificityScorer();
    }

    /* ══════════════════════════════════════════════════════════════════
       Payments domain specificity
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Payments domain scoring")
    class PaymentsScoringTests {

        @Test
        @DisplayName("Highly specific payment answer scores high for SYSTEM_DESIGN")
        void highlySpecificPaymentAnswer() {
            String answer = """
                    To build a reliable payment system, use an idempotency key per payment attempt.
                    Separate authorization and capture phases. Write every state change to a ledger.
                    Run daily reconciliation against the PSP. Use an outbox pattern to guarantee
                    exactly-once delivery. Handle unknown provider state via webhook retries with
                    exponential back-off. Prevent double charge by checking the idempotency key
                    before creating a new payment intent. Use circuit breaker around PSP calls.
                    """;

            double score = scorer.score(answer, TaskType.SYSTEM_DESIGN);
            assertTrue(score > 0.5, "Specific payment answer should score > 0.5, got " + score);
        }

        @Test
        @DisplayName("Generic payment answer scores low for SYSTEM_DESIGN")
        void genericPaymentAnswer() {
            String answer = """
                    To build a payment system, ensure reliability by following best practices.
                    Use a robust and scalable architecture. Leverage industry standard tools.
                    Ensure seamless integration with payment providers. Use a holistic approach
                    to properly handle all edge cases.
                    """;

            double score = scorer.score(answer, TaskType.SYSTEM_DESIGN);
            assertTrue(score < 0.3, "Generic payment answer should score < 0.3, got " + score);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       Distributed systems specificity
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Distributed systems scoring")
    class DistributedSystemsScoringTests {

        @Test
        @DisplayName("Specific distributed systems answer scores high")
        void specificDistributedAnswer() {
            String answer = """
                    Use Kafka with consumer groups and monitor consumer lag carefully.
                    Offset commits must be idempotent — use an idempotent consumer with
                    deduplication at the application level. Apply optimistic locking with
                    a fencing token to prevent split brain. Use a dead letter queue for
                    messages that fail after max retries. Implement saga with compensation
                    for distributed transactions. Ensure eventual consistency via outbox.
                    """;

            double score = scorer.score(answer, TaskType.BACKEND_ARCHITECTURE);
            assertTrue(score > 0.5, "Specific distributed answer should score > 0.5, got " + score);
        }

        @Test
        @DisplayName("Buzzword-heavy but low-detail answer scores low")
        void buzzwordHeavyAnswer() {
            String answer = """
                    Use state-of-the-art distributed systems patterns. Follow best practices
                    for a robust and scalable system. Leverage cutting-edge technology.
                    Ensure reliability with a holistic approach. It is important to consider
                    all aspects of the system to properly handle failures.
                    """;

            double score = scorer.score(answer, TaskType.BACKEND_ARCHITECTURE);
            assertTrue(score < 0.2, "Buzzword-heavy answer should score < 0.2, got " + score);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       Debugging domain specificity
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Debugging domain scoring")
    class DebuggingScoringTests {

        @Test
        @DisplayName("Specific debugging answer with root cause and mechanisms")
        void specificDebuggingAnswer() {
            String answer = """
                    The root cause is a race condition in the connection pool leading to
                    a deadlock under high concurrency. Reproduce with a flame graph under load.
                    Use a thread dump to identify the contending threads. Check for connection
                    exhaustion via metrics and p99 latency spikes. Apply a circuit breaker
                    with rollback and canary deployment to mitigate.
                    """;

            double score = scorer.score(answer, TaskType.DEBUGGING);
            assertTrue(score > 0.4, "Specific debugging answer should score > 0.4, got " + score);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       CODING / GENERAL tasks → neutral scoring
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Non-technical task scoring")
    class NonTechnicalScoringTests {

        @Test
        @DisplayName("CODING task type returns neutral score (0.5)")
        void codingTaskNeutral() {
            double score = scorer.score("Implement a binary search tree", TaskType.CODING);
            assertEquals(0.5, score, 0.01, "CODING should return neutral 0.5");
        }

        @Test
        @DisplayName("GENERAL_REASONING returns neutral score (0.5)")
        void generalReasoningNeutral() {
            double score = scorer.score("What is the capital of France?", TaskType.GENERAL_REASONING);
            assertEquals(0.5, score, 0.01, "GENERAL_REASONING should return neutral 0.5");
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       Edge cases
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Null answer returns 0.0")
        void nullAnswer() {
            assertEquals(0.0, scorer.score(null, TaskType.SYSTEM_DESIGN));
        }

        @Test
        @DisplayName("Empty answer returns 0.0")
        void emptyAnswer() {
            assertEquals(0.0, scorer.score("", TaskType.SYSTEM_DESIGN));
        }

        @Test
        @DisplayName("Breakdown includes matched keywords")
        void breakdownIncludesKeywords() {
            String answer = "Use an idempotency key for each payment attempt with reconciliation";
            SpecificityScorer.ScoringBreakdown breakdown =
                    scorer.scoreWithBreakdown(answer, TaskType.SYSTEM_DESIGN);

            assertTrue(breakdown.keywordHits() >= 3,
                    "Should have >= 3 keyword hits, got " + breakdown.keywordHits());
            assertTrue(breakdown.matchedKeywords().contains("idempotency key"));
            assertTrue(breakdown.matchedKeywords().contains("payment attempt"));
            assertTrue(breakdown.matchedKeywords().contains("reconciliation"));
            assertEquals(0, breakdown.fluffCount());
        }

        @Test
        @DisplayName("Fluff counter detects generic phrases")
        void fluffCounter() {
            String text = "follow best practices and leverage industry standard for a robust and scalable solution";
            int fluff = scorer.countFluff(text);
            assertTrue(fluff >= 3, "Should detect >= 3 fluff phrases, got " + fluff);
        }
    }
}

