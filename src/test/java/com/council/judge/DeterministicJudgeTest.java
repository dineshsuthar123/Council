package com.council.judge;

import com.council.config.CouncilProperties;
import com.council.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicJudgeTest {

    private DeterministicJudge judge;

    @BeforeEach
    void setUp() {
        CouncilProperties props = new CouncilProperties();
        var gemini = new CouncilProperties.ProviderConfig();
        gemini.setReliability(0.85);
        var deepseek = new CouncilProperties.ProviderConfig();
        deepseek.setReliability(0.75);
        var claude = new CouncilProperties.ProviderConfig();
        claude.setReliability(0.90);
        props.setProviders(Map.of("gemini", gemini, "deepseek", deepseek, "claude", claude));
        judge = new DeterministicJudge(props, new SpecificityScorer());
    }

    @Test
    @DisplayName("No valid drafts returns no-winner result")
    void noValidDrafts() {
        JudgeResult result = judge.evaluate(List.of(), null);
        assertNull(result.winnerProvider());
        assertEquals(0.0, result.winnerScore());
        assertTrue(result.reason().contains("No valid drafts"));
    }

    @Test
    @DisplayName("Single draft is auto-selected")
    void singleDraft() {
        DraftResult only = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer", "summary", List.of(), List.of(), 0.80, 1000, "raw");

        JudgeResult result = judge.evaluate(List.of(only), null);
        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.winnerScore() > 0);
        assertTrue(result.reason().toLowerCase().contains("only one")
                || result.reason().toLowerCase().contains("single"));
    }

    @Test
    @DisplayName("Higher confidence wins when critic is unavailable")
    void higherConfidenceWinsWithoutCritic() {
        DraftResult high = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer-a", "summary-a", List.of(), List.of(), 0.95, 1000, "raw");
        DraftResult low = DraftResult.success("deepseek", "deepseek-chat",
                "answer-b", "summary-b", List.of(), List.of(), 0.50, 1200, "raw");

        JudgeResult result = judge.evaluate(List.of(high, low), null);
        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.winnerScore() > result.rankings().getLast().score());
    }

    @Test
    @DisplayName("With critic available, higher confidence wins under shared critic envelope")
    void higherConfidenceWinsWithSharedCriticEnvelope() {
        DraftResult a = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer-a", "summary-a", List.of(), List.of(), 0.90, 1000, "raw");
        DraftResult b = DraftResult.success("deepseek", "deepseek-chat",
                "answer-b", "summary-b", List.of(), List.of(), 0.82, 1200, "raw");

        CriticResult critic = CriticResult.successFull("claude", "claude-test",
                "summary", 0.6,
                Map.of("gemini", 4, "deepseek", 0),
                List.of(), List.of(), List.of(),
                0.8, 0.75, 0.70,
                0.2, List.of(), false, false,
                "gemini has better operational confidence in this pairwise comparison",
                500, "raw");

        JudgeResult result = judge.evaluate(List.of(a, b), critic);
        assertEquals("gemini", result.winnerProvider());
    }

    @Test
    @DisplayName("Critic failure means no contradiction penalty applied")
    void criticFailureMeansNoPenalty() {
        DraftResult a = DraftResult.success("gemini", "gemini-2.5-pro",
                "answer-a", "summary-a", List.of(), List.of(), 0.90, 1000, "raw");
        DraftResult b = DraftResult.success("deepseek", "deepseek-chat",
                "answer-b", "summary-b", List.of(), List.of(), 0.80, 1200, "raw");

        CriticResult failed = CriticResult.failure("claude", "claude-test", "timeout", 0);

        JudgeResult result = judge.evaluate(List.of(a, b), failed);
        // Gemini should win because it has higher confidence and no penalty
        assertEquals("gemini", result.winnerProvider());
                assertTrue(result.reason().contains("Critic unavailable"));
    }

    @Test
    @DisplayName("Rankings are sorted descending by score")
    void rankingsAreSorted() {
        DraftResult a = DraftResult.success("claude", "claude-test",
                "a", "s", List.of(), List.of(), 0.70, 100, "raw");
        DraftResult b = DraftResult.success("gemini", "gemini-test",
                "b", "s", List.of(), List.of(), 0.90, 100, "raw");
        DraftResult c = DraftResult.success("deepseek", "deepseek-test",
                "c", "s", List.of(), List.of(), 0.80, 100, "raw");

        JudgeResult result = judge.evaluate(List.of(a, b, c), null);

        List<JudgeRanking> rankings = result.rankings();
        assertEquals(3, rankings.size());
        for (int i = 1; i < rankings.size(); i++) {
            assertTrue(rankings.get(i - 1).score() >= rankings.get(i).score(),
                    "Rankings must be sorted descending");
        }
    }

    @Test
    @DisplayName("Verifier fatal math error disqualifies draft to zero")
    void verifierFatalMathDisqualifiesDraft() {
        DraftResult fatal = DraftResult.success("deepseek", "deepseek-chat",
                "bad math", "summary", List.of(), List.of(), 0.99, 100, "raw");
        DraftResult safe = DraftResult.success("gemini", "gemini-2.5-pro",
                "good math", "summary", List.of(), List.of(), 0.70, 100, "raw");

        CriticResult critic = CriticResult.successFull("claude", "claude-test",
                "summary", 0.2,
                Map.of("deepseek", 0, "gemini", 0),
                List.of(), List.of(), List.of(),
                0.9, 0.9, 0.9,
                0.1, List.of(), false, false,
                "none",
                100, "raw");

        JudgeResult result = judge.evaluate(
                List.of(fatal, safe),
                critic,
                Map.of("deepseek", VerifierResult.disqualifiedMath("KB to GB conversion is off by 1024x")),
                TaskType.BACKEND_ARCHITECTURE
        );

        assertEquals("gemini", result.winnerProvider());
        assertTrue(result.reason().contains("Verifier disqualifications"));
    }
}


