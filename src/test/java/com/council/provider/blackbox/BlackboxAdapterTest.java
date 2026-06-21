package com.council.provider.blackbox;

import com.council.common.exception.ProviderException;
import com.council.common.exception.ProviderFailureCategory;
import com.council.common.exception.RateLimitException;
import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BlackboxAdapterTest {

    private static final String TEST_CREDENTIAL = "not-a-real-blackbox-credential";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesChoiceContent() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String endpoint = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"resolved answer\"}}]}",
                authorization, requestBody);

        String answer = adapter(endpoint).invoke("Explain the failure mode");

        assertEquals("resolved answer", answer);
        assertEquals("Bearer " + TEST_CREDENTIAL, authorization.get());
        JsonNode body = mapper.readTree(requestBody.get());
        assertEquals("blackbox/example-model", body.path("model").asText());
        assertEquals(0.2, body.path("temperature").asDouble());
        assertEquals(512, body.path("max_tokens").asInt());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("Explain the failure mode", body.path("messages").get(1).path("content").asText());
    }

    @Test
    void classifiesAuthRateLimitAndEmptyResponsesWithoutLeakingCredential() throws Exception {
        String authEndpoint = startServer(401, "{\"error\":\"ignored\"}", new AtomicReference<>(), new AtomicReference<>());
        ProviderException auth = assertThrows(ProviderException.class, () -> adapter(authEndpoint).invoke("prompt"));
        assertEquals(ProviderFailureCategory.AUTH, auth.getFailureCategory());
        assertFalse(auth.getMessage().contains(TEST_CREDENTIAL));

        stopServer();
        String forbiddenEndpoint = startServer(403, "{\"error\":\"ignored\"}", new AtomicReference<>(), new AtomicReference<>());
        ProviderException forbidden = assertThrows(ProviderException.class,
                () -> adapter(forbiddenEndpoint).invoke("prompt"));
        assertEquals(ProviderFailureCategory.AUTH, forbidden.getFailureCategory());

        stopServer();
        String rateLimitEndpoint = startServer(429, "{\"error\":\"ignored\"}", new AtomicReference<>(), new AtomicReference<>());
        RateLimitException rateLimit = assertThrows(RateLimitException.class,
                () -> adapter(rateLimitEndpoint).invoke("prompt"));
        assertEquals(ProviderFailureCategory.RATE_LIMIT, rateLimit.getFailureCategory());

        stopServer();
        String emptyEndpoint = startServer(200, "{\"choices\":[]}", new AtomicReference<>(), new AtomicReference<>());
        ProviderException empty = assertThrows(ProviderException.class, () -> adapter(emptyEndpoint).invoke("prompt"));
        assertEquals(ProviderFailureCategory.EMPTY_RESPONSE, empty.getFailureCategory());
        assertFalse(empty.getMessage().contains(TEST_CREDENTIAL));
    }

    @Test
    void classifiesTimeoutAndSanitizesCredentialBearingMessages() {
        ExposedBlackboxAdapter adapter = adapter("http://127.0.0.1:1/chat/completions");

        assertEquals(ProviderFailureCategory.TIMEOUT,
                adapter.classify(new ResourceAccessException("read timed out")));
        assertEquals(ProviderFailureCategory.NETWORK,
                adapter.classify(new ResourceAccessException("connection refused")));

        ProviderException sanitized = new ProviderException("blackbox-gpt55",
                "request failed with Bearer " + TEST_CREDENTIAL, ProviderFailureCategory.UNKNOWN);
        assertFalse(sanitized.getMessage().contains(TEST_CREDENTIAL));
        assertTrue(sanitized.getMessage().contains("[REDACTED]"));
    }

    private String startServer(int status,
                               String response,
                               AtomicReference<String> authorization,
                               AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }

    private ExposedBlackboxAdapter adapter(String endpoint) {
        BlackboxProviderProperties.ModelConfig logical = new BlackboxProviderProperties.ModelConfig();
        logical.setEnabled(true);
        logical.setProviderId("blackbox-gpt55");
        logical.setDisplayName("Blackbox GPT test");
        logical.setApiKey(TEST_CREDENTIAL);
        logical.setModel("blackbox/example-model");

        CouncilProperties properties = new CouncilProperties();
        CouncilProperties.ProviderConfig config = new CouncilProperties.ProviderConfig();
        config.setEnabled(true);
        config.setApiKey(TEST_CREDENTIAL);
        config.setBaseUrl(endpoint);
        config.setModel(logical.getModel());
        config.setTimeoutMs(1_000);
        config.setTemperature(0.2);
        config.setMaxTokens(512);
        properties.setProviders(new HashMap<>(Map.of(logical.getProviderId(), config)));

        return new ExposedBlackboxAdapter(logical, properties, mapper,
                mock(JsonResponseNormalizer.class), mock(ResponseMapper.class),
                mock(ProviderCallExecutor.class), new OrchestrationMetrics(new SimpleMeterRegistry()),
                new RestClientFactory());
    }

    private static final class ExposedBlackboxAdapter extends BlackboxOpenAiCompatibleAdapter {
        private ExposedBlackboxAdapter(BlackboxProviderProperties.ModelConfig logicalConfig,
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

        private ProviderFailureCategory classify(ResourceAccessException exception) {
            return classifyResourceAccess(exception);
        }
    }
}
