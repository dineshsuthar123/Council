package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.judge.DeterministicJudge;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.trace.TraceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReasoningOrchestratorTest {

    private ProviderRegistry registry;
    private CriticEngine criticEngine;
    private DeterministicJudge judge;
    private TraceService traceService;
    private OrchestrationMetrics metrics;
    private ReasoningOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);
        criticEngine = mock(CriticEngine.class);
        traceService = mock(TraceService.class);
        metrics = new OrchestrationMetrics(new SimpleMeterRegistry());

        CouncilProperties props = new CouncilProperties();
        var gemini = new CouncilProperties.ProviderConfig();
        gemini.setReliability(0.85);
        var deepseek = new CouncilProperties.ProviderConfig();
        deepseek.setReliability(0.75);
        var claude = new CouncilProperties.ProviderConfig();
        claude.setReliability(0.90);
        props.setProviders(Map.of("gemini", gemini, "deepseek", deepseek, "claude", claude));
        props.getOrchestrator().setDraftTimeoutSeconds(5);
        props.getOrchestrator().setCriticTimeoutSeconds(5);

        judge = new DeterministicJudge(props);
        orchestrator = new ReasoningOrchestrator(registry, criticEngine, judge,
                traceService, metrics, props);
    }

    /* ── Happy path: all providers succeed ─────────────────────────── */

    @Test
    @DisplayName("Happy path: all providers return drafts, critic succeeds, winner selected")
    void happyPath_allProvidersSucceed() {
        // Mock two providers returning successful drafts
        LlmAdapter geminiAdapter = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "Gemini answer", "Gemini summary",
                        List.of("assumption1"), List.of(), 0.90, 800, "raw-gemini"));

        LlmAdapter deepseekAdapter = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.success("deepseek", "deepseek-chat",
                        "DeepSeek answer", "DeepSeek summary",
                        List.of(), List.of("uncertainty1"), 0.80, 1200, "raw-deepseek"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(geminiAdapter, deepseekAdapter));

        // Mock critic returning a successful critique
        CriticResult critic = CriticResult.success("claude", "claude-sonnet",
                "Gemini is stronger", 0.3,
                Map.of("gemini", 0, "deepseek", 2),
                List.of(new Contradiction("gemini", "deepseek", "date disagreement")),
                List.of(), List.of(), 500, "raw-critic");
        when(criticEngine.critique(any())).thenReturn(critic);

        // Execute
        FinalResponse response = orchestrator.reason("What is Java?");

        // Assertions
        assertNotNull(response);
        assertNotNull(response.traceId());
        assertNotNull(response.finalAnswer());
        assertTrue(response.confidence() > 0);
        assertEquals(2, response.usedProviders().size());
        assertTrue(response.failedProviders().isEmpty());

        // Winner should be gemini (higher confidence, 0 contradictions, higher reliability)
        assertEquals("Gemini answer", response.finalAnswer());

        // Trace should be persisted
        verify(traceService).persistAsync(anyString(), eq("What is Java?"),
                any(), any(), any(), any(), anyLong());
    }

    /* ── Partial failure: one provider fails ───────────────────────── */

    @Test
    @DisplayName("Partial failure: one provider fails, orchestrator continues with remaining")
    void partialFailure_oneProviderFails() {
        LlmAdapter geminiAdapter = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "Gemini answer", "summary", List.of(), List.of(), 0.85, 900, "raw"));

        LlmAdapter failingAdapter = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.failure("deepseek", "deepseek-chat", "Connection timeout", 5000));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(geminiAdapter, failingAdapter));

        // Critic fails too — should still work
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("claude", "claude-test", "Critic unavailable", 0));

        FinalResponse response = orchestrator.reason("Explain quantum computing");

        assertNotNull(response);
        assertNotNull(response.finalAnswer());
        assertEquals("Gemini answer", response.finalAnswer());
        assertEquals(1, response.usedProviders().size());
        assertEquals(1, response.failedProviders().size());
        assertTrue(response.failedProviders().contains("deepseek"));
        // Single draft → judge auto-selects without critic penalties
        assertTrue(response.judgeReason().toLowerCase().contains("only one"));
    }

    /* ── All providers fail ────────────────────────────────────────── */

    @Test
    @DisplayName("All providers fail: graceful error response")
    void allProvidersFail_gracefulError() {
        LlmAdapter failA = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.failure("gemini", "gemini-2.5-pro", "Timeout", 5000));
        LlmAdapter failB = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.failure("deepseek", "deepseek-chat", "Rate limited", 1000));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(failA, failB));

        FinalResponse response = orchestrator.reason("Test query");

        assertNotNull(response);
        assertNotNull(response.traceId());
        assertNull(response.finalAnswer());
        assertTrue(response.judgeReason().contains("All providers failed"));
        assertEquals(0.0, response.confidence());
    }

    /* ── No providers available ────────────────────────────────────── */

    @Test
    @DisplayName("No providers available: graceful error response")
    void noProvidersAvailable() {
        when(registry.getAvailableDraftProviders()).thenReturn(List.of());

        FinalResponse response = orchestrator.reason("Test");

        assertNotNull(response);
        assertNull(response.finalAnswer());
        assertTrue(response.judgeReason().contains("No LLM providers"));
    }

    /* ── Single provider success ───────────────────────────────────── */

    @Test
    @DisplayName("Single provider: auto-selected without critic contradiction penalties")
    void singleProvider_autoSelected() {
        LlmAdapter single = mockAdapter("claude", "claude-sonnet",
                DraftResult.success("claude", "claude-sonnet",
                        "Claude's answer", "summary", List.of(), List.of(), 0.92, 700, "raw"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(single));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("claude", "claude-sonnet", "Cannot self-critique", 0));

        FinalResponse response = orchestrator.reason("What is Spring Boot?");

        assertNotNull(response);
        assertEquals("Claude's answer", response.finalAnswer());
        assertEquals(0.92, response.confidence(), 0.01);
        assertEquals(1, response.usedProviders().size());
    }

    /* ── Helper ────────────────────────────────────────────────────── */

    private LlmAdapter mockAdapter(String name, String model, DraftResult result) {
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.providerName()).thenReturn(name);
        when(adapter.modelName()).thenReturn(model);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.generateDraft(any())).thenReturn(result);
        return adapter;
    }
}


