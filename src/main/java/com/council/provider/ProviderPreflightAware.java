package com.council.provider;

/**
 * Optional provider capability for lightweight configuration validation.
 */
public interface ProviderPreflightAware {

    /**
     * Run a bounded, low-token preflight check and return the latest safe status.
     */
    ProviderStatusDetails runPreflight();
}
