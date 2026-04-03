package com.council.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Aggregate metrics for a single baseline provider across all prompts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaselineAggregateResponse(
        String provider,
        double averageLatencyMs,
        double averageConfidence,
        double averageAnswerLength,
        Double averageKeywordMatchScore,
        int successes,
        int failures
) {}

