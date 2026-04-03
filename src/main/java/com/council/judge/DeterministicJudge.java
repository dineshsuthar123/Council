package com.council.judge;

import com.council.common.CouncilConstants;
import com.council.common.CouncilUtils;
import com.council.config.CouncilProperties;
import com.council.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure-Java deterministic judge.
 * <p>
 * Scores each draft using:
 * <pre>
 *   score = (confidence × 0.40) + (providerReliability × 0.30) − (contradictionPenalty × 0.30)
 * </pre>
 * <p>
 * Handles edge cases: 0 valid drafts, 1 valid draft, critic failure.
 */
@Component
public class DeterministicJudge {

    private static final Logger log = LoggerFactory.getLogger(DeterministicJudge.class);

    private final CouncilProperties properties;

    public DeterministicJudge(CouncilProperties properties) {
        this.properties = properties;
    }

    /**
     * Evaluate drafts and return a ranking with the winning provider.
     */
    public JudgeResult evaluate(List<DraftResult> drafts, CriticResult criticResult) {
        if (drafts == null || drafts.isEmpty()) {
            log.warn("[judge] No valid drafts to evaluate");
            return JudgeResult.noValidDrafts();
        }

        if (drafts.size() == 1) {
            log.info("[judge] Single draft – auto-selecting {}", drafts.getFirst().provider());
            return JudgeResult.singleDraft(drafts.getFirst());
        }

        boolean criticAvailable = criticResult != null && criticResult.isSuccess();
        if (!criticAvailable) {
            log.info("[judge] Critic unavailable – scoring without contradiction penalties");
        }

        // Score each draft
        Map<String, Double> scores = new LinkedHashMap<>();
        for (DraftResult draft : drafts) {
            scores.put(draft.provider(), scoreDraft(draft, criticAvailable ? criticResult : null));
        }

        // Rank and select winner
        List<JudgeRanking> rankings = rankDrafts(scores);
        JudgeRanking winner = rankings.getFirst();
        DraftResult winnerDraft = drafts.stream()
                .filter(d -> d.provider().equals(winner.provider()))
                .findFirst()
                .orElse(drafts.getFirst());

        String reason = buildExplanation(rankings, criticResult, criticAvailable);
        log.info("[judge] Winner: {} (score={})", winner.provider(), winner.score());

        return new JudgeResult(
                winner.provider(),
                winnerDraft.model(),
                winner.score(),
                reason,
                rankings
        );
    }

    /**
     * Compute the composite score for a single draft.
     *
     * @param draft         the draft to score
     * @param criticResult  critic output (null if critic was unavailable)
     * @return score in [0.0, 1.0]
     */
    double scoreDraft(DraftResult draft, CriticResult criticResult) {
        double confidence  = draft.confidence();
        double reliability = getReliability(draft.provider());
        double penalty     = criticResult != null
                ? getContradictionPenalty(draft.provider(), criticResult) : 0.0;

        double score = (confidence  * CouncilConstants.W_CONFIDENCE)
                     + (reliability * CouncilConstants.W_RELIABILITY)
                     - (penalty     * CouncilConstants.W_CONTRADICTION);
        return CouncilUtils.clamp01(score);
    }

    /**
     * Sort provider scores descending and return as an immutable ranking list.
     */
    List<JudgeRanking> rankDrafts(Map<String, Double> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new JudgeRanking(e.getKey(), CouncilUtils.round3(e.getValue())))
                .toList();
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    private double getReliability(String provider) {
        CouncilProperties.ProviderConfig cfg = properties.getProviders().get(provider);
        return cfg != null ? cfg.getReliability() : CouncilConstants.DEFAULT_RELIABILITY;
    }

    private double getContradictionPenalty(String provider, CriticResult criticResult) {
        int count = criticResult.contradictionCountPerDraft().getOrDefault(provider, 0);
        return Math.min(1.0, count * CouncilConstants.PENALTY_PER_CONTRADICTION);
    }

    private String buildExplanation(List<JudgeRanking> rankings, CriticResult criticResult,
                                    boolean criticAvailable) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ranking: ");
        sb.append(rankings.stream()
                .map(r -> "%s=%.2f".formatted(r.provider(), r.score()))
                .collect(Collectors.joining(", ")));

        if (!criticAvailable) {
            sb.append(". Critic was unavailable – no contradiction penalties applied.");
        } else if (criticResult.contradictionSeverity() > 0.5) {
            sb.append(". High contradiction severity (%.2f) detected."
                    .formatted(criticResult.contradictionSeverity()));
        }

        JudgeRanking winner = rankings.getFirst();
        sb.append(". Winner '%s' selected with best composite score.".formatted(winner.provider()));
        return sb.toString();
    }
}
