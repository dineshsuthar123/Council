package com.council.synthesizer;

import com.council.config.CouncilProperties;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.SynthesisRequest;
import com.council.model.SynthesisResult;
import com.council.model.VerifierBatchResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SynthesizerEngineTest {

    private ProviderRegistry registry;
    private SynthesizerEngine synthesizerEngine;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);

        CouncilProperties properties = new CouncilProperties();
        properties.getSynthesizer().setProvider("openrouter");

        synthesizerEngine = new SynthesizerEngine(registry, properties);
    }

    @Test
    @DisplayName("Primary synthesizer success is returned directly")
    void primarySuccess() {
        LlmAdapter openrouter = mock(LlmAdapter.class);
        when(openrouter.providerName()).thenReturn("openrouter");
        when(openrouter.modelName()).thenReturn("nvidia/llama-3.1-nemotron-70b-instruct");
        when(openrouter.generateSynthesis(any())).thenReturn(SynthesisResult.success(
                "openrouter",
                "nvidia/llama-3.1-nemotron-70b-instruct",
                "Unified final answer",
                "summary",
                List.of("decision"),
                List.of("strength"),
                List.of(),
                List.of(),
                List.of(),
                0.88,
                400,
                "raw"
        ));

        when(registry.getCriticAdapter("openrouter")).thenReturn(Optional.of(openrouter));

        SynthesisResult result = synthesizerEngine.synthesize(buildRequest());

        assertTrue(result.isSuccess());
        assertEquals("Unified final answer", result.synthesizedAnswer());
        verify(openrouter).generateSynthesis(any());
    }

    @Test
    @DisplayName("Falls back when primary synthesizer fails")
    void fallbackSuccess() {
        LlmAdapter openrouter = mock(LlmAdapter.class);
        when(openrouter.providerName()).thenReturn("openrouter");
        when(openrouter.modelName()).thenReturn("openrouter-model");
        when(openrouter.generateSynthesis(any())).thenReturn(
                SynthesisResult.failure("openrouter", "openrouter-model", "429", 0));

        LlmAdapter gemini = mock(LlmAdapter.class);
        when(gemini.providerName()).thenReturn("gemini");
        when(gemini.modelName()).thenReturn("gemini-2.5-flash");
        when(gemini.isEnabled()).thenReturn(true);
        when(gemini.generateSynthesis(any())).thenReturn(SynthesisResult.success(
                "gemini",
                "gemini-2.5-flash",
                "Fallback synthesis",
                "summary",
                List.of("decision"),
                List.of("strength"),
                List.of(),
                List.of(),
                List.of(),
                0.75,
                350,
                "raw"
        ));

        when(registry.getCriticAdapter("openrouter")).thenReturn(Optional.of(openrouter));
        when(registry.getAllAdapters()).thenReturn(Map.of(
                "openrouter", openrouter,
                "gemini", gemini
        ));

        SynthesisResult result = synthesizerEngine.synthesize(buildRequest());

        assertTrue(result.isSuccess());
        assertEquals("gemini", result.provider());
        assertEquals("Fallback synthesis", result.synthesizedAnswer());
    }

    private SynthesisRequest buildRequest() {
        List<DraftResult> drafts = List.of(
                DraftResult.success("gemini", "g", "a", "s", List.of(), List.of(), 0.8, 100, "raw")
        );
        return new SynthesisRequest(
                "trace-1",
                "query",
                drafts,
                VerifierBatchResult.passedFor(drafts),
                CriticResult.failure("none", "none", "no critic", 0)
        );
    }
}
