package com.council.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Verifier verdict for a single provider draft.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifierVerdict(
        boolean containsFatalMathError,
        boolean containsConsistencyViolation,
        boolean containsThroughputContradiction,
        String fatalErrorReason
) {

    public static VerifierVerdict passed() {
        return new VerifierVerdict(false, false, false, null);
    }

    public static VerifierVerdict disqualifiedMath(String reason) {
        return new VerifierVerdict(true, false, false, reason);
    }

    public static VerifierVerdict disqualifiedConsistency(String reason) {
        return new VerifierVerdict(false, true, false, reason);
    }

    public static VerifierVerdict disqualifiedThroughput(String reason) {
        return new VerifierVerdict(false, false, true, reason);
    }

    public boolean isDisqualified() {
        return containsFatalMathError || containsConsistencyViolation || containsThroughputContradiction;
    }
}