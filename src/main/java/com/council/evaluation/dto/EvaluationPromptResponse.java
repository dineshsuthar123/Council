package com.council.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Per-prompt evaluation result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationPromptResponse(
        int promptIndex,
        String prompt,
        String status,

        /* ── council result ───────────────────────────────────────── */
        String councilTraceId,
        String councilAnswer,
        Double councilConfidence,
        Long councilLatencyMs,
        String councilWinnerProvider,
        Double councilContradictionSeverity,
        List<String> councilUsedProviders,
        List<String> councilFailedProviders,
        String councilJudgeReason,
        Integer councilAnswerLength,

        /* ── keyword matching ─────────────────────────────────────── */
        Double keywordMatchScore,

        /* ── baselines ────────────────────────────────────────────── */
        Map<String, BaselineResultResponse> baselines,

        String errorMessage
) {}

