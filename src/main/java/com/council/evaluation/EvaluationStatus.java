package com.council.evaluation;

/**
 * Lifecycle status of an evaluation run or individual prompt result.
 */
public enum EvaluationStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL_FAILURE,
    FAILED
}

