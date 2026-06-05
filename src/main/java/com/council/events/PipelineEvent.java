package com.council.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Server-side progress event for a single Council reasoning run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineEvent(
        String traceId,
        String phase,
        String status,
        String message,
        long elapsedMs,
        Map<String, Object> details,
        Instant createdAt
) {
    public static PipelineEvent of(String traceId,
                                   String phase,
                                   String status,
                                   String message,
                                   long elapsedMs,
                                   Map<String, Object> details) {
        return new PipelineEvent(traceId, phase, status, message, elapsedMs,
                details == null ? Map.of() : Map.copyOf(details), Instant.now());
    }
}
