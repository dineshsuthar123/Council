package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Aggregated provider quality and latency view computed from recent traces.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderScorecardResponse(
        String provider,
        String model,
        int totalCalls,
        int successes,
        int failures,
        double successRate,
        long avgLatencyMs,
        long p50LatencyMs,
        long p95LatencyMs,
        double avgConfidence,
        double bestConfidence,
        String lastSeen,
        List<String> recentErrors
) {}
