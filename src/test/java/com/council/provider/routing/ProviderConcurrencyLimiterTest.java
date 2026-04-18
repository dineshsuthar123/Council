package com.council.provider.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderConcurrencyLimiterTest {

    private ProviderConcurrencyLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ProviderConcurrencyLimiter();
    }

    @Test
    @DisplayName("Acquire succeeds when under limit")
    void acquireSucceedsUnderLimit() {
        limiter.register("deepseek", 2);
        assertTrue(limiter.tryAcquire("deepseek"));
        assertTrue(limiter.tryAcquire("deepseek"));
    }

    @Test
    @DisplayName("Acquire fails when at limit")
    void acquireFailsAtLimit() {
        limiter.register("gemini", 1);
        assertTrue(limiter.tryAcquire("gemini"));
        assertFalse(limiter.tryAcquire("gemini")); // at max
    }

    @Test
    @DisplayName("Release allows new acquire")
    void releaseAllowsNewAcquire() {
        limiter.register("gemini", 1);
        assertTrue(limiter.tryAcquire("gemini"));
        assertFalse(limiter.tryAcquire("gemini"));

        limiter.release("gemini");
        assertTrue(limiter.tryAcquire("gemini")); // should work again
    }

    @Test
    @DisplayName("Independent providers don't interfere")
    void independentProviders() {
        limiter.register("deepseek", 1);
        limiter.register("groq", 1);

        assertTrue(limiter.tryAcquire("deepseek"));
        assertTrue(limiter.tryAcquire("groq")); // independent

        assertFalse(limiter.tryAcquire("deepseek")); // deepseek full
        assertTrue(limiter.tryAcquire("groq") == false); // groq full too
    }

    @Test
    @DisplayName("Available permits reflects current state")
    void availablePermitsReflectsState() {
        limiter.register("deepseek", 3);
        assertEquals(3, limiter.availablePermits("deepseek"));

        limiter.tryAcquire("deepseek");
        assertEquals(2, limiter.availablePermits("deepseek"));

        limiter.tryAcquire("deepseek");
        assertEquals(1, limiter.availablePermits("deepseek"));

        limiter.release("deepseek");
        assertEquals(2, limiter.availablePermits("deepseek"));
    }

    @Test
    @DisplayName("Unregistered provider uses default concurrency")
    void unregisteredUsesDefault() {
        // No explicit registration
        assertTrue(limiter.tryAcquire("unknown-provider"));
        assertEquals(5, limiter.getMaxConcurrency("unknown-provider")); // default
    }

    @Test
    @DisplayName("Max concurrency returns configured value")
    void maxConcurrencyReturnsConfigured() {
        limiter.register("gemini", 1);
        assertEquals(1, limiter.getMaxConcurrency("gemini"));

        limiter.register("deepseek", 3);
        assertEquals(3, limiter.getMaxConcurrency("deepseek"));
    }

    @Test
    @DisplayName("Concurrent access is thread-safe")
    void threadSafety() throws InterruptedException {
        limiter.register("test", 10);
        int threads = 20;
        java.util.concurrent.atomic.AtomicInteger acquired = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger(0);

        Thread[] workers = new Thread[threads];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.tryAcquire("test")) {
                    acquired.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
            });
            workers[i].start();
        }

        latch.countDown(); // start all at once
        for (Thread w : workers) w.join();

        assertEquals(10, acquired.get());
        assertEquals(10, rejected.get());
    }
}

