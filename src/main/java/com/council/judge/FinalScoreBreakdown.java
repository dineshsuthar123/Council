package com.council.judge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Auditable inputs and caps used to derive final answer quality for a persisted trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinalScoreBreakdown(
        Double draftJudgeScore,
        Double synthesisConfidence,
        Double baseRubricScore,
        Double researchCalibratedScore,
        Double invariantCap,
        Double finalCompletenessCap,
        Double productionConsistencyCap,
        double finalAnswerQuality,
        String formula,
        List<String> reasons,
        Map<String, String> unavailableReasons
) {
    public FinalScoreBreakdown {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        unavailableReasons = unavailableReasons == null ? Map.of() : Map.copyOf(unavailableReasons);
    }
}
