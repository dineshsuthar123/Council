package com.council.research;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Shared evidence context supplied to all model roles for time-sensitive prompts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchPack(
        boolean required,
        boolean attempted,
        String reason,
        List<String> queries,
        List<ResearchSource> sources,
        String errorMessage
) {
    public ResearchPack {
        queries = queries == null ? List.of() : List.copyOf(queries);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static ResearchPack notRequired() {
        return new ResearchPack(false, false, "Prompt does not require external research.",
                List.of(), List.of(), null);
    }

    public static ResearchPack unavailable(String reason, List<String> queries, String errorMessage) {
        return new ResearchPack(true, false, reason, queries, List.of(), errorMessage);
    }

    public static ResearchPack withSources(String reason, List<String> queries, List<ResearchSource> sources) {
        return new ResearchPack(true, true, reason, queries, sources, null);
    }

    public boolean hasSources() {
        return sources != null && !sources.isEmpty();
    }
}
