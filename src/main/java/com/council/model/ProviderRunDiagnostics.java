package com.council.model;

import com.council.orchestrator.EarlyStopDecision;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Run-level provider diversity signal. This intentionally remains independent from final answer quality.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderRunDiagnostics(
        int selectedProviders,
        int attemptedProviders,
        int validDraftProviders,
        int failedAttempts,
        int skippedProviders,
        int skippedEarlyStopProviders,
        int unavailableProviders,
        double providerCoverage,
        double attemptCoverage,
        String runHealth,
        double runConfidence,
        String degradedRunStatus,
        EarlyStopDecision earlyStopDecision
) {
    /** Compatibility constructor for traces created before outcome accounting existed. */
    public ProviderRunDiagnostics(int attemptedProviders,
                                  int validDraftProviders,
                                  double providerCoverage,
                                  String runHealth,
                                  double runConfidence,
                                  String degradedRunStatus) {
        this(attemptedProviders, attemptedProviders, validDraftProviders,
                Math.max(0, attemptedProviders - validDraftProviders), 0, 0, 0,
                providerCoverage, providerCoverage, runHealth, runConfidence, degradedRunStatus, null);
    }

    public static ProviderRunDiagnostics from(List<DraftResult> drafts) {
        return fromOutcomes(ProviderOutcome.fromDraftResults(drafts), null);
    }

    public static ProviderRunDiagnostics from(List<DraftResult> drafts, EarlyStopDecision earlyStopDecision) {
        return fromOutcomes(ProviderOutcome.fromDraftResults(drafts), earlyStopDecision);
    }

    public static ProviderRunDiagnostics fromOutcomes(List<ProviderOutcome> outcomes,
                                                      EarlyStopDecision earlyStopDecision) {
        List<ProviderOutcome> safeOutcomes = outcomes == null ? List.of() : outcomes;
        int selected = safeOutcomes.size();
        int attempted = (int) safeOutcomes.stream().filter(ProviderOutcome::attempted).count();
        int valid = (int) safeOutcomes.stream().filter(ProviderOutcome::validDraftProduced).count();
        int failed = (int) safeOutcomes.stream().filter(outcome -> outcome.status() == ProviderOutcomeStatus.FAILED).count();
        int skipped = (int) safeOutcomes.stream().filter(outcome -> outcome.status().name().startsWith("SKIPPED_")).count();
        int earlySkipped = (int) safeOutcomes.stream()
                .filter(outcome -> outcome.status() == ProviderOutcomeStatus.SKIPPED_EARLY_STOP).count();
        int unavailable = (int) safeOutcomes.stream()
                .filter(outcome -> outcome.status().name().startsWith("UNAVAILABLE_")).count();
        double providerCoverage = selected == 0 ? 0.0 : (double) valid / selected;
        double attemptCoverage = selected == 0 ? 0.0 : (double) attempted / selected;
        String runHealth;
        String status;
        if (valid == 0) {
            runHealth = "FAILED";
            status = selected == 0 ? "No provider attempts were made."
                    : "No selected providers produced a valid draft.";
        } else if (valid < selected) {
            runHealth = "DEGRADED";
            status = "Only " + valid + " of " + selected + " selected providers produced valid drafts."
                    + (earlySkipped > 0 ? " " + earlySkipped + " skipped after early stop." : "");
        } else {
            runHealth = "HEALTHY";
            status = null;
        }
        return new ProviderRunDiagnostics(selected, attempted, valid, failed, skipped, earlySkipped, unavailable,
                providerCoverage, attemptCoverage, runHealth, providerCoverage, status, earlyStopDecision);
    }
}
