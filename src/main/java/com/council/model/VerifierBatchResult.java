package com.council.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch verifier result containing one verdict per provider.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifierBatchResult(
        Map<String, VerifierVerdict> verdicts,
        String internalErrorReason
) {
    private static final String INTERNAL_ERROR_PREFIX = "VERIFIER_BATCH_INTERNAL_ERROR: ";

    public VerifierBatchResult {
        verdicts = verdicts == null ? Map.of() : Map.copyOf(verdicts);
    }

    public static VerifierBatchResult success(Map<String, VerifierVerdict> verdicts) {
        return new VerifierBatchResult(verdicts, null);
    }

    public static VerifierBatchResult passedFor(List<DraftResult> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return success(Map.of());
        }

        Map<String, VerifierVerdict> map = new LinkedHashMap<>();
        for (DraftResult draft : drafts) {
            map.put(draft.provider(), VerifierVerdict.passed());
        }
        return success(map);
    }

    public static VerifierBatchResult internalError(String message) {
        String reason = message == null || message.isBlank() ? "unknown" : message;
        return new VerifierBatchResult(Map.of(), INTERNAL_ERROR_PREFIX + reason);
    }

    public boolean isInternalError() {
        return internalErrorReason != null && internalErrorReason.startsWith(INTERNAL_ERROR_PREFIX);
    }

    public VerifierVerdict verdictForProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return VerifierVerdict.passed();
        }
        return verdicts.getOrDefault(provider, VerifierVerdict.passed());
    }
}