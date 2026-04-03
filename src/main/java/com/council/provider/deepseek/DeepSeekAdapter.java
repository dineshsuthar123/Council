package com.council.provider.deepseek;

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
 * Adapter for the DeepSeek API (OpenAI-compatible).
 */
@Component
public class DeepSeekAdapter extends AbstractLlmAdapter {

    private static final String PROVIDER = "deepseek";
    private final RestClient restClient;

    public DeepSeekAdapter(CouncilProperties properties,
                           ObjectMapper mapper,
                           JsonResponseNormalizer normalizer,
                           ResponseMapper responseMapper,
                           ProviderCallExecutor callExecutor,
                           OrchestrationMetrics metrics,
                           RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics);

        if (config.isUsable()) {
            this.restClient = restClientFactory.create(
                    config.getBaseUrl(),
                    config.getTimeoutSeconds(),
                    Map.of("Authorization", "Bearer " + config.getApiKey()));
        } else {
            this.restClient = null;
        }
    }

    @Override
    protected String callApi(String prompt) {
        if (restClient == null) {
            throw new ProviderException(PROVIDER, "DeepSeek adapter is not configured");
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", config.getModel(),
                    "max_tokens", config.getMaxTokens(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object")
            );

            String responseBody = restClient.post()
                    .uri("/v1/chat/completions")
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
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("");
            }
            throw new ProviderException(PROVIDER, "No choices in DeepSeek response");
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(PROVIDER, "Failed to parse DeepSeek response: " + e.getMessage(), e);
        }
    }
}


