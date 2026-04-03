package com.council.model;

/**
 * Score entry in the judge ranking.
 */
public record JudgeRanking(
        String provider,
        double score
) {}

