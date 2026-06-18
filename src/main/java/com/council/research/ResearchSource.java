package com.council.research;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One registered source included in the shared evidence pack for research-aware prompts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchSource(
        String id,
        String title,
        String url,
        String domain,
        String snippet,
        String publishedAt,
        double score,
        SourceType sourceType,
        EvidenceOrigin origin,
        String providedAt,
        String updatedAt,
        double authorityScore,
        double recencyScore,
        InjectionRisk injectionRisk,
        boolean supportsCurrentFacts,
        Map<String, Object> metadata
) {
    public ResearchSource(String id,
                          String title,
                          String url,
                          String domain,
                          String snippet,
                          String publishedAt,
                          double score) {
        this(id, title, url, domain, snippet, publishedAt, score,
                SourceType.UNKNOWN,
                EvidenceOrigin.EXTERNAL_RESEARCH,
                null,
                publishedAt,
                score,
                publishedAt == null || publishedAt.isBlank() ? 0.60 : 0.82,
                InjectionRisk.LOW,
                true,
                Map.of());
    }

    public ResearchSource {
        id = normalizeId(id);
        title = title == null || title.isBlank() ? id : title.trim();
        url = blankToNull(url);
        domain = blankToNull(domain);
        snippet = snippet == null ? "" : snippet.trim();
        publishedAt = blankToNull(publishedAt);
        score = clamp(score);
        sourceType = sourceType == null ? SourceType.UNKNOWN : sourceType;
        origin = origin == null ? EvidenceOrigin.EXTERNAL_RESEARCH : origin;
        providedAt = blankToNull(providedAt);
        updatedAt = blankToNull(updatedAt);
        authorityScore = clamp(authorityScore);
        recencyScore = clamp(recencyScore);
        injectionRisk = injectionRisk == null ? InjectionRisk.LOW : injectionRisk;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean hasHighInjectionRisk() {
        return injectionRisk == InjectionRisk.HIGH;
    }

    public boolean isPromptProvided() {
        return origin == EvidenceOrigin.PROMPT_PROVIDED;
    }

    public boolean isInternalTrace() {
        return origin == EvidenceOrigin.INTERNAL_TRACE || sourceType == SourceType.INTERNAL_TRACE;
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            return "S?";
        }
        String trimmed = value.trim().toUpperCase();
        if (trimmed.matches("\\d+")) {
            return "S" + trimmed;
        }
        return trimmed.startsWith("S") ? trimmed : "S" + trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
