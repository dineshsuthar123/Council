package com.council.model;

import com.council.common.CouncilUtils;
import com.council.common.ProviderResultStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result from a single draft generation attempt (success or failure).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftResult(
        String provider,
        String model,
        ProviderResultStatus status,
        String answer,
        String summary,
        List<String> assumptions,
        List<String> uncertainties,
        double confidence,
        long latencyMs,
        String rawResponse,
        String errorMessage
) {
    public static DraftResult success(String provider, String model,
                                      String answer, String summary,
                                      List<String> assumptions, List<String> uncertainties,
                                      double confidence, long latencyMs, String rawResponse) {
        return new DraftResult(provider, model, ProviderResultStatus.SUCCESS,
                answer, summary, assumptions, uncertainties,
                CouncilUtils.clamp01(confidence), latencyMs, rawResponse, null);
    }

    public static DraftResult failure(String provider, String model,
                                      String errorMessage, long latencyMs) {
        return new DraftResult(provider, model, ProviderResultStatus.FAILURE,
                null, null, List.of(), List.of(),
                0.0, latencyMs, null, errorMessage);
    }

    public boolean isSuccess() {
        return status == ProviderResultStatus.SUCCESS;
    }
}
