package com.council.provider.claude;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeAdapterTest {

    @Test
    @DisplayName("Uses Blackbox OpenAI-compatible transport for CLAUDE_API_KEY slot")
    void usesBlackboxOpenAiCompatibleTransport() {
        CouncilProperties properties = new CouncilProperties();
        CouncilProperties.ProviderConfig config = new CouncilProperties.ProviderConfig();
        config.setEnabled(true);
        config.setApiKey("blackbox-test-key");
        config.setBaseUrl("https://api.blackbox.ai");
        config.setModel("blackboxai/anthropic/claude-sonnet-4.6");
        config.setTimeoutSeconds(60);
        properties.setProviders(Map.of("claude", config));

        RestClientFactory restClientFactory = mock(RestClientFactory.class);
        when(restClientFactory.create(anyString(), anyInt(), anyMap()))
                .thenReturn(mock(RestClient.class));

        ClaudeAdapter adapter = new ClaudeAdapter(
                properties,
                new ObjectMapper(),
                mock(JsonResponseNormalizer.class),
                mock(ResponseMapper.class),
                mock(ProviderCallExecutor.class),
                mock(OrchestrationMetrics.class),
                restClientFactory
        );

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.captor();
        verify(restClientFactory).create(
                eq("https://api.blackbox.ai"),
                eq(60),
                headersCaptor.capture()
        );

        Map<String, String> headers = headersCaptor.getValue();
        assertEquals("claude", adapter.providerName());
        assertEquals("blackboxai/anthropic/claude-sonnet-4.6", adapter.modelName());
        assertEquals("Bearer blackbox-test-key", headers.get("Authorization"));
        assertFalse(headers.containsKey("x-api-key"));
        assertFalse(headers.containsKey("anthropic-version"));
    }
}
