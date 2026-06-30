package com.council.api.controller;

import com.council.api.dto.ProviderStatusResponse;
import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.metrics.ProviderScorecardService;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ProviderRegistry;
import com.council.provider.ResponseMapper;
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
import static org.mockito.Mockito.mock;

class BlackboxProviderStatusTest {

    @Test
    void providerStatusShowsSafeBlackboxAvailabilityWithoutExposingCredentials() throws Exception {
        CouncilProperties properties = new CouncilProperties();
        properties.setProviders(new HashMap<>());
        properties.getRouting().setEnabled(true);
        properties.getRouting().setProviderRoutes(new HashMap<>());
        BlackboxProviderProperties blackbox = new BlackboxProviderProperties();
        blackbox.setBaseUrl("https://api.blackbox.ai/chat/completions");
        blackbox.setModels(Map.of(
                "missing", model("blackbox-gpt55", "Blackbox GPT", "blackbox/example-gpt", ""),
                "mismatch", model("blackbox-gpt55-pro", "Blackbox GPT-5.5 Pro",
                        "blackboxai/openai/gpt-5.4-pro", "configured-test-credential-must-not-leak"),
                "configured", model("blackbox-claude-sonnet", "Blackbox Claude", "blackbox/example-claude",
                        "configured-test-credential-must-not-leak")
        ));

        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(properties, metrics);
        ProviderCallExecutor executor = new ProviderCallExecutor(breaker, metrics, properties);
        BlackboxAdapterFactory factory = new BlackboxAdapterFactory(blackbox, properties, new ObjectMapper(),
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class), executor, metrics,
                mock(RestClientFactory.class));
        ProviderRegistry registry = new ProviderRegistry(List.of(), breaker, properties,
                new ProviderConcurrencyLimiter(), factory);
        HealthController controller = new HealthController(registry, breaker, new ProviderConcurrencyLimiter(),
                mock(ProviderScorecardService.class), properties);

        List<ProviderStatusResponse> statuses = controller.providerStatus().getBody();
        ProviderStatusResponse missing = statuses.stream()
                .filter(status -> status.provider().equals("blackbox-gpt55"))
                .findFirst().orElseThrow();
        ProviderStatusResponse configured = statuses.stream()
                .filter(status -> status.provider().equals("blackbox-claude-sonnet"))
                .findFirst().orElseThrow();
        ProviderStatusResponse mismatch = statuses.stream()
                .filter(status -> status.provider().equals("blackbox-gpt55-pro"))
                .findFirst().orElseThrow();

        assertTrue(missing.enabled());
        assertFalse(missing.configured());
        assertFalse(missing.available());
        assertEquals("API_KEY_MISSING", missing.failureReason());
        assertEquals("https://api.blackbox.ai/chat/completions", missing.baseUrl());
        assertEquals("SKIPPED_NO_KEY", missing.preflightStatus());
        assertEquals("API_KEY_MISSING", missing.preflightFailureCategory());
        assertTrue(configured.configured());
        assertTrue(configured.available());
        assertEquals("NOT_RUN", configured.preflightStatus());
        assertEquals(60_000, configured.timeoutMsConfigured());
        assertEquals("DEFAULT", configured.timeoutSource());
        assertEquals(1, mismatch.configWarnings().size());
        assertTrue(mismatch.configWarnings().getFirst().contains("GPT-5.5 Pro"));

        Map<String, Object> health = controller.health().getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> healthBlackbox = (Map<String, Object>) health.get("blackbox");
        assertTrue(healthBlackbox.containsKey("blackbox-gpt55"));

        String payload = new ObjectMapper().writeValueAsString(statuses);
        assertFalse(payload.contains("configured-test-credential-must-not-leak"));
        assertFalse(payload.contains("apiKey"));
    }

    private BlackboxProviderProperties.ModelConfig model(String providerId,
                                                           String displayName,
                                                           String model,
                                                           String apiKey) {
        BlackboxProviderProperties.ModelConfig config = new BlackboxProviderProperties.ModelConfig();
        config.setEnabled(true);
        config.setProviderId(providerId);
        config.setDisplayName(displayName);
        config.setModel(model);
        config.setApiKey(apiKey);
        return config;
    }
}
