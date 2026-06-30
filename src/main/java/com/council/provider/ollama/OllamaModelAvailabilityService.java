package com.council.provider.ollama;

import com.council.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight local Ollama availability probe. It uses /api/tags and never runs generation.
 */
@Component
public class OllamaModelAvailabilityService {

    private final OllamaProviderProperties properties;
    private final ObjectMapper mapper;
    private final RestClientFactory restClientFactory;

    public OllamaModelAvailabilityService(OllamaProviderProperties properties,
                                          ObjectMapper mapper,
                                          RestClientFactory restClientFactory) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClientFactory = restClientFactory;
    }

    public OllamaModelAvailability check(OllamaProviderProperties.ModelConfig modelConfig) {
        String providerId = safeProviderId(modelConfig);
        String model = safeModel(modelConfig);
        if (!properties.isEnabled() || modelConfig == null || !modelConfig.isEnabled()) {
            return OllamaModelAvailability.disabled(providerId, model);
        }
        if (model.isBlank()) {
            return new OllamaModelAvailability(providerId, model, true, false, false,
                    OllamaAvailabilityStatus.BAD_RESPONSE_SCHEMA,
                    "Ollama model name is blank.", null, List.of());
        }

        try {
            RestClient restClient = restClientFactory.createWithTimeoutMillis(
                    properties.getBaseUrl(), effectiveTimeoutMs(), Map.of());
            String body = restClient.get().uri("/api/tags").retrieve().body(String.class);
            List<String> installed = parseInstalledModels(body);
            boolean installedModel = installed.stream().anyMatch(name -> modelMatches(name, model));
            if (!installedModel) {
                return OllamaModelAvailability.notInstalled(providerId, model, installed);
            }
            return new OllamaModelAvailability(providerId, model, true, true, true,
                    OllamaAvailabilityStatus.AVAILABLE, "Ollama model is installed.",
                    null, installed);
        } catch (ResourceAccessException error) {
            OllamaAvailabilityStatus status = classifyResourceAccess(error);
            String message = switch (status) {
                case TIMEOUT -> "Ollama /api/tags check timed out.";
                case OLLAMA_NOT_RUNNING -> "Ollama is not reachable. Start Ollama and verify the base URL.";
                default -> "Ollama /api/tags network check failed.";
            };
            return new OllamaModelAvailability(providerId, model, true, false, false,
                    status, message, remediationForRuntime(status), List.of());
        } catch (Exception error) {
            return new OllamaModelAvailability(providerId, model, true, false, false,
                    OllamaAvailabilityStatus.UNKNOWN, "Ollama /api/tags check failed.",
                    "Verify Ollama is running and OLLAMA_BASE_URL is correct.", List.of());
        }
    }

    private List<String> parseInstalledModels(String body) {
        try {
            JsonNode root = mapper.readTree(body == null ? "" : body);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode modelNode : models) {
                String name = firstNonBlank(
                        modelNode.path("name").asText(""),
                        modelNode.path("model").asText(""));
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        } catch (Exception error) {
            return List.of();
        }
    }

    static OllamaAvailabilityStatus classifyResourceAccess(ResourceAccessException error) {
        String message = exceptionFingerprint(error);
        if (message.contains("timed out") || message.contains("timeout")) {
            return OllamaAvailabilityStatus.TIMEOUT;
        }
        if (message.contains("interrupted")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("operation interrupted")) {
            return OllamaAvailabilityStatus.TIMEOUT;
        }
        if (message.contains("connection refused")
                || message.contains("connectexception")
                || message.contains("failed to connect")
                || message.contains("connect failed")
                || message.contains("connection reset")) {
            return OllamaAvailabilityStatus.OLLAMA_NOT_RUNNING;
        }
        return OllamaAvailabilityStatus.NETWORK_ERROR;
    }

    private static String exceptionFingerprint(Throwable error) {
        StringBuilder fingerprint = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            fingerprint.append(current.getClass().getName()).append(' ');
            if (current.getMessage() != null) {
                fingerprint.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return fingerprint.toString().toLowerCase(Locale.ROOT);
    }

    private int effectiveTimeoutMs() {
        int configured = properties.getPreflight().getTimeoutMs();
        if (configured > 0) {
            return configured;
        }
        return properties.getDefaults().getTimeoutMs();
    }

    private String remediationForRuntime(OllamaAvailabilityStatus status) {
        if (status == OllamaAvailabilityStatus.OLLAMA_NOT_RUNNING) {
            return "Start Ollama and verify OLLAMA_BASE_URL=" + properties.getBaseUrl();
        }
        if (status == OllamaAvailabilityStatus.TIMEOUT) {
            return "Increase OLLAMA_PREFLIGHT_TIMEOUT_MS or verify Ollama is responsive.";
        }
        return "Verify Ollama is reachable at " + properties.getBaseUrl();
    }

    private static boolean modelMatches(String installed, String configured) {
        return installed != null && configured != null
                && installed.trim().equalsIgnoreCase(configured.trim());
    }

    private static String safeProviderId(OllamaProviderProperties.ModelConfig config) {
        return config == null || config.getProviderId() == null ? "" : config.getProviderId();
    }

    private static String safeModel(OllamaProviderProperties.ModelConfig config) {
        return config == null || config.getModel() == null ? "" : config.getModel();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }
}
