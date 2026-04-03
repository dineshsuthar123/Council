package com.council.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central metrics facade.  All counters and timers are registered with Micrometer
 * and automatically exposed via the Prometheus actuator endpoint.
 */
@Component
public class OrchestrationMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> providerSuccessCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> providerFailureCounters = new ConcurrentHashMap<>();

    private final Counter totalRequests;
    private final Counter retryCounter;
    private final Counter rateLimitCounter;
    private final Counter cooldownCounter;
    private final Counter invalidJsonCounter;
    private final Timer totalLatencyTimer;
    private final Timer criticLatencyTimer;

    public OrchestrationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.totalRequests   = Counter.builder("council.requests.total").register(registry);
        this.retryCounter    = Counter.builder("council.retries.total").register(registry);
        this.rateLimitCounter    = Counter.builder("council.rate_limits.total").register(registry);
        this.cooldownCounter     = Counter.builder("council.cooldowns.total").register(registry);
        this.invalidJsonCounter  = Counter.builder("council.invalid_json.total").register(registry);
        this.totalLatencyTimer   = Timer.builder("council.request.latency").register(registry);
        this.criticLatencyTimer  = Timer.builder("council.critic.latency").register(registry);
    }

    public void recordRequest() {
        totalRequests.increment();
    }

    public void recordProviderCall(String provider, String status, long latencyMs) {
        Timer.builder("council.provider.latency")
                .tag("provider", provider)
                .tag("status", status)
                .register(registry)
                .record(Duration.ofMillis(latencyMs));

        if ("SUCCESS".equals(status)) {
            providerSuccessCounters
                    .computeIfAbsent(provider, p -> Counter.builder("council.provider.success")
                            .tag("provider", p).register(registry))
                    .increment();
        } else {
            providerFailureCounters
                    .computeIfAbsent(provider, p -> Counter.builder("council.provider.failure")
                            .tag("provider", p).register(registry))
                    .increment();
        }
    }

    public void recordRetry(String provider) {
        retryCounter.increment();
        Counter.builder("council.retries").tag("provider", provider).register(registry).increment();
    }

    public void recordRateLimit(String provider) {
        rateLimitCounter.increment();
        Counter.builder("council.rate_limits").tag("provider", provider).register(registry).increment();
    }

    public void recordCooldownActivation(String provider) {
        cooldownCounter.increment();
        Counter.builder("council.cooldowns").tag("provider", provider).register(registry).increment();
    }

    public void recordInvalidJson(String provider) {
        invalidJsonCounter.increment();
        Counter.builder("council.invalid_json").tag("provider", provider).register(registry).increment();
    }

    public void recordCriticLatency(long latencyMs) {
        criticLatencyTimer.record(Duration.ofMillis(latencyMs));
    }

    public void recordJudgeDecision(String winnerProvider) {
        Counter.builder("council.judge.winner").tag("provider", winnerProvider).register(registry).increment();
    }

    public void recordTotalLatency(long latencyMs) {
        totalLatencyTimer.record(Duration.ofMillis(latencyMs));
    }
}

