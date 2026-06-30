package com.council.provider.blackbox;

/**
 * Safe Blackbox preflight failure categories. These are operator-facing and
 * intentionally exclude raw upstream response bodies.
 */
public enum BlackboxPreflightFailureCategory {
    API_KEY_MISSING,
    CONFIG_INVALID,
    MODEL_NOT_FOUND_OR_UNAVAILABLE,
    BAD_REQUEST_MODEL_CONFIG,
    AUTH_FAILED,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK_ERROR,
    BAD_RESPONSE_SCHEMA,
    EMPTY_RESPONSE,
    UNKNOWN
}
