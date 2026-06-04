package com.council.designagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single iteration in the self-correcting loop.
 * Records what failed, what was repaired, and the state after repair.
 */
public record RepairIteration(
        @JsonProperty("iteration") int iteration,
        @JsonProperty("failingConstraint") String failingConstraint,
        @JsonProperty("repairAction") String repairAction,
        @JsonProperty("stateAfter") PaymentDesign stateAfter
) {
    @JsonCreator
    public RepairIteration {
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be >= 1");
        }
        if (failingConstraint == null || failingConstraint.isBlank()) {
            throw new IllegalArgumentException("failingConstraint must be non-empty");
        }
        if (repairAction == null || repairAction.isBlank()) {
            throw new IllegalArgumentException("repairAction must be non-empty");
        }
        if (stateAfter == null) {
            throw new IllegalArgumentException("stateAfter must not be null");
        }
    }
}
