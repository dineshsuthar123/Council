package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.List;

/**
 * Detailed debug view of a reasoning trace.
 * Returned by GET /api/v1/traces/{traceId}/debug.
 * <p>
 * Includes all pipeline artefacts for debugging without leaking secrets.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceDebugResponse(
        String traceId,
        String status,
        String userQuery,

        /* ── pipeline timing ──────────────────────────────────────── */
        Long totalLatencyMs,
        String createdAt,
        String completedAt,

        /* ── draft phase ──────────────────────────────────────────── */
        int totalDrafts,
        int successfulDrafts,
        int failedDrafts,
        List<String> usedProviders,
        List<String> failedProviders,
        @JsonRawValue String draftResults,

        /* ── raw provider output (never contains API keys) ───────── */
        @JsonRawValue String rawResponses,

        /* ── critic phase ─────────────────────────────────────────── */
        @JsonRawValue String criticResult,

        /* ── judge phase ──────────────────────────────────────────── */
        @JsonRawValue String judgeResult,
        String judgeReason,

        /* ── final output ─────────────────────────────────────────── */
        String finalAnswer,
        Double finalConfidence
) {}

