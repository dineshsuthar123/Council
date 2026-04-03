package com.council.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound API request for the reasoning endpoint.
 */
public record ReasonRequest(

        @NotBlank(message = "Query must not be blank")
        @Size(max = 32_000, message = "Query must be at most 32000 characters")
        String query
) {}

