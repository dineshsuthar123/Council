package com.council.common.exception;

/**
 * Thrown when provider output cannot be normalized into valid JSON.
 */
public class JsonNormalizationException extends RuntimeException {

    private final String provider;
    private final String rawFragment;

    public JsonNormalizationException(String provider, String message, String rawFragment) {
        super(message);
        this.provider = provider;
        this.rawFragment = rawFragment;
    }

    public String getProvider() { return provider; }

    /** First 500 chars of the raw text that could not be parsed. */
    public String getRawFragment() {
        if (rawFragment == null) return null;
        return rawFragment.length() > 500 ? rawFragment.substring(0, 500) + "…" : rawFragment;
    }
}

