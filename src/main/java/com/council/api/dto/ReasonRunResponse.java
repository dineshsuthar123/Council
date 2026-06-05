package com.council.api.dto;

/**
 * Response returned when an asynchronous reasoning run is accepted.
 */
public record ReasonRunResponse(
        String traceId,
        String status,
        String eventsUrl
) {}
