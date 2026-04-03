package com.council.evaluation;

import com.council.evaluation.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps between evaluation entities and DTOs.
 */
@Component
public class EvaluationMapper {

    private static final Logger log = LoggerFactory.getLogger(EvaluationMapper.class);

    private final ObjectMapper mapper;

    public EvaluationMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /* ── Entity → Response ─────────────────────────────────────────── */

    public EvaluationResponse toResponse(EvaluationRunEntity run) {
        List<EvaluationPromptResponse> promptResponses = run.getPromptResults().stream()
                .map(this::toPromptResponse)
                .toList();

        EvaluationAggregateResponse aggregate = fromJson(
                run.getAggregateMetrics(), EvaluationAggregateResponse.class);

        return new EvaluationResponse(
                run.getRunId().toString(),
                run.getName(),
                run.getStatus().name(),
                splitCsv(run.getTags()),
                run.getTotalPrompts(),
                run.getCompletedPrompts(),
                run.getFailedPrompts(),
                aggregate,
                promptResponses,
                run.getCreatedAt() != null ? run.getCreatedAt().toString() : null,
                run.getCompletedAt() != null ? run.getCompletedAt().toString() : null
        );
    }

    public EvaluationPromptResponse toPromptResponse(EvaluationPromptResultEntity e) {
        Map<String, BaselineResultResponse> baselines = fromJson(
                e.getBaselineResults(),
                new TypeReference<Map<String, BaselineResultResponse>>() {});

        return new EvaluationPromptResponse(
                e.getPromptIndex(),
                e.getPrompt(),
                e.getStatus().name(),
                e.getCouncilTraceId(),
                e.getCouncilAnswer(),
                e.getCouncilConfidence(),
                e.getCouncilLatencyMs(),
                e.getCouncilWinnerProvider(),
                e.getCouncilContradictionSeverity(),
                splitCsv(e.getCouncilUsedProviders()),
                splitCsv(e.getCouncilFailedProviders()),
                e.getCouncilJudgeReason(),
                e.getCouncilAnswerLength(),
                e.getKeywordMatchScore(),
                baselines,
                e.getErrorMessage()
        );
    }

    public EvaluationRunSummary toSummary(EvaluationRunEntity run) {
        return new EvaluationRunSummary(
                run.getRunId().toString(),
                run.getName(),
                run.getStatus().name(),
                splitCsv(run.getTags()),
                run.getTotalPrompts(),
                run.getCompletedPrompts(),
                run.getFailedPrompts(),
                run.getCreatedAt() != null ? run.getCreatedAt().toString() : null,
                run.getCompletedAt() != null ? run.getCompletedAt().toString() : null
        );
    }

    /* ── JSON helpers ──────────────────────────────────────────────── */

    public String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[eval-mapper] JSON serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("[eval-mapper] JSON deserialization failed: {}", e.getMessage());
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.warn("[eval-mapper] JSON deserialization failed: {}", e.getMessage());
            return null;
        }
    }

    List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.asList(csv.split(","));
    }
}

