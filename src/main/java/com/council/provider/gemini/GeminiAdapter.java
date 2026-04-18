package com.council.provider.gemini;

import com.council.common.exception.ProviderException;
import com.council.common.exception.RateLimitException;
import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.AbstractLlmAdapter;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Adapter for the Google Gemini (Generative Language) API.
 */
@Component
public class GeminiAdapter extends AbstractLlmAdapter {

    private static final String PROVIDER = "gemini";
    private final RestClient restClient;
    private final String apiKey;

    public GeminiAdapter(CouncilProperties properties,
                         ObjectMapper mapper,
                         JsonResponseNormalizer normalizer,
                         ResponseMapper responseMapper,
                         ProviderCallExecutor callExecutor,
                         OrchestrationMetrics metrics,
                         RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics);
        this.apiKey = config.getApiKey();

        if (config.isUsable()) {
            this.restClient = restClientFactory.create(
                    config.getBaseUrl(),
                    config.getTimeoutSeconds(),
                    Map.of());
        } else {
            this.restClient = null;
        }
    }

    @Override
    protected String callApi(String prompt) {
        if (restClient == null) {
            throw new ProviderException(PROVIDER, "Gemini adapter is not configured");
        }
        try {
            String jsonOnlyInstruction = "You must output raw, unformatted JSON. Do NOT wrap your response in markdown blocks like ```json.";
            String geminiPrompt = jsonOnlyInstruction + "\n\n" + prompt;

            Map<String, Object> body = Map.of(
                    "contents", List.of(
                    Map.of("parts", List.of(Map.of("text", geminiPrompt)))
                    ),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "maxOutputTokens", config.getMaxTokens()
                    )
            );

            String uri = "/v1beta/models/" + config.getModel() + ":generateContent?key=" + apiKey;

            String responseBody = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        throw new RateLimitException(PROVIDER);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new ProviderException(PROVIDER,
                                "Server error " + resp.getStatusCode());
                    })
                    .body(String.class);

            return extractText(responseBody);

        } catch (ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ProviderException(PROVIDER, "Network/timeout error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProviderException(PROVIDER, "Unexpected error: " + e.getMessage(), e);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText("");
                }
            }
            throw new ProviderException(PROVIDER, "No content in Gemini response");
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(PROVIDER, "Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }
}


