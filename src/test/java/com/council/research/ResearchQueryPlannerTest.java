package com.council.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchQueryPlannerTest {

    private final ResearchQueryPlanner planner = new ResearchQueryPlanner();

    @Test
    @DisplayName("Builds deterministic bounded queries")
    void buildsDeterministicBoundedQueries() {
        var queries = planner.plan("Latest model routing and LLM evaluation rubric best practices");

        assertFalse(queries.isEmpty());
        assertTrue(queries.size() <= 3);
        assertTrue(queries.getFirst().contains("Latest model routing"));
    }
}
