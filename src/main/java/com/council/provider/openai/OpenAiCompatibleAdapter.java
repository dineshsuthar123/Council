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
import com.fasterxml.jackson.core.JsonProcessingException;
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
 * <p>
 * Subclasses only need to provide their provider name and any custom headers.
 * The request body and response parsing follow the standard format:
 * <ul>
 *   <li>POST /v1/chat/completions</li>
 *   <li>Bearer token auth</li>
 *   <li>Response: choices[0].message.content</li>
 * </ul>
 *
 * <p><b>Debug Logging:</b> Set {@code logging.level.com.council.provider = DEBUG} in
 * {@code application.yml} to emit the full serialized request body, masked Authorization
 * header, and the raw HTTP response status + body (trimmed to 2 000 characters).
 */
public abstract class OpenAiCompatibleAdapter extends AbstractLlmAdapter {

    private final RestClient restClient;
    /** Stored only for masked logging — never exposed in plain text. */
    private final String maskedKey;

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
            this.maskedKey = maskKey(apiKey);

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
            this.maskedKey = "***";
        }
    }

    /**
     * Override to customise the API endpoint path. Default: /v1/chat/completions
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

            // ── DEBUG: log outbound request ──────────────────────────────────
            if (log.isDebugEnabled()) {
                String bodyJson = serializeForLog(body);
                log.debug("[{}] → POST {}{} | Authorization: Bearer {} | body: {}",
                        provider,
                        config.getBaseUrl(),
                        chatCompletionsPath(),
                        maskedKey,
                        bodyJson);
            }

            String responseBody = restClient.post()
                    .uri(chatCompletionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        log.debug("[{}] ← HTTP 429 Rate-Limited", provider);
                        throw new RateLimitException(provider);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.debug("[{}] ← HTTP {} Server Error", provider, resp.getStatusCode());
                        throw new ProviderException(provider,
                                "Server error " + resp.getStatusCode());
                    })
                    .body(String.class);

            // ── DEBUG: log raw response ──────────────────────────────────────
            if (log.isDebugEnabled()) {
                String preview = responseBody == null ? "<null>"
                        : (responseBody.length() > 2000
                        ? responseBody.substring(0, 2000) + " … [truncated]"
                        : responseBody);
                log.debug("[{}] ← HTTP 200 OK | body: {}", provider, preview);
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
     * Extract text from OpenAI-compatible response: choices[0].message.content
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

    // ── helpers ─────────────────────────────────────────────────────────────

    private String serializeForLog(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "<serialization-failed: " + e.getMessage() + ">";
        }
    }

    /**
     * Masks an API key, showing only the first 8 and last 4 characters.
     * e.g. {@code nvapi-rZd9Is...b7i6}
     */
    private static String maskKey(String key) {
        if (key == null || key.length() < 14) return "***";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
