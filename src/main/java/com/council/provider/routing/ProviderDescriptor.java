package com.council.provider.routing;

import java.util.List;

/**
 * Canonical metadata for a single routable provider/model entry.
 * Merges static configuration with live runtime state to serve as
 * the single source of truth for all routing decisions.
 *
 * @param name              provider key (e.g. "deepseek", "gemini")
 * @param displayName       human-readable name (e.g. "DeepSeek Chat")
 * @param model             model identifier (e.g. "deepseek-chat")
 * @param enabled           whether the provider is configured and enabled
 * @param roles             roles this provider can fulfil
 * @param priority          lower = selected first (within role)
 * @param reliability       historical reliability score [0..1]
 * @param timeoutSeconds    per-call timeout
 * @param maxConcurrency    max parallel in-flight calls allowed
 * @param fallbackProviders ordered fallback provider names
 * @param inCooldown        true if the circuit breaker has this provider in cooldown
 * @param recentFailureRate approximate failure rate [0..1]
 */
public record ProviderDescriptor(
        String name,
        String displayName,
        String model,
        boolean enabled,
        List<ProviderRole> roles,
        int priority,
        double reliability,
        int timeoutSeconds,
        int maxConcurrency,
        List<String> fallbackProviders,
        boolean inCooldown,
        double recentFailureRate
) {

    /**
     * Whether this provider is available for routing right now:
     * enabled, not in cooldown, and has a reasonable failure rate.
     */
    public boolean isAvailableForRouting() {
        return enabled && !inCooldown;
    }

    /**
     * Whether this provider can serve the given role.
     */
    public boolean hasRole(ProviderRole role) {
        return roles != null && roles.contains(role);
    }
}

