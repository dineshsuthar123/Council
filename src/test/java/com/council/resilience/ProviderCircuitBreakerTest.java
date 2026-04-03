package com.council.resilience;

import com.council.config.CouncilProperties;
import com.council.metrics.OrchestrationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCircuitBreakerTest {

    private ProviderCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        CouncilProperties props = new CouncilProperties();
        props.getOrchestrator().setConsecutive429Threshold(3);
        props.getOrchestrator().setCooldownMinutes(1);
        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        breaker = new ProviderCircuitBreaker(props, metrics);
    }

    @Test
    @DisplayName("Provider starts not in cooldown")
    void initialStateNotInCooldown() {
        assertFalse(breaker.isInCooldown("gemini"));
    }

    @Test
    @DisplayName("3 consecutive 429s trip cooldown")
    void tripsCooldownAfterThreshold() {
        breaker.record429("gemini");
        breaker.record429("gemini");
        assertFalse(breaker.isInCooldown("gemini"));

        breaker.record429("gemini");
        assertTrue(breaker.isInCooldown("gemini"));
    }

    @Test
    @DisplayName("Success resets consecutive 429 count")
    void successResets429Count() {
        breaker.record429("gemini");
        breaker.record429("gemini");
        breaker.recordSuccess("gemini");

        // After success, we need 3 more consecutive 429s
        breaker.record429("gemini");
        breaker.record429("gemini");
        assertFalse(breaker.isInCooldown("gemini"));
    }

    @Test
    @DisplayName("Non-429 failure does not increment 429 counter")
    void failureDoesNotIncrement429() {
        breaker.record429("gemini");
        breaker.record429("gemini");
        breaker.recordFailure("gemini"); // generic failure, NOT a 429
        breaker.record429("gemini");     // only 3rd consecutive 429
        assertTrue(breaker.isInCooldown("gemini"));
    }

    @Test
    @DisplayName("Different providers are independent")
    void providersAreIndependent() {
        breaker.record429("gemini");
        breaker.record429("gemini");
        breaker.record429("gemini");
        assertTrue(breaker.isInCooldown("gemini"));
        assertFalse(breaker.isInCooldown("claude"));
    }
}

