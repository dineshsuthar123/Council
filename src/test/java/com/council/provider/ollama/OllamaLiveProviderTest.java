package com.council.provider.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Optional free local-provider smoke test. It is disabled unless
 * -Dlocal.provider.tests=true is supplied.
 */
@EnabledIfSystemProperty(named = "local.provider.tests", matches = "true")
class OllamaLiveProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void localOllamaChatReturnsNonEmptyAnswerWhenModelIsInstalled() throws Exception {
        String baseUrl = systemOrEnv("ollama.base-url", "OLLAMA_BASE_URL", "http://localhost:11434");
        String model = systemOrEnv("ollama.model", "OLLAMA_QWEN_CODER_MODEL", "qwen2.5-coder:7b");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpResponse<String> tags = send(client, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
        Assumptions.assumeTrue(tags.statusCode() >= 200 && tags.statusCode() < 300,
                "Ollama is not reachable at " + baseUrl);
        Assumptions.assumeTrue(modelInstalled(tags.body(), model),
                "Model " + model + " is not installed. Run: ollama pull " + model);

        String body = MAPPER.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "Reply with one concise sentence."),
                        Map.of("role", "user", "content", "Reply OK")
                ),
                "stream", false,
                "options", Map.of(
                        "temperature", 0.0,
                        "num_predict", 16
                )
        ));
        HttpResponse<String> chat = send(client, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        Assumptions.assumeTrue(chat.statusCode() >= 200 && chat.statusCode() < 300,
                "Ollama chat failed with HTTP " + chat.statusCode());

        String content = MAPPER.readTree(chat.body()).path("message").path("content").asText("");
        assertFalse(content.isBlank(), "Ollama returned an empty assistant message");
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            Assumptions.abort("Ollama is not reachable: " + error.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    private static boolean modelInstalled(String body, String model) throws Exception {
        JsonNode models = MAPPER.readTree(body == null ? "{}" : body).path("models");
        if (!models.isArray()) {
            return false;
        }
        for (JsonNode node : models) {
            String name = firstNonBlank(node.path("name").asText(""), node.path("model").asText(""));
            if (name.equalsIgnoreCase(model)) {
                return true;
            }
        }
        return false;
    }

    private static String systemOrEnv(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
