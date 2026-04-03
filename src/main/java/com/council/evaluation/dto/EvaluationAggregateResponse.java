package com.council.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Aggregate metrics computed across all prompts in an evaluation run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationAggregateResponse(
        int totalPrompts,
        int successfulPrompts,
        int failedPrompts,

        /* ── council aggregates ───────────────────────────────────── */
        double averageLatencyMs,
        double averageConfidence,
        double averageContradictionSeverity,
        double averageAnswerLength,
        Double averageKeywordMatchScore,
        Map<String, Integer> winnerFrequency,
        Map<String, Integer> providerSuccessCounts,
        Map<String, Integer> providerFailureCounts,

        /* ── baseline aggregates (one entry per provider) ─────────── */
        Map<String, BaselineAggregateResponse> baselineAggregates
) {}

