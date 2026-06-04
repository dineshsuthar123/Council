package com.council.designagent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Aggregate result of all 5 constraint checks.
 */
public record ConstraintReport(
        @JsonProperty("checks") List<ConstraintCheck> checks,
        @JsonProperty("allPass") boolean allPass,
        @JsonProperty("firstFailingConstraint") ConstraintCheck firstFailingConstraint
) {
    public ConstraintReport {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public static ConstraintReport from(List<ConstraintCheck> checks) {
        boolean allPass = true;
        ConstraintCheck firstFailing = null;
        List<ConstraintCheck> safeChecks = checks == null ? List.of() : checks;

        for (ConstraintCheck check : safeChecks) {
            if (check != null && !check.pass()) {
                allPass = false;
                firstFailing = check;
                break;
            }
        }

        return new ConstraintReport(safeChecks, allPass, firstFailing);
    }
}
