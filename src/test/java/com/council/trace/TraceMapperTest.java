package com.council.trace;

import com.council.api.dto.FinalResponse;
import com.council.common.TraceStatus;
import com.council.common.exception.ProviderFailureCategory;
import com.council.judge.FinalScoreBreakdown;
import com.council.judge.invariant.InvariantCriticResult;
import com.council.judge.invariant.InvariantLibrary;
import com.council.judge.invariant.InvariantViolation;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.JudgeRanking;
import com.council.model.JudgeResult;
import com.council.model.ProviderFailureDetails;
import com.council.model.ProviderRunDiagnostics;
import com.council.model.ProviderOutcome;
import com.council.model.ProviderOutcomeStatus;
import com.council.orchestrator.EarlyStopDecision;
import com.council.judge.TaskType;
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
                        Map.of("pseudocode", 0.42, "deletion_safety", 0.90),
                        sampleScoreBreakdown())
                .withResearch(ResearchPack.withSources(
                        "Prompt asks for current information.",
                        List.of("latest routing"),
                        List.of(new ResearchSource("S1", "Routing source", "https://example.com",
                                "example.com", "snippet", "2026-01-01", 0.9))))
                .withInvariants(sampleInvariantResult())
                .withRunDiagnostics(new ProviderRunDiagnostics(2, 1, 0.5, "DEGRADED", 0.5,
                        "Only 1 of 2 selected providers produced valid drafts."));

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
        assertTrue(entity.getRunDiagnostics().contains("\"providerCoverage\":0.5"));
        assertTrue(entity.getScoreDimensions().contains("\"pseudocode\":0.42"));
        assertTrue(entity.getScoreBreakdown().contains("\"finalAnswerQuality\":0.84"));
        assertTrue(entity.getResearchContext().contains("\"id\":\"S1\""));
        assertTrue(entity.getInvariantFindings().contains("\"invariantId\":\"url.tombstone_precedes_active_cache\""));
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
        entity.setRunDiagnostics("{\"attemptedProviders\":2,\"validDraftProviders\":2,\"providerCoverage\":1.0,\"runHealth\":\"HEALTHY\"}");
        entity.setScoreDimensions("{\"pseudocode\":0.42}");
        entity.setScoreBreakdown("{\"baseRubricScore\":0.84,\"finalAnswerQuality\":0.76}");
        entity.setResearchContext("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}");
        entity.setInvariantFindings("{\"overallCap\":0.75}");
        entity.setTotalLatencyMs(1000L);

        var resp = mapper.toResponse(entity);

        assertEquals("query", resp.userQuery());
        assertEquals("answer", resp.finalAnswer());
        assertEquals(0.8, resp.finalConfidence());
        assertEquals(0.76, resp.answerQuality());
        assertEquals(0.55, resp.winnerConfidence());
        assertEquals(0.95, resp.modelAgreement());
        assertTrue(resp.runDiagnostics().contains("HEALTHY"));
        assertEquals("{\"pseudocode\":0.42}", resp.dimensions());
        assertEquals("{\"baseRubricScore\":0.84,\"finalAnswerQuality\":0.76}", resp.scoreBreakdown());
        assertEquals("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}", resp.researchContext());
        assertEquals("{\"overallCap\":0.75}", resp.invariantFindings());
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
    @DisplayName("failed draft diagnostics are redacted and persisted with the trace")
    void populateEntity_persistsSafeProviderFailureDiagnostics() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "query");
        ProviderFailureDetails failure = new ProviderFailureDetails(
                "blackbox-gpt55", "Blackbox GPT 5.5", "blackbox/gpt-5.5", "api.blackbox.ai",
                ProviderFailureCategory.AUTH_FAILED, "Provider authentication failed (HTTP 401)", 401,
                124, false, 1, "CLOSED", "2026-06-22T00:00:00Z");
        List<DraftResult> drafts = List.of(DraftResult.failure("blackbox-gpt55", "blackbox/gpt-5.5",
                "Provider authentication failed (HTTP 401)", 124, failure));
        FinalResponse response = new FinalResponse("trace", "answer", "reason", List.of(),
                List.of("blackbox-gpt55"), 0.80)
                .withRunDiagnostics(ProviderRunDiagnostics.from(drafts));

        mapper.populateEntity(entity, drafts, null, null, response, 124);

        assertTrue(entity.getDraftResults().contains("AUTH_FAILED"));
        assertTrue(entity.getDraftResults().contains("api.blackbox.ai"));
        assertTrue(entity.getRunDiagnostics().contains("FAILED"));
        assertFalse(entity.getDraftResults().toLowerCase().contains("authorization"));
    }

    @Test
    @DisplayName("early-stop outcomes and decision persist as trace diagnostics")
    void populateEntity_persistsEarlyStopOutcomeSemantics() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "query");
        List<DraftResult> drafts = List.of(
                DraftResult.success("groq", "model", "answer", "summary", List.of(), List.of(), 0.95, 20, "raw"),
                DraftResult.skipped("blackbox-gpt55", "gpt-5.5", ProviderOutcomeStatus.SKIPPED_EARLY_STOP,
                        "Draft skipped: early stop after valid draft confidence 0.95 >= 0.94"));
        EarlyStopDecision decision = new EarlyStopDecision(true, "Diversity threshold met", 0.94,
                1, 1, TaskType.GENERAL_REASONING, List.of(), null);
        FinalResponse response = new FinalResponse("trace", "answer", "reason", List.of("groq"), List.of(), 0.95)
                .withRunDiagnostics(ProviderRunDiagnostics.from(drafts, decision))
                .withProviderOutcomes(ProviderOutcome.fromDraftResults(drafts));

        mapper.populateEntity(entity, drafts, null, null, response, 20);

        assertTrue(entity.getDraftResults().contains("SKIPPED_EARLY_STOP"));
        assertTrue(entity.getRunDiagnostics().contains("earlyStopDecision"));
        assertTrue(entity.getRunDiagnostics().contains("selectedProviders"));
    }

    @Test
    @DisplayName("populateEntity redacts raw provider output and final answer before persistence")
    void populateEntity_redactsSensitiveArtifacts() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "query");
        List<DraftResult> drafts = List.of(
                DraftResult.success("groq", "m",
                        "answer", "summary", List.of(), List.of(), 0.8, 100,
                        "Authorization: Bearer sk-secret-abcdef1234567890 user owner@example.com")
        );
        FinalResponse response = new FinalResponse("trace-1",
                "final answer mentions apiKey=sk-secret-abcdef1234567890",
                "judge reason", List.of("groq"), List.of(), 0.8);

        mapper.populateEntity(entity, drafts, null, null, response, 250);

        assertTrue(entity.getRawResponses().contains("[REDACTED]"));
        assertTrue(entity.getRawResponses().contains("[REDACTED_EMAIL]"));
        assertTrue(entity.getFinalAnswer().contains("[REDACTED]"));
        assertFalse(entity.getRawResponses().contains("sk-secret-abcdef1234567890"));
        assertFalse(entity.getRawResponses().contains("owner@example.com"));
        assertFalse(entity.getFinalAnswer().contains("sk-secret-abcdef1234567890"));
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
        entity.setRunDiagnostics("{\"attemptedProviders\":3,\"validDraftProviders\":2,\"providerCoverage\":0.6667,\"runHealth\":\"DEGRADED\"}");
        entity.setScoreDimensions("{\"pseudocode\":0.42,\"deletion_safety\":0.9}");
        entity.setScoreBreakdown("{\"invariantCap\":0.6,\"finalAnswerQuality\":0.55}");
        entity.setResearchContext("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}");
        entity.setInvariantFindings("{\"overallCap\":0.75,\"violations\":[]}");
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
        assertTrue(debug.runDiagnostics().contains("DEGRADED"));
        assertEquals("{\"pseudocode\":0.42,\"deletion_safety\":0.9}", debug.dimensions());
        assertEquals("{\"invariantCap\":0.6,\"finalAnswerQuality\":0.55}", debug.scoreBreakdown());
        assertEquals("{\"required\":true,\"sources\":[{\"id\":\"S1\"}]}", debug.researchContext());
        assertEquals("{\"overallCap\":0.75,\"violations\":[]}", debug.invariantFindings());
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

    private InvariantCriticResult sampleInvariantResult() {
        var definition = InvariantLibrary.definition(InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE);
        return InvariantCriticResult.from(
                List.of(definition),
                List.of(InvariantViolation.of(definition, "active cache checked first", "check tombstone first")));
    }

    private FinalScoreBreakdown sampleScoreBreakdown() {
        return new FinalScoreBreakdown(0.55, 0.84, 0.84, 0.76, 0.60, null,
                1.0, 0.84, "final = min(base, research, invariant)", List.of("example"),
                Map.of("finalCompletenessCap", "No sentence-count cap applied."));
    }
}

