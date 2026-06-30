package com.council.api.controller;

import com.council.api.dto.ProviderStatusResponse;
import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.metrics.ProviderScorecardService;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ProviderRegistry;
import com.council.provider.ResponseMapper;
import com.council.provider.blackbox.BlackboxAdapterFactory;
import com.council.provider.blackbox.BlackboxProviderProperties;
import com.council.provider.ollama.OllamaAdapterFactory;
import com.council.provider.ollama.OllamaModelAvailabilityService;
import com.council.provider.ollama.OllamaProviderProperties;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.resilience.ProviderCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OllamaProviderStatusTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void healthAndProviderStatusExposeOllamaModelState() throws Exception {
        String baseUrl = startTagsServer("""
                {"models":[{"name":"qwen2.5-coder:7b"}]}
                """);
        CouncilProperties properties = new CouncilProperties();
        properties.setProviderMode(ProviderMode.LOCAL_ONLY);
        properties.setProviders(new HashMap<>());
        properties.getRouting().setEnabled(true);
        properties.getRouting().setProviderRoutes(new HashMap<>());

        OllamaProviderProperties ollama = new OllamaProviderProperties();
        ollama.setBaseUrl(baseUrl);
        ollama.setModels(Map.of(
                "qwen", model("ollama-qwen-coder", "Ollama Qwen", "qwen2.5-coder:7b"),
                "deepseek", model("ollama-deepseek", "Ollama DeepSeek", "deepseek-r1:8b")
        ));

        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(properties, metrics);
        ProviderCallExecutor executor = new ProviderCallExecutor(breaker, metrics, properties);
        ObjectMapper mapper = new ObjectMapper();
        RestClientFactory restClientFactory = new RestClientFactory();
        OllamaAdapterFactory ollamaFactory = new OllamaAdapterFactory(ollama, properties, mapper,
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class), executor, metrics,
                restClientFactory, new OllamaModelAvailabilityService(ollama, mapper, restClientFactory));
        BlackboxAdapterFactory blackboxFactory = new BlackboxAdapterFactory(new BlackboxProviderProperties(),
                properties, mapper, mock(JsonResponseNormalizer.class), mock(ResponseMapper.class), executor,
                metrics, mock(RestClientFactory.class));
        ProviderRegistry registry = new ProviderRegistry(List.of(), breaker, properties,
                new ProviderConcurrencyLimiter(), blackboxFactory, ollamaFactory);
        HealthController controller = new HealthController(registry, breaker, new ProviderConcurrencyLimiter(),
                mock(ProviderScorecardService.class), properties);

        List<ProviderStatusResponse> statuses = controller.providerStatus().getBody();
        ProviderStatusResponse qwen = statuses.stream()
                .filter(status -> status.provider().equals("ollama-qwen-coder"))
                .findFirst().orElseThrow();
        ProviderStatusResponse deepseek = statuses.stream()
                .filter(status -> status.provider().equals("ollama-deepseek"))
                .findFirst().orElseThrow();

        assertEquals("LOCAL", qwen.providerType());
        assertTrue(qwen.modelInstalled());
        assertTrue(qwen.available());
        assertNull(qwen.remediation());
        assertEquals("LOCAL", deepseek.providerType());
        assertFalse(deepseek.modelInstalled());
        assertFalse(deepseek.available());
        assertEquals("MODEL_NOT_INSTALLED", deepseek.failureReason());
        assertEquals("ollama pull deepseek-r1:8b", deepseek.remediation());

        Map<String, Object> health = controller.health().getBody();
        assertEquals("local_only", health.get("providerMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> healthOllama = (Map<String, Object>) health.get("ollama");
        assertTrue(healthOllama.containsKey("ollama-qwen-coder"));
        String payload = mapper.writeValueAsString(statuses);
        assertFalse(payload.contains("apiKey"));
    }

    private OllamaProviderProperties.ModelConfig model(String providerId, String displayName, String model) {
        OllamaProviderProperties.ModelConfig config = new OllamaProviderProperties.ModelConfig();
        config.setEnabled(true);
        config.setProviderId(providerId);
        config.setDisplayName(displayName);
        config.setModel(model);
        return config;
    }

    private String startTagsServer(String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
