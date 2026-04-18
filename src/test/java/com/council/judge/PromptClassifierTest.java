package com.council.judge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptClassifierTest {

    private PromptClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new PromptClassifier();
    }

    /* ══════════════════════════════════════════════════════════════════
       SYSTEM_DESIGN classification
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("SYSTEM_DESIGN classification")
    class SystemDesignTests {

        @Test
        @DisplayName("Detects system design prompt with 'design a system' + 'scalable'")
        void detectsSystemDesign() {
            TaskType result = classifier.classify(
                    "Design a system for a scalable notification system with high availability");
            assertEquals(TaskType.SYSTEM_DESIGN, result);
        }

        @Test
        @DisplayName("Detects payment system design")
        void detectsPaymentSystemDesign() {
            TaskType result = classifier.classify(
                    "Design a payment system that handles distributed transactions and sharding");
            assertEquals(TaskType.SYSTEM_DESIGN, result);
        }

        @Test
        @DisplayName("Detects URL shortener system design")
        void detectsUrlShortener() {
            TaskType result = classifier.classify(
                    "Design a scalable URL shortener with caching layer and replication");
            assertEquals(TaskType.SYSTEM_DESIGN, result);
        }

        @Test
        @DisplayName("Detects microservice architecture prompt")
        void detectsMicroserviceDesign() {
            TaskType result = classifier.classify(
                    "Design a microservice architecture for an event-driven booking system");
            assertEquals(TaskType.SYSTEM_DESIGN, result);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       BACKEND_ARCHITECTURE classification
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("BACKEND_ARCHITECTURE classification")
    class BackendArchitectureTests {

        @Test
        @DisplayName("Detects backend architecture with outbox pattern and saga")
        void detectsOutboxAndSaga() {
            TaskType result = classifier.classify(
                    "Explain the outbox pattern and saga pattern for distributed transactions with Kafka");
            assertEquals(TaskType.BACKEND_ARCHITECTURE, result);
        }

        @Test
        @DisplayName("Detects idempotency and circuit breaker topic")
        void detectsIdempotencyCircuitBreaker() {
            TaskType result = classifier.classify(
                    "How to implement idempotency with optimistic locking and a circuit breaker in a webhook handler");
            assertEquals(TaskType.BACKEND_ARCHITECTURE, result);
        }

        @Test
        @DisplayName("Detects CQRS and event sourcing topic")
        void detectsCqrsEventSourcing() {
            TaskType result = classifier.classify(
                    "What are the tradeoffs of cqrs with event sourcing and eventual consistency?");
            assertEquals(TaskType.BACKEND_ARCHITECTURE, result);
        }

        @Test
        @DisplayName("Detects TPS/high-throughput/payment orchestration as backend architecture")
        void detectsPaymentScaleTerms() {
            TaskType result = classifier.classify(
                    "We need payment orchestration for a high-throughput service at 12k TPS.");
            assertEquals(TaskType.BACKEND_ARCHITECTURE, result);
        }

        @Test
        @DisplayName("Detects transactions per second phrasing as backend architecture")
        void detectsTransactionsPerSecondPhrase() {
            TaskType result = classifier.classify(
                    "How should we design backend retries and storage for 2000 transactions per second?");
            assertEquals(TaskType.BACKEND_ARCHITECTURE, result);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       DEBUGGING classification
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("DEBUGGING classification")
    class DebuggingTests {

        @Test
        @DisplayName("Detects debugging with root cause and stack trace")
        void detectsDebugging() {
            TaskType result = classifier.classify(
                    "Debug this issue: intermittent deadlock causing connection refused. " +
                    "Stack trace shows a race condition in the connection pool.");
            assertEquals(TaskType.DEBUGGING, result);
        }

        @Test
        @DisplayName("Detects memory leak investigation")
        void detectsMemoryLeak() {
            TaskType result = classifier.classify(
                    "Troubleshoot a memory leak in our Java service. The heap dump shows GC pressure. " +
                    "How to reproduce the issue?");
            assertEquals(TaskType.DEBUGGING, result);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       CODING classification
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("CODING classification")
    class CodingTests {

        @Test
        @DisplayName("Detects coding task with algorithm and data structure")
        void detectsCoding() {
            TaskType result = classifier.classify(
                    "Implement a binary search algorithm with dynamic programming optimisation");
            assertEquals(TaskType.CODING, result);
        }

        @Test
        @DisplayName("Detects LeetCode style question")
        void detectsLeetcode() {
            TaskType result = classifier.classify(
                    "Write a function to solve this leetcode problem using a hash map and recursion");
            assertEquals(TaskType.CODING, result);
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       GENERAL_REASONING classification
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("GENERAL_REASONING classification")
    class GeneralReasoningTests {

        @Test
        @DisplayName("Simple query classified as GENERAL_REASONING")
        void simpleQuery() {
            TaskType result = classifier.classify("What is 2+2?");
            assertEquals(TaskType.GENERAL_REASONING, result);
        }

        @Test
        @DisplayName("Non-technical query classified as GENERAL_REASONING")
        void nonTechnicalQuery() {
            TaskType result = classifier.classify(
                    "Explain the history of the Roman Empire and its influence on modern law");
            assertEquals(TaskType.GENERAL_REASONING, result);
        }

        @Test
        @DisplayName("Null input returns GENERAL_REASONING")
        void nullInput() {
            assertEquals(TaskType.GENERAL_REASONING, classifier.classify(null));
        }

        @Test
        @DisplayName("Blank input returns GENERAL_REASONING")
        void blankInput() {
            assertEquals(TaskType.GENERAL_REASONING, classifier.classify("  "));
        }

        @Test
        @DisplayName("Single keyword in long prompt is not enough (requires 2+ hits)")
        void singleKeywordNotEnough() {
            // A long sentence with only one keyword hit should still be GENERAL_REASONING
            TaskType result = classifier.classify(
                    "Can you explain how the concept of scalable teams works in a corporate environment and "
                    + "what management best practices exist for growing engineering organisations effectively?");
            assertEquals(TaskType.GENERAL_REASONING, result);
        }
    }
}

