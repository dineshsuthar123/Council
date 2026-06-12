package com.council.provider.openai;

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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Base adapter for providers that use the OpenAI-compatible chat completions API.
 * Debug logs intentionally exclude prompt bodies, response bodies, API keys, and
 * Authorization headers.
 */
public abstract class OpenAiCompatibleAdapter extends AbstractLlmAdapter {

    private final RestClient restClient;

    protected OpenAiCompatibleAdapter(String provider,
                                      CouncilProperties properties,
                                      ObjectMapper mapper,
                                      JsonResponseNormalizer normalizer,
                                      ResponseMapper responseMapper,
                                      ProviderCallExecutor callExecutor,
                                      OrchestrationMetrics metrics,
                                      RestClientFactory restClientFactory,
                                      Map<String, String> extraHeaders) {
        super(provider, properties, mapper, normalizer, responseMapper, callExecutor, metrics);

        if (config.isUsable()) {
            String apiKey = config.getApiKey();
            Map<String, String> headers = new java.util.HashMap<>(
                    Map.of("Authorization", "Bearer " + apiKey));
            if (extraHeaders != null) {
                headers.putAll(extraHeaders);
            }
            this.restClient = restClientFactory.create(
                    config.getBaseUrl(),
                    config.getTimeoutSeconds(),
                    headers);
        } else {
            this.restClient = null;
        }
    }

    /**
     * Override to customise the API endpoint path. Default: /v1/chat/completions.
     */
    protected String chatCompletionsPath() {
        return "/v1/chat/completions";
    }

    /**
     * Override to customise the request body. Default: standard OpenAI chat format with JSON response.
     */
    protected Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "model", config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", Map.of("type", "json_object")
        );
    }

    @Override
    protected String callApi(String prompt) {
        if (restClient == null) {
            throw new ProviderException(provider, provider + " adapter is not configured");
        }
        try {
            Map<String, Object> body = buildRequestBody(prompt);

            if (log.isDebugEnabled()) {
                log.debug("[{}] POST {}{} model={} maxTokens={}",
                        provider,
                        config.getBaseUrl(),
                        chatCompletionsPath(),
                        config.getModel(),
                        config.getMaxTokens());
            }

            String responseBody = restClient.post()
                    .uri(chatCompletionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        log.debug("[{}] HTTP 429 Rate-Limited", provider);
                        throw new RateLimitException(provider);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.debug("[{}] HTTP {} Server Error", provider, resp.getStatusCode());
                        throw new ProviderException(provider,
                                "Server error " + resp.getStatusCode());
                    })
                    .body(String.class);

            if (log.isDebugEnabled()) {
                log.debug("[{}] HTTP 200 OK response received", provider);
            }

            return extractText(responseBody);

        } catch (ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ProviderException(provider, "Network/timeout error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProviderException(provider, "Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Extract text from OpenAI-compatible response: choices[0].message.content.
     */
    protected String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("");
            }
            throw new ProviderException(provider, "No choices in " + provider + " response");
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(provider,
                    "Failed to parse " + provider + " response: " + e.getMessage(), e);
        }
    }
}
