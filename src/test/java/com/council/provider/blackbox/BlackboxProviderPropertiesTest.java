package com.council.provider.blackbox;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ProviderStatusAware;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BlackboxProviderPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsMultipleLogicalProvidersAndCustomModelOverride() {
        contextRunner.withPropertyValues(
                "council.providers.blackbox.base-url=https://blackbox.example/chat/completions",
                "council.providers.blackbox.models.gpt55.enabled=true",
                "council.providers.blackbox.models.gpt55.provider-id=blackbox-gpt55",
                "council.providers.blackbox.models.gpt55.display-name=Blackbox GPT",
                "council.providers.blackbox.models.gpt55.model=custom/gpt-model",
                "council.providers.blackbox.models.gpt55.timeout-ms=61000",
                "council.providers.blackbox.models.claude-sonnet.enabled=true",
                "council.providers.blackbox.models.claude-sonnet.provider-id=blackbox-claude-sonnet",
                "council.providers.blackbox.models.claude-sonnet.display-name=Blackbox Claude",
                "council.providers.blackbox.models.claude-sonnet.model=custom/claude-model",
                "council.providers.blackbox.preflight.enabled=true",
                "council.providers.blackbox.preflight.timeout-ms=9000",
                "council.providers.blackbox.preflight.max-tokens=7"
        ).run(context -> {
            BlackboxProviderProperties properties = context.getBean(BlackboxProviderProperties.class);

            assertEquals("https://blackbox.example/chat/completions", properties.getBaseUrl());
            assertEquals(2, properties.getModels().size());
            assertEquals("custom/gpt-model", properties.getModels().get("gpt55").getModel());
            assertEquals(61000, properties.getModels().get("gpt55").getTimeoutMs());
            assertTrue(properties.getPreflight().isEnabled());
            assertEquals(9000, properties.getPreflight().getTimeoutMs());
            assertEquals(7, properties.getPreflight().getMaxTokens());
            assertEquals("blackbox-claude-sonnet",
                    properties.getModels().get("claude-sonnet").getProviderId());
        });
    }

    @Test
    void disabledLogicalProvidersAreIgnored() {
        BlackboxProviderProperties blackbox = new BlackboxProviderProperties();
        BlackboxProviderProperties.ModelConfig disabled = model("blackbox-gpt55", "custom/gpt", false, "");
        blackbox.setModels(java.util.Map.of("gpt55", disabled));

        assertTrue(factory(blackbox).adapters().isEmpty());
    }

    @Test
    void enabledProviderWithoutKeyIsRegisteredAsUnavailable() {
        BlackboxProviderProperties blackbox = new BlackboxProviderProperties();
        BlackboxProviderProperties.ModelConfig missingKey = model("blackbox-gpt55", "custom/gpt", true, "");
        blackbox.setModels(java.util.Map.of("gpt55", missingKey));

        List<LlmAdapter> adapters = factory(blackbox).adapters();

        assertEquals(1, adapters.size());
        assertFalse(adapters.getFirst().isEnabled());
        ProviderStatusAware statusAware = assertInstanceOf(ProviderStatusAware.class, adapters.getFirst());
        assertTrue(statusAware.providerStatusDetails().enabled());
        assertFalse(statusAware.providerStatusDetails().configured());
        assertFalse(statusAware.providerStatusDetails().available());
        assertEquals("API_KEY_MISSING", statusAware.providerStatusDetails().failureReason());
        assertEquals("SKIPPED_NO_KEY", statusAware.providerStatusDetails().preflightStatus());
        assertEquals("API_KEY_MISSING", statusAware.providerStatusDetails().preflightFailureCategory());
    }

    @Test
    void timeoutFallbackAndMismatchWarningAreExposedSafely() {
        BlackboxProviderProperties blackbox = new BlackboxProviderProperties();
        blackbox.getDefaults().setTimeoutMs(45_000);
        BlackboxProviderProperties.ModelConfig mismatch = model(
                "blackbox-gpt55-pro", "blackboxai/openai/gpt-5.4-pro", true, "key");
        mismatch.setDisplayName("Blackbox GPT-5.5 Pro");
        blackbox.setModels(java.util.Map.of("gpt55pro", mismatch));

        List<LlmAdapter> adapters = factory(blackbox).adapters();

        ProviderStatusAware statusAware = assertInstanceOf(ProviderStatusAware.class, adapters.getFirst());
        assertEquals(45_000, statusAware.providerStatusDetails().timeoutMsConfigured());
        assertEquals("DEFAULT", statusAware.providerStatusDetails().timeoutSource());
        assertEquals("NOT_RUN", statusAware.providerStatusDetails().preflightStatus());
        assertEquals(1, statusAware.providerStatusDetails().configWarnings().size());
        assertTrue(statusAware.providerStatusDetails().configWarnings().getFirst().contains("GPT-5.5 Pro"));
    }

    private BlackboxAdapterFactory factory(BlackboxProviderProperties blackbox) {
        CouncilProperties council = new CouncilProperties();
        council.setProviders(new HashMap<>());
        council.getRouting().setProviderRoutes(new HashMap<>());
        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        return new BlackboxAdapterFactory(blackbox, council, new ObjectMapper(),
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class),
                mock(ProviderCallExecutor.class), metrics, mock(RestClientFactory.class));
    }

    private BlackboxProviderProperties.ModelConfig model(String providerId,
                                                           String model,
                                                           boolean enabled,
                                                           String apiKey) {
        BlackboxProviderProperties.ModelConfig config = new BlackboxProviderProperties.ModelConfig();
        config.setEnabled(enabled);
        config.setProviderId(providerId);
        config.setDisplayName(providerId + " display");
        config.setModel(model);
        config.setApiKey(apiKey);
        return config;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BlackboxProviderProperties.class)
    static class PropertiesConfiguration {
    }
}
