package com.council.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Lightweight summary for evaluation run listings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationRunSummary(
        String runId,
        String name,
        String status,
        List<String> tags,
        int totalPrompts,
        int completedPrompts,
        int failedPrompts,
        String createdAt,
        String completedAt
) {}

