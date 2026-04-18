package com.council.judge;

import com.council.config.CouncilProperties;
import com.council.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests deterministic scoring behavior after principal-level judge upgrades.
 * in the DeterministicJudge.
 */
class TaskAwareJudgeTest {

    private DeterministicJudge judge;

    @BeforeEach
    void setUp() {
        CouncilProperties props = new CouncilProperties();
        var deepseek = new CouncilProperties.ProviderConfig();
        deepseek.setReliability(0.85);
        var openrouter = new CouncilProperties.ProviderConfig();
        openrouter.setReliability(0.80);
        var groq = new CouncilProperties.ProviderConfig();
        groq.setReliability(0.80);
        props.setProviders(Map.of("deepseek", deepseek, "openrouter", openrouter, "groq", groq));
        judge = new DeterministicJudge(props, new SpecificityScorer());
    }

    @Test
    @DisplayName("Fallback mode uses confidence when critic is unavailable")
    void fallbackModeUsesConfidenceOnly() {
        DraftResult highConf = DraftResult.success("deepseek", "deepseek-chat",
                "answer-a", "summary", List.of(), List.of(), 0.92, 500, "raw");
        DraftResult lowConf = DraftResult.success("openrouter", "qwen-72b",
                "answer-b", "summary", List.of(), List.of(), 0.61, 600, "raw");

        JudgeResult result = judge.evaluate(List.of(highConf, lowConf), null, TaskType.SYSTEM_DESIGN);

        assertEquals("deepseek", result.winnerProvider());
        assertTrue(result.reason().contains("Fallback mode"));
    }

    @Test
    @DisplayName("scoreDraft applies weighted principal formula when critic exists")
    void scoreDraftUsesWeightedPrincipalFormula() {
        DraftResult draft = DraftResult.success("deepseek", "deepseek-chat",
                "answer", "summary", List.of(), List.of(), 0.90, 500, "raw");

        CriticResult critic = critic(0.80, 0.70, 0.60);
        TaskAwareWeights weights = TaskAwareWeights.forTask(TaskType.SYSTEM_DESIGN);

        double score = judge.scoreDraft(draft, critic, TaskType.SYSTEM_DESIGN, weights);
        double expected = (0.80 * 0.30) + (0.70 * 0.30) + (0.60 * 0.20) + (0.90 * 0.20);

        assertEquals(expected, score, 1e-9);
    }

    @Test
    @DisplayName("scoreDraft falls back to confidence when critic is null")
    void scoreDraftFallsBackWithoutCritic() {
        DraftResult draft = DraftResult.success("deepseek", "deepseek-chat",
                "answer", "summary", List.of(), List.of(), 0.77, 500, "raw");
        TaskAwareWeights weights = TaskAwareWeights.forTask(TaskType.SYSTEM_DESIGN);

        double score = judge.scoreDraft(draft, null, TaskType.SYSTEM_DESIGN, weights);
        assertEquals(0.77, score, 1e-9);
    }

    @Test
    @DisplayName("Judge reason includes critic sub-scores when critic is available")
    void reasonIncludesSubScores() {
        DraftResult a = DraftResult.success("deepseek", "deepseek-chat",
                "answer-a", "summary", List.of(), List.of(), 0.85, 500, "raw");
        DraftResult b = DraftResult.success("openrouter", "qwen-72b",
                "answer-b", "summary", List.of(), List.of(), 0.80, 600, "raw");

        JudgeResult result = judge.evaluate(List.of(a, b), critic(0.81, 0.72, 0.66), TaskType.SYSTEM_DESIGN);

        assertTrue(result.reason().contains("mathCorrectnessScore"));
        assertTrue(result.reason().contains("feasibilityScore"));
        assertTrue(result.reason().contains("failureDepthScore"));
    }

    @Test
    @DisplayName("Judge reason includes task type")
    void judgeReasonIncludesTaskType() {
        DraftResult a = DraftResult.success("deepseek", "deepseek-chat",
                "answer", "summary", List.of(), List.of(), 0.85, 500, "raw");
        DraftResult b = DraftResult.success("openrouter", "qwen-72b",
                "answer", "summary", List.of(), List.of(), 0.80, 600, "raw");

        JudgeResult result = judge.evaluate(List.of(a, b), null, TaskType.SYSTEM_DESIGN);
        assertTrue(result.reason().contains("SYSTEM_DESIGN"));
    }

    private CriticResult critic(double mathCorrectnessScore,
                                double feasibilityScore,
                                double failureDepthScore) {
        return CriticResult.successFull(
                "gemini", "gemini-flash",
                "Principal-level critique", 0.10,
                Map.of("deepseek", 0, "openrouter", 0),
                List.<Contradiction>of(),
                List.of(),
                List.of(),
                mathCorrectnessScore,
                feasibilityScore,
                failureDepthScore,
                0.20,
                List.of("Missing chaos exercise for broker outage"),
                false,
                false,
                "deepseek had better quantitative confidence under same critic envelope",
                500,
                "raw"
        );
    }
}

