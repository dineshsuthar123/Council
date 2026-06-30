package com.council.common.exception;

/**
 * Safe, transport-level provider failure categories suitable for traces and operator status.
 * Categories deliberately exclude upstream response bodies and credentials.
 */
public enum ProviderFailureCategory {
    DISABLED(false),
    API_KEY_MISSING(false),
    OLLAMA_NOT_RUNNING(true),
    MODEL_NOT_INSTALLED(false),
    AUTH_FAILED(false),
    MODEL_NOT_FOUND_OR_UNAVAILABLE(false),
    RATE_LIMITED(true),
    TIMEOUT(true),
    NETWORK_ERROR(true),
    BAD_REQUEST(false),
    BAD_RESPONSE_SCHEMA(false),
    EMPTY_RESPONSE(false),
    CIRCUIT_OPEN(false),
    UNKNOWN(true);

    private final boolean retryable;

    ProviderFailureCategory(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
