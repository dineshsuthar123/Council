package com.council.provider.ollama;

import com.council.config.RestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OllamaModelAvailabilityServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void tagsWithModelPresentReportsInstalled() throws Exception {
        String baseUrl = startTagsServer("""
                {"models":[{"name":"qwen2.5-coder:7b"},{"model":"llama3.1:8b"}]}
                """);
        OllamaModelAvailability result = service(baseUrl).check(model("ollama-qwen-coder",
                "qwen2.5-coder:7b", true));

        assertEquals(OllamaAvailabilityStatus.AVAILABLE, result.status());
        assertTrue(result.reachable());
        assertTrue(result.modelInstalled());
        assertTrue(result.available());
        assertNull(result.remediation());
    }

    @Test
    void tagsWithoutModelReportsMissingWithRemediation() throws Exception {
        String baseUrl = startTagsServer("""
                {"models":[{"name":"llama3.1:8b"}]}
                """);
        OllamaModelAvailability result = service(baseUrl).check(model("ollama-deepseek",
                "deepseek-r1:8b", true));

        assertEquals(OllamaAvailabilityStatus.MODEL_NOT_INSTALLED, result.status());
        assertTrue(result.reachable());
        assertFalse(result.modelInstalled());
        assertEquals("ollama pull deepseek-r1:8b", result.remediation());
        assertTrue(result.safeMessage().contains("ollama pull deepseek-r1:8b"));
    }

    @Test
    void unreachableOllamaReportsNotRunning() {
        OllamaModelAvailability result = service("http://127.0.0.1:1")
                .check(model("ollama-llama", "llama3.1:8b", true));

        assertEquals(OllamaAvailabilityStatus.OLLAMA_NOT_RUNNING, result.status());
        assertFalse(result.reachable());
        assertFalse(result.modelInstalled());
        assertTrue(result.remediation().contains("OLLAMA_BASE_URL"));
    }

    @Test
    void disabledProviderReportsDisabledWithoutNetwork() {
        OllamaModelAvailability result = service("http://127.0.0.1:1")
                .check(model("ollama-gemma", "gemma3:4b", false));

        assertEquals(OllamaAvailabilityStatus.DISABLED, result.status());
        assertFalse(result.enabled());
        assertFalse(result.reachable());
    }

    private OllamaModelAvailabilityService service(String baseUrl) {
        OllamaProviderProperties properties = new OllamaProviderProperties();
        properties.setBaseUrl(baseUrl);
        properties.getPreflight().setTimeoutMs(500);
        return new OllamaModelAvailabilityService(properties, new ObjectMapper(), new RestClientFactory());
    }

    private OllamaProviderProperties.ModelConfig model(String providerId, String model, boolean enabled) {
        OllamaProviderProperties.ModelConfig config = new OllamaProviderProperties.ModelConfig();
        config.setProviderId(providerId);
        config.setModel(model);
        config.setEnabled(enabled);
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
