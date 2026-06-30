package com.council.provider.ollama;

import com.council.common.exception.ProviderException;
import com.council.common.exception.ProviderFailureCategory;
import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ProviderStatusDetails;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OllamaAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsNativeChatRequestAndParsesMessageContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String baseUrl = startServer(
                "{\"models\":[{\"name\":\"qwen2.5-coder:7b\"}]}",
                200,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"resolved answer\"},\"done\":true}",
                requestBody,
                0);

        String answer = adapter(baseUrl, "ollama-qwen-coder", "qwen2.5-coder:7b",
                0.2, 2048, 1_000).invoke("Explain the failure mode");

        assertEquals("resolved answer", answer);
        JsonNode body = mapper.readTree(requestBody.get());
        assertEquals("qwen2.5-coder:7b", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean(true));
        assertEquals(0.2, body.path("options").path("temperature").asDouble());
        assertEquals(2048, body.path("options").path("num_predict").asInt());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("Explain the failure mode", body.path("messages").get(1).path("content").asText());
    }

    @Test
    void classifiesEmptyAndBadResponseSchemas() throws Exception {
        String emptyBaseUrl = startServer(
                "{\"models\":[{\"name\":\"llama3.1:8b\"}]}",
                200,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}",
                new AtomicReference<>(),
                0);
        ProviderException empty = assertThrows(ProviderException.class,
                () -> adapter(emptyBaseUrl, "ollama-llama", "llama3.1:8b", 0.2, 512, 1_000)
                        .invoke("prompt"));
        assertEquals(ProviderFailureCategory.EMPTY_RESPONSE, empty.getFailureCategory());

        stopServer();
        String badBaseUrl = startServer(
                "{\"models\":[{\"name\":\"llama3.1:8b\"}]}",
                200,
                "{\"message\":{\"role\":\"assistant\"},\"done\":true}",
                new AtomicReference<>(),
                0);
        ProviderException badSchema = assertThrows(ProviderException.class,
                () -> adapter(badBaseUrl, "ollama-llama", "llama3.1:8b", 0.2, 512, 1_000)
                        .invoke("prompt"));
        assertEquals(ProviderFailureCategory.BAD_RESPONSE_SCHEMA, badSchema.getFailureCategory());
    }

    @Test
    void classifiesOllamaNotRunningAndTimeout() throws Exception {
        ProviderException notRunning = assertThrows(ProviderException.class,
                () -> adapter("http://127.0.0.1:1", "ollama-llama", "llama3.1:8b", 0.2, 512, 200)
                        .invoke("prompt"));
        assertEquals(ProviderFailureCategory.OLLAMA_NOT_RUNNING, notRunning.getFailureCategory());

        String slowBaseUrl = startServer(
                "{\"models\":[{\"name\":\"llama3.1:8b\"}]}",
                200,
                "{\"message\":{\"content\":\"late\"},\"done\":true}",
                new AtomicReference<>(),
                500);
        ProviderException timeout = assertThrows(ProviderException.class,
                () -> adapter(slowBaseUrl, "ollama-llama", "llama3.1:8b", 0.2, 512, 100)
                        .invoke("prompt"));
        assertEquals(ProviderFailureCategory.TIMEOUT, timeout.getFailureCategory());
    }

    @Test
    void classifiesModelNotInstalledFromTagsAndChatError() throws Exception {
        String missingTagsBaseUrl = startServer(
                "{\"models\":[{\"name\":\"llama3.1:8b\"}]}",
                200,
                "{\"message\":{\"content\":\"unused\"},\"done\":true}",
                new AtomicReference<>(),
                0);
        ProviderException missingFromTags = assertThrows(ProviderException.class,
                () -> adapter(missingTagsBaseUrl, "ollama-deepseek", "deepseek-r1:8b", 0.2, 512, 1_000)
                        .invoke("prompt"));
        assertEquals(ProviderFailureCategory.MODEL_NOT_INSTALLED, missingFromTags.getFailureCategory());
        assertTrue(missingFromTags.getMessage().contains("ollama pull deepseek-r1:8b"));

        stopServer();
        String missingChatBaseUrl = startServer(
                "{\"models\":[{\"name\":\"deepseek-r1:8b\"}]}",
                404,
                "{\"error\":\"model deepseek-r1:8b not found, try pulling it first\"}",
                new AtomicReference<>(),
                0);
        ProviderException missingFromChat = assertThrows(ProviderException.class,
                () -> adapter(missingChatBaseUrl, "ollama-deepseek", "deepseek-r1:8b", 0.2, 512, 1_000)
                        .invoke("prompt"));
        assertEquals(ProviderFailureCategory.MODEL_NOT_INSTALLED, missingFromChat.getFailureCategory());
    }

    @Test
    void providerStatusUsesCurrentModelAvailabilityInsteadOfStaleGenerationFailure() throws Exception {
        String baseUrl = startServer(
                "{\"models\":[{\"name\":\"qwen2.5-coder:7b\"}]}",
                500,
                "{\"error\":\"temporary local runtime failure\"}",
                new AtomicReference<>(),
                0);
        ExposedOllamaAdapter adapter = adapter(baseUrl, "ollama-qwen-coder",
                "qwen2.5-coder:7b", 0.2, 512, 1_000);

        ProviderException failure = assertThrows(ProviderException.class,
                () -> adapter.invoke("prompt"));
        assertEquals(ProviderFailureCategory.NETWORK_ERROR, failure.getFailureCategory());

        ProviderStatusDetails status = adapter.providerStatusDetails();
        assertTrue(status.available());
        assertTrue(status.modelInstalled());
        assertNull(status.failureReason());
    }

    private String startServer(String tagsResponse,
                               int chatStatus,
                               String chatResponse,
                               AtomicReference<String> requestBody,
                               long chatDelayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = tagsResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            if (chatDelayMs > 0) {
                try {
                    Thread.sleep(chatDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = chatResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(chatStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ExposedOllamaAdapter adapter(String baseUrl,
                                         String providerId,
                                         String model,
                                         double temperature,
                                         int numPredict,
                                         int timeoutMs) {
        OllamaProviderProperties family = new OllamaProviderProperties();
        family.setBaseUrl(baseUrl);
        family.getPreflight().setTimeoutMs(timeoutMs);
        OllamaProviderProperties.ModelConfig logical = new OllamaProviderProperties.ModelConfig();
        logical.setEnabled(true);
        logical.setProviderId(providerId);
        logical.setDisplayName(providerId + " display");
        logical.setModel(model);
        logical.setTemperature(temperature);
        logical.setNumPredict(numPredict);
        logical.setTimeoutMs(timeoutMs);

        CouncilProperties properties = new CouncilProperties();
        CouncilProperties.ProviderConfig config = new CouncilProperties.ProviderConfig();
        config.setEnabled(true);
        config.setApiKey("");
        config.setBaseUrl(baseUrl);
        config.setModel(model);
        config.setTemperature(temperature);
        config.setMaxTokens(numPredict);
        config.setTimeoutMs(timeoutMs);
        properties.setProviders(new HashMap<>(Map.of(providerId, config)));

        OllamaModelAvailabilityService availability =
                new OllamaModelAvailabilityService(family, mapper, new RestClientFactory());
        return new ExposedOllamaAdapter(family, logical, properties, mapper,
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class),
                mock(ProviderCallExecutor.class), new OrchestrationMetrics(new SimpleMeterRegistry()),
                new RestClientFactory(), availability);
    }

    private static final class ExposedOllamaAdapter extends OllamaChatAdapter {
        private ExposedOllamaAdapter(OllamaProviderProperties familyProperties,
                                     OllamaProviderProperties.ModelConfig logicalConfig,
                                     CouncilProperties properties,
                                     ObjectMapper mapper,
                                     JsonResponseNormalizer normalizer,
                                     ResponseMapper responseMapper,
                                     ProviderCallExecutor callExecutor,
                                     OrchestrationMetrics metrics,
                                     RestClientFactory restClientFactory,
                                     OllamaModelAvailabilityService availabilityService) {
            super(familyProperties, logicalConfig, properties, mapper, normalizer, responseMapper,
                    callExecutor, metrics, restClientFactory, availabilityService, false);
        }

        private String invoke(String prompt) {
            return callApi(prompt);
        }
    }
}
