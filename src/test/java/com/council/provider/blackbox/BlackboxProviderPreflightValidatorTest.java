package com.council.provider.blackbox;

import com.council.config.RestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BlackboxProviderPreflightValidatorTest {

    private static final String TEST_KEY = "preflight-test-key-must-not-leak";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void missingKeyAndBlankModelAreClassifiedWithoutNetwork() {
        BlackboxProviderPreflightValidator validator = validator("http://127.0.0.1:1/chat/completions", 100);

        BlackboxPreflightResult missingKey = validator.validate(model("blackbox-gpt55", "blackbox/gpt", ""));
        assertEquals(BlackboxPreflightStatus.SKIPPED_NO_KEY, missingKey.status());
        assertEquals(BlackboxPreflightFailureCategory.API_KEY_MISSING, missingKey.failureCategory());

        BlackboxPreflightResult blankModel = validator.validate(model("blackbox-gpt55", "", TEST_KEY));
        assertEquals(BlackboxPreflightStatus.FAILED, blankModel.status());
        assertEquals(BlackboxPreflightFailureCategory.CONFIG_INVALID, blankModel.failureCategory());
        assertFalse(blankModel.safeMessage().contains(TEST_KEY));
    }

    @Test
    void classifiesHttpFailuresAndNeverLeaksRawKey() throws Exception {
        assertFailure(400, "{\"error\":\"model not found\"}",
                BlackboxPreflightFailureCategory.MODEL_NOT_FOUND_OR_UNAVAILABLE);
        assertFailure(401, "{\"error\":\"bad key\"}", BlackboxPreflightFailureCategory.AUTH_FAILED);
        assertFailure(429, "{\"error\":\"slow down\"}", BlackboxPreflightFailureCategory.RATE_LIMITED);
        assertFailure(400, "{\"error\":\"bad request\"}", BlackboxPreflightFailureCategory.BAD_REQUEST_MODEL_CONFIG);
    }

    @Test
    void successfulOkResponsePassesAndSendsTinyRequest() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String endpoint = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", 0, body);

        BlackboxPreflightResult result = validator(endpoint, 500).validate(
                model("blackbox-gemini", "blackboxai/google/gemini-3.1-flash-lite", TEST_KEY));

        assertEquals(BlackboxPreflightStatus.PASSED, result.status());
        assertNull(result.failureCategory());
        assertTrue(body.get().contains("\"max_tokens\":8"));
        assertTrue(body.get().contains("Reply with OK."));
    }

    @Test
    void malformedAndEmptyResponsesAreClassified() throws Exception {
        assertFailure(200, "{\"choices\":[]}", BlackboxPreflightFailureCategory.BAD_RESPONSE_SCHEMA);
        assertFailure(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
                BlackboxPreflightFailureCategory.EMPTY_RESPONSE);
    }

    @Test
    void readTimeoutIsClassified() throws Exception {
        String endpoint = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", 350,
                new AtomicReference<>());

        BlackboxPreflightResult result = validator(endpoint, 50).validate(
                model("blackbox-gpt55", "blackboxai/openai/gpt-5.5", TEST_KEY));

        assertEquals(BlackboxPreflightStatus.FAILED, result.status());
        assertEquals(BlackboxPreflightFailureCategory.TIMEOUT, result.failureCategory());
        assertFalse(result.safeMessage().contains(TEST_KEY));
    }

    @Test
    void modelMismatchWarningIsNonFatal() throws Exception {
        String endpoint = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", 0,
                new AtomicReference<>());
        BlackboxProviderProperties.ModelConfig config = model(
                "blackbox-gpt55-pro", "blackboxai/openai/gpt-5.4-pro", TEST_KEY);
        config.setDisplayName("Blackbox GPT-5.5 Pro");

        BlackboxPreflightResult result = validator(endpoint, 500).validate(config);

        assertEquals(BlackboxPreflightStatus.PASSED, result.status());
        assertEquals(1, result.configWarnings().size());
        assertTrue(result.configWarnings().getFirst().contains("GPT-5.5 Pro"));
    }

    private void assertFailure(int status,
                               String response,
                               BlackboxPreflightFailureCategory expected) throws Exception {
        String endpoint = startServer(status, response, 0, new AtomicReference<>());
        BlackboxPreflightResult result = validator(endpoint, 500).validate(
                model("blackbox-claude-sonnet", "blackboxai/anthropic/claude-sonnet-4.6", TEST_KEY));

        assertEquals(BlackboxPreflightStatus.FAILED, result.status());
        assertEquals(expected, result.failureCategory());
        assertFalse(result.safeMessage().contains(TEST_KEY));
        stopServer();
        server = null;
    }

    private BlackboxProviderPreflightValidator validator(String endpoint, int timeoutMs) {
        BlackboxProviderProperties properties = new BlackboxProviderProperties();
        properties.setBaseUrl(endpoint);
        properties.getPreflight().setTimeoutMs(timeoutMs);
        properties.getPreflight().setMaxTokens(8);
        return new BlackboxProviderPreflightValidator(properties, new ObjectMapper(), new RestClientFactory());
    }

    private String startServer(int status,
                               String response,
                               int delayMs,
                               AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }

    private BlackboxProviderProperties.ModelConfig model(String providerId, String model, String apiKey) {
        BlackboxProviderProperties.ModelConfig config = new BlackboxProviderProperties.ModelConfig();
        config.setEnabled(true);
        config.setProviderId(providerId);
        config.setDisplayName(providerId);
        config.setModel(model);
        config.setApiKey(apiKey);
        return config;
    }
}
