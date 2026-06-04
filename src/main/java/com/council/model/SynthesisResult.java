package com.council.model;

import com.council.common.CouncilUtils;
import com.council.common.ProviderResultStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result from the synthesis model (success or failure).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SynthesisResult(
        String provider,
        String model,
        ProviderResultStatus status,
        String synthesizedAnswer,
        String summary,
        List<String> decisions,
        List<String> mergedStrengths,
        List<String> discardedClaims,
        List<String> assumptions,
        List<String> uncertainties,
        double confidence,
        long latencyMs,
        String rawResponse,
        String errorMessage
) {

    public static SynthesisResult success(String provider,
                                          String model,
                                          String synthesizedAnswer,
                                          String summary,
                                          List<String> decisions,
                                          List<String> mergedStrengths,
                                          List<String> discardedClaims,
                                          List<String> assumptions,
                                          List<String> uncertainties,
                                          double confidence,
                                          long latencyMs,
                                          String rawResponse) {
        return new SynthesisResult(
                provider,
                model,
                ProviderResultStatus.SUCCESS,
                synthesizedAnswer,
                summary,
                decisions == null ? List.of() : decisions,
                mergedStrengths == null ? List.of() : mergedStrengths,
                discardedClaims == null ? List.of() : discardedClaims,
                assumptions == null ? List.of() : assumptions,
                uncertainties == null ? List.of() : uncertainties,
                CouncilUtils.clamp01(confidence),
                latencyMs,
                rawResponse,
                null
        );
    }

    public static SynthesisResult failure(String provider, String model, String errorMessage, long latencyMs) {
        return new SynthesisResult(
                provider,
                model,
                ProviderResultStatus.FAILURE,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.0,
                latencyMs,
                null,
                errorMessage
        );
    }

    public boolean isSuccess() {
        return status == ProviderResultStatus.SUCCESS;
    }
}
