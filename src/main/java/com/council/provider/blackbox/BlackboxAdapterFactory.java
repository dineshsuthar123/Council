package com.council.provider.blackbox;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.council.provider.routing.ProviderRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes enabled Blackbox logical-model configurations as regular Council adapters.
 */
@Component
public class BlackboxAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(BlackboxAdapterFactory.class);

    private final BlackboxProviderProperties blackboxProperties;
    private final CouncilProperties properties;
    private final ObjectMapper mapper;
    private final JsonResponseNormalizer normalizer;
    private final ResponseMapper responseMapper;
    private final ProviderCallExecutor callExecutor;
    private final OrchestrationMetrics metrics;
    private final RestClientFactory restClientFactory;
    private volatile List<LlmAdapter> adapters;

    public BlackboxAdapterFactory(BlackboxProviderProperties blackboxProperties,
                                  CouncilProperties properties,
                                  ObjectMapper mapper,
                                  JsonResponseNormalizer normalizer,
                                  ResponseMapper responseMapper,
                                  ProviderCallExecutor callExecutor,
                                  OrchestrationMetrics metrics,
                                  RestClientFactory restClientFactory) {
        this.blackboxProperties = blackboxProperties;
        this.properties = properties;
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.responseMapper = responseMapper;
        this.callExecutor = callExecutor;
        this.metrics = metrics;
        this.restClientFactory = restClientFactory;
    }

    public List<LlmAdapter> adapters() {
        List<LlmAdapter> snapshot = adapters;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (adapters == null) {
                adapters = List.copyOf(createAdapters());
            }
            return adapters;
        }
    }

    private List<LlmAdapter> createAdapters() {
        if (!blackboxProperties.isEnabled()) {
            log.info("Blackbox provider family is disabled");
            return List.of();
        }

        Map<String, CouncilProperties.ProviderConfig> providerConfigs =
                new HashMap<>(properties.getProviders());
        Map<String, CouncilProperties.ProviderRouteConfig> routes =
                new HashMap<>(properties.getRouting().getProviderRoutes());
        Set<String> providerIds = new HashSet<>();
        List<LlmAdapter> created = new ArrayList<>();

        for (BlackboxProviderProperties.ModelConfig modelConfig : blackboxProperties.getModels().values()) {
            String apiKey = modelConfig.getApiKey();
            boolean hasKey = apiKey != null && !apiKey.isBlank();
            // A non-empty API key acts as an implicit enable — BLACKBOX_*_ENABLED=true is not required.
            // Skip only when explicitly disabled AND no key is present.
            // Models with enabled=true and no key remain registered for status/diagnostic display.
            if (!modelConfig.isEnabled() && !hasKey) {
                continue;
            }
            if (modelConfig.getProviderId() == null || modelConfig.getProviderId().isBlank()
                    || modelConfig.getModel() == null || modelConfig.getModel().isBlank()) {
                log.warn("Ignoring invalid Blackbox provider configuration: provider id and model are required");
                continue;
            }
            if (!providerIds.add(modelConfig.getProviderId())) {
                log.warn("Ignoring duplicate Blackbox provider id '{}'", modelConfig.getProviderId());
                continue;
            }

            CouncilProperties.ProviderConfig providerConfig = toProviderConfig(modelConfig);
            providerConfigs.put(modelConfig.getProviderId(), providerConfig);
            routes.putIfAbsent(modelConfig.getProviderId(), toRouteConfig(modelConfig));
            // The shared OpenAI adapter resolves its provider config during construction.
            properties.setProviders(providerConfigs);
            properties.getRouting().setProviderRoutes(routes);
            created.add(new BlackboxOpenAiCompatibleAdapter(modelConfig, properties, mapper, normalizer,
                    responseMapper, callExecutor, metrics, restClientFactory));
            log.info("Configured Blackbox logical provider id={} model={} available={}",
                    modelConfig.getProviderId(), modelConfig.getModel(), providerConfig.isUsable());
        }

        properties.setProviders(providerConfigs);
        properties.getRouting().setProviderRoutes(routes);
        return created;
    }

    private CouncilProperties.ProviderConfig toProviderConfig(BlackboxProviderProperties.ModelConfig modelConfig) {
        BlackboxProviderProperties.Defaults defaults = blackboxProperties.getDefaults();
        CouncilProperties.ProviderConfig config = new CouncilProperties.ProviderConfig();
        // Enabled when explicitly enabled OR when a key is present (key acts as implicit opt-in).
        boolean hasKey = modelConfig.getApiKey() != null && !modelConfig.getApiKey().isBlank();
        config.setEnabled(modelConfig.isEnabled() || hasKey);
        config.setApiKey(modelConfig.getApiKey());
        config.setBaseUrl(blackboxProperties.getBaseUrl());
        config.setModel(modelConfig.getModel());
        config.setTimeoutMs(valueOrDefault(modelConfig.getTimeoutMs(), defaults.getTimeoutMs()));
        config.setMaxTokens(valueOrDefault(modelConfig.getMaxTokens(), defaults.getMaxTokens()));
        config.setTemperature(valueOrDefault(modelConfig.getTemperature(), defaults.getTemperature()));
        config.setReliability(valueOrDefault(modelConfig.getReliability(), defaults.getReliability()));
        return config;
    }

    private CouncilProperties.ProviderRouteConfig toRouteConfig(BlackboxProviderProperties.ModelConfig modelConfig) {
        BlackboxProviderProperties.Defaults defaults = blackboxProperties.getDefaults();
        CouncilProperties.ProviderRouteConfig route = new CouncilProperties.ProviderRouteConfig();
        route.setDisplayName(modelConfig.getDisplayName());
        route.setRoles(List.of(ProviderRole.DRAFT));
        route.setPriority(valueOrDefault(modelConfig.getPriority(), defaults.getPriority()));
        route.setMaxConcurrency(1);
        return route;
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private static double valueOrDefault(Double value, double defaultValue) {
        return value != null ? value : defaultValue;
    }
}
