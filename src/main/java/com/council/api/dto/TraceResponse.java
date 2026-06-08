package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.List;

/**
 * Full trace payload for the GET /traces/{traceId} endpoint.
 * JSON-typed fields are returned as raw JSON to avoid double-serialization.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceResponse(
        String traceId,
        String userQuery,
        @JsonRawValue String draftResults,
        @JsonRawValue String criticResult,
        @JsonRawValue String judgeResult,
        String finalAnswer,
        Double finalConfidence,
        Double answerQuality,
        Double winnerConfidence,
        Double modelAgreement,
        @JsonRawValue String dimensions,
        String judgeReason,
        List<String> usedProviders,
        List<String> failedProviders,
        Long totalLatencyMs,
        String status,
        String createdAt,
        String completedAt
) {}

