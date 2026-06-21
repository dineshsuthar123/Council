package com.council.common.exception;

/**
 * Thrown when a provider returns HTTP 429 (Too Many Requests).
 */
public class RateLimitException extends ProviderException {

    public RateLimitException(String provider) {
        super(provider, "Rate limit (429) from provider: " + provider,
                ProviderFailureCategory.RATE_LIMIT);
    }

    public RateLimitException(String provider, Throwable cause) {
        super(provider, "Rate limit (429) from provider: " + provider,
                ProviderFailureCategory.RATE_LIMIT, cause);
    }
}

