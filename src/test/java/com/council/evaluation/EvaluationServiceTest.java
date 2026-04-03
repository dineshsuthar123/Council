package com.council.evaluation;

import com.council.api.dto.FinalResponse;
import com.council.evaluation.dto.BaselineResultResponse;
import com.council.evaluation.dto.EvaluationPromptInput;
import com.council.evaluation.dto.EvaluationRequest;
import com.council.evaluation.dto.EvaluationResponse;
import com.council.orchestrator.ReasoningOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EvaluationServiceTest {

    private ReasoningOrchestrator orchestrator;
    private BaselineRunner baselineRunner;
    private KeywordMatcher keywordMatcher;
    private EvaluationMetricsCalculator metricsCalculator;
    private EvaluationMapper evaluationMapper;
    private EvaluationRunRepository runRepository;
    private EvaluationPromptResultRepository promptResultRepository;
    private EvaluationService service;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ReasoningOrchestrator.class);
        baselineRunner = mock(BaselineRunner.class);
        keywordMatcher = new KeywordMatcher();
        metricsCalculator = new EvaluationMetricsCalculator();
        evaluationMapper = new EvaluationMapper(new ObjectMapper());
        runRepository = mock(EvaluationRunRepository.class);
        promptResultRepository = mock(EvaluationPromptResultRepository.class);
        service = new EvaluationService(
                orchestrator, baselineRunner, keywordMatcher,
                metricsCalculator, evaluationMapper,
                runRepository, promptResultRepository);
    }

    @Test
    @DisplayName("startEvaluation creates a run entity and returns run ID")
    void startEvaluation_createsRun() {
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.findByRunId(any())).thenReturn(Optional.empty());

        EvaluationRequest request = new EvaluationRequest(
                "test-run", List.of("reasoning"), null, false,
                List.of(new EvaluationPromptInput("What is 2+2?", "4", List.of("four", "4")))
        );

        String runId = service.startEvaluation(request);

        assertNotNull(runId);
        assertDoesNotThrow(() -> UUID.fromString(runId));
        verify(runRepository).save(any(EvaluationRunEntity.class));
    }

    @Test
    @DisplayName("executeRun processes all prompts via orchestrator")
    void executeRun_processesAllPrompts() {
        // Setup: create a real run entity
        UUID runId = UUID.randomUUID();
        EvaluationRunEntity run = new EvaluationRunEntity(runId, "test", 2);
        run.setRunBaselines(false);
        run.addPromptResult(new EvaluationPromptResultEntity(0, "prompt 1"));
        run.addPromptResult(new EvaluationPromptResultEntity(1, "prompt 2"));

        when(runRepository.findByRunId(runId)).thenReturn(Optional.of(run));
        when(promptResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalResponse response1 = new FinalResponse(
                "trace-1", "Answer 1", "Winner gemini",
                List.of("gemini", "claude"), List.of(), 0.85);
        FinalResponse response2 = new FinalResponse(
                "trace-2", "Answer 2", "Winner claude",
                List.of("gemini", "claude"), List.of("deepseek"), 0.78);

        when(orchestrator.reason("prompt 1")).thenReturn(response1);
        when(orchestrator.reason("prompt 2")).thenReturn(response2);

        EvaluationRequest request = new EvaluationRequest(
                "test", null, null, false,
                List.of(
                        new EvaluationPromptInput("prompt 1", null, null),
                        new EvaluationPromptInput("prompt 2", null, null)
                )
        );

        service.executeRun(runId, request);

        verify(orchestrator, times(2)).reason(anyString());
        verify(promptResultRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("executeRun handles orchestrator failure gracefully")
    void executeRun_handlesFailure() {
        UUID runId = UUID.randomUUID();
        EvaluationRunEntity run = new EvaluationRunEntity(runId, "fail-test", 1);
        run.addPromptResult(new EvaluationPromptResultEntity(0, "failing prompt"));

        when(runRepository.findByRunId(runId)).thenReturn(Optional.of(run));
        when(promptResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.reason(anyString())).thenThrow(new RuntimeException("Provider down"));

        EvaluationRequest request = new EvaluationRequest(
                "fail-test", null, null, false,
                List.of(new EvaluationPromptInput("failing prompt", null, null))
        );

        // Should not throw
        assertDoesNotThrow(() -> service.executeRun(runId, request));

        // Prompt result should be saved with FAILED status
        verify(promptResultRepository).save(argThat(entity -> {
            EvaluationPromptResultEntity e = (EvaluationPromptResultEntity) entity;
            return e.getStatus() == EvaluationStatus.FAILED
                    && e.getErrorMessage() != null;
        }));
    }

    @Test
    @DisplayName("executeRun invokes baseline runner when requested")
    void executeRun_withBaselines() {
        UUID runId = UUID.randomUUID();
        EvaluationRunEntity run = new EvaluationRunEntity(runId, "baseline-test", 1);
        run.setRunBaselines(true);
        run.addPromptResult(new EvaluationPromptResultEntity(0, "baseline prompt"));

        when(runRepository.findByRunId(runId)).thenReturn(Optional.of(run));
        when(promptResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalResponse response = new FinalResponse(
                "trace-b", "Council answer", "reason",
                List.of("gemini"), List.of(), 0.9);
        when(orchestrator.reason(anyString())).thenReturn(response);

        Map<String, BaselineResultResponse> baselines = Map.of(
                "gemini", new BaselineResultResponse("gemini", "base ans", 0.8, 400L, 50, null, null)
        );
        when(baselineRunner.runBaselines(anyString(), any(), any(), any())).thenReturn(baselines);

        EvaluationRequest request = new EvaluationRequest(
                "baseline-test", null, null, true,
                List.of(new EvaluationPromptInput("baseline prompt", null, null))
        );

        service.executeRun(runId, request);

        verify(baselineRunner).runBaselines(eq("baseline prompt"), any(), any(), any());
    }

    @Test
    @DisplayName("executeRun computes keyword match when expected keywords provided")
    void executeRun_withKeywords() {
        UUID runId = UUID.randomUUID();
        EvaluationRunEntity run = new EvaluationRunEntity(runId, "kw-test", 1);
        EvaluationPromptResultEntity promptEntity = new EvaluationPromptResultEntity(0, "test");
        promptEntity.setExpectedKeywords("gravity,force");
        run.addPromptResult(promptEntity);

        when(runRepository.findByRunId(runId)).thenReturn(Optional.of(run));
        when(promptResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalResponse response = new FinalResponse(
                "trace-kw", "Gravity is a fundamental force of nature",
                "reason", List.of("gemini"), List.of(), 0.9);
        when(orchestrator.reason(anyString())).thenReturn(response);

        EvaluationRequest request = new EvaluationRequest(
                "kw-test", null, null, false,
                List.of(new EvaluationPromptInput("test", null, List.of("gravity", "force")))
        );

        service.executeRun(runId, request);

        // The prompt result should have a keyword match score set
        verify(promptResultRepository).save(argThat(entity -> {
            EvaluationPromptResultEntity e = (EvaluationPromptResultEntity) entity;
            return e.getKeywordMatchScore() != null && e.getKeywordMatchScore() > 0.0;
        }));
    }

    @Test
    @DisplayName("extractContradictionSeverity parses from judge reason")
    void extractContradictionSeverity() {
        assertEquals(0.75, service.extractContradictionSeverity(
                "High contradiction severity (0.75) detected."));
        assertEquals(0.0, service.extractContradictionSeverity(
                "No contradictions found"));
        assertEquals(0.0, service.extractContradictionSeverity(null));
    }
}

