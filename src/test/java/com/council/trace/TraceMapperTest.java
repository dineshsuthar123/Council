package com.council.trace;

import com.council.api.dto.FinalResponse;
import com.council.common.TraceStatus;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.JudgeRanking;
import com.council.model.JudgeResult;
import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TraceMapperTest {

    private TraceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TraceMapper(new ObjectMapper());
    }

    @Test
    @DisplayName("populateEntity sets all fields for a successful response")
    void populateEntity_successfulResponse() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "test query");

        List<DraftResult> drafts = List.of(
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "answer", "summary", List.of(), List.of(), 0.9, 800, "raw-gemini")
        );
        CriticResult critic = CriticResult.failure("claude", "claude-test", "skipped", 0);
        JudgeResult judge = new JudgeResult("gemini", "gemini-2.5-pro", 0.85,
                "Winner by default", List.of(new JudgeRanking("gemini", 0.85)));
        FinalResponse response = new FinalResponse("trace-1", "answer",
                "Winner by default", List.of("gemini"), List.of("deepseek"), 0.9)
                .withScoreBreakdown(0.84, 0.55, 0.95,
                        Map.of("pseudocode", 0.42, "deletion_safety", 0.90))
                .withResearch(ResearchPack.withSources(
                        "Prompt asks for current information.",
                        List.of("latest routing"),
                        List.of(new ResearchSource("S1", "Routing source", "https://example.com",
                                "example.com", "snippet", "2026-01-01", 0.9))));

        mapper.populateEntity(entity, drafts, critic, judge, response, 1500);

        assertNotNull(entity.getDraftResults());
        assertNotNull(entity.getRawResponses());
        assertNotNull(entity.getCriticResult());
        assertNotNull(entity.getJudgeResult());
        assertEquals("answer", entity.getFinalAnswer());
        assertEquals(0.84, entity.getFinalConfidence());
        assertEquals(0.84, entity.getAnswerQuality());
        assertEquals(0.55, entity.getWinnerConfidence());
        assertEquals(0.95, entity.getModelAgreement());
        assertTrue(entity.getScoreDimensions().contains("\"pseudocode\":0.42"));
        assertTrue(entity.getResearchContext().contains("\"id\":\"S1\""));
        assertEquals("Winner by default", entity.getJudgeReason());
        assertEquals("gemini", entity.getUsedProviders());
        assertEquals("deepseek", entity.getFailedProviders());
        assertEquals(1500L, entity.getTotalLatencyMs());
        assertEquals(TraceStatus.COMPLETED, entity.getStatus());
    }

    @Test
    @DisplayName("populateEntity handles null response as FAILED")
    void populateEntity_nullResponse() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "query");
        mapper.populateEntity(entity, List.of(), null, null, null, 500);
        assertEquals(TraceStatus.FAILED, entity.getStatus());
    }

    @Test
    @DisplayName("toResponse maps entity to TraceResponse correctly")
    void toResponse_mapsCorrectly() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "query");
        entity.setUsedProviders("gemini,deepseek");
        entity.setFailedProviders("");
        entity.setFinalAnswer("answer");
        entity.setFinalConfidence(0.8);
        entity.setAnswerQuality(0.76);
        entity.setWinnerConfidence(0.55);
        entity.setModelAgreement(0.95);
        entity.setScoreDimensions("{\"pseudocode\":0.42}");
        entity.setResearchContext("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}");
        entity.setTotalLatencyMs(1000L);

        var resp = mapper.toResponse(entity);

        assertEquals("query", resp.userQuery());
        assertEquals("answer", resp.finalAnswer());
        assertEquals(0.8, resp.finalConfidence());
        assertEquals(0.76, resp.answerQuality());
        assertEquals(0.55, resp.winnerConfidence());
        assertEquals(0.95, resp.modelAgreement());
        assertEquals("{\"pseudocode\":0.42}", resp.dimensions());
        assertEquals("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}", resp.researchContext());
        assertEquals(List.of("gemini", "deepseek"), resp.usedProviders());
        assertTrue(resp.failedProviders().isEmpty());
    }

    @Test
    @DisplayName("extractRawResponses collects raw text per provider")
    void extractRawResponses_works() {
        List<DraftResult> drafts = List.of(
                DraftResult.success("gemini", "m", "a", "s", List.of(), List.of(), 0.8, 100, "raw-g"),
                DraftResult.failure("deepseek", "m", "err", 0)
        );

        Map<String, String> raw = mapper.extractRawResponses(drafts);
        assertEquals(1, raw.size());
        assertEquals("raw-g", raw.get("gemini"));
    }

    @Test
    @DisplayName("toDebugResponse maps entity to TraceDebugResponse with correct counts")
    void toDebugResponse_mapsCorrectly() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "debug query");
        entity.setUsedProviders("gemini,deepseek");
        entity.setFailedProviders("claude");
        entity.setFinalAnswer("best answer");
        entity.setFinalConfidence(0.92);
        entity.setAnswerQuality(0.84);
        entity.setWinnerConfidence(0.55);
        entity.setModelAgreement(0.95);
        entity.setScoreDimensions("{\"pseudocode\":0.42,\"deletion_safety\":0.9}");
        entity.setResearchContext("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}");
        entity.setJudgeReason("Gemini had the highest composite score");
        entity.setTotalLatencyMs(2500L);
        entity.setDraftResults("{\"drafts\":[]}");
        entity.setRawResponses("{\"gemini\":\"raw\"}");
        entity.setCriticResult("{\"summary\":\"ok\"}");
        entity.setJudgeResult("{\"winner\":\"gemini\"}");

        var debug = mapper.toDebugResponse(entity);

        assertEquals(entity.getTraceId().toString(), debug.traceId());
        assertEquals("PENDING", debug.status());
        assertEquals("debug query", debug.userQuery());
        assertEquals(2500L, debug.totalLatencyMs());
        assertEquals(3, debug.totalDrafts());       // 2 used + 1 failed
        assertEquals(2, debug.successfulDrafts());
        assertEquals(1, debug.failedDrafts());
        assertEquals(List.of("gemini", "deepseek"), debug.usedProviders());
        assertEquals(List.of("claude"), debug.failedProviders());
        assertEquals("{\"drafts\":[]}", debug.draftResults());
        assertEquals("{\"gemini\":\"raw\"}", debug.rawResponses());
        assertEquals("{\"summary\":\"ok\"}", debug.criticResult());
        assertEquals("{\"winner\":\"gemini\"}", debug.judgeResult());
        assertEquals("Gemini had the highest composite score", debug.judgeReason());
        assertEquals("best answer", debug.finalAnswer());
        assertEquals(0.92, debug.finalConfidence());
        assertEquals(0.84, debug.answerQuality());
        assertEquals(0.55, debug.winnerConfidence());
        assertEquals(0.95, debug.modelAgreement());
        assertEquals("{\"pseudocode\":0.42,\"deletion_safety\":0.9}", debug.dimensions());
        assertEquals("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}", debug.researchContext());
    }

    @Test
    @DisplayName("toDebugResponse handles entity with no providers")
    void toDebugResponse_noProviders() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "empty query");

        var debug = mapper.toDebugResponse(entity);

        assertEquals(0, debug.totalDrafts());
        assertEquals(0, debug.successfulDrafts());
        assertEquals(0, debug.failedDrafts());
        assertTrue(debug.usedProviders().isEmpty());
        assertTrue(debug.failedProviders().isEmpty());
        assertNull(debug.finalAnswer());
        assertNull(debug.finalConfidence());
    }

    @Test
    @DisplayName("splitProviders handles empty/null input")
    void splitProviders_edgeCases() {
        assertTrue(mapper.splitProviders(null).isEmpty());
        assertTrue(mapper.splitProviders("").isEmpty());
        assertTrue(mapper.splitProviders("   ").isEmpty());
        assertEquals(List.of("gemini", "claude"), mapper.splitProviders("gemini,claude"));
    }
}

