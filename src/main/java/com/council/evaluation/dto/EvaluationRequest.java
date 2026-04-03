package com.council.evaluation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Inbound request to start an evaluation run.
 */
public record EvaluationRequest(

        @Size(max = 200, message = "Name must be at most 200 characters")
        String name,

        List<String> tags,

        List<String> providerSubset,

        boolean runBaselines,

        @NotEmpty(message = "At least one prompt is required")
        @Size(max = 100, message = "At most 100 prompts per evaluation run")
        @Valid
        List<EvaluationPromptInput> prompts
) {}

