package com.council.provider.openai;

import com.council.common.exception.ProviderException;
import com.council.common.exception.ProviderFailureCategory;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
            this.restClient = restClientFactory.createWithTimeoutMillis(
                    config.getBaseUrl(),
                    config.getEffectiveTimeoutMillis(),
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
            throw new ProviderException(provider, provider + " adapter is not configured",
                    ProviderFailureCategory.DISABLED);
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
                    .onStatus(status -> status.value() == 401 || status.value() == 403, (req, resp) -> {
                        log.debug("[{}] HTTP {} authentication failure", provider, resp.getStatusCode());
                        throw new ProviderException(provider,
                                "Provider authentication failed (HTTP " + resp.getStatusCode().value() + ")",
                                ProviderFailureCategory.AUTH_FAILED, resp.getStatusCode().value(), null);
                    })
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        log.debug("[{}] HTTP 404 model or endpoint unavailable", provider);
                        throw new ProviderException(provider,
                                "Provider model or endpoint is unavailable (HTTP 404)",
                                ProviderFailureCategory.MODEL_NOT_FOUND_OR_UNAVAILABLE,
                                resp.getStatusCode().value(), null);
                    })
                    .onStatus(status -> status.value() == 400, (req, resp) -> {
                        boolean modelUnavailable = responseIndicatesModelUnavailable(resp.getBody());
                        ProviderFailureCategory category = modelUnavailable
                                ? ProviderFailureCategory.MODEL_NOT_FOUND_OR_UNAVAILABLE
                                : ProviderFailureCategory.BAD_REQUEST;
                        String message = modelUnavailable
                                ? "Provider model is unavailable (HTTP 400)"
                                : "Provider request rejected (HTTP 400)";
                        log.debug("[{}] HTTP 400 {}", provider,
                                modelUnavailable ? "model unavailable" : "bad request");
                        throw new ProviderException(provider, message, category, 400, null);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.debug("[{}] HTTP {} Server Error", provider, resp.getStatusCode());
                        throw new ProviderException(provider,
                                "Provider upstream server error (HTTP " + resp.getStatusCode().value() + ")",
                                ProviderFailureCategory.NETWORK_ERROR, resp.getStatusCode().value(), null);
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        log.debug("[{}] HTTP {} client error", provider, resp.getStatusCode());
                        throw new ProviderException(provider,
                                "Provider request rejected (HTTP " + resp.getStatusCode().value() + ")",
                                ProviderFailureCategory.BAD_REQUEST, resp.getStatusCode().value(), null);
                    })
                    .body(String.class);

            if (log.isDebugEnabled()) {
                log.debug("[{}] HTTP 200 OK response received", provider);
            }

            return extractText(responseBody);

        } catch (ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            ProviderFailureCategory category = classifyResourceAccess(e);
            String message = category == ProviderFailureCategory.TIMEOUT
                    ? "Provider request timed out"
                    : "Provider network request failed";
            throw new ProviderException(provider, message, category, e);
        } catch (Exception e) {
            throw new ProviderException(provider, "Unexpected provider transport failure",
                    ProviderFailureCategory.UNKNOWN, e);
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
                String content = choices.get(0).path("message").path("content").asText("");
                if (!content.isBlank()) {
                    return content;
                }
                throw new ProviderException(provider, "Provider returned an empty response",
                        ProviderFailureCategory.EMPTY_RESPONSE);
            }
            throw new ProviderException(provider, "Provider returned no choices",
                    ProviderFailureCategory.BAD_RESPONSE_SCHEMA);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(provider, "Provider returned an invalid response",
                    ProviderFailureCategory.BAD_RESPONSE_SCHEMA, e);
        }
    }

    protected ProviderFailureCategory classifyResourceAccess(ResourceAccessException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("timed out") || message.contains("timeout")
                ? ProviderFailureCategory.TIMEOUT
                : ProviderFailureCategory.NETWORK_ERROR;
    }

    private boolean responseIndicatesModelUnavailable(InputStream body) {
        if (body == null) {
            return false;
        }
        try {
            byte[] bytes = body.readNBytes(1024);
            String text = new String(bytes, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
            return text.contains("model not found") || text.contains("model unavailable")
                    || text.contains("model is not available") || text.contains("unknown model");
        } catch (IOException ignored) {
            return false;
        }
    }
}
