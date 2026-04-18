package com.council.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured verifier verdict for a single draft.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifierResult(
        boolean containsFatalMathError,
        boolean containsConsistencyViolation,
        String fatalErrorReason
) {
    private static final String INTERNAL_ERROR_PREFIX = "VERIFIER_INTERNAL_ERROR: ";

    public static VerifierResult passed() {
        return new VerifierResult(false, false, null);
    }

    public static VerifierResult disqualifiedMath(String reason) {
        return new VerifierResult(true, false, reason);
    }

    public static VerifierResult disqualifiedConsistency(String reason) {
        return new VerifierResult(false, true, reason);
    }

    public static VerifierResult internalError(String message) {
        String reason = message == null || message.isBlank()
                ? "unknown"
                : message;
        return new VerifierResult(false, false, INTERNAL_ERROR_PREFIX + reason);
    }

    public boolean isDisqualified() {
        return containsFatalMathError || containsConsistencyViolation;
    }

    public boolean isInternalError() {
        return fatalErrorReason != null && fatalErrorReason.startsWith(INTERNAL_ERROR_PREFIX);
    }
}
