package com.council.resilience;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe mutable state for a single provider's cooldown tracking.
 */
public class ProviderCooldownState {

    private final String provider;
    private final AtomicInteger consecutive429Count = new AtomicInteger(0);
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicInteger totalFailures = new AtomicInteger(0);
    private final AtomicInteger totalSuccesses = new AtomicInteger(0);
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>(null);

    public ProviderCooldownState(String provider) {
        this.provider = provider;
    }

    public String getProvider() { return provider; }

    public int getConsecutive429Count() { return consecutive429Count.get(); }

    public Instant getCooldownUntil() { return cooldownUntil.get(); }

    public Instant getLastSuccessAt() { return lastSuccessAt.get(); }

    public Instant getLastFailureAt() { return lastFailureAt.get(); }

    public int getTotalSuccesses() { return totalSuccesses.get(); }

    public int getTotalFailures() { return totalFailures.get(); }

    public boolean isInCooldown() {
        return Instant.now().isBefore(cooldownUntil.get());
    }

    public void increment429() {
        consecutive429Count.incrementAndGet();
        totalFailures.incrementAndGet();
        lastFailureAt.set(Instant.now());
    }

    public void recordFailure() {
        totalFailures.incrementAndGet();
        lastFailureAt.set(Instant.now());
    }

    public void recordSuccess() {
        consecutive429Count.set(0);
        totalSuccesses.incrementAndGet();
        lastSuccessAt.set(Instant.now());
    }

    public void activateCooldown(int cooldownMinutes) {
        cooldownUntil.set(Instant.now().plusSeconds(cooldownMinutes * 60L));
    }

    public void resetCooldown() {
        cooldownUntil.set(Instant.EPOCH);
        consecutive429Count.set(0);
    }

    /**
     * Approximate recent failure rate (total failures / total calls).
     */
    public double getRecentFailureRate() {
        int f = totalFailures.get();
        int s = totalSuccesses.get();
        int total = f + s;
        return total == 0 ? 0.0 : (double) f / total;
    }
}

