package com.council.evaluation;

import com.council.evaluation.dto.BaselineResultResponse;
import com.council.evaluation.dto.EvaluationAggregateResponse;
import com.council.evaluation.dto.EvaluationPromptResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationMetricsCalculatorTest {

    private EvaluationMetricsCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new EvaluationMetricsCalculator();
    }

    @Test
    @DisplayName("Calculates correct averages for successful prompts")
    void aggregatesSuccessfulPrompts() {
        List<EvaluationPromptResponse> results = List.of(
                prompt(0, "COMPLETED", 0.85, 1000L, "gemini", 0.1,
                        List.of("gemini", "claude"), List.of(), 500, 0.8, null),
                prompt(1, "COMPLETED", 0.90, 1200L, "claude", 0.2,
                        List.of("gemini", "claude"), List.of("deepseek"), 600, 0.9, null)
        );

        EvaluationAggregateResponse agg = calculator.calculate(results);

        assertEquals(2, agg.totalPrompts());
        assertEquals(2, agg.successfulPrompts());
        assertEquals(0, agg.failedPrompts());
        assertEquals(1100.0, agg.averageLatencyMs());
        assertEquals(0.88, agg.averageConfidence(), 0.01);
        assertEquals(0.15, agg.averageContradictionSeverity());
        assertEquals(550.0, agg.averageAnswerLength());
        assertEquals(0.85, agg.averageKeywordMatchScore());

        // Winner frequency
        assertEquals(1, agg.winnerFrequency().get("gemini"));
        assertEquals(1, agg.winnerFrequency().get("claude"));

        // Provider counts
        assertEquals(2, agg.providerSuccessCounts().get("gemini"));
        assertEquals(2, agg.providerSuccessCounts().get("claude"));
        assertEquals(1, agg.providerFailureCounts().get("deepseek"));
    }

    @Test
    @DisplayName("Handles empty results list")
    void emptyResults() {
        EvaluationAggregateResponse agg = calculator.calculate(List.of());

        assertEquals(0, agg.totalPrompts());
        assertEquals(0, agg.successfulPrompts());
        assertEquals(0.0, agg.averageLatencyMs());
        assertNull(agg.averageKeywordMatchScore());
    }

    @Test
    @DisplayName("Handles all-failed results")
    void allFailed() {
        List<EvaluationPromptResponse> results = List.of(
                prompt(0, "FAILED", null, null, null, null,
                        null, null, null, null, "Provider error")
        );

        EvaluationAggregateResponse agg = calculator.calculate(results);

        assertEquals(1, agg.totalPrompts());
        assertEquals(0, agg.successfulPrompts());
        assertEquals(1, agg.failedPrompts());
    }

    @Test
    @DisplayName("Calculates baseline aggregates correctly")
    void baselineAggregates() {
        Map<String, BaselineResultResponse> baselines1 = Map.of(
                "gemini", new BaselineResultResponse("gemini", "ans1", 0.8, 500L, 100, 0.7, null),
                "claude", new BaselineResultResponse("claude", "ans2", 0.9, 600L, 200, 0.8, null)
        );
        Map<String, BaselineResultResponse> baselines2 = Map.of(
                "gemini", new BaselineResultResponse("gemini", "ans3", 0.7, 400L, 150, 0.6, null),
                "claude", new BaselineResultResponse("claude", null, null, null, null, null, "timeout")
        );

        List<EvaluationPromptResponse> results = List.of(
                prompt(0, "COMPLETED", 0.85, 1000L, "gemini", 0.0,
                        List.of("gemini"), List.of(), 500, null, null, baselines1),
                prompt(1, "COMPLETED", 0.80, 900L, "gemini", 0.0,
                        List.of("gemini"), List.of(), 400, null, null, baselines2)
        );

        EvaluationAggregateResponse agg = calculator.calculate(results);

        assertNotNull(agg.baselineAggregates());
        assertEquals(2, agg.baselineAggregates().size());

        var geminiBaseline = agg.baselineAggregates().get("gemini");
        assertEquals(2, geminiBaseline.successes());
        assertEquals(0, geminiBaseline.failures());
        assertEquals(450.0, geminiBaseline.averageLatencyMs());

        var claudeBaseline = agg.baselineAggregates().get("claude");
        assertEquals(1, claudeBaseline.successes());
        assertEquals(1, claudeBaseline.failures());
    }

    @Test
    @DisplayName("Keyword match score average skips nulls")
    void keywordMatchSkipsNulls() {
        List<EvaluationPromptResponse> results = List.of(
                prompt(0, "COMPLETED", 0.85, 1000L, "gemini", 0.0,
                        List.of("gemini"), List.of(), 500, 0.8, null),
                prompt(1, "COMPLETED", 0.80, 900L, "claude", 0.0,
                        List.of("claude"), List.of(), 400, null, null) // no keyword score
        );

        EvaluationAggregateResponse agg = calculator.calculate(results);
        // Only one prompt has keyword match → average of that one
        assertEquals(0.8, agg.averageKeywordMatchScore());
    }

    /* ── test helpers ──────────────────────────────────────────────── */

    private EvaluationPromptResponse prompt(int idx, String status, Double conf, Long latency,
                                            String winner, Double contradiction,
                                            List<String> used, List<String> failed,
                                            Integer answerLen, Double kwScore, String error) {
        return prompt(idx, status, conf, latency, winner, contradiction,
                used, failed, answerLen, kwScore, error, null);
    }

    private EvaluationPromptResponse prompt(int idx, String status, Double conf, Long latency,
                                            String winner, Double contradiction,
                                            List<String> used, List<String> failed,
                                            Integer answerLen, Double kwScore, String error,
                                            Map<String, BaselineResultResponse> baselines) {
        return new EvaluationPromptResponse(
                idx, "test prompt " + idx, status,
                "trace-" + idx,
                answerLen != null ? "a".repeat(answerLen) : null,
                conf, latency, winner, contradiction,
                used, failed, "judge reason", answerLen,
                kwScore, baselines, error
        );
    }
}

