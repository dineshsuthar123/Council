package com.council.provider.blackbox;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Safe result of a Blackbox model preflight check.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlackboxPreflightResult(
        String providerId,
        String model,
        BlackboxPreflightStatus status,
        BlackboxPreflightFailureCategory failureCategory,
        String safeMessage,
        String checkedAt,
        Long latencyMs,
        List<String> configWarnings
) {
    public BlackboxPreflightResult {
        status = status == null ? BlackboxPreflightStatus.NOT_RUN : status;
        safeMessage = safeMessage == null || safeMessage.isBlank() ? null : safeMessage.trim();
        configWarnings = configWarnings == null ? List.of() : List.copyOf(configWarnings);
    }

    public static BlackboxPreflightResult notRun(String providerId, String model, List<String> warnings) {
        return new BlackboxPreflightResult(providerId, model, BlackboxPreflightStatus.NOT_RUN,
                null, "Preflight has not run.", null, null, warnings);
    }

    public static BlackboxPreflightResult skippedDisabled(String providerId, String model, List<String> warnings) {
        return new BlackboxPreflightResult(providerId, model, BlackboxPreflightStatus.SKIPPED_DISABLED,
                null, "Provider is disabled.", Instant.now().toString(), 0L, warnings);
    }

    public static BlackboxPreflightResult skippedNoKey(String providerId, String model, List<String> warnings) {
        return new BlackboxPreflightResult(providerId, model, BlackboxPreflightStatus.SKIPPED_NO_KEY,
                BlackboxPreflightFailureCategory.API_KEY_MISSING, "API key is not configured.",
                Instant.now().toString(), 0L, warnings);
    }

    public static BlackboxPreflightResult failedConfig(String providerId,
                                                       String model,
                                                       String message,
                                                       List<String> warnings) {
        return new BlackboxPreflightResult(providerId, model, BlackboxPreflightStatus.FAILED,
                BlackboxPreflightFailureCategory.CONFIG_INVALID, message,
                Instant.now().toString(), 0L, warnings);
    }

    public static BlackboxPreflightResult failed(String providerId,
                                                 String model,
                                                 BlackboxPreflightFailureCategory category,
                                                 String message,
                                                 long latencyMs,
                                                 List<String> warnings) {
        return new BlackboxPreflightResult(providerId, model, BlackboxPreflightStatus.FAILED,
                category == null ? BlackboxPreflightFailureCategory.UNKNOWN : category,
                message, Instant.now().toString(), Math.max(0L, latencyMs), warnings);
    }

    public static BlackboxPreflightResult passed(String providerId,
                                                 String model,
                                                 long latencyMs,
                                                 List<String> warnings) {
        return new BlackboxPreflightResult(providerId, model, BlackboxPreflightStatus.PASSED,
                null, "Preflight passed.", Instant.now().toString(), Math.max(0L, latencyMs), warnings);
    }
}
