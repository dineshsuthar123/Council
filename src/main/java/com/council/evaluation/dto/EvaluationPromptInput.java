package com.council.evaluation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A single prompt within an evaluation request.
 */
public record EvaluationPromptInput(

        @NotBlank(message = "Prompt must not be blank")
        @Size(max = 32_000, message = "Prompt must be at most 32000 characters")
        String prompt,

        String expectedAnswer,

        List<String> expectedKeywords
) {}

