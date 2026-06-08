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

    public ResearchService(CouncilProperties properties,
                           ResearchNeedDetector detector,
                           ResearchQueryPlanner queryPlanner,
                           ResearchClient researchClient) {
        this.properties = properties;
        this.detector = detector;
        this.queryPlanner = queryPlanner;
        this.researchClient = researchClient;
    }

    public ResearchPack buildEvidencePack(String userQuery, TaskType taskType) {
        if (!properties.getResearch().isEnabled() || !detector.requiresResearch(userQuery)) {
            return ResearchPack.notRequired();
        }

        String reason = detector.reason(userQuery);
        List<String> queries = queryPlanner.plan(userQuery);
        if (queries.isEmpty()) {
            return ResearchPack.unavailable(reason, queries, "No viable search query could be generated");
        }

        try {
            List<ResearchSource> sources = researchClient.search(queries, properties.getResearch().getMaxResults());
            if (sources.isEmpty()) {
                return ResearchPack.unavailable(reason, queries, "Research provider returned no usable sources");
            }
            log.info("[research] Built evidence pack for taskType={} with {} sources", taskType, sources.size());
            return ResearchPack.withSources(reason, queries, sources);
        } catch (Exception e) {
            log.warn("[research] Evidence pack unavailable: {}", e.getMessage());
            return ResearchPack.unavailable(reason, queries, e.getMessage());
        }
    }
}
