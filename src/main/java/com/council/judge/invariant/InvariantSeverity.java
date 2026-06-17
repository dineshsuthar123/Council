package com.council.judge.invariant;

/**
 * Severity levels for invariant findings. Higher severities generally carry
 * lower score caps.
 */
public enum InvariantSeverity {
    INFO,
    WARNING,
    MAJOR,
    CRITICAL
}
