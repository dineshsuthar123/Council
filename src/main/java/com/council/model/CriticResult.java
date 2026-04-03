package com.council.model;

import com.council.common.CouncilUtils;
import com.council.common.ProviderResultStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result from the critic model (success or failure).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CriticResult(
        String provider,
        String model,
        ProviderResultStatus status,
        String globalSummary,
        double contradictionSeverity,
        Map<String, Integer> contradictionCountPerDraft,
        List<Contradiction> contradictionsFound,
        List<String> missingPoints,
        List<String> riskyClaims,
        long latencyMs,
        String rawResponse,
        String errorMessage
) {
    public static CriticResult success(String provider, String model,
                                       String globalSummary, double contradictionSeverity,
                                       Map<String, Integer> contradictionCountPerDraft,
                                       List<Contradiction> contradictionsFound,
                                       List<String> missingPoints, List<String> riskyClaims,
                                       long latencyMs, String rawResponse) {
        return new CriticResult(provider, model, ProviderResultStatus.SUCCESS,
                globalSummary, CouncilUtils.clamp01(contradictionSeverity),
                contradictionCountPerDraft, contradictionsFound,
                missingPoints, riskyClaims, latencyMs, rawResponse, null);
    }

    public static CriticResult failure(String provider, String model,
                                       String errorMessage, long latencyMs) {
        return new CriticResult(provider, model, ProviderResultStatus.FAILURE,
                null, 0.0, Map.of(), List.of(), List.of(), List.of(),
                latencyMs, null, errorMessage);
    }

    public boolean isSuccess() {
        return status == ProviderResultStatus.SUCCESS;
    }
}
