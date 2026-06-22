package com.council.provider;

import com.council.common.exception.ProviderException;
import com.council.common.exception.ProviderFailureCategory;
import com.council.common.exception.RateLimitException;
import com.council.config.CouncilProperties;
import com.council.metrics.OrchestrationMetrics;
import com.council.resilience.ProviderCircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCallExecutorTest {

    private ProviderCallExecutor executor;
    private ProviderCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        CouncilProperties props = new CouncilProperties();
        props.getOrchestrator().setMaxRetries(1);         // 1 retry = 2 total attempts
        props.getOrchestrator().setConsecutive429Threshold(3);
        props.getOrchestrator().setCooldownMinutes(1);
        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        circuitBreaker = new ProviderCircuitBreaker(props, metrics);
        executor = new ProviderCallExecutor(circuitBreaker, metrics, props);
    }

    @Test
    @DisplayName("Successful call returns result")
    void successfulCall() {
        String result = executor.execute("gemini", () -> "hello world");
        assertEquals("hello world", result);
    }

    @Test
    @DisplayName("Provider in cooldown throws immediately without calling API")
    void providerInCooldown_throwsImmediately() {
        // Trip cooldown
        circuitBreaker.record429("gemini");
        circuitBreaker.record429("gemini");
        circuitBreaker.record429("gemini");
        assertTrue(circuitBreaker.isInCooldown("gemini"));

        Callable<String> neverCalled = () -> {
            fail("Should not be called");
            return null;
        };

        ProviderException ex = assertThrows(ProviderException.class,
                () -> executor.execute("gemini", neverCalled));
        assertEquals(ProviderFailureCategory.CIRCUIT_OPEN, ex.getFailureCategory());
        assertTrue(ex.isCircuitOpen());
    }

    @Test
    @DisplayName("ProviderException is propagated after retries exhausted")
    void providerException_propagated() {
        ProviderException exception = assertThrows(ProviderException.class,
                () -> executor.execute("deepseek", () -> {
                    throw new ProviderException("deepseek", "Server error 500");
                }));
        assertEquals(2, exception.getAttemptCount());
        assertTrue(exception.isRetryAttempted());
    }

    @Test
    @DisplayName("RateLimitException records 429 on circuit breaker")
    void rateLimitException_records429() {
        assertThrows(ProviderException.class,
                () -> executor.execute("claude", () -> {
                    throw new RateLimitException("claude");
                }));

        // After retries, multiple 429s should be recorded
        var state = circuitBreaker.getState("claude");
        assertTrue(state.getConsecutive429Count() > 0);
    }

    @Test
    @DisplayName("Success after failure resets circuit breaker")
    void successAfterFailure_resetsCircuitBreaker() {
        // Simulate a single 429 then succeed
        circuitBreaker.record429("gemini");

        String result = executor.execute("gemini", () -> "recovered");
        assertEquals("recovered", result);

        // Success should have reset the counter
        assertEquals(0, circuitBreaker.getState("gemini").getConsecutive429Count());
    }
}

