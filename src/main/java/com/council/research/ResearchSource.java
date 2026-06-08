package com.council.research;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One source included in the shared evidence pack for research-aware prompts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchSource(
        String id,
        String title,
        String url,
        String domain,
        String snippet,
        String publishedAt,
        double score
) {}
