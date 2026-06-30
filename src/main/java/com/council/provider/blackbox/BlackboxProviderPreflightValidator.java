package com.council.provider.blackbox;

import com.council.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight Blackbox model/config validation. Live calls are bounded and
 * disabled by default through configuration.
 */
@Component
public class BlackboxProviderPreflightValidator {

    private static final String PROMPT = "Reply with OK.";

    private final BlackboxProviderProperties properties;
    private final ObjectMapper mapper;
    private final RestClientFactory restClientFactory;

    public BlackboxProviderPreflightValidator(BlackboxProviderProperties properties,
                                              ObjectMapper mapper,
                                              RestClientFactory restClientFactory) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClientFactory = restClientFactory;
    }

    public BlackboxPreflightResult initialStatus(BlackboxProviderProperties.ModelConfig config) {
        List<String> warnings = configWarnings(config);
        String providerId = safeProviderId(config);
        String model = safeModel(config);
        boolean hasKey = hasText(config.getApiKey());
        boolean logicalEnabled = config.isEnabled() || hasKey;
        if (!logicalEnabled) {
            return BlackboxPreflightResult.skippedDisabled(providerId, model, warnings);
        }
        if (!hasText(config.getModel())) {
            return BlackboxPreflightResult.failedConfig(providerId, model,
                    "Model id is blank; provider will not receive traffic.", warnings);
        }
        if (!hasKey) {
            return BlackboxPreflightResult.skippedNoKey(providerId, model, warnings);
        }
        return BlackboxPreflightResult.notRun(providerId, model, warnings);
    }

    public BlackboxPreflightResult validate(BlackboxProviderProperties.ModelConfig config) {
        List<String> warnings = configWarnings(config);
        String providerId = safeProviderId(config);
        String model = safeModel(config);
        boolean hasKey = hasText(config.getApiKey());
        boolean logicalEnabled = config.isEnabled() || hasKey;
        if (!logicalEnabled) {
            return BlackboxPreflightResult.skippedDisabled(providerId, model, warnings);
        }
        if (!hasText(config.getModel())) {
            return BlackboxPreflightResult.failedConfig(providerId, model,
                    "Model id is blank; provider will not receive traffic.", warnings);
        }
        if (!hasKey) {
            return BlackboxPreflightResult.skippedNoKey(providerId, model, warnings);
        }

        long start = System.currentTimeMillis();
        try {
            RestClient client = restClientFactory.createWithTimeoutMillis(
                    properties.getBaseUrl(),
                    properties.getPreflight().getTimeoutMs(),
                    Map.of("Authorization", "Bearer " + config.getApiKey()));

            String response = client.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(preflightBody(config.getModel()))
                    .retrieve()
                    .onStatus(status -> status.value() == 400, (req, resp) -> {
                        String body = safeBody(resp.getBody());
                        BlackboxPreflightFailureCategory category = bodyLooksModelRelated(body)
                                ? BlackboxPreflightFailureCategory.MODEL_NOT_FOUND_OR_UNAVAILABLE
                                : BlackboxPreflightFailureCategory.BAD_REQUEST_MODEL_CONFIG;
                        throw new PreflightFailure(category, 400, messageForModelConfig(config, category));
                    })
                    .onStatus(status -> status.value() == 401 || status.value() == 403, (req, resp) -> {
                        throw new PreflightFailure(BlackboxPreflightFailureCategory.AUTH_FAILED,
                                resp.getStatusCode().value(),
                                "Blackbox authentication failed; check the configured key.");
                    })
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        throw new PreflightFailure(BlackboxPreflightFailureCategory.RATE_LIMITED, 429,
                                "Blackbox preflight was rate limited.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new PreflightFailure(BlackboxPreflightFailureCategory.NETWORK_ERROR,
                                resp.getStatusCode().value(),
                                "Blackbox upstream returned a server error during preflight.");
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw new PreflightFailure(BlackboxPreflightFailureCategory.BAD_REQUEST_MODEL_CONFIG,
                                resp.getStatusCode().value(),
                                "Blackbox rejected the preflight request configuration.");
                    })
                    .body(String.class);

            validateResponseSchema(response);
            return BlackboxPreflightResult.passed(providerId, model,
                    System.currentTimeMillis() - start, warnings);
        } catch (PreflightFailure error) {
            return BlackboxPreflightResult.failed(providerId, model, error.category,
                    error.getMessage(), System.currentTimeMillis() - start, warnings);
        } catch (ResourceAccessException error) {
            BlackboxPreflightFailureCategory category = classifyResourceAccess(error);
            String message = category == BlackboxPreflightFailureCategory.TIMEOUT
                    ? "Blackbox preflight timed out."
                    : "Blackbox preflight network request failed.";
            return BlackboxPreflightResult.failed(providerId, model, category, message,
                    System.currentTimeMillis() - start, warnings);
        } catch (Exception error) {
            return BlackboxPreflightResult.failed(providerId, model, BlackboxPreflightFailureCategory.UNKNOWN,
                    "Blackbox preflight failed unexpectedly.", System.currentTimeMillis() - start, warnings);
        }
    }

    public static List<String> configWarnings(BlackboxProviderProperties.ModelConfig config) {
        List<String> warnings = new ArrayList<>();
        String providerId = lower(config.getProviderId());
        String displayName = lower(config.getDisplayName());
        String model = lower(config.getModel());
        boolean suggestsGpt55Pro = providerId.contains("gpt55-pro")
                || displayName.contains("gpt-5.5 pro")
                || displayName.contains("gpt 5.5 pro");
        if (suggestsGpt55Pro && model.contains("gpt-5.4-pro")) {
            warnings.add("Provider id/display name suggests GPT-5.5 Pro but configured model is GPT-5.4 Pro.");
        }
        return List.copyOf(warnings);
    }

    private Map<String, Object> preflightBody(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", PROMPT)));
        body.put("temperature", 0);
        body.put("max_tokens", properties.getPreflight().getMaxTokens());
        return body;
    }

    private void validateResponseSchema(String response) {
        try {
            JsonNode choices = mapper.readTree(response).path("choices");
            if (!choices.isArray()) {
                throw new PreflightFailure(BlackboxPreflightFailureCategory.BAD_RESPONSE_SCHEMA, null,
                        "Blackbox preflight response did not include a choices array.");
            }
            if (choices.isEmpty()) {
                throw new PreflightFailure(BlackboxPreflightFailureCategory.BAD_RESPONSE_SCHEMA, null,
                        "Blackbox preflight response included no choices.");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new PreflightFailure(BlackboxPreflightFailureCategory.EMPTY_RESPONSE, null,
                        "Blackbox preflight response content was empty.");
            }
        } catch (PreflightFailure error) {
            throw error;
        } catch (Exception error) {
            throw new PreflightFailure(BlackboxPreflightFailureCategory.BAD_RESPONSE_SCHEMA, null,
                    "Blackbox preflight response could not be parsed.");
        }
    }

    private BlackboxPreflightFailureCategory classifyResourceAccess(ResourceAccessException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("timed out") || message.contains("timeout")
                ? BlackboxPreflightFailureCategory.TIMEOUT
                : BlackboxPreflightFailureCategory.NETWORK_ERROR;
    }

    private String messageForModelConfig(BlackboxProviderProperties.ModelConfig config,
                                         BlackboxPreflightFailureCategory category) {
        String model = lower(config.getModel());
        if (model.contains("claude-opus-4.8") || model.contains("claude-sonnet-4.6")) {
            return "Model may be unavailable for this account or model ID may be invalid.";
        }
        if (category == BlackboxPreflightFailureCategory.MODEL_NOT_FOUND_OR_UNAVAILABLE) {
            return "Model may be unavailable for this account or model ID may be invalid.";
        }
        return "Blackbox rejected the model/request configuration.";
    }

    private static String safeBody(InputStream body) {
        if (body == null) {
            return "";
        }
        try {
            return new String(body.readNBytes(2048), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static boolean bodyLooksModelRelated(String body) {
        String text = lower(body);
        return text.contains("model not found")
                || text.contains("model unavailable")
                || text.contains("model is not available")
                || text.contains("unknown model")
                || text.contains("invalid model")
                || text.contains("unsupported model")
                || text.contains("model id");
    }

    private static String safeProviderId(BlackboxProviderProperties.ModelConfig config) {
        return hasText(config.getProviderId()) ? config.getProviderId() : "blackbox-unknown";
    }

    private static String safeModel(BlackboxProviderProperties.ModelConfig config) {
        return hasText(config.getModel()) ? config.getModel() : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final class PreflightFailure extends RuntimeException {
        private final BlackboxPreflightFailureCategory category;

        private PreflightFailure(BlackboxPreflightFailureCategory category,
                                 Integer httpStatus,
                                 String message) {
            super(message == null || message.isBlank()
                    ? "Blackbox preflight failed." : message);
            this.category = category == null ? BlackboxPreflightFailureCategory.UNKNOWN : category;
        }
    }
}
