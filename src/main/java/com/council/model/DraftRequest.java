package com.council.model;

/**
 * Immutable request handed to each draft adapter.
 */
public record DraftRequest(
        String traceId,
        String userQuery,
        String role
) {
    public static DraftRequest of(String traceId, String userQuery) {
        return new DraftRequest(traceId, userQuery, "draft");
    }
}

