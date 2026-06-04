package com.council.model;

import java.util.List;

/**
 * Request sent to the synthesizer model to produce a single unified final answer.
 */
public record SynthesisRequest(
        String traceId,
        String userQuery,
        List<DraftResult> drafts,
        VerifierBatchResult verifierBatchResult,
        CriticResult criticResult
) {
    public SynthesisRequest {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        verifierBatchResult = verifierBatchResult == null
                ? VerifierBatchResult.success(java.util.Map.of())
                : verifierBatchResult;
    }
}
