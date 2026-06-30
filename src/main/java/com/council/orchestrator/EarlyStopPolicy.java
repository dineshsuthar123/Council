package com.council.orchestrator;

import com.council.judge.TaskType;
import com.council.judge.research.ResearchClaimConsistencyCritic;
import com.council.model.DraftResult;
import com.council.research.ResearchPack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Bounded, deterministic early-stop policy. It protects provider diversity for hard tasks while retaining
 * single-provider latency for routine questions. It makes no network or model calls.
 */
public final class EarlyStopPolicy {

    private final ResearchClaimConsistencyCritic researchCritic;

    public EarlyStopPolicy() {
        this(new ResearchClaimConsistencyCritic());
    }

    EarlyStopPolicy(ResearchClaimConsistencyCritic researchCritic) {
        this.researchCritic = researchCritic;
    }

    public Requirements requirements(TaskType taskType, String prompt, ResearchPack researchPack,
                                     int selectedProviders, double configuredThreshold) {
        TaskType type = taskType == null ? TaskType.GENERAL_REASONING : taskType;
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        boolean paymentSafety = containsAny(text, "payment", "transfer", "financial", "ledger", "idempotency");
        boolean urlDeletionSafety = containsAny(text, "url shortener", "short url", "redirect")
                && containsAny(text, "delete", "deleted", "tombstone", "stale");
        boolean research = type == TaskType.RESEARCH_REQUIRED || (researchPack != null && researchPack.required());

        int minimum = switch (type) {
            case CODING, DEBUGGING, BACKEND_ARCHITECTURE -> 2;
            case SYSTEM_DESIGN, RESEARCH_REQUIRED -> 3;
            case GENERAL_REASONING -> 1;
        };
        double threshold = switch (type) {
            case CODING, DEBUGGING -> 0.92;
            case BACKEND_ARCHITECTURE -> 0.93;
            case SYSTEM_DESIGN, RESEARCH_REQUIRED -> 0.94;
            case GENERAL_REASONING -> 0.90;
        };
        if (configuredThreshold > 0.0) {
            threshold = Math.max(threshold, configuredThreshold);
        }
        if (paymentSafety || urlDeletionSafety) {
            minimum = 3;
        }
        minimum = Math.max(1, Math.min(Math.max(1, selectedProviders), minimum));
        return new Requirements(minimum, threshold, !(paymentSafety || urlDeletionSafety), research,
                paymentSafety || urlDeletionSafety);
    }

    public EarlyStopDecision evaluate(TaskType taskType,
                                      String prompt,
                                      ResearchPack researchPack,
                                      int selectedProviders,
                                      Collection<DraftResult> results,
                                      boolean globallyEnabled,
                                      double configuredThreshold,
                                      double minImprovement,
                                      double remainingProviderCeiling,
                                      long remainingMillis,
                                      long requestTimeoutMillis) {
        Requirements requirements = requirements(taskType, prompt, researchPack, selectedProviders, configuredThreshold);
        int valid = results == null ? 0 : (int) results.stream().filter(DraftResult::isSuccess).count();
        Optional<DraftResult> best = results == null ? Optional.empty() : results.stream()
                .filter(DraftResult::isSuccess)
                .max(java.util.Comparator.comparingDouble(DraftResult::confidence));
        List<String> blocked = new ArrayList<>();

        if (!globallyEnabled) {
            blocked.add("early_stop_disabled");
        }
        if (valid < requirements.minValidDraftsBeforeEarlyStop()) {
            blocked.add("minimum_provider_diversity_not_met");
        }
        if (best.isEmpty()) {
            blocked.add("no_valid_draft");
        }
        boolean nearBudgetExhaustion = remainingMillis <= Math.max(1_000L, requestTimeoutMillis / 10L);
        if (!requirements.allowEarlyStop() && !nearBudgetExhaustion) {
            blocked.add("critical_task_requires_all_selected_providers");
        }
        if (requirements.requiresEvidenceContract()) {
            blocked.addAll(researchContractBlocks(prompt, researchPack, best.orElse(null)));
        }
        if (!blocked.isEmpty()) {
            return EarlyStopDecision.continueWaiting(taskType, requirements.confidenceThreshold(),
                    requirements.minValidDraftsBeforeEarlyStop(), valid, blocked);
        }

        double confidence = best.orElseThrow().confidence();
        if (confidence >= requirements.confidenceThreshold()) {
            return new EarlyStopDecision(true,
                    "Draft scheduling stopped after sufficient confidence " + format(confidence)
                            + " >= " + format(requirements.confidenceThreshold()),
                    requirements.confidenceThreshold(), requirements.minValidDraftsBeforeEarlyStop(), valid,
                    taskType, List.of(), null);
        }
        if (confidence + Math.max(0.0, minImprovement) >= remainingProviderCeiling) {
            return new EarlyStopDecision(true,
                    "Draft scheduling stopped because remaining providers were unlikely to materially improve the best draft",
                    requirements.confidenceThreshold(), requirements.minValidDraftsBeforeEarlyStop(), valid,
                    taskType, List.of(), null);
        }
        blocked.add("confidence_below_threshold");
        return EarlyStopDecision.continueWaiting(taskType, requirements.confidenceThreshold(),
                requirements.minValidDraftsBeforeEarlyStop(), valid, blocked);
    }

    private List<String> researchContractBlocks(String prompt, ResearchPack pack, DraftResult best) {
        if (best == null || best.answer() == null) {
            return List.of("no_research_draft_available");
        }
        String answer = best.answer().toLowerCase(Locale.ROOT);
        String promptText = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        List<String> blocked = new ArrayList<>();
        if (pack != null && pack.hasSources() && !answer.matches("(?s).*\\[s\\d+].*")) {
            blocked.add("research_citations_missing");
        }
        ResearchClaimConsistencyCritic.Assessment assessment = researchCritic.assess(prompt, best.answer(), pack);
        boolean sourceFiveViolation = assessment.findings().stream()
                .anyMatch(finding -> finding.invariantId().contains("prompt_injection"));
        if (sourceFiveViolation) {
            blocked.add("source_five_handling_missing");
        }
        if (containsAny(promptText, "recommendation", "recommend")
                && !containsAny(answer, "recommend", "default", "choose", "canary")) {
            blocked.add("final_recommendation_contract_missing");
        }
        if (containsAny(promptText, "pseudocode", "algorithm")
                && (!containsAny(answer, "if ", "if(") || !containsAny(answer, "return", "else"))) {
            blocked.add("concrete_pseudocode_missing");
        }
        if (assessment.claimEvidenceConsistency() < 0.70) {
            blocked.add("claim_evidence_consistency_below_threshold");
        }
        return blocked;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public record Requirements(int minValidDraftsBeforeEarlyStop,
                               double confidenceThreshold,
                               boolean allowEarlyStop,
                               boolean requiresEvidenceContract,
                               boolean criticalSafetyTask) {
    }
}
