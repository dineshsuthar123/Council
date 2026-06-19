package com.council.research;

/**
 * Explains why a prompt-provided source stopped at a specific prompt boundary.
 */
public enum SourceBoundaryEndReason {
    NEXT_SOURCE,
    INSTRUCTION_BOUNDARY,
    END_OF_PROMPT
}
