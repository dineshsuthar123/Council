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
        double confidence,
        String error,
        String message,
        Double answerQuality,
        Double winnerConfidence,
        Double modelAgreement
) {
    public FinalResponse(String traceId,
                         String finalAnswer,
                         String judgeReason,
                         List<String> usedProviders,
                         List<String> failedProviders,
                         double confidence) {
        this(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, null, null, confidence, confidence, null);
    }

    public FinalResponse(String traceId,
                         String finalAnswer,
                         String judgeReason,
                         List<String> usedProviders,
                         List<String> failedProviders,
                         double confidence,
                         String error,
                         String message) {
        this(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, error, message, confidence, confidence, null);
    }

    public FinalResponse withScoreBreakdown(double answerQuality,
                                            double winnerConfidence,
                                            double modelAgreement) {
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                answerQuality, error, message, answerQuality, winnerConfidence, modelAgreement);
    }
}

