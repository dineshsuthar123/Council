package com.council.provider.ollama;

import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Materializes configured Ollama models as first-class Council providers.
 */
@Component
public class OllamaAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdapterFactory.class);

    private final OllamaProviderProperties ollamaProperties;
    private final CouncilProperties properties;
    private final ObjectMapper mapper;
    private final JsonResponseNormalizer normalizer;
    private final ResponseMapper responseMapper;
    private final ProviderCallExecutor callExecutor;
    private final OrchestrationMetrics metrics;
    private final RestClientFactory restClientFactory;
    private final OllamaModelAvailabilityService availabilityService;
    private volatile List<LlmAdapter> adapters;

    @Autowired
    public OllamaAdapterFactory(OllamaProviderProperties ollamaProperties,
                                CouncilProperties properties,
                                ObjectMapper mapper,
                                JsonResponseNormalizer normalizer,
                                ResponseMapper responseMapper,
                                ProviderCallExecutor callExecutor,
                                OrchestrationMetrics metrics,
                                RestClientFactory restClientFactory,
                                OllamaModelAvailabilityService availabilityService) {
        this.ollamaProperties = ollamaProperties;
        this.properties = properties;
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.responseMapper = responseMapper;
        this.callExecutor = callExecutor;
        this.metrics = metrics;
        this.restClientFactory = restClientFactory;
        this.availabilityService = availabilityService;
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
        if (!ollamaProperties.isEnabled()) {
            log.info("Ollama provider family is disabled");
            return List.of();
        }

        Map<String, CouncilProperties.ProviderConfig> providerConfigs =
                new HashMap<>(properties.getProviders());
        Map<String, CouncilProperties.ProviderRouteConfig> routes =
                new HashMap<>(properties.getRouting().getProviderRoutes());
        List<LlmAdapter> created = new ArrayList<>();
        Set<String> providerIds = new HashSet<>();

        for (OllamaProviderProperties.ModelConfig modelConfig : ollamaProperties.getModels().values()) {
            if (modelConfig.getProviderId() == null || modelConfig.getProviderId().isBlank()) {
                log.warn("Ignoring invalid Ollama provider configuration: provider id is required");
                continue;
            }
            if (!providerIds.add(modelConfig.getProviderId())) {
                log.warn("Ignoring duplicate Ollama provider id '{}'", modelConfig.getProviderId());
                continue;
            }

            CouncilProperties.ProviderConfig providerConfig = toProviderConfig(modelConfig);
            providerConfigs.put(modelConfig.getProviderId(), providerConfig);
            routes.putIfAbsent(modelConfig.getProviderId(), toRouteConfig(modelConfig));
            properties.setProviders(providerConfigs);
            properties.getRouting().setProviderRoutes(routes);

            created.add(new OllamaChatAdapter(ollamaProperties, modelConfig, properties, mapper, normalizer,
                    responseMapper, callExecutor, metrics, restClientFactory, availabilityService,
                    ollamaProperties.getPreflight().isEnabled()));
            log.info("Configured Ollama logical provider id={} model={} enabled={}",
                    modelConfig.getProviderId(), modelConfig.getModel(), modelConfig.isEnabled());
        }

        properties.setProviders(providerConfigs);
        properties.getRouting().setProviderRoutes(routes);
        return created;
    }

    private CouncilProperties.ProviderConfig toProviderConfig(OllamaProviderProperties.ModelConfig modelConfig) {
        OllamaProviderProperties.Defaults defaults = ollamaProperties.getDefaults();
        CouncilProperties.ProviderConfig config = new CouncilProperties.ProviderConfig();
        int timeoutMs = valueOrDefault(modelConfig.getTimeoutMs(), defaults.getTimeoutMs());
        if (properties.getProviderMode() == ProviderMode.LOCAL_ONLY) {
            int localOnlyTimeoutMs = properties.getOrchestrator().getLocalOnlyPerProviderDeadlineSeconds() * 1000;
            if (localOnlyTimeoutMs > 0) {
                timeoutMs = Math.max(timeoutMs, localOnlyTimeoutMs);
            }
        }
        config.setEnabled(modelConfig.isEnabled());
        config.setApiKey("");
        config.setBaseUrl(ollamaProperties.getBaseUrl());
        config.setModel(modelConfig.getModel());
        config.setTimeoutMs(timeoutMs);
        config.setTimeoutSeconds(Math.max(1, (int) Math.ceil(timeoutMs / 1000.0)));
        config.setMaxTokens(valueOrDefault(modelConfig.getNumPredict(), defaults.getNumPredict()));
        config.setTemperature(valueOrDefault(modelConfig.getTemperature(), defaults.getTemperature()));
        config.setReliability(valueOrDefault(modelConfig.getReliability(), defaults.getReliability()));
        return config;
    }

    private CouncilProperties.ProviderRouteConfig toRouteConfig(OllamaProviderProperties.ModelConfig modelConfig) {
        OllamaProviderProperties.Defaults defaults = ollamaProperties.getDefaults();
        CouncilProperties.ProviderRouteConfig route = new CouncilProperties.ProviderRouteConfig();
        route.setDisplayName(modelConfig.getDisplayName());
        route.setRoles(rolesFor(modelConfig.getRole()));
        route.setPriority(valueOrDefault(modelConfig.getPriority(), priorityForRole(modelConfig.getRole(), defaults)));
        route.setMaxConcurrency(1);
        return route;
    }

    private List<ProviderRole> rolesFor(String role) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (normalized.contains("reasoning")) {
            return List.of(ProviderRole.DRAFT, ProviderRole.CRITIC, ProviderRole.BASELINE);
        }
        if (normalized.contains("summarizer")) {
            return List.of(ProviderRole.DRAFT, ProviderRole.BASELINE);
        }
        return List.of(ProviderRole.DRAFT);
    }

    private int priorityForRole(String role, OllamaProviderProperties.Defaults defaults) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (normalized.contains("reasoning")) {
            return 11;
        }
        if (normalized.contains("coding")) {
            return 10;
        }
        if (normalized.contains("general")) {
            return 12;
        }
        if (normalized.contains("summarizer")) {
            return 13;
        }
        return defaults.getPriority();
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private static double valueOrDefault(Double value, double defaultValue) {
        return value != null ? value : defaultValue;
    }
}
