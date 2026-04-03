package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Lightweight trace summary for paginated listing.
 * Does NOT include raw draft/critic/judge payloads to keep the response small.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceSummaryResponse(
        String traceId,
        String status,
        String userQuery,
        String finalAnswer,
        Double finalConfidence,
        List<String> usedProviders,
        List<String> failedProviders,
        Long totalLatencyMs,
        String createdAt,
        String completedAt
) {}

