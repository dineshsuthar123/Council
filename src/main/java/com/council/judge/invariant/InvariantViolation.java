package com.council.judge.invariant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One invariant violation with evidence suitable for trace/debug output.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvariantViolation(
        InvariantDomain domain,
        String invariantId,
        String title,
        InvariantSeverity severity,
        double scoreCap,
        String evidence,
        String remediation
) {
    public static InvariantViolation of(InvariantDefinition definition,
                                        String evidence,
                                        String remediation) {
        return new InvariantViolation(
                definition.domain(),
                definition.id(),
                definition.title(),
                definition.severity(),
                definition.scoreCap(),
                evidence,
                remediation);
    }
}
