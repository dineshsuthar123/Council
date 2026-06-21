package com.council.provider;

/**
 * Safe provider configuration snapshot for operational endpoints.
 */
public record ProviderStatusDetails(
        String displayName,
        boolean configured,
        boolean enabled,
        boolean available,
        String baseUrl,
        String failureReason
) {
}
