package com.council.model;

import com.council.common.exception.ProviderFailureCategory;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Safe, structured diagnostic data for a failed provider attempt. It is designed for trace persistence
 * and operator UI surfaces; credentials, request bodies, and upstream response bodies are deliberately absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderFailureDetails(
        String providerId,
        String displayName,
        String model,
        String baseUrlHost,
        ProviderFailureCategory failureCategory,
        String safeMessage,
        Integer httpStatus,
        long latencyMs,
        boolean retryAttempted,
        int attemptCount,
        String circuitBreakerState,
        String timestamp
) {
    public ProviderFailureDetails {
        failureCategory = failureCategory == null ? ProviderFailureCategory.UNKNOWN : failureCategory;
        safeMessage = safeMessage == null || safeMessage.isBlank() ? "Provider request failed" : safeMessage.trim();
        attemptCount = Math.max(1, attemptCount);
        circuitBreakerState = circuitBreakerState == null || circuitBreakerState.isBlank()
                ? "UNKNOWN" : circuitBreakerState.trim();
    }

    public static ProviderFailureDetails local(String providerId,
                                               String model,
                                               ProviderFailureCategory category,
                                               String safeMessage,
                                               long latencyMs) {
        return new ProviderFailureDetails(providerId, providerId, model, null, category, safeMessage,
                null, latencyMs, false, 1,
                category == ProviderFailureCategory.CIRCUIT_OPEN ? "OPEN" : "UNKNOWN",
                java.time.Instant.now().toString());
    }

    public static List<ProviderFailureDetails> fromDraftResults(List<DraftResult> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return drafts.stream()
                .filter(DraftResult::isFailure)
                .map(DraftResult::failureDetails)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
