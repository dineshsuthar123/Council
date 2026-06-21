package com.council.provider;

/**
 * Optional provider metadata for operator-facing status views. Implementations must never expose credentials.
 */
public interface ProviderStatusAware {

    ProviderStatusDetails providerStatusDetails();
}
