package com.council.model;

import com.council.common.CouncilUtils;
import com.council.common.ProviderResultStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result from the critic model (success or failure).
 * <p>
 * Includes anti-generic quality signals:
 * <ul>
 *   <li>{@code mathCorrectnessScore} – quality of quantitative engineering math [0..1]</li>
 *   <li>{@code feasibilityScore} – production feasibility / operational realism [0..1]</li>
 *   <li>{@code failureDepthScore} – depth of failure mitigation strategy [0..1]</li>
 *   <li>{@code genericnessPenalty} – how generic / fluffy the drafts are [0..1]</li>
 *   <li>{@code missingFailureModes} – failure scenarios the drafts ignore</li>
 *   <li>{@code weakTradeoffAnalysis} – whether tradeoff reasoning is shallow</li>
 * </ul>
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
        // ── Principal-review quality dimensions ──
        double mathCorrectnessScore,
        double feasibilityScore,
        double failureDepthScore,
        // ── Anti-generic quality signals ──
        double genericnessPenalty,
        List<String> missingFailureModes,
        boolean weakTradeoffAnalysis,
        boolean missingMathJustification,
        String winnerRationale,
        long latencyMs,
        String rawResponse,
        String errorMessage
) {
    /** Legacy factory – backwards compatible (no anti-generic fields). */
    public static CriticResult success(String provider, String model,
                                       String globalSummary, double contradictionSeverity,
                                       Map<String, Integer> contradictionCountPerDraft,
                                       List<Contradiction> contradictionsFound,
                                       List<String> missingPoints, List<String> riskyClaims,
                                       long latencyMs, String rawResponse) {
        return new CriticResult(provider, model, ProviderResultStatus.SUCCESS,
                globalSummary, CouncilUtils.clamp01(contradictionSeverity),
                contradictionCountPerDraft, contradictionsFound,
                missingPoints, riskyClaims,
                0.5, 0.5, 0.5,
                0.0, List.of(), false, false, null,
                latencyMs, rawResponse, null);
    }

    /** Full factory – includes anti-generic quality signals. */
    public static CriticResult successFull(String provider, String model,
                                       String globalSummary, double contradictionSeverity,
                                       Map<String, Integer> contradictionCountPerDraft,
                                       List<Contradiction> contradictionsFound,
                                       List<String> missingPoints, List<String> riskyClaims,
                                       double mathCorrectnessScore,
                                       double feasibilityScore,
                                       double failureDepthScore,
                                       double genericnessPenalty,
                                       List<String> missingFailureModes,
                                       boolean weakTradeoffAnalysis,
                                       boolean missingMathJustification,
                                       String winnerRationale,
                                       long latencyMs, String rawResponse) {
        return new CriticResult(provider, model, ProviderResultStatus.SUCCESS,
                globalSummary, CouncilUtils.clamp01(contradictionSeverity),
                contradictionCountPerDraft, contradictionsFound,
                missingPoints, riskyClaims,
                CouncilUtils.clamp01(mathCorrectnessScore),
                CouncilUtils.clamp01(feasibilityScore),
                CouncilUtils.clamp01(failureDepthScore),
                CouncilUtils.clamp01(genericnessPenalty),
                missingFailureModes != null ? missingFailureModes : List.of(),
                weakTradeoffAnalysis,
                missingMathJustification,
                winnerRationale,
                latencyMs, rawResponse, null);
    }

    public static CriticResult failure(String provider, String model,
                                       String errorMessage, long latencyMs) {
        return new CriticResult(provider, model, ProviderResultStatus.FAILURE,
                null, 0.0, Map.of(), List.of(), List.of(), List.of(),
                0.0, 0.0, 0.0,
                0.0, List.of(), false, false, null,
                latencyMs, null, errorMessage);
    }

    public boolean isSuccess() {
        return status == ProviderResultStatus.SUCCESS;
    }
}
