package com.council.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskAwareWeightsTest {

    @Test
    @DisplayName("SYSTEM_DESIGN weights de-emphasise confidence, emphasise specificity")
    void systemDesignWeights() {
        TaskAwareWeights w = TaskAwareWeights.forTask(TaskType.SYSTEM_DESIGN);

        assertTrue(w.wConfidence() < 0.25,
                "SYSTEM_DESIGN confidence weight should be < 0.25, got " + w.wConfidence());
        assertTrue(w.wSpecificity() >= 0.25,
                "SYSTEM_DESIGN specificity weight should be >= 0.25, got " + w.wSpecificity());
        assertTrue(w.wGenericnessPenalty() >= 0.10,
                "SYSTEM_DESIGN genericness penalty weight should be >= 0.10, got " + w.wGenericnessPenalty());

        assertEquals(1.0, w.sum(), 0.01, "Weights must sum to ~1.0");
    }

    @Test
    @DisplayName("BACKEND_ARCHITECTURE weights similar to SYSTEM_DESIGN but slightly less extreme")
    void backendArchitectureWeights() {
        TaskAwareWeights w = TaskAwareWeights.forTask(TaskType.BACKEND_ARCHITECTURE);
        TaskAwareWeights sd = TaskAwareWeights.forTask(TaskType.SYSTEM_DESIGN);

        assertTrue(w.wConfidence() >= sd.wConfidence(),
                "BACKEND_ARCHITECTURE should have >= confidence weight than SYSTEM_DESIGN");
        assertTrue(w.wSpecificity() > 0.15,
                "BACKEND_ARCHITECTURE specificity weight should be > 0.15");

        assertEquals(1.0, w.sum(), 0.01, "Weights must sum to ~1.0");
    }

    @Test
    @DisplayName("DEBUGGING weights emphasise specificity, moderate confidence")
    void debuggingWeights() {
        TaskAwareWeights w = TaskAwareWeights.forTask(TaskType.DEBUGGING);

        assertTrue(w.wSpecificity() >= 0.25,
                "DEBUGGING should heavily weight specificity, got " + w.wSpecificity());

        assertEquals(1.0, w.sum(), 0.01, "Weights must sum to ~1.0");
    }

    @Test
    @DisplayName("CODING weights keep confidence high, specificity low")
    void codingWeights() {
        TaskAwareWeights w = TaskAwareWeights.forTask(TaskType.CODING);

        assertTrue(w.wConfidence() >= 0.35,
                "CODING confidence weight should be >= 0.35, got " + w.wConfidence());
        assertTrue(w.wSpecificity() <= 0.10,
                "CODING specificity weight should be <= 0.10, got " + w.wSpecificity());

        assertEquals(1.0, w.sum(), 0.01, "Weights must sum to ~1.0");
    }

    @Test
    @DisplayName("GENERAL_REASONING is balanced")
    void generalReasoningWeights() {
        TaskAwareWeights w = TaskAwareWeights.forTask(TaskType.GENERAL_REASONING);

        assertTrue(w.wConfidence() >= 0.30,
                "GENERAL_REASONING confidence should be >= 0.30");
        assertTrue(w.wSpecificity() <= 0.15,
                "GENERAL_REASONING specificity should be moderate");

        assertEquals(1.0, w.sum(), 0.01, "Weights must sum to ~1.0");
    }

    @Test
    @DisplayName("All task types have weights summing to 1.0")
    void allWeightsSum() {
        for (TaskType t : TaskType.values()) {
            TaskAwareWeights w = TaskAwareWeights.forTask(t);
            assertEquals(1.0, w.sum(), 0.01,
                    "Weights for " + t + " must sum to ~1.0, got " + w.sum());
        }
    }
}

