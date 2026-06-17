package com.council.trace;

import com.council.api.dto.FinalResponse;
import com.council.api.dto.TraceDebugResponse;
import com.council.api.dto.TraceResponse;
import com.council.api.dto.TraceSummaryResponse;
import com.council.common.TraceStatus;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.JudgeResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps between reasoning artefacts and the persistence layer.
 * <p>
 * Extracted from {@link TraceService} so the service focuses on async
 * persistence and this class owns all serialization / DTO mapping.
 */
@Component
public class TraceMapper {

    private static final Logger log = LoggerFactory.getLogger(TraceMapper.class);

    private final ObjectMapper mapper;
    private final TraceRedactor redactor;

    public TraceMapper(ObjectMapper mapper) {
        this(mapper, new TraceRedactor());
    }

    @Autowired
    public TraceMapper(ObjectMapper mapper, TraceRedactor redactor) {
        this.mapper = mapper;
        this.redactor = redactor;
    }

    /* ── Entity population ─────────────────────────────────────────── */

    /**
     * Populate all fields on the entity from the reasoning pipeline outputs.
     */
    public void populateEntity(TraceEntity entity,
                               List<DraftResult> draftResults,
                               CriticResult criticResult,
                               JudgeResult judgeResult,
                               FinalResponse response,
                               long totalLatencyMs) {
        entity.setDraftResults(toJson(draftResults));
        entity.setRawResponses(toJson(extractRawResponses(draftResults)));
        entity.setCriticResult(toJson(criticResult));
        entity.setJudgeResult(toJson(judgeResult));

        if (response != null) {
            entity.setFinalAnswer(redactor.redact(response.finalAnswer()));
            entity.setFinalConfidence(response.confidence());
            entity.setAnswerQuality(response.answerQuality() == null ? response.confidence() : response.answerQuality());
            entity.setWinnerConfidence(response.winnerConfidence());
            entity.setModelAgreement(response.modelAgreement());
            entity.setScoreDimensions(toJson(response.dimensions()));
            entity.setResearchContext(toJson(response.research()));
            entity.setInvariantFindings(toJson(response.invariants()));
            entity.setJudgeReason(redactor.redact(response.judgeReason()));
            entity.setUsedProviders(String.join(",", response.usedProviders()));
            entity.setFailedProviders(String.join(",", response.failedProviders()));
            entity.setStatus(response.finalAnswer() != null ? TraceStatus.COMPLETED : TraceStatus.FAILED);
        } else {
            entity.setStatus(TraceStatus.FAILED);
        }

        entity.setTotalLatencyMs(totalLatencyMs);
    }

    /* ── Entity → DTO ──────────────────────────────────────────────── */

    public TraceResponse toResponse(TraceEntity e) {
        return new TraceResponse(
                e.getTraceId().toString(),
                e.getUserQuery(),
                e.getDraftResults(),
                e.getCriticResult(),
                e.getJudgeResult(),
                e.getFinalAnswer(),
                e.getFinalConfidence(),
                e.getAnswerQuality(),
                e.getWinnerConfidence(),
                e.getModelAgreement(),
                e.getScoreDimensions(),
                e.getResearchContext(),
                e.getInvariantFindings(),
                e.getJudgeReason(),
                splitProviders(e.getUsedProviders()),
                splitProviders(e.getFailedProviders()),
                e.getTotalLatencyMs(),
                e.getStatus().name(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.getCompletedAt() != null ? e.getCompletedAt().toString() : null
        );
    }

    /* ── Entity → Debug DTO ───────────────────────────────────────── */

    public TraceDebugResponse toDebugResponse(TraceEntity e) {
        List<String> used = splitProviders(e.getUsedProviders());
        List<String> failed = splitProviders(e.getFailedProviders());
        return new TraceDebugResponse(
                e.getTraceId().toString(),
                e.getStatus().name(),
                e.getUserQuery(),
                e.getTotalLatencyMs(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                used.size() + failed.size(),
                used.size(),
                failed.size(),
                used,
                failed,
                e.getDraftResults(),
                e.getRawResponses(),
                e.getCriticResult(),
                e.getJudgeResult(),
                e.getJudgeReason(),
                e.getFinalAnswer(),
                e.getFinalConfidence(),
                e.getAnswerQuality(),
                e.getWinnerConfidence(),
                e.getModelAgreement(),
                e.getScoreDimensions(),
                e.getResearchContext(),
                e.getInvariantFindings()
        );
    }

    /* ── Entity → Summary DTO (lightweight, for listing) ─────────── */

    public TraceSummaryResponse toSummary(TraceEntity e) {
        return new TraceSummaryResponse(
                e.getTraceId().toString(),
                e.getStatus().name(),
                e.getUserQuery(),
                e.getFinalAnswer(),
                e.getFinalConfidence(),
                splitProviders(e.getUsedProviders()),
                splitProviders(e.getFailedProviders()),
                e.getTotalLatencyMs(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.getCompletedAt() != null ? e.getCompletedAt().toString() : null
        );
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    Map<String, String> extractRawResponses(List<DraftResult> drafts) {
        if (drafts == null) return Map.of();
        Map<String, String> raw = new LinkedHashMap<>();
        for (DraftResult d : drafts) {
            if (d.rawResponse() != null) {
                raw.put(d.provider(), redactor.redact(d.rawResponse()));
            }
        }
        return raw;
    }

    List<String> splitProviders(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.asList(csv.split(","));
    }

    String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return redactor.redact(mapper.writeValueAsString(obj));
        } catch (JsonProcessingException e) {
            log.warn("[trace] JSON serialization failed: {}", e.getMessage());
            return null;
        }
    }
}

