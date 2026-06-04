package com.council.model;

import java.util.List;

/**
 * Request sent to the verifier model for batch validation of successful drafts.
 */
public record VerifierBatchRequest(
        String traceId,
        String userQuery,
        List<DraftResult> drafts
) {
    public VerifierBatchRequest {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
    }
}