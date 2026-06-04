package com.council.designagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single constraint verification result.
 * <p>
 * {@code lhs} and {@code rhs} are the numeric values compared by the constraint.
 * {@code formula} is a human-readable explanation of how {@code rhs} was computed.
 */
public record ConstraintCheck(
        @JsonProperty("name") String name,
        @JsonProperty("lhs") double lhs,
        @JsonProperty("rhs") double rhs,
        @JsonProperty("pass") boolean pass,
        @JsonProperty("formula") String formula
) {
    @JsonCreator
    public ConstraintCheck {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-empty");
        }
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("formula must be non-empty");
        }
    }
}
