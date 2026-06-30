package com.council.research;

import com.council.config.CouncilProperties;
import com.council.judge.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the shared evidence pack for prompts that need current external context.
 */
@Service
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    private final CouncilProperties properties;
    private final ResearchNeedDetector detector;
    private final ResearchQueryPlanner queryPlanner;
    private final ResearchClient researchClient;
    private final PromptProvidedEvidenceParser promptEvidenceParser;
    private final ResearchSourceRelevanceScorer relevanceScorer;

    public ResearchService(CouncilProperties properties,
                           ResearchNeedDetector detector,
                           ResearchQueryPlanner queryPlanner,
                           ResearchClient researchClient,
                           PromptProvidedEvidenceParser promptEvidenceParser) {
        this.properties = properties;
        this.detector = detector;
        this.queryPlanner = queryPlanner;
        this.researchClient = researchClient;
        this.promptEvidenceParser = promptEvidenceParser;
        this.relevanceScorer = new ResearchSourceRelevanceScorer();
    }

    public ResearchPack buildEvidencePack(String userQuery, TaskType taskType) {
        List<ResearchSource> promptSources = promptEvidenceParser == null
                ? List.of()
                : promptEvidenceParser.parse(userQuery);
        boolean requiresResearch = detector.requiresResearch(userQuery) || !promptSources.isEmpty();
        if (!properties.getResearch().isEnabled() && promptSources.isEmpty()) {
            return ResearchPack.notRequired();
        }
        if (!requiresResearch) {
            return ResearchPack.notRequired();
        }

        String reason = detector.reason(userQuery);
        if (!promptSources.isEmpty() && !detector.requiresResearch(userQuery)) {
            reason = "Prompt includes a source evidence pack.";
        }
        List<String> queries = queryPlanner.plan(userQuery);
        if (queries.isEmpty() && promptSources.isEmpty()) {
            return ResearchPack.unavailable(reason, queries, "No viable search query could be generated");
        }

        if (!properties.getResearch().isEnabled()) {
            return promptOnly(reason, queries, promptSources, "External research unavailable: research mode disabled");
        }

        boolean externalConfigured = properties.getResearch().getApiKey() != null
                && !properties.getResearch().getApiKey().isBlank();
        if (!externalConfigured) {
            String unavailable = "External research unavailable: TAVILY_API_KEY not configured";
            if (!promptSources.isEmpty()) {
                return promptOnly(reason, queries, promptSources, unavailable);
            }
            return ResearchPack.unavailable(reason, queries, "TAVILY_API_KEY is not configured");
        }

        try {
            List<ResearchSource> sources = researchClient.search(queries, properties.getResearch().getMaxResults());
            ExternalSourceSelection selection = selectRelevantExternalSources(userQuery, taskType, sources);
            List<ResearchSource> combined = combine(promptSources, selection.included());
            if (combined.isEmpty()) {
                return ResearchPack.withEvidence(reason, queries, List.of(),
                        "Research provider returned no relevant sources", selection.warnings(), selection.excluded());
            }
            log.info("[research] Built evidence pack for taskType={} with {} sources (promptProvided={})",
                    taskType, combined.size(), promptSources.size());
            List<String> warnings = new java.util.ArrayList<>(selection.warnings());
            if (!promptSources.isEmpty() && !selection.included().isEmpty()) {
                warnings.add("Using mixed prompt-provided and external evidence");
            }
            return ResearchPack.withEvidence(reason, queries, combined, null, warnings, selection.excluded());
        } catch (Exception e) {
            log.warn("[research] Evidence pack unavailable: {}", e.getMessage());
            if (!promptSources.isEmpty()) {
                return promptOnly(reason, queries, promptSources,
                        "External research unavailable: " + e.getMessage());
            }
            return ResearchPack.unavailable(reason, queries, e.getMessage());
        }
    }

    private ResearchPack promptOnly(String reason,
                                    List<String> queries,
                                    List<ResearchSource> promptSources,
                                    String unavailableReason) {
        return ResearchPack.withEvidence(reason, queries, promptSources, unavailableReason,
                List.of("Using prompt-provided evidence only"));
    }

    private List<ResearchSource> combine(List<ResearchSource> promptSources, List<ResearchSource> externalSources) {
        if ((promptSources == null || promptSources.isEmpty()) && (externalSources == null || externalSources.isEmpty())) {
            return List.of();
        }
        java.util.LinkedHashMap<String, ResearchSource> byId = new java.util.LinkedHashMap<>();
        if (promptSources != null) {
            for (ResearchSource source : promptSources) {
                byId.put(source.id(), source);
            }
        }
        int next = byId.size() + 1;
        if (externalSources != null) {
            for (ResearchSource source : externalSources) {
                String id = source.id();
                while (id == null || id.isBlank() || byId.containsKey(id)) {
                    id = "S" + next++;
                }
                byId.put(id, withId(source, id));
            }
        }
        return List.copyOf(byId.values());
    }

    private ResearchSource withId(ResearchSource source, String id) {
        if (source.id().equals(id)) {
            return source;
        }
        return new ResearchSource(id, source.title(), source.url(), source.domain(), source.snippet(),
                source.publishedAt(), source.score(), source.sourceType(), source.origin(),
                source.providedAt(), source.updatedAt(), source.authorityScore(), source.recencyScore(),
                source.injectionRisk(), source.supportsCurrentFacts(), source.metadata(), source.relevanceScore(),
                source.relevanceReason(), source.excludedReason());
    }

    private ExternalSourceSelection selectRelevantExternalSources(String userQuery,
                                                                   TaskType taskType,
                                                                   List<ResearchSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return new ExternalSourceSelection(List.of(), List.of(), List.of());
        }
        List<ResearchSource> included = new java.util.ArrayList<>();
        List<ResearchSource> excluded = new java.util.ArrayList<>();
        for (ResearchSource source : sources) {
            ResearchSourceRelevanceScorer.Assessment assessment = relevanceScorer.assess(userQuery, taskType, source);
            ResearchSource scored = new ResearchSource(source.id(), source.title(), source.url(), source.domain(),
                    source.snippet(), source.publishedAt(), source.score(), source.sourceType(), source.origin(),
                    source.providedAt(), source.updatedAt(), source.authorityScore(), source.recencyScore(),
                    source.injectionRisk(), source.supportsCurrentFacts(), source.metadata(),
                    assessment.relevanceScore(), assessment.relevanceReason(), assessment.excludedReason());
            if (assessment.isIncluded()) {
                included.add(scored);
            } else {
                excluded.add(scored);
            }
        }
        List<String> warnings = excluded.isEmpty()
                ? List.of()
                : List.of("External search returned low-relevance sources; ignored " + excluded.size() + " sources.");
        return new ExternalSourceSelection(List.copyOf(included), List.copyOf(excluded), warnings);
    }

    private record ExternalSourceSelection(List<ResearchSource> included,
                                           List<ResearchSource> excluded,
                                           List<String> warnings) {
    }
}
