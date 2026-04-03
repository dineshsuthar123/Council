package com.council.model;

/**
 * A single contradiction identified between two drafts.
 */
public record Contradiction(
        String draftA,
        String draftB,
        String issue
) {}

