package com.council.judge.mutation;

import com.council.judge.invariant.InvariantDomain;

import java.util.List;

/**
 * Controlled mutation of a hard evaluation prompt.
 */
public record ScenarioMutation(
        String id,
        InvariantDomain domain,
        String mutationType,
        String prompt,
        double goldenMaxScore,
        List<String> expectedInvariantIds
) {
    public ScenarioMutation {
        expectedInvariantIds = expectedInvariantIds == null ? List.of() : List.copyOf(expectedInvariantIds);
    }
}
