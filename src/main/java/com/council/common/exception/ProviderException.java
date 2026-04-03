package com.council.common.exception;

/**
 * Base exception for all provider-related failures.
 */
public class ProviderException extends RuntimeException {

    private final String provider;

    public ProviderException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public ProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}

