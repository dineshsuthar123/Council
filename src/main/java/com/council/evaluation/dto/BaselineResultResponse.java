package com.council.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result from a single-model baseline run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaselineResultResponse(
        String provider,
        String answer,
        Double confidence,
        Long latencyMs,
        Integer answerLength,
        Double keywordMatchScore,
        String error
) {}

