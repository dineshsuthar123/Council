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
        String errorMessage,
        ProviderFailureDetails failureDetails,
        ProviderOutcomeStatus outcomeStatus
) {
    public DraftResult(String provider, String model, ProviderResultStatus status, String answer, String summary,
                       List<String> assumptions, List<String> uncertainties, double confidence, long latencyMs,
                       String rawResponse, String errorMessage) {
        this(provider, model, status, answer, summary, assumptions, uncertainties, confidence, latencyMs,
                rawResponse, errorMessage, null, status == ProviderResultStatus.SUCCESS
                        ? ProviderOutcomeStatus.SUCCEEDED : ProviderOutcomeStatus.FAILED);
    }
    public static DraftResult success(String provider, String model,
                                      String answer, String summary,
                                      List<String> assumptions, List<String> uncertainties,
                                      double confidence, long latencyMs, String rawResponse) {
        return new DraftResult(provider, model, ProviderResultStatus.SUCCESS,
                answer, summary, assumptions, uncertainties,
                CouncilUtils.clamp01(confidence), latencyMs, rawResponse, null, null,
                ProviderOutcomeStatus.SUCCEEDED);
    }

    public static DraftResult failure(String provider, String model,
                                      String errorMessage, long latencyMs) {
        return failure(provider, model, errorMessage, latencyMs, null);
    }

    public static DraftResult failure(String provider, String model, String errorMessage, long latencyMs,
                                      ProviderFailureDetails failureDetails) {
        ProviderOutcomeStatus outcomeStatus = outcomeStatusFor(failureDetails);
        return new DraftResult(provider, model,
                outcomeStatus == ProviderOutcomeStatus.FAILED ? ProviderResultStatus.FAILURE : ProviderResultStatus.SKIPPED,
                null, null, List.of(), List.of(),
                0.0, latencyMs, null, errorMessage, failureDetails, outcomeStatus);
    }

    public static DraftResult skipped(String provider, String model, ProviderOutcomeStatus outcomeStatus,
                                      String reason) {
        if (outcomeStatus == null || !outcomeStatus.name().startsWith("SKIPPED_")) {
            throw new IllegalArgumentException("Skipped draft requires a SKIPPED_* provider outcome status");
        }
        return new DraftResult(provider, model, ProviderResultStatus.SKIPPED,
                null, null, List.of(), List.of(), 0.0, 0L, null, reason, null, outcomeStatus);
    }

    public boolean isSuccess() {
        return status == ProviderResultStatus.SUCCESS;
    }

    public boolean isFailure() {
        return status == ProviderResultStatus.FAILURE && outcomeStatus == ProviderOutcomeStatus.FAILED;
    }

    public boolean isSkipped() {
        return status == ProviderResultStatus.SKIPPED;
    }

    private static ProviderOutcomeStatus outcomeStatusFor(ProviderFailureDetails failureDetails) {
        if (failureDetails == null) {
            return ProviderOutcomeStatus.FAILED;
        }
        return switch (failureDetails.failureCategory()) {
            case DISABLED -> ProviderOutcomeStatus.SKIPPED_DISABLED;
            case API_KEY_MISSING -> ProviderOutcomeStatus.UNAVAILABLE_API_KEY_MISSING;
            case CIRCUIT_OPEN -> ProviderOutcomeStatus.SKIPPED_CIRCUIT_OPEN;
            default -> ProviderOutcomeStatus.FAILED;
        };
    }
}
