package com.council.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Full response for an evaluation run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationResponse(
        String runId,
        String name,
        String status,
        List<String> tags,
        int totalPrompts,
        int completedPrompts,
        int failedPrompts,
        EvaluationAggregateResponse aggregate,
        List<EvaluationPromptResponse> results,
        String createdAt,
        String completedAt
) {}

