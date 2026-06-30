package com.council.config;

import java.util.Locale;

/**
 * Runtime provider posture for cost and privacy control.
 */
public enum ProviderMode {
    LOCAL_ONLY,
    FREE_FIRST,
    HYBRID,
    PREMIUM;

    public boolean allowsLocalProvider() {
        return true;
    }

    public boolean allowsExternalProvider() {
        return this != LOCAL_ONLY;
    }

    public static boolean isLocalProvider(String providerId) {
        return providerId != null && providerId.startsWith("ollama-");
    }

    public boolean allowsProvider(String providerId) {
        return isLocalProvider(providerId) ? allowsLocalProvider() : allowsExternalProvider();
    }

    public static ProviderMode safe(ProviderMode mode) {
        return mode == null ? FREE_FIRST : mode;
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
