package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Outbound response returned by the reasoning endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinalResponse(
        String traceId,
        String finalAnswer,
        String judgeReason,
        List<String> usedProviders,
        List<String> failedProviders,
        double confidence
) {}

