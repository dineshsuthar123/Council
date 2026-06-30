package com.council.provider;

import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.blackbox.BlackboxAdapterFactory;
import com.council.provider.blackbox.BlackboxProviderProperties;
import com.council.provider.ollama.OllamaAdapterFactory;
import com.council.provider.ollama.OllamaModelAvailabilityService;
import com.council.provider.ollama.OllamaProviderProperties;
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

    @Test
    void registersEnabledOllamaProvidersWithoutApiKeys() {
        CouncilProperties properties = new CouncilProperties();
        properties.setProviders(new HashMap<>());
        properties.getRouting().setProviderRoutes(new HashMap<>());
        OllamaProviderProperties ollama = new OllamaProviderProperties();
        ollama.setBaseUrl("http://127.0.0.1:11434");
        ollama.setModels(Map.of(
                "qwen", ollamaModel("ollama-qwen-coder", "qwen2.5-coder:7b", "coding"),
                "deepseek", ollamaModel("ollama-deepseek", "deepseek-r1:8b", "reasoning"),
                "llama", ollamaModel("ollama-llama", "llama3.1:8b", "general"),
                "gemma", ollamaModel("ollama-gemma", "gemma3:4b", "summarizer")
        ));

        ProviderRegistry registry = registry(properties, new BlackboxProviderProperties(), ollama,
                directAdapter("groq"));

        assertTrue(registry.getAllAdapters().keySet().containsAll(List.of(
                "ollama-qwen-coder", "ollama-deepseek", "ollama-llama", "ollama-gemma")));
        assertEquals(4, registry.getAvailableDraftProviders().stream()
                .filter(adapter -> adapter.providerName().startsWith("ollama-"))
                .count());
        assertTrue(registry.getAdapter("ollama-qwen-coder").isPresent());
    }

    @Test
    void localOnlyModeExcludesExternalAdaptersFromAvailableSelection() {
        CouncilProperties properties = new CouncilProperties();
        properties.setProviderMode(ProviderMode.LOCAL_ONLY);
        properties.getOrchestrator().setLocalOnlyPerProviderDeadlineSeconds(95);
        properties.setProviders(new HashMap<>());
        properties.getRouting().setProviderRoutes(new HashMap<>());
        OllamaProviderProperties ollama = new OllamaProviderProperties();
        ollama.setModels(Map.of("qwen", ollamaModel("ollama-qwen-coder", "qwen2.5-coder:7b", "coding")));

        ProviderRegistry registry = registry(properties, new BlackboxProviderProperties(), ollama,
                directAdapter("groq"));

        assertTrue(registry.getAllAdapters().containsKey("groq"));
        assertFalse(registry.getAdapter("groq").isPresent());
        assertEquals(List.of("ollama-qwen-coder"),
                registry.getAvailableDraftProviders().stream().map(LlmAdapter::providerName).toList());
        assertEquals(List.of("ollama-qwen-coder"),
                registry.getAdaptersForCurrentMode().keySet().stream().toList());
        assertEquals(95_000,
                properties.getProviders().get("ollama-qwen-coder").getEffectiveTimeoutMillis());
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

    private ProviderRegistry registry(CouncilProperties properties,
                                      BlackboxProviderProperties blackbox,
                                      OllamaProviderProperties ollama,
                                      LlmAdapter directAdapter) {
        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(properties, metrics);
        ProviderCallExecutor executor = new ProviderCallExecutor(breaker, metrics, properties);
        ObjectMapper mapper = new ObjectMapper();
        RestClientFactory restClientFactory = new RestClientFactory();
        BlackboxAdapterFactory blackboxFactory = new BlackboxAdapterFactory(blackbox, properties, mapper,
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class), executor, metrics,
                mock(RestClientFactory.class));
        OllamaAdapterFactory ollamaFactory = new OllamaAdapterFactory(ollama, properties, mapper,
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class), executor, metrics,
                restClientFactory, new OllamaModelAvailabilityService(ollama, mapper, restClientFactory));
        return new ProviderRegistry(List.of(directAdapter), breaker, properties,
                new ProviderConcurrencyLimiter(), blackboxFactory, ollamaFactory);
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

    private OllamaProviderProperties.ModelConfig ollamaModel(String providerId, String model, String role) {
        OllamaProviderProperties.ModelConfig config = new OllamaProviderProperties.ModelConfig();
        config.setEnabled(true);
        config.setProviderId(providerId);
        config.setDisplayName(providerId + " display");
        config.setModel(model);
        config.setRole(role);
        return config;
    }
}
