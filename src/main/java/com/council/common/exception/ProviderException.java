package com.council.common.exception;

/**
 * Base exception for all provider-related failures.
 */
public class ProviderException extends RuntimeException {

    private final String provider;
    private final ProviderFailureCategory failureCategory;

    public ProviderException(String provider, String message) {
        this(provider, message, ProviderFailureCategory.UNKNOWN);
    }

    public ProviderException(String provider, String message, Throwable cause) {
        this(provider, message, ProviderFailureCategory.UNKNOWN, cause);
    }

    public ProviderException(String provider, String message, ProviderFailureCategory failureCategory) {
        super(sanitize(message));
        this.provider = provider;
        this.failureCategory = failureCategory == null ? ProviderFailureCategory.UNKNOWN : failureCategory;
    }

    public ProviderException(String provider,
                             String message,
                             ProviderFailureCategory failureCategory,
                             Throwable cause) {
        super(sanitize(message), cause);
        this.provider = provider;
        this.failureCategory = failureCategory == null ? ProviderFailureCategory.UNKNOWN : failureCategory;
    }

    public String getProvider() {
        return provider;
    }

    public ProviderFailureCategory getFailureCategory() {
        return failureCategory;
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

