package com.council.research;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        String errorMessage,
        String originSummary,
        boolean hasExternalResearch,
        boolean hasPromptProvidedSources,
        boolean hasInternalTraceSources,
        boolean hasCitationRegistry,
        String researchUnavailableReason,
        List<String> warnings
) {
    public ResearchPack(boolean required,
                        boolean attempted,
                        String reason,
                        List<String> queries,
                        List<ResearchSource> sources,
                        String errorMessage) {
        this(required, attempted, reason, queries, sources, errorMessage,
                null, false, false, false, false, null, List.of());
    }

    public ResearchPack {
        queries = queries == null ? List.of() : List.copyOf(queries);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        hasExternalResearch = hasExternalResearch || sources.stream()
                .anyMatch(source -> source.origin() == EvidenceOrigin.EXTERNAL_RESEARCH);
        hasPromptProvidedSources = hasPromptProvidedSources || sources.stream()
                .anyMatch(source -> source.origin() == EvidenceOrigin.PROMPT_PROVIDED
                        || "prompt-provided".equals(source.metadata().get("parser")));
        hasInternalTraceSources = hasInternalTraceSources || sources.stream()
                .anyMatch(ResearchSource::isInternalTrace);
        hasCitationRegistry = hasCitationRegistry || !sources.isEmpty();
        originSummary = originSummary == null || originSummary.isBlank()
                ? summarizeOrigins(sources, hasExternalResearch, hasPromptProvidedSources, hasInternalTraceSources)
                : originSummary.trim();
        researchUnavailableReason = researchUnavailableReason == null || researchUnavailableReason.isBlank()
                ? errorMessage
                : researchUnavailableReason.trim();
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

    public static ResearchPack withEvidence(String reason,
                                            List<String> queries,
                                            List<ResearchSource> sources,
                                            String researchUnavailableReason,
                                            List<String> warnings) {
        return new ResearchPack(true, true, reason, queries, sources, researchUnavailableReason,
                null, false, false, false, false, researchUnavailableReason, warnings);
    }

    public boolean hasSources() {
        return sources != null && !sources.isEmpty();
    }

    public Set<String> sourceIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ResearchSource source : sources) {
            if (source.id() != null && !source.id().isBlank()) {
                ids.add(source.id().toUpperCase());
            }
        }
        return Set.copyOf(ids);
    }

    public boolean hasSourceId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return sourceIds().contains(id.trim().toUpperCase());
    }

    private static String summarizeOrigins(List<ResearchSource> sources,
                                           boolean hasExternalResearch,
                                           boolean hasPromptProvidedSources,
                                           boolean hasInternalTraceSources) {
        if (sources == null || sources.isEmpty()) {
            return "None";
        }
        int origins = 0;
        if (hasExternalResearch) origins++;
        if (hasPromptProvidedSources) origins++;
        if (hasInternalTraceSources) origins++;
        if (origins > 1) {
            return "Mixed";
        }
        if (hasPromptProvidedSources) {
            return "Prompt-provided evidence";
        }
        if (hasExternalResearch) {
            return "External research";
        }
        if (hasInternalTraceSources) {
            return "Internal trace";
        }
        return "Evidence pack";
    }
}
