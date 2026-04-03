package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Standard error payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String message,
        String traceId,
        String timestamp
) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, Instant.now().toString());
    }

    public static ErrorResponse of(String error, String message, String traceId) {
        return new ErrorResponse(error, message, traceId, Instant.now().toString());
    }
}

