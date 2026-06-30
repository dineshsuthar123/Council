package com.council.model;

import com.council.common.ProviderResultStatus;
import com.council.common.exception.ProviderFailureCategory;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Safe, complete outcome for one selected provider. Unlike a failure diagnostic, this also represents
 * intentional skips and preflight unavailability so operator surfaces cannot mislabel them as failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderOutcome(
        String providerId,
        String displayName,
        String model,
        ProviderOutcomeStatus status,
        ProviderFailureCategory failureCategory,
        String skipReason,
        String safeMessage,
        boolean attempted,
        boolean validDraftProduced,
        Long latencyMs,
        int attemptCount,
        boolean retryAttempted,
        Integer httpStatus,
        String circuitBreakerState,
        String timestamp,
        Integer timeoutMsConfigured,
        String timeoutSource,
        Integer promptTokenEstimate,
        Integer requestSizeBytes
) {
    public ProviderOutcome {
        status = status == null ? ProviderOutcomeStatus.FAILED : status;
        attemptCount = Math.max(0, attemptCount);
        timestamp = timestamp == null || timestamp.isBlank() ? Instant.now().toString() : timestamp;
    }

    public static ProviderOutcome from(DraftResult result) {
        ProviderFailureDetails details = result.failureDetails();
        ProviderOutcomeStatus outcomeStatus = result.outcomeStatus();
        if (outcomeStatus == null) {
            outcomeStatus = result.status() == ProviderResultStatus.SUCCESS
                    ? ProviderOutcomeStatus.SUCCEEDED
                    : ProviderOutcomeStatus.FAILED;
        }

        ProviderFailureCategory category = details == null ? null : details.failureCategory();
        boolean succeeded = outcomeStatus == ProviderOutcomeStatus.SUCCEEDED;
        boolean skipped = outcomeStatus.name().startsWith("SKIPPED_");
        boolean unavailable = outcomeStatus.name().startsWith("UNAVAILABLE_");
        return new ProviderOutcome(
                details == null ? result.provider() : details.providerId(),
                details == null ? result.provider() : details.displayName(),
                result.model() == null && details != null ? details.model() : result.model(),
                outcomeStatus,
                category,
                skipped ? result.errorMessage() : null,
                succeeded ? "Draft succeeded." : details == null ? result.errorMessage() : details.safeMessage(),
                !skipped && !unavailable,
                succeeded,
                result.latencyMs() > 0 ? result.latencyMs() : null,
                details == null ? (skipped || unavailable ? 0 : 1) : details.attemptCount(),
                details != null && details.retryAttempted(),
                details == null ? null : details.httpStatus(),
                details == null ? null : details.circuitBreakerState(),
                details == null ? Instant.now().toString() : details.timestamp(),
                details == null ? null : details.timeoutMsConfigured(),
                details == null ? null : details.timeoutSource(),
                details == null ? null : details.promptTokenEstimate(),
                details == null ? null : details.requestSizeBytes());
    }

    public static List<ProviderOutcome> fromDraftResults(List<DraftResult> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return drafts.stream().map(ProviderOutcome::from).toList();
    }
}
