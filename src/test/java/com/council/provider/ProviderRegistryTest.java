package com.council.provider;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.blackbox.BlackboxAdapterFactory;
import com.council.provider.blackbox.BlackboxProviderProperties;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.resilience.ProviderCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderRegistryTest {

    @Test
    void registersMultipleBlackboxLogicalProvidersAlongsideExistingProvider() {
        CouncilProperties properties = new CouncilProperties();
        properties.setProviders(new HashMap<>());
        properties.getRouting().setProviderRoutes(new HashMap<>());
        BlackboxProviderProperties blackbox = new BlackboxProviderProperties();
        blackbox.setModels(Map.of(
                "gpt55", model("blackbox-gpt55", "blackbox/example-gpt", "configured-test-credential-a"),
                "claude", model("blackbox-claude-sonnet", "blackbox/example-claude", "configured-test-credential-b")
        ));

        ProviderRegistry registry = registry(properties, blackbox, directAdapter("groq"));

        assertEquals(3, registry.getAllAdapters().size());
        assertTrue(registry.getAllAdapters().containsKey("groq"));
        assertTrue(registry.getAllAdapters().containsKey("blackbox-gpt55"));
        assertTrue(registry.getAllAdapters().containsKey("blackbox-claude-sonnet"));
        assertEquals(3, registry.getAllAdapters().keySet().stream().distinct().count());
        assertEquals(2, registry.getAvailableDraftProviders().stream()
                .filter(adapter -> adapter.providerName().startsWith("blackbox-"))
                .count());
    }

    @Test
    void missingKeyBlackboxProviderDoesNotCrashRegistryAndRemainsUnavailable() {
        CouncilProperties properties = new CouncilProperties();
        properties.setProviders(new HashMap<>());
        properties.getRouting().setProviderRoutes(new HashMap<>());
        BlackboxProviderProperties blackbox = new BlackboxProviderProperties();
        blackbox.setModels(Map.of("gpt55", model("blackbox-gpt55", "blackbox/example-gpt", "")));

        ProviderRegistry registry = registry(properties, blackbox, directAdapter("groq"));

        assertTrue(registry.getAllAdapters().containsKey("blackbox-gpt55"));
        assertFalse(registry.getAdapter("blackbox-gpt55").isPresent());
        assertTrue(registry.getAdapter("groq").isPresent());
    }

    private ProviderRegistry registry(CouncilProperties properties,
                                      BlackboxProviderProperties blackbox,
                                      LlmAdapter directAdapter) {
        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(properties, metrics);
        ProviderCallExecutor executor = new ProviderCallExecutor(breaker, metrics, properties);
        BlackboxAdapterFactory factory = new BlackboxAdapterFactory(blackbox, properties, new ObjectMapper(),
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class), executor, metrics,
                mock(RestClientFactory.class));
        return new ProviderRegistry(List.of(directAdapter), breaker, properties,
                new ProviderConcurrencyLimiter(), factory);
    }

    private BlackboxProviderProperties.ModelConfig model(String providerId, String model, String credential) {
        BlackboxProviderProperties.ModelConfig config = new BlackboxProviderProperties.ModelConfig();
        config.setEnabled(true);
        config.setProviderId(providerId);
        config.setDisplayName(providerId + " display");
        config.setModel(model);
        config.setApiKey(credential);
        return config;
    }

    private LlmAdapter directAdapter(String providerId) {
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.providerName()).thenReturn(providerId);
        when(adapter.modelName()).thenReturn(providerId + "-model");
        when(adapter.isEnabled()).thenReturn(true);
        return adapter;
    }
}
