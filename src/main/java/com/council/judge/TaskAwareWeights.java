package com.council.judge;

import java.util.Map;

/**
 * Task-aware scoring weights for the deterministic judge.
 * <p>
 * Different task types require different emphasis:
 * <ul>
 *   <li>SYSTEM_DESIGN: reduce confidence weight, increase specificity and critic penalties</li>
 *   <li>CODING: confidence remains high (correct answers self-evident)</li>
 *   <li>DEBUGGING: reward root-cause depth and realism</li>
 *   <li>BACKEND_ARCHITECTURE: similar to system design but slightly less extreme</li>
 *   <li>GENERAL_REASONING: default balanced weights</li>
 * </ul>
 */
public record TaskAwareWeights(
        double wConfidence,
        double wReliability,
        double wContradiction,
        double wSpecificity,
        double wGenericnessPenalty
) {

    private static final Map<TaskType, TaskAwareWeights> WEIGHT_MAP = Map.of(
            TaskType.SYSTEM_DESIGN,
            new TaskAwareWeights(0.15, 0.15, 0.25, 0.30, 0.15),

            TaskType.BACKEND_ARCHITECTURE,
            new TaskAwareWeights(0.20, 0.15, 0.25, 0.25, 0.15),

            TaskType.DEBUGGING,
            new TaskAwareWeights(0.20, 0.15, 0.20, 0.30, 0.15),

            TaskType.CODING,
            new TaskAwareWeights(0.40, 0.25, 0.25, 0.05, 0.05),

            TaskType.GENERAL_REASONING,
            new TaskAwareWeights(0.35, 0.25, 0.25, 0.10, 0.05)
    );

    /**
     * Get the scoring weights appropriate for the given task type.
     */
    public static TaskAwareWeights forTask(TaskType taskType) {
        return WEIGHT_MAP.getOrDefault(taskType,
                WEIGHT_MAP.get(TaskType.GENERAL_REASONING));
    }

    /**
     * All weights must sum to approximately 1.0.
     */
    public double sum() {
        return wConfidence + wReliability + wContradiction + wSpecificity + wGenericnessPenalty;
    }
}

