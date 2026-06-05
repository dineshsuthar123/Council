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
 * If the critic is unavailable, we fall back to calibrated confidence scoring.
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
        return evaluate(drafts, criticResult, VerifierBatchResult.passedFor(drafts), TaskType.GENERAL_REASONING);
    }

    /**
     * Task-aware evaluation of drafts.
     */
    public JudgeResult evaluate(List<DraftResult> drafts, CriticResult criticResult, TaskType taskType) {
        return evaluate(drafts, criticResult, VerifierBatchResult.passedFor(drafts), taskType);
    }

    /**
     * Task-aware evaluation of drafts with verifier guillotine.
     */
    public JudgeResult evaluate(List<DraftResult> drafts,
                                CriticResult criticResult,
                                VerifierBatchResult verifierBatchResult,
                                TaskType taskType) {
        if (drafts == null || drafts.isEmpty()) {
            log.warn("[judge] No valid drafts to evaluate");
            return JudgeResult.noValidDrafts();
        }

        VerifierBatchResult safeVerifierBatch = verifierBatchResult == null
                ? VerifierBatchResult.passedFor(drafts)
                : verifierBatchResult;

        if (drafts.size() == 1) {
            DraftResult only = drafts.getFirst();
            log.info("[judge] Single draft – auto-selecting {}", only.provider());
            boolean criticAvailable = criticResult != null && criticResult.isSuccess();
            VerifierVerdict verifierVerdict = safeVerifierBatch.verdictForProvider(only.provider());
            boolean disqualified = isVerifierDisqualified(verifierVerdict);
            double score = disqualified
                    ? 0.0
                    : scoreDraft(only, criticAvailable ? criticResult : null);

            String reason;
            if (disqualified) {
                reason = "Only one valid draft available, but it was disqualified by Verifier. "
                        + verifierReason(verifierVerdict);
            } else if (criticAvailable) {
                double calibratedConfidence = calibratedConfidence(only);
                reason = "Only one valid draft available. Applied weighted critic formula "
                        + "(mathCorrectnessScore=%.2f, feasibilityScore=%.2f, failureDepthScore=%.2f, confidence=%.2f)."
                        .formatted(
                        criticResult.mathCorrectnessScore(),
                        criticResult.feasibilityScore(),
                        criticResult.failureDepthScore(),
                        calibratedConfidence);
            } else {
                reason = "Only one valid draft available. Critic unavailable, score = calibrated draft confidence.";
            }

            double productionConsistencyCap = productionConsistencyScoreCap(only);
            if (productionConsistencyCap < 1.0) {
                reason += " Production consistency cap applied: %s<=%.2f."
                        .formatted(only.provider(), productionConsistencyCap);
            }

            return new JudgeResult(only.provider(), only.model(),
                    CouncilUtils.clamp01(score),
                    reason,
                    List.of(new JudgeRanking(only.provider(), CouncilUtils.round3(score))));
        }

        boolean criticAvailable = criticResult != null && criticResult.isSuccess();
        if (!criticAvailable) {
            log.info("[judge] Critic unavailable – calibrated confidence fallback scoring");
        }

        // Apply the verifier guillotine before any weighted scoring.
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> verifierDisqualifications = new LinkedHashMap<>();
        for (DraftResult draft : drafts) {
            VerifierVerdict verifierVerdict = safeVerifierBatch.verdictForProvider(draft.provider());
            if (isVerifierDisqualified(verifierVerdict)) {
                scores.put(draft.provider(), 0.0);
                verifierDisqualifications.put(draft.provider(), verifierReason(verifierVerdict));
                continue;
            }

            // Standard weighted scoring applies only to drafts that pass verifier checks.
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
     * Backward-compatible overload (map-based verifier results).
     */
    public JudgeResult evaluate(List<DraftResult> drafts,
                                CriticResult criticResult,
                                Map<String, VerifierResult> verifierResults,
                                TaskType taskType) {
        Map<String, VerifierVerdict> verdicts = new LinkedHashMap<>();
        if (verifierResults != null) {
            verifierResults.forEach((provider, result) -> {
                if (result != null) {
                    verdicts.put(provider, new VerifierVerdict(
                            result.containsFatalMathError(),
                            result.containsConsistencyViolation(),
                            false,
                            result.fatalErrorReason()
                    ));
                }
            });
        }
        return evaluate(drafts, criticResult, VerifierBatchResult.success(verdicts), taskType);
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
     * Fallback mode (no critic): score = calibrated draft confidence.
     */
    double scoreDraft(DraftResult draft, CriticResult criticResult) {
        double confidence = calibratedConfidence(draft);
        double productionConsistencyCap = productionConsistencyScoreCap(draft);

        if (criticResult == null || !criticResult.isSuccess()) {
            return CouncilUtils.clamp01(Math.min(confidence, productionConsistencyCap));
        }

        double score = (criticResult.mathCorrectnessScore() * W_MATH)
                     + (criticResult.feasibilityScore() * W_FEASIBILITY)
                     + (criticResult.failureDepthScore() * W_FAILURE_DEPTH)
                     + (confidence * W_CONFIDENCE);

        return CouncilUtils.clamp01(Math.min(score, productionConsistencyCap));
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
            sb.append(". Critic unavailable. Fallback mode: score = calibrated draft confidence.");
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

        String consistencyCaps = productionConsistencyCapsSummary(drafts);
        if (!consistencyCaps.isBlank()) {
            sb.append(". Production consistency caps applied: ").append(consistencyCaps);
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
                .collect(Collectors.toMap(DraftResult::provider, this::calibratedConfidence, (a, b) -> a));

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

    private boolean isVerifierDisqualified(VerifierVerdict verifierVerdict) {
        return verifierVerdict != null && verifierVerdict.isDisqualified();
    }

    private String verifierReason(VerifierVerdict verifierVerdict) {
        if (verifierVerdict == null) {
            return "Verifier flagged disqualification without details.";
        }
        String reason = verifierVerdict.fatalErrorReason();
        if (reason == null || reason.isBlank()) {
            return "Verifier flagged a fatal math/consistency/throughput violation.";
        }
        return reason;
    }

    private double calibratedConfidence(DraftResult draft) {
        return Math.min(CouncilUtils.clamp01(draft.confidence()), productionConsistencyScoreCap(draft));
    }

    private double productionConsistencyScoreCap(DraftResult draft) {
        String answer = normalize(draft.answer() + " " + draft.summary());
        if (!looksLikeStaleDeletionConsistencyAnswer(answer)) {
            return 1.0;
        }

        int missing = 0;
        boolean hasTombstone = containsAny(answer, "tombstone", "negative cache", "negative-cache",
                "deleted marker", "deletion marker", "cache delete marker");
        boolean hasPrimarySafeRead = containsAny(answer, "primary", "leader", "bypass replica",
                "avoid replica", "source of truth", "read-your-writes", "replica lag window");
        boolean hasStampedeCoalescing = containsAny(answer, "singleflight", "single flight",
                "request coalescing", "request collapsing", "per-key lock", "per key lock",
                "distributed lock", "mutex", "dogpile");
        boolean separatesAnalytics = containsAny(answer, "analytics lag", "dashboard",
                "consumer lag", "redirect correctness", "correctness is independent",
                "does not change the redirect");
        boolean hasVersionOrDeletedAt = containsAny(answer, "deleted_at", "deleted at",
                "version", "row_version", "generation", "delete version");
        boolean hasCorrectStatus = containsAny(answer, "404", "410", "not found", "gone");

        if (!hasTombstone) {
            missing++;
        }
        if (!hasPrimarySafeRead && !hasVersionOrDeletedAt) {
            missing++;
        }
        if (!hasStampedeCoalescing) {
            missing++;
        }
        if (!separatesAnalytics) {
            missing++;
        }
        if (!hasCorrectStatus) {
            missing++;
        }

        double cap = 1.0;
        if (missing >= 4) {
            cap = 0.68;
        } else if (missing >= 3) {
            cap = 0.72;
        } else if (missing >= 2) {
            cap = 0.80;
        }

        if (treatsRedisTtlAsEnough(answer) && !hasTombstone) {
            cap = Math.min(cap, 0.65);
        }
        if (answer.contains("replica") && !hasPrimarySafeRead && !hasVersionOrDeletedAt) {
            cap = Math.min(cap, 0.65);
        }

        return cap;
    }

    private boolean looksLikeStaleDeletionConsistencyAnswer(String answer) {
        return answer.contains("redis")
                && (answer.contains("postgres") || answer.contains("replica"))
                && (answer.contains("deleted") || answer.contains("deletion"))
                && (answer.contains("kafka") || answer.contains("analytics"))
                && (answer.contains("redirect") || answer.contains("url"));
    }

    private boolean treatsRedisTtlAsEnough(String answer) {
        return (answer.contains("ttl") || answer.contains("expiration") || answer.contains("expire"))
                && containsAny(answer, "prevent stale", "avoid stale", "stale redirects", "cache invalidation");
    }

    private String productionConsistencyCapsSummary(List<DraftResult> drafts) {
        return drafts.stream()
                .map(draft -> Map.entry(draft.provider(), productionConsistencyScoreCap(draft)))
                .filter(entry -> entry.getValue() < 1.0)
                .map(entry -> "%s<=%.2f".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
