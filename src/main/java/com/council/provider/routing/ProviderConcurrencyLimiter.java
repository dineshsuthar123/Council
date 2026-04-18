package com.council.provider.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Per-provider concurrency limiter.
 * <p>
 * Each provider has a configurable max number of in-flight calls.
 * If a provider's semaphore is full, {@link #tryAcquire} returns false
 * and the caller should skip or queue the request.
 * <p>
 * Thread-safe: backed by {@link Semaphore} per provider.
 */
@Component
public class ProviderConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ProviderConcurrencyLimiter.class);
    private static final int DEFAULT_MAX_CONCURRENCY = 5;

    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final Map<String, Integer> maxPermits = new ConcurrentHashMap<>();

    /**
     * Register a provider with a specific max concurrency.
     * Safe to call multiple times — only the first registration takes effect.
     */
    public void register(String provider, int maxConcurrency) {
        int permits = maxConcurrency > 0 ? maxConcurrency : DEFAULT_MAX_CONCURRENCY;
        maxPermits.putIfAbsent(provider, permits);
        semaphores.putIfAbsent(provider, new Semaphore(permits));
    }

    /**
     * Try to acquire a permit for the given provider (non-blocking).
     *
     * @return true if a permit was acquired; false if at capacity
     */
    public boolean tryAcquire(String provider) {
        Semaphore sem = semaphores.computeIfAbsent(provider,
                k -> new Semaphore(maxPermits.getOrDefault(k, DEFAULT_MAX_CONCURRENCY)));
        boolean acquired = sem.tryAcquire();
        if (!acquired) {
            log.warn("[concurrency] Provider '{}' at max concurrency ({}), request rejected",
                    provider, maxPermits.getOrDefault(provider, DEFAULT_MAX_CONCURRENCY));
        }
        return acquired;
    }

    /**
     * Release a permit back for the given provider.
     */
    public void release(String provider) {
        Semaphore sem = semaphores.get(provider);
        if (sem != null) {
            sem.release();
        }
    }

    /**
     * Available permits for the given provider.
     */
    public int availablePermits(String provider) {
        Semaphore sem = semaphores.get(provider);
        return sem != null ? sem.availablePermits()
                : maxPermits.getOrDefault(provider, DEFAULT_MAX_CONCURRENCY);
    }

    /**
     * Max configured concurrency for the given provider.
     */
    public int getMaxConcurrency(String provider) {
        return maxPermits.getOrDefault(provider, DEFAULT_MAX_CONCURRENCY);
    }
}

