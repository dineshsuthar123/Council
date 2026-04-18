package com.council.judge;

/**
 * Classification of prompt/task types.
 * Used to influence routing, judge weighting, and escalation behaviour.
 */
public enum TaskType {

    /** System design questions (e.g. "design a payment system") */
    SYSTEM_DESIGN,

    /** Backend architecture (e.g. "how to handle distributed locks") */
    BACKEND_ARCHITECTURE,

    /** Debugging / root-cause analysis */
    DEBUGGING,

    /** Write-code / implement-function tasks */
    CODING,

    /** Catch-all for non-technical or general prompts */
    GENERAL_REASONING
}

