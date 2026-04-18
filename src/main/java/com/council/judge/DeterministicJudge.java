package com.council.judge;

import com.council.common.CouncilUtils;
import com.council.config.CouncilProperties;
import com.council.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure-Java deterministic judge with task-aware scoring.
 * <p>
 * Scores each draft using principal-review dimensions:
 * <pre>
 *   score = (mathCorrectnessScore × 0.30)
 *         + (feasibilityScore × 0.30)
 *         + (failureDepthScore × 0.20)
 *         + (draftConfidence × 0.20)
 * </pre>
 * <p>
 * If the critic is unavailable, we fall back to confidence-only scoring.
 */
@Component
public class DeterministicJudge {

    private static final Logger log = LoggerFactory.getLogger(DeterministicJudge.class);

    private static final double W_MATH = 0.30;
    private static final double W_FEASIBILITY = 0.30;
    private static final double W_FAILURE_DEPTH = 0.20;
    private static final double W_CONFIDENCE = 0.20;

    public DeterministicJudge(CouncilProperties properties, SpecificityScorer specificityScorer) {
        // Signature retained for Spring wiring and test compatibility.
    }

    /**
     * Evaluate drafts and return a ranking with the winning provider.
     */
    public JudgeResult evaluate(List<DraftResult> drafts, CriticResult criticResult) {
        return evaluate(drafts, criticResult, Map.of(), TaskType.GENERAL_REASONING);
    }

    /**
     * Task-aware evaluation of drafts.
     */
    public JudgeResult evaluate(List<DraftResult> drafts, CriticResult criticResult, TaskType taskType) {
        return evaluate(drafts, criticResult, Map.of(), taskType);
    }

    /**
     * Task-aware evaluation of drafts with verifier guillotine.
     */
    public JudgeResult evaluate(List<DraftResult> drafts,
                                CriticResult criticResult,
                                Map<String, VerifierResult> verifierResults,
                                TaskType taskType) {
        if (drafts == null || drafts.isEmpty()) {
            log.warn("[judge] No valid drafts to evaluate");
            return JudgeResult.noValidDrafts();
        }

        Map<String, VerifierResult> safeVerifierResults =
                verifierResults == null ? Map.of() : verifierResults;

        if (drafts.size() == 1) {
            DraftResult only = drafts.getFirst();
            log.info("[judge] Single draft – auto-selecting {}", only.provider());
            boolean criticAvailable = criticResult != null && criticResult.isSuccess();
            VerifierResult verifierResult = safeVerifierResults.get(only.provider());
            boolean disqualified = isVerifierDisqualified(verifierResult);
            double score = disqualified
                    ? 0.0
                    : scoreDraft(only, criticAvailable ? criticResult : null);

            String reason;
            if (disqualified) {
                reason = "Only one valid draft available, but it was disqualified by Verifier. "
                        + verifierReason(verifierResult);
            } else if (criticAvailable) {
                reason = "Only one valid draft available. Applied weighted critic formula "
                        + "(mathCorrectnessScore=%.2f, feasibilityScore=%.2f, failureDepthScore=%.2f, confidence=%.2f)."
                        .formatted(
                        criticResult.mathCorrectnessScore(),
                        criticResult.feasibilityScore(),
                        criticResult.failureDepthScore(),
                        only.confidence());
            } else {
                reason = "Only one valid draft available. Critic unavailable, score = draft confidence.";
            }

            return new JudgeResult(only.provider(), only.model(),
                    CouncilUtils.clamp01(score),
                    reason,
                    List.of(new JudgeRanking(only.provider(), CouncilUtils.round3(score))));
        }

        boolean criticAvailable = criticResult != null && criticResult.isSuccess();
        if (!criticAvailable) {
            log.info("[judge] Critic unavailable – confidence-only fallback scoring");
        }

        // Score each draft
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> verifierDisqualifications = new LinkedHashMap<>();
        for (DraftResult draft : drafts) {
            VerifierResult verifierResult = safeVerifierResults.get(draft.provider());
            if (isVerifierDisqualified(verifierResult)) {
                scores.put(draft.provider(), 0.0);
                verifierDisqualifications.put(draft.provider(), verifierReason(verifierResult));
                continue;
            }
            scores.put(draft.provider(), scoreDraft(draft, criticAvailable ? criticResult : null));
        }

        // Rank and select winner
        List<JudgeRanking> rankings = rankDrafts(scores);
        JudgeRanking winner = rankings.getFirst();
        DraftResult winnerDraft = drafts.stream()
                .filter(d -> d.provider().equals(winner.provider()))
                .findFirst()
                .orElse(drafts.getFirst());

        String reason = buildExplanation(rankings, drafts, criticResult, criticAvailable,
            taskType, verifierDisqualifications);
        log.info("[judge] Winner: {} (score={}) taskType={}", winner.provider(), winner.score(), taskType);

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
     */
    double scoreDraft(DraftResult draft, CriticResult criticResult,
                      TaskType taskType, TaskAwareWeights weights) {
        return scoreDraft(draft, criticResult);
    }

    /**
     * Compute the principal-review score for a single draft.
     * Fallback mode (no critic): score = draft confidence.
     */
    double scoreDraft(DraftResult draft, CriticResult criticResult) {
        if (criticResult == null || !criticResult.isSuccess()) {
            return CouncilUtils.clamp01(draft.confidence());
        }

        double confidence  = draft.confidence();
        double score = (criticResult.mathCorrectnessScore() * W_MATH)
                     + (criticResult.feasibilityScore() * W_FEASIBILITY)
                     + (criticResult.failureDepthScore() * W_FAILURE_DEPTH)
                     + (confidence * W_CONFIDENCE);

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

    private String buildExplanation(List<JudgeRanking> rankings, List<DraftResult> drafts,
                                    CriticResult criticResult, boolean criticAvailable,
                                    TaskType taskType,
                                    Map<String, String> verifierDisqualifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("TaskType: %s. ".formatted(taskType));
        sb.append("Ranking: ");
        sb.append(rankings.stream()
                .map(r -> "%s=%.2f".formatted(r.provider(), r.score()))
                .collect(Collectors.joining(", ")));

        if (!verifierDisqualifications.isEmpty()) {
            sb.append(". Verifier disqualifications: ");
            sb.append(verifierDisqualifications.entrySet().stream()
                    .map(e -> e.getKey() + " -> " + e.getValue())
                    .collect(Collectors.joining("; ")));
        }

        if (!criticAvailable) {
            sb.append(". Critic unavailable. Fallback mode: score = draft confidence.");
        } else {
            sb.append(". Formula: (mathCorrectnessScore*%.2f) + (feasibilityScore*%.2f) + "
                    .formatted(W_MATH, W_FEASIBILITY));
            sb.append("(failureDepthScore*%.2f) + (confidence*%.2f)."
                    .formatted(W_FAILURE_DEPTH, W_CONFIDENCE));
            sb.append(" Critic sub-scores: mathCorrectnessScore=%.2f, feasibilityScore=%.2f, "
                    .formatted(criticResult.mathCorrectnessScore(), criticResult.feasibilityScore()));
            sb.append("failureDepthScore=%.2f, contradictionSeverity=%.2f."
                    .formatted(criticResult.failureDepthScore(), criticResult.contradictionSeverity()));

            if (criticResult.weakTradeoffAnalysis()) {
                sb.append(". Weak tradeoff analysis detected.");
            }
            if (criticResult.missingMathJustification()) {
                sb.append(". Missing mathematical justification detected.");
            }
            if (criticResult.winnerRationale() != null && !criticResult.winnerRationale().isBlank()) {
                sb.append(". Critic rationale: ").append(criticResult.winnerRationale().trim());
            }
        }

        sb.append(". ").append(buildWinnerReason(rankings, drafts, criticResult,
            criticAvailable, verifierDisqualifications));

        return sb.toString();
    }

    private String buildWinnerReason(List<JudgeRanking> rankings, List<DraftResult> drafts,
                                     CriticResult criticResult,
                         boolean criticAvailable,
                         Map<String, String> verifierDisqualifications) {
        JudgeRanking winner = rankings.getFirst();
        JudgeRanking runnerUp = rankings.size() > 1 ? rankings.get(1) : null;

        if (verifierDisqualifications.containsKey(winner.provider())) {
            return "Winner '%s' was disqualified by Verifier and scored 0.000. Reason: %s"
                .formatted(winner.provider(), verifierDisqualifications.get(winner.provider()));
        }

        Map<String, Double> confidenceByProvider = drafts.stream()
                .collect(Collectors.toMap(DraftResult::provider, DraftResult::confidence, (a, b) -> a));

        double winnerConfidence = confidenceByProvider.getOrDefault(winner.provider(), 0.0);

        if (!criticAvailable || criticResult == null) {
            if (runnerUp == null) {
                return "Winner '%s' selected because score = confidence (%.2f)."
                        .formatted(winner.provider(), winnerConfidence);
            }

            double runnerUpConfidence = confidenceByProvider.getOrDefault(runnerUp.provider(), 0.0);
            return "Winner '%s' selected because it had higher confidence (%.2f vs %.2f) in fallback mode."
                    .formatted(winner.provider(), winnerConfidence, runnerUpConfidence);
        }

        if (runnerUp == null) {
            return "Winner '%s' selected with score %.3f using sub-scores "
                    .formatted(winner.provider(), winner.score())
                    + "(math=%.2f, feasibility=%.2f, failureDepth=%.2f) and confidence=%.2f."
                    .formatted(
                            criticResult.mathCorrectnessScore(),
                            criticResult.feasibilityScore(),
                            criticResult.failureDepthScore(),
                            winnerConfidence);
        }

        double runnerUpConfidence = confidenceByProvider.getOrDefault(runnerUp.provider(), 0.0);
        return "Winner '%s' beat '%s' because confidence %.2f vs %.2f under shared critic "
                .formatted(winner.provider(), runnerUp.provider(), winnerConfidence, runnerUpConfidence)
                + "sub-scores (math=%.2f, feasibility=%.2f, failureDepth=%.2f). Final scores: %.3f vs %.3f."
                .formatted(
                        criticResult.mathCorrectnessScore(),
                        criticResult.feasibilityScore(),
                        criticResult.failureDepthScore(),
                        winner.score(),
                        runnerUp.score());
    }

    private boolean isVerifierDisqualified(VerifierResult verifierResult) {
        return verifierResult != null
                && (verifierResult.containsFatalMathError() || verifierResult.containsConsistencyViolation());
    }

    private String verifierReason(VerifierResult verifierResult) {
        if (verifierResult == null) {
            return "Verifier flagged disqualification without details.";
        }
        String reason = verifierResult.fatalErrorReason();
        if (reason == null || reason.isBlank()) {
            return "Verifier flagged a fatal math/consistency violation.";
        }
        return reason;
    }
}
