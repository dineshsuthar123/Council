package com.council.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Run-level provider diversity signal. This intentionally remains independent from final answer quality.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderRunDiagnostics(
        int attemptedProviders,
        int validDraftProviders,
        double providerCoverage,
        String runHealth,
        double runConfidence,
        String degradedRunStatus
) {
    public static ProviderRunDiagnostics from(List<DraftResult> drafts) {
        int attempted = drafts == null ? 0 : drafts.size();
        int valid = drafts == null ? 0 : (int) drafts.stream().filter(DraftResult::isSuccess).count();
        double coverage = attempted == 0 ? 0.0 : (double) valid / attempted;
        if (valid == 0) {
            return new ProviderRunDiagnostics(attempted, valid, coverage, "FAILED", coverage,
                    attempted == 0 ? "No provider attempts were made." : "No selected providers produced a valid draft.");
        }
        if (valid < attempted) {
            return new ProviderRunDiagnostics(attempted, valid, coverage, "DEGRADED", coverage,
                    "Only " + valid + " of " + attempted + " selected providers produced valid drafts.");
        }
        return new ProviderRunDiagnostics(attempted, valid, coverage, "HEALTHY", coverage, null);
    }
}
