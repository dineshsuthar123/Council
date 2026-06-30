package com.council.provider.ollama;

import java.util.List;

/**
 * Safe snapshot of local Ollama runtime/model availability.
 */
public record OllamaModelAvailability(
        String providerId,
        String model,
        boolean enabled,
        boolean reachable,
        boolean modelInstalled,
        OllamaAvailabilityStatus status,
        String safeMessage,
        String remediation,
        List<String> installedModels
) {
    public OllamaModelAvailability {
        status = status == null ? OllamaAvailabilityStatus.UNKNOWN : status;
        installedModels = installedModels == null ? List.of() : List.copyOf(installedModels);
    }

    public boolean available() {
        return enabled && reachable && modelInstalled && status == OllamaAvailabilityStatus.AVAILABLE;
    }

    public static OllamaModelAvailability disabled(String providerId, String model) {
        return new OllamaModelAvailability(providerId, model, false, false, false,
                OllamaAvailabilityStatus.DISABLED, "Ollama provider is disabled.", null, List.of());
    }

    public static OllamaModelAvailability notInstalled(String providerId, String model, List<String> installed) {
        return new OllamaModelAvailability(providerId, model, true, true, false,
                OllamaAvailabilityStatus.MODEL_NOT_INSTALLED,
                "Model " + model + " is not installed. Run: ollama pull " + model,
                "ollama pull " + model,
                installed);
    }
}
