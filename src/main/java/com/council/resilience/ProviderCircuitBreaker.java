package com.council.resilience;

import com.council.config.CouncilProperties;
import com.council.metrics.OrchestrationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom circuit breaker that tracks per-provider 429 rate-limit state
 * and activates a cooldown period after consecutive failures.
 * <p>
 * Thread-safe: all mutable state is in {@link ProviderCooldownState} which uses atomics.
 */
@Component
public class ProviderCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    private final Map<String, ProviderCooldownState> states = new ConcurrentHashMap<>();
    private final int threshold;
    private final int cooldownMinutes;
    private final OrchestrationMetrics metrics;

    public ProviderCircuitBreaker(CouncilProperties properties, OrchestrationMetrics metrics) {
        this.threshold = properties.getOrchestrator().getConsecutive429Threshold();
        this.cooldownMinutes = properties.getOrchestrator().getCooldownMinutes();
        this.metrics = metrics;
    }

    public boolean isInCooldown(String provider) {
        ProviderCooldownState state = states.get(provider);
        return state != null && state.isInCooldown();
    }

    public void record429(String provider) {
        ProviderCooldownState state = stateFor(provider);
        state.increment429();
        metrics.recordRateLimit(provider);

        if (state.getConsecutive429Count() >= threshold) {
            state.activateCooldown(cooldownMinutes);
            metrics.recordCooldownActivation(provider);
            log.warn("[{}] Cooldown activated for {} minutes after {} consecutive 429s",
                    provider, cooldownMinutes, threshold);
        }
    }

    public void recordSuccess(String provider) {
        stateFor(provider).recordSuccess();
    }

    public void recordFailure(String provider) {
        stateFor(provider).recordFailure();
    }

    public ProviderCooldownState getState(String provider) {
        return stateFor(provider);
    }

    public Map<String, ProviderCooldownState> getAllStates() {
        return Map.copyOf(states);
    }

    private ProviderCooldownState stateFor(String provider) {
        return states.computeIfAbsent(provider, ProviderCooldownState::new);
    }
}

