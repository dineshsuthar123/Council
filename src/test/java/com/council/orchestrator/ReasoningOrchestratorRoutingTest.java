package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.events.PipelineEventBroadcaster;
import com.council.judge.DeterministicJudge;
import com.council.judge.PromptClassifier;
import com.council.judge.SpecificityScorer;
import com.council.judge.TaskType;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.provider.routing.*;
import com.council.research.ResearchPack;
import com.council.research.ResearchPromptAugmenter;
import com.council.research.ResearchService;
import com.council.synthesizer.SynthesizerEngine;
import com.council.trace.TraceService;
import com.council.verifier.VerifierEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the orchestrator with routing ENABLED.
 */
class ReasoningOrchestratorRoutingTest {

    private ProviderRegistry registry;
    private CriticEngine criticEngine;
    private VerifierEngine verifierEngine;
    private SynthesizerEngine synthesizerEngine;
    private TraceService traceService;
    private OrchestrationMetrics metrics;
    private ProviderSelectionStrategy selectionStrategy;
    private ProviderConcurrencyLimiter concurrencyLimiter;
    private PipelineEventBroadcaster eventBroadcaster;
    private ResearchService researchService;
    private ReasoningOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);
        criticEngine = mock(CriticEngine.class);
        verifierEngine = mock(VerifierEngine.class);
        synthesizerEngine = mock(SynthesizerEngine.class);
        traceService = mock(TraceService.class);
        selectionStrategy = mock(ProviderSelectionStrategy.class);
        eventBroadcaster = mock(PipelineEventBroadcaster.class);
        researchService = mock(ResearchService.class);
        concurrencyLimiter = new ProviderConcurrencyLimiter();
        metrics = new OrchestrationMetrics(new SimpleMeterRegistry());

        CouncilProperties props = new CouncilProperties();
        var deepseek = new CouncilProperties.ProviderConfig();
        deepseek.setReliability(0.85);
        var openrouter = new CouncilProperties.ProviderConfig();
        openrouter.setReliability(0.80);
        var groq = new CouncilProperties.ProviderConfig();
        groq.setReliability(0.80);
        var gemini = new CouncilProperties.ProviderConfig();
        gemini.setReliability(0.92);
        props.setProviders(Map.of("deepseek", deepseek, "openrouter", openrouter,
                "groq", groq, "gemini", gemini));
        props.getOrchestrator().setDraftTimeoutSeconds(5);
        props.getOrchestrator().setCriticTimeoutSeconds(5);
        props.getRouting().setEnabled(true);

        // Routing is enabled
        when(registry.isRoutingEnabled()).thenReturn(true);
        when(registry.getAllAdapters()).thenReturn(Map.of());
        when(registry.buildDescriptors()).thenReturn(List.of());
        when(verifierEngine.verify(anyString(), anyString(), anyList()))
                .thenReturn(VerifierBatchResult.success(Map.of()));
        when(synthesizerEngine.synthesize(any()))
                .thenReturn(SynthesisResult.failure("none", "none", "synthesis unavailable", 0));
        when(researchService.buildEvidencePack(anyString(), any()))
                .thenReturn(ResearchPack.notRequired());

        DeterministicJudge judge = new DeterministicJudge(props, new SpecificityScorer());
        orchestrator = new ReasoningOrchestrator(registry, criticEngine, verifierEngine, synthesizerEngine, judge,
                new PromptClassifier(), traceService, metrics, props, selectionStrategy, concurrencyLimiter,
                eventBroadcaster, researchService, new ResearchPromptAugmenter());
    }

    private LlmAdapter mockAdapter(String name, String model, DraftResult result) {
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.providerName()).thenReturn(name);
        when(adapter.modelName()).thenReturn(model);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.generateDraft(any())).thenReturn(result);
        return adapter;
    }

    @Test
    @DisplayName("Uses strategy to select draft providers when routing is enabled")
    void usesStrategyForDraftSelection() {
        LlmAdapter deepseek = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.success("deepseek", "deepseek-chat",
                        "DeepSeek answer", "summary", List.of(), List.of(), 0.85, 500, "raw"));
        LlmAdapter openrouter = mockAdapter("openrouter", "qwen-72b",
                DraftResult.success("openrouter", "qwen-72b",
                        "OpenRouter answer", "summary", List.of(), List.of(), 0.80, 600, "raw"));
        LlmAdapter groq = mockAdapter("groq", "llama-70b",
                DraftResult.success("groq", "llama-70b",
                        "Groq answer", "summary", List.of(), List.of(), 0.78, 300, "raw"));

        when(selectionStrategy.selectDraftProviders(any(), any(), anyString(), any(TaskType.class)))
                .thenReturn(List.of(deepseek, openrouter, groq));
        when(selectionStrategy.selectEscalationProviders(any(), any(), anyDouble(),
                anyDouble(), any(), anyString()))
                .thenReturn(List.of()); // no escalation

        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("none", "none", "No critic", 0));

        FinalResponse response = orchestrator.reason("What is Java?");

        assertNotNull(response);
        assertNotNull(response.finalAnswer());
        assertEquals(3, response.usedProviders().size());
        assertTrue(response.failedProviders().isEmpty());

        // Verify strategy was called
        verify(selectionStrategy).selectDraftProviders(any(), any(), anyString(), any(TaskType.class));
    }

    @Test
    @DisplayName("Triggers premium escalation when confidence is low")
    void triggersEscalationOnLowConfidence() {
        LlmAdapter deepseek = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.success("deepseek", "deepseek-chat",
                        "Low confidence answer", "summary", List.of(), List.of(), 0.30, 500, "raw"));

        LlmAdapter gemini = mockAdapter("gemini", "gemini-flash",
                DraftResult.success("gemini", "gemini-flash",
                        "Premium answer", "summary", List.of(), List.of(), 0.95, 400, "raw"));

        when(selectionStrategy.selectDraftProviders(any(), any(), anyString(), any(TaskType.class)))
                .thenReturn(List.of(deepseek));

        // Escalation returns gemini
        when(selectionStrategy.selectEscalationProviders(any(), any(), anyDouble(),
                anyDouble(), any(), anyString()))
                .thenReturn(List.of(gemini));

        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("none", "none", "No critic", 0));

        FinalResponse response = orchestrator.reason("Complex question");

        assertNotNull(response);
        // Should have 2 providers used (deepseek + gemini)
        assertTrue(response.usedProviders().size() >= 1);
        // Gemini premium answer should be preferred due to higher confidence
        verify(selectionStrategy).selectEscalationProviders(any(), any(), anyDouble(),
                anyDouble(), any(), anyString());
    }

    @Test
    @DisplayName("No escalation when confidence is high")
    void noEscalationWhenConfidenceHigh() {
        LlmAdapter deepseek = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.success("deepseek", "deepseek-chat",
                        "Good answer", "summary", List.of(), List.of(), 0.90, 500, "raw"));

        when(selectionStrategy.selectDraftProviders(any(), any(), anyString(), any(TaskType.class)))
                .thenReturn(List.of(deepseek));
        when(selectionStrategy.selectEscalationProviders(any(), any(), anyDouble(),
                anyDouble(), any(), anyString()))
                .thenReturn(List.of()); // no escalation

        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("none", "none", "No critic", 0));

        FinalResponse response = orchestrator.reason("Simple question");

        assertNotNull(response);
        assertEquals("Good answer", response.finalAnswer());
        assertEquals(1, response.usedProviders().size());
    }

    @Test
    @DisplayName("Empty draft selection returns error gracefully")
    void emptyDraftSelectionReturnsError() {
        when(selectionStrategy.selectDraftProviders(any(), any(), anyString(), any(TaskType.class)))
                .thenReturn(List.of());

        FinalResponse response = orchestrator.reason("Test");

        assertNotNull(response);
        assertNull(response.finalAnswer());
        assertTrue(response.judgeReason().contains("No LLM providers"));
    }
}

