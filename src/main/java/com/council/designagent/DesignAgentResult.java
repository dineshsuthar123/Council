package com.council.designagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Final output envelope from the self-correcting design agent.
 * <p>
 * {@code status} is "VALID" only when all constraints pass after ≤ maxIterations repairs.
 * {@code confidence} is 1.0 for VALID, 0.0 for NO_VALID_DESIGN.
 */
public record DesignAgentResult(
        @JsonProperty("status") String status,
        @JsonProperty("design") PaymentDesign design,
        @JsonProperty("constraintsCheck") ConstraintReport constraintsCheck,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("self_check_iterations") List<RepairIteration> selfCheckIterations
) {
    @JsonCreator
    public DesignAgentResult {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must be non-empty");
        }
        selfCheckIterations = selfCheckIterations == null ? List.of() : List.copyOf(selfCheckIterations);
    }

    public static DesignAgentResult valid(PaymentDesign design, ConstraintReport report, List<RepairIteration> iterations) {
        return new DesignAgentResult("VALID", design, report, 1.0, iterations);
    }

    public static DesignAgentResult noValidDesign(PaymentDesign design,
                                                  ConstraintReport report,
                                                  List<RepairIteration> iterations) {
        return new DesignAgentResult("NO_VALID_DESIGN", design, report, 0.0, iterations);
    }
}
