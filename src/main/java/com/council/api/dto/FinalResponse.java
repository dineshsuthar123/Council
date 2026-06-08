package com.council.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

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
        Double modelAgreement,
        Map<String, Double> dimensions
) {
    public FinalResponse(String traceId,
                         String finalAnswer,
                         String judgeReason,
                         List<String> usedProviders,
                         List<String> failedProviders,
                         double confidence) {
        this(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, null, null, confidence, confidence, null, null);
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
                confidence, error, message, confidence, confidence, null, null);
    }

    public FinalResponse withScoreBreakdown(double answerQuality,
                                            double winnerConfidence,
                                            double modelAgreement) {
        return withScoreBreakdown(answerQuality, winnerConfidence, modelAgreement, null);
    }

    public FinalResponse withScoreBreakdown(double answerQuality,
                                            double winnerConfidence,
                                            double modelAgreement,
                                            Map<String, Double> dimensions) {
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                answerQuality, error, message, answerQuality, winnerConfidence, modelAgreement, dimensions);
    }
}

