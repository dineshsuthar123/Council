package com.council.common.exception;

/**
 * Safe, transport-level provider failure categories suitable for traces and operator status.
 * Categories deliberately exclude upstream response bodies and credentials.
 */
public enum ProviderFailureCategory {
    API_KEY_MISSING(false),
    AUTH(false),
    RATE_LIMIT(true),
    TIMEOUT(true),
    BAD_RESPONSE(false),
    EMPTY_RESPONSE(false),
    NETWORK(true),
    UNKNOWN(true);

    private final boolean retryable;

    ProviderFailureCategory(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
