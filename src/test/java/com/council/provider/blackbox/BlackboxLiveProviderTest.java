package com.council.provider.blackbox;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * Optional quota-consuming smoke test. It is disabled unless -Dlive.provider.tests=true is supplied.
 */
@EnabledIfSystemProperty(named = "live.provider.tests", matches = "true")
class BlackboxLiveProviderTest {

    @Test
    void configuredBlackboxModelReturnsNonEmptyContent() {
        String apiKey = System.getenv("BLACKBOX_GPT55_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "BLACKBOX_GPT55_API_KEY is not configured");

        String model = System.getenv().getOrDefault("BLACKBOX_GPT55_MODEL", "blackboxai/openai/gpt-5.5");
        String baseUrl = System.getenv().getOrDefault("BLACKBOX_BASE_URL",
                "https://api.blackbox.ai/chat/completions");
        BlackboxProviderProperties.ModelConfig logical = new BlackboxProviderProperties.ModelConfig();
        logical.setEnabled(true);
        logical.setProviderId("blackbox-gpt55-live");
        logical.setDisplayName("Blackbox GPT-5.5 live smoke");
        logical.setApiKey(apiKey);
        logical.setModel(model);

        CouncilProperties properties = new CouncilProperties();
        CouncilProperties.ProviderConfig provider = new CouncilProperties.ProviderConfig();
        provider.setEnabled(true);
        provider.setApiKey(apiKey);
        provider.setBaseUrl(baseUrl);
        provider.setModel(model);
        provider.setTimeoutMs(60_000);
        provider.setMaxTokens(128);
        properties.setProviders(new HashMap<>(Map.of(logical.getProviderId(), provider)));

        LiveAdapter adapter = new LiveAdapter(logical, properties, new ObjectMapper(),
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class),
                mock(ProviderCallExecutor.class), new OrchestrationMetrics(new SimpleMeterRegistry()),
                new RestClientFactory());

        String response = adapter.invoke("Reply with one concise sentence confirming the service is available.");

        assertFalse(response.isBlank());
    }

    private static final class LiveAdapter extends BlackboxOpenAiCompatibleAdapter {
        private LiveAdapter(BlackboxProviderProperties.ModelConfig logicalConfig,
                            CouncilProperties properties,
                            ObjectMapper mapper,
                            JsonResponseNormalizer normalizer,
                            ResponseMapper responseMapper,
                            ProviderCallExecutor callExecutor,
                            OrchestrationMetrics metrics,
                            RestClientFactory restClientFactory) {
            super(logicalConfig, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
                    restClientFactory);
        }

        private String invoke(String prompt) {
            return callApi(prompt);
        }
    }
}
