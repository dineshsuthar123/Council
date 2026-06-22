package com.council.common.exception;

/**
 * Base exception for all provider-related failures.
 */
public class ProviderException extends RuntimeException {

    private final String provider;
    private final ProviderFailureCategory failureCategory;
    private final Integer httpStatus;
    private final int attemptCount;
    private final boolean retryAttempted;
    private final boolean circuitOpen;

    public ProviderException(String provider, String message) {
        this(provider, message, ProviderFailureCategory.UNKNOWN);
    }

    public ProviderException(String provider, String message, Throwable cause) {
        this(provider, message, ProviderFailureCategory.UNKNOWN, cause);
    }

    public ProviderException(String provider, String message, ProviderFailureCategory failureCategory) {
        this(provider, message, failureCategory, null, null);
    }

    public ProviderException(String provider,
                             String message,
                             ProviderFailureCategory failureCategory,
                             Throwable cause) {
        this(provider, message, failureCategory, null, cause);
    }

    public ProviderException(String provider,
                             String message,
                             ProviderFailureCategory failureCategory,
                             Integer httpStatus,
                             Throwable cause) {
        this(provider, message, failureCategory, httpStatus, cause, 1, false,
                failureCategory == ProviderFailureCategory.CIRCUIT_OPEN);
    }

    private ProviderException(String provider,
                              String message,
                              ProviderFailureCategory failureCategory,
                              Integer httpStatus,
                              Throwable cause,
                              int attemptCount,
                              boolean retryAttempted,
                              boolean circuitOpen) {
        super(sanitize(message), cause);
        this.provider = provider;
        this.failureCategory = failureCategory == null ? ProviderFailureCategory.UNKNOWN : failureCategory;
        this.httpStatus = httpStatus;
        this.attemptCount = Math.max(1, attemptCount);
        this.retryAttempted = retryAttempted;
        this.circuitOpen = circuitOpen;
    }

    public String getProvider() {
        return provider;
    }

    public ProviderFailureCategory getFailureCategory() {
        return failureCategory;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public boolean isRetryAttempted() {
        return retryAttempted;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    public ProviderException withAttemptMetadata(int attempts) {
        return new ProviderException(provider, getMessage(), failureCategory, httpStatus, getCause(), attempts,
                attempts > 1, circuitOpen);
    }

    public ProviderException asCircuitOpen() {
        return new ProviderException(provider, getMessage(), ProviderFailureCategory.CIRCUIT_OPEN, httpStatus,
                getCause(), attemptCount, retryAttempted, true);
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Provider request failed";
        }
        return message
                .replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_ -]?key\\s*[=:]\\s*)[^\\s,;]+", "$1[REDACTED]");
    }
}

