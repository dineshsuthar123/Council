package com.council.provider.blackbox;

/**
 * Lifecycle state for Blackbox model preflight validation.
 */
public enum BlackboxPreflightStatus {
    NOT_RUN,
    PASSED,
    FAILED,
    SKIPPED_DISABLED,
    SKIPPED_NO_KEY
}
