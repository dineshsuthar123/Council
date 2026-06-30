package com.council.provider;

import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.provider.routing.ProviderDescriptor;
import com.council.provider.routing.ProviderRole;
import com.council.provider.blackbox.BlackboxAdapterFactory;
import com.council.provider.ollama.OllamaAdapterFactory;
import com.council.resilience.ProviderCircuitBreaker;
import com.council.resilience.ProviderCooldownState;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Central registry of all available {@link LlmAdapter} beans.
 * Filters by enabled status and cooldown state before returning adapters.
 * <p>
 * When routing is enabled, also builds {@link ProviderDescriptor} snapshots
 * that merge static config with live runtime state.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, LlmAdapter> adapters;
    private final ProviderCircuitBreaker circuitBreaker;
    private final CouncilProperties properties;
    private final ProviderConcurrencyLimiter concurrencyLimiter;

    @Autowired
    public ProviderRegistry(List<LlmAdapter> adapterList,
                            ProviderCircuitBreaker circuitBreaker,
                            CouncilProperties properties,
                            ProviderConcurrencyLimiter concurrencyLimiter,
                            BlackboxAdapterFactory blackboxAdapterFactory,
                            OllamaAdapterFactory ollamaAdapterFactory) {
        Map<String, LlmAdapter> registered = new LinkedHashMap<>();
        adapterList.forEach(adapter -> registerAdapter(registered, adapter));
        if (ollamaAdapterFactory != null) {
            ollamaAdapterFactory.adapters().forEach(adapter -> registerAdapter(registered, adapter));
        }
        blackboxAdapterFactory.adapters().forEach(adapter -> registerAdapter(registered, adapter));
        this.adapters = Map.copyOf(registered);
        this.circuitBreaker = circuitBreaker;
        this.properties = properties;
        this.concurrencyLimiter = concurrencyLimiter;

        // Register concurrency limits from routing config
        if (properties.getRouting().isEnabled()) {
            properties.getRouting().getProviderRoutes().forEach((name, route) ->
                    concurrencyLimiter.register(name, route.getMaxConcurrency()));
        }

        log.info("Registered {} provider adapters: {}", adapters.size(), adapters.keySet());
    }

    public ProviderRegistry(List<LlmAdapter> adapterList,
                            ProviderCircuitBreaker circuitBreaker,
                            CouncilProperties properties,
                            ProviderConcurrencyLimiter concurrencyLimiter,
                            BlackboxAdapterFactory blackboxAdapterFactory) {
        this(adapterList, circuitBreaker, properties, concurrencyLimiter, blackboxAdapterFactory, null);
    }

    private void registerAdapter(Map<String, LlmAdapter> registered, LlmAdapter adapter) {
        LlmAdapter existing = registered.putIfAbsent(adapter.providerName(), adapter);
        if (existing != null) {
            log.warn("Ignoring duplicate provider id '{}' from {}; existing adapter is retained",
                    adapter.providerName(), adapter.getClass().getSimpleName());
        }
    }

    /**
     * All enabled providers not currently in cooldown.
     * This is the legacy method used when routing is disabled.
     */
    public List<LlmAdapter> getAvailableDraftProviders() {
        return adapters.values().stream()
                .filter(LlmAdapter::isEnabled)
                .filter(a -> isAllowedInCurrentMode(a.providerName()))
                .filter(a -> !circuitBreaker.isInCooldown(a.providerName()))
                .toList();
    }

    /**
     * Get the preferred critic adapter, falling back to any available adapter.
     */
    public Optional<LlmAdapter> getCriticAdapter(String preferredProvider) {
        LlmAdapter preferred = adapters.get(preferredProvider);
        if (preferred != null && preferred.isEnabled()
                && isAllowedInCurrentMode(preferredProvider)
                && !circuitBreaker.isInCooldown(preferredProvider)) {
            return Optional.of(preferred);
        }
        log.warn("Preferred critic provider '{}' unavailable, looking for fallback", preferredProvider);
        return getAvailableDraftProviders().stream().findFirst();
    }

    public Optional<LlmAdapter> getAdapter(String providerName) {
        return Optional.ofNullable(adapters.get(providerName))
                .filter(adapter -> isAllowedInCurrentMode(providerName))
                .filter(LlmAdapter::isEnabled);
    }

    public Map<String, LlmAdapter> getAllAdapters() {
        return Map.copyOf(adapters);
    }

    public Map<String, LlmAdapter> getAdaptersForCurrentMode() {
        return adapters.entrySet().stream()
                .filter(entry -> isAllowedInCurrentMode(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    public ProviderMode providerMode() {
        ProviderMode mode = properties.getProviderMode();
        return mode == null ? ProviderMode.FREE_FIRST : mode;
    }

    public boolean isAllowedInCurrentMode(String providerId) {
        return providerMode().allowsProvider(providerId);
    }

    /**
     * Whether intelligent routing is enabled.
     */
    public boolean isRoutingEnabled() {
        return properties.getRouting().isEnabled();
    }

    /**
     * Build live {@link ProviderDescriptor} snapshots for every registered provider.
     * Merges static config from {@link CouncilProperties} with live circuit-breaker state.
     */
    public List<ProviderDescriptor> buildDescriptors() {
        return buildDescriptors(false);
    }

    public List<ProviderDescriptor> buildDescriptorsForCurrentMode() {
        return buildDescriptors(true);
    }

    private List<ProviderDescriptor> buildDescriptors(boolean currentModeOnly) {
        CouncilProperties.RoutingConfig routing = properties.getRouting();
        Map<String, CouncilProperties.ProviderRouteConfig> routes = routing.getProviderRoutes();

        return adapters.entrySet().stream()
                .filter(entry -> !currentModeOnly || isAllowedInCurrentMode(entry.getKey()))
                .map(entry -> {
                    String name = entry.getKey();
                    LlmAdapter adapter = entry.getValue();
                    CouncilProperties.ProviderConfig providerConfig =
                            properties.getProviders().getOrDefault(name, new CouncilProperties.ProviderConfig());
                    CouncilProperties.ProviderRouteConfig routeConfig =
                            routes.getOrDefault(name, new CouncilProperties.ProviderRouteConfig());

                    ProviderCooldownState cooldownState = circuitBreaker.getState(name);

                    return new ProviderDescriptor(
                            name,
                            routeConfig.getDisplayName().isEmpty() ? name : routeConfig.getDisplayName(),
                            adapter.modelName(),
                            adapter.isEnabled(),
                            routeConfig.getRoles().isEmpty()
                                    ? List.of(ProviderRole.DRAFT)
                                    : routeConfig.getRoles(),
                            routeConfig.getPriority(),
                            providerConfig.getReliability(),
                            providerConfig.getTimeoutSeconds(),
                            routeConfig.getMaxConcurrency(),
                            routeConfig.getFallbackProviders(),
                            cooldownState.isInCooldown(),
                            cooldownState.getRecentFailureRate()
                    );
                })
                .toList();
    }
}
