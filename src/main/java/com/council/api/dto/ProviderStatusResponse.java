package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-provider health/status snapshot returned by GET /api/v1/providers/status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderStatusResponse(
        String provider,
        String model,
        boolean enabled,
        boolean coolingDown,
        String cooldownUntil,
        int consecutive429Count,
        double recentFailureRate,
        int totalSuccesses,
        int totalFailures,
        String lastSuccess,
        String lastFailure,
        // Routing metadata (null when routing is disabled)
        List<String> roles,
        Integer priority,
        Integer maxConcurrency,
        Integer availableConcurrencyPermits,
        List<String> fallbackProviders,
        Boolean availableForRouting
) {
    /**
     * Compact constructor for legacy (non-routing) usage.
     */
    public ProviderStatusResponse(String provider, String model, boolean enabled,
                                   boolean coolingDown, String cooldownUntil,
                                   int consecutive429Count, double recentFailureRate,
                                   int totalSuccesses, int totalFailures,
                                   String lastSuccess, String lastFailure) {
        this(provider, model, enabled, coolingDown, cooldownUntil,
                consecutive429Count, recentFailureRate, totalSuccesses, totalFailures,
                lastSuccess, lastFailure,
                null, null, null, null, null, null);
    }
}
