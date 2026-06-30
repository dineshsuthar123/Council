package com.council.model;

/**
 * Operator-facing outcome for a provider selected by a reasoning run.
 * <p>
 * This deliberately separates a failed request from work that Council chose not to start.
 */
public enum ProviderOutcomeStatus {
    SUCCEEDED,
    FAILED,
    SKIPPED_EARLY_STOP,
    SKIPPED_DISABLED,
    SKIPPED_CIRCUIT_OPEN,
    SKIPPED_BUDGET_LIMIT,
    SKIPPED_TIMEOUT_BUDGET,
    UNAVAILABLE_API_KEY_MISSING,
    UNAVAILABLE_CONFIG_INVALID
}
