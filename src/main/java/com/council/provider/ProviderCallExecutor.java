package com.council.provider;

import com.council.common.exception.JsonNormalizationException;
import com.council.common.exception.ProviderException;
import com.council.common.exception.RateLimitException;
import com.council.config.CouncilProperties;
import com.council.metrics.OrchestrationMetrics;
import com.council.resilience.ProviderCircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates retry + circuit-breaker logic for provider HTTP calls.
 * <p>
 * Extracted from {@link AbstractLlmAdapter} so the adapter is not responsible
 * for resilience concerns, and this class can be unit-tested in isolation.
 */
@Component
public class ProviderCallExecutor {

    private final ProviderCircuitBreaker circuitBreaker;
    private final OrchestrationMetrics metrics;
    private final ConcurrentHashMap<String, Retry> retryCache = new ConcurrentHashMap<>();
    private final int maxAttempts;

    public ProviderCallExecutor(ProviderCircuitBreaker circuitBreaker,
                                OrchestrationMetrics metrics,
                                CouncilProperties properties) {
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.maxAttempts = properties.getOrchestrator().getMaxRetries() + 1;
    }

    /**
     * Execute a provider API call with retry, circuit-breaker, and metrics integration.
     *
     * @param provider lowercase provider name (e.g. "claude")
     * @param apiCall  the actual HTTP call — may throw {@link ProviderException}
     * @return raw text content from the provider
     * @throws ProviderException if all retries are exhausted or provider is in cooldown
     */
    public String execute(String provider, Callable<String> apiCall) {
        if (circuitBreaker.isInCooldown(provider)) {
            throw new ProviderException(provider, "Provider is in cooldown");
        }

        Retry retry = retryCache.computeIfAbsent(provider, this::buildRetry);

        try {
            String result = Retry.decorateCheckedSupplier(retry, () -> {
                try {
                    return apiCall.call();
                } catch (RateLimitException e) {
                    circuitBreaker.record429(provider);
                    metrics.recordRetry(provider);
                    throw e;
                } catch (ProviderException e) {
                    circuitBreaker.recordFailure(provider);
                    metrics.recordRetry(provider);
                    throw e;
                } catch (Exception e) {
                    circuitBreaker.recordFailure(provider);
                    metrics.recordRetry(provider);
                    throw new ProviderException(provider, e.getMessage(), e);
                }
            }).get();
            circuitBreaker.recordSuccess(provider);
            return result;
        } catch (ProviderException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProviderException(provider, "Unexpected error: " + t.getMessage(), t);
        }
    }

    private Retry buildRetry(String provider) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(1000L, 2.0, 0.5))
                .retryOnException(this::isRetryable)
                .build();
        return Retry.of(provider + "-retry", config);
    }

    private boolean isRetryable(Throwable t) {
        return t instanceof RateLimitException
                || (t instanceof ProviderException providerException
                && !(t instanceof JsonNormalizationException)
                && providerException.getFailureCategory().isRetryable());
    }
}




