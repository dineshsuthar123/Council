package com.council.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Output of the deterministic judge.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JudgeResult(
        String winnerProvider,
        String winnerModel,
        double winnerScore,
        String reason,
        List<JudgeRanking> rankings
) {
    public static JudgeResult noValidDrafts() {
        return new JudgeResult(null, null, 0.0,
                "No valid drafts available for judging", List.of());
    }

    public static JudgeResult singleDraft(DraftResult only) {
        double score = only.confidence() * 0.70 + 0.30;   // no contradictions possible
        return new JudgeResult(only.provider(), only.model(), score,
                "Only one valid draft available – selected by default",
                List.of(new JudgeRanking(only.provider(), score)));
    }
}

