package com.council.provider;

import java.util.List;

/**
 * Safe provider configuration snapshot for operational endpoints.
 */
public record ProviderStatusDetails(
        String displayName,
        boolean configured,
        boolean enabled,
        boolean available,
        String baseUrl,
        String failureReason,
        Integer timeoutMsConfigured,
        String timeoutSource,
        List<String> configWarnings,
        String preflightStatus,
        String preflightFailureCategory,
        String preflightSafeMessage,
        String preflightCheckedAt,
        Long preflightLatencyMs
) {
    public ProviderStatusDetails(String displayName,
                                 boolean configured,
                                 boolean enabled,
                                 boolean available,
                                 String baseUrl,
                                 String failureReason) {
        this(displayName, configured, enabled, available, baseUrl, failureReason,
                null, null, List.of(), null, null, null, null, null);
    }

    public ProviderStatusDetails {
        configWarnings = configWarnings == null ? List.of() : List.copyOf(configWarnings);
    }
}
