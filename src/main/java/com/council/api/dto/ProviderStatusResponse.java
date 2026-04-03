package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        String lastFailure
) {}

