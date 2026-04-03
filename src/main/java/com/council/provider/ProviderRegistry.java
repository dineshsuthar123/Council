package com.council.provider;

import com.council.resilience.ProviderCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry of all available {@link LlmAdapter} beans.
 * Filters by enabled status and cooldown state before returning adapters.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, LlmAdapter> adapters;
    private final ProviderCircuitBreaker circuitBreaker;

    public ProviderRegistry(List<LlmAdapter> adapterList, ProviderCircuitBreaker circuitBreaker) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(LlmAdapter::providerName, Function.identity()));
        this.circuitBreaker = circuitBreaker;
        log.info("Registered {} provider adapters: {}", adapters.size(), adapters.keySet());
    }

    /**
     * All enabled providers not currently in cooldown.
     */
    public List<LlmAdapter> getAvailableDraftProviders() {
        return adapters.values().stream()
                .filter(LlmAdapter::isEnabled)
                .filter(a -> !circuitBreaker.isInCooldown(a.providerName()))
                .toList();
    }

    /**
     * Get the preferred critic adapter, falling back to any available adapter.
     */
    public Optional<LlmAdapter> getCriticAdapter(String preferredProvider) {
        LlmAdapter preferred = adapters.get(preferredProvider);
        if (preferred != null && preferred.isEnabled()
                && !circuitBreaker.isInCooldown(preferredProvider)) {
            return Optional.of(preferred);
        }
        log.warn("Preferred critic provider '{}' unavailable, looking for fallback", preferredProvider);
        return getAvailableDraftProviders().stream().findFirst();
    }

    public Optional<LlmAdapter> getAdapter(String providerName) {
        return Optional.ofNullable(adapters.get(providerName))
                .filter(LlmAdapter::isEnabled);
    }

    public Map<String, LlmAdapter> getAllAdapters() {
        return Map.copyOf(adapters);
    }
}

