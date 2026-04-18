package com.council.model;

/**
 * Request sent to the verifier model for a single draft.
 */
public record VerifierRequest(
        String traceId,
        String userQuery,
        DraftResult draft
) {}
