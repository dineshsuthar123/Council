package com.council.api.dto;

import com.council.judge.FinalScoreBreakdown;
import com.council.judge.invariant.InvariantCriticResult;
import com.council.model.ProviderRunDiagnostics;
import com.council.model.ProviderFailureDetails;
import com.council.research.ResearchPack;
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
        Map<String, Double> dimensions,
        FinalScoreBreakdown scoreBreakdown,
        ResearchPack research,
        InvariantCriticResult invariants,
        ProviderRunDiagnostics runDiagnostics,
        List<ProviderFailureDetails> providerFailures
) {
    public FinalResponse(String traceId,
                         String finalAnswer,
                         String judgeReason,
                         List<String> usedProviders,
                         List<String> failedProviders,
                         double confidence) {
        this(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, null, null, confidence, confidence, null, null, null, null, null, null, null);
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
                confidence, error, message, confidence, confidence, null, null, null, null, null, null, null);
    }

    public FinalResponse withScoreBreakdown(double answerQuality,
                                            double winnerConfidence,
                                            Double modelAgreement) {
        return withScoreBreakdown(answerQuality, winnerConfidence, modelAgreement, null);
    }

    public FinalResponse withScoreBreakdown(double answerQuality,
                                            double winnerConfidence,
                                            Double modelAgreement,
                                            Map<String, Double> dimensions) {
        return withScoreBreakdown(answerQuality, winnerConfidence, modelAgreement, dimensions, null);
    }

    public FinalResponse withScoreBreakdown(double answerQuality,
                                            double winnerConfidence,
                                            Double modelAgreement,
                                            Map<String, Double> dimensions,
                                            FinalScoreBreakdown scoreBreakdown) {
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                answerQuality, error, message, answerQuality, winnerConfidence, modelAgreement,
                dimensions, scoreBreakdown, research, invariants, runDiagnostics, providerFailures);
    }

    public FinalResponse withResearch(ResearchPack research) {
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, error, message, answerQuality, winnerConfidence, modelAgreement,
                dimensions, scoreBreakdown, research, invariants, runDiagnostics, providerFailures);
    }

    public FinalResponse withInvariants(InvariantCriticResult invariants) {
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, error, message, answerQuality, winnerConfidence, modelAgreement,
                dimensions, scoreBreakdown, research, invariants, runDiagnostics, providerFailures);
    }

    public FinalResponse withRunDiagnostics(ProviderRunDiagnostics runDiagnostics) {
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, error, message, answerQuality, winnerConfidence, modelAgreement,
                dimensions, scoreBreakdown, research, invariants, runDiagnostics, providerFailures);
    }

    public FinalResponse withProviderFailures(List<ProviderFailureDetails> providerFailures) {
        List<ProviderFailureDetails> safeFailures = providerFailures == null || providerFailures.isEmpty()
                ? null : List.copyOf(providerFailures);
        return new FinalResponse(traceId, finalAnswer, judgeReason, usedProviders, failedProviders,
                confidence, error, message, answerQuality, winnerConfidence, modelAgreement,
                dimensions, scoreBreakdown, research, invariants, runDiagnostics, safeFailures);
    }
}

