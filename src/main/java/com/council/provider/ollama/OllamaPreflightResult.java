package com.council.provider.ollama;

import com.council.common.exception.ProviderFailureCategory;

import java.time.Instant;

public record OllamaPreflightResult(
        OllamaPreflightStatus status,
        ProviderFailureCategory failureCategory,
        String safeMessage,
        String checkedAt,
        Long latencyMs
) {
    public OllamaPreflightResult {
        status = status == null ? OllamaPreflightStatus.NOT_RUN : status;
    }

    public static OllamaPreflightResult notRun() {
        return new OllamaPreflightResult(OllamaPreflightStatus.NOT_RUN, null,
                "Live Ollama preflight has not been run.", null, null);
    }

    public static OllamaPreflightResult skippedDisabled() {
        return new OllamaPreflightResult(OllamaPreflightStatus.SKIPPED_DISABLED,
                ProviderFailureCategory.DISABLED, "Ollama provider is disabled.",
                Instant.now().toString(), null);
    }

    public static OllamaPreflightResult passed(long latencyMs) {
        return new OllamaPreflightResult(OllamaPreflightStatus.PASSED, null,
                "Ollama live preflight passed.", Instant.now().toString(), latencyMs);
    }

    public static OllamaPreflightResult failed(ProviderFailureCategory category, String message, long latencyMs) {
        return new OllamaPreflightResult(OllamaPreflightStatus.FAILED,
                category == null ? ProviderFailureCategory.UNKNOWN : category,
                message == null || message.isBlank() ? "Ollama live preflight failed." : message,
                Instant.now().toString(), latencyMs);
    }
}
