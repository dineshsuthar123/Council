package com.council.critic;

import com.council.config.CouncilProperties;
import com.council.model.CriticRequest;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.provider.routing.ProviderSelectionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the aggressive critic fallback chain.
 */
class CriticFallbackTest {

    private ProviderRegistry registry;
    private CouncilProperties properties;
    private CriticEngine criticEngine;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);
        properties = new CouncilProperties();
        properties.getCritic().setProvider("gemini");
        // Routing disabled — uses legacy getCriticAdapter
        when(registry.isRoutingEnabled()).thenReturn(false);
        ProviderSelectionStrategy strategy = mock(ProviderSelectionStrategy.class);
        criticEngine = new CriticEngine(registry, properties, strategy);
    }

    private CriticRequest buildRequest() {
        List<DraftResult> drafts = List.of(
                DraftResult.success("deepseek", "model", "ans", "sum",
                        List.of(), List.of(), 0.8, 100, "raw")
        );
        return new CriticRequest("trace-1", "query", drafts);
    }

    @Test
    @DisplayName("Falls back to deepseek when primary gemini fails")
    void fallsBackToDeepseekWhenGeminiFails() {
        // Primary gemini adapter fails
        LlmAdapter geminiAdapter = mock(LlmAdapter.class);
        when(geminiAdapter.providerName()).thenReturn("gemini");
        when(geminiAdapter.modelName()).thenReturn("gemini-flash");
        when(geminiAdapter.isEnabled()).thenReturn(true);
        when(geminiAdapter.generateCritique(any())).thenReturn(
                CriticResult.failure("gemini", "gemini-flash", "Rate limited", 0));
        when(registry.getCriticAdapter("gemini")).thenReturn(Optional.of(geminiAdapter));

        // Fallback deepseek succeeds
        LlmAdapter deepseekAdapter = mock(LlmAdapter.class);
        when(deepseekAdapter.providerName()).thenReturn("deepseek");
        when(deepseekAdapter.modelName()).thenReturn("deepseek-chat");
        when(deepseekAdapter.isEnabled()).thenReturn(true);
        CriticResult deepseekResult = CriticResult.success("deepseek", "deepseek-chat",
                "Critique from deepseek", 0.2, Map.of(), List.of(), List.of(), List.of(), 300, "raw");
        when(deepseekAdapter.generateCritique(any())).thenReturn(deepseekResult);

        Map<String, LlmAdapter> adapters = Map.of(
                "gemini", geminiAdapter,
                "deepseek", deepseekAdapter
        );
        when(registry.getAllAdapters()).thenReturn(adapters);
        when(registry.getAdaptersForCurrentMode()).thenReturn(adapters);

        CriticResult result = criticEngine.critique(buildRequest());

        assertTrue(result.isSuccess(), "Should succeed via deepseek fallback");
        assertEquals("deepseek", result.provider());
    }

    @Test
    @DisplayName("Falls through multiple failures until one succeeds")
    void fallsThroughMultipleFailures() {
        // Primary not available
        when(registry.getCriticAdapter("gemini")).thenReturn(Optional.empty());

        // gemini fallback fails
        LlmAdapter geminiAdapter = mock(LlmAdapter.class);
        when(geminiAdapter.providerName()).thenReturn("gemini");
        when(geminiAdapter.modelName()).thenReturn("gemini-flash");
        when(geminiAdapter.isEnabled()).thenReturn(true);
        when(geminiAdapter.generateCritique(any())).thenReturn(
                CriticResult.failure("gemini", "gemini-flash", "Error", 0));

        // deepseek fallback fails
        LlmAdapter deepseekAdapter = mock(LlmAdapter.class);
        when(deepseekAdapter.providerName()).thenReturn("deepseek");
        when(deepseekAdapter.modelName()).thenReturn("deepseek-chat");
        when(deepseekAdapter.isEnabled()).thenReturn(true);
        when(deepseekAdapter.generateCritique(any())).thenReturn(
                CriticResult.failure("deepseek", "deepseek-chat", "Error", 0));

        // mistral fallback succeeds
        LlmAdapter mistralAdapter = mock(LlmAdapter.class);
        when(mistralAdapter.providerName()).thenReturn("mistral");
        when(mistralAdapter.modelName()).thenReturn("mistral-large");
        when(mistralAdapter.isEnabled()).thenReturn(true);
        CriticResult mistralResult = CriticResult.success("mistral", "mistral-large",
                "Critique from mistral", 0.1, Map.of(), List.of(), List.of(), List.of(), 400, "raw");
        when(mistralAdapter.generateCritique(any())).thenReturn(mistralResult);

        Map<String, LlmAdapter> adapters = Map.of(
                "gemini", geminiAdapter,
                "deepseek", deepseekAdapter,
                "mistral", mistralAdapter
        );
        when(registry.getAllAdapters()).thenReturn(adapters);
        when(registry.getAdaptersForCurrentMode()).thenReturn(adapters);

        CriticResult result = criticEngine.critique(buildRequest());

        assertTrue(result.isSuccess(), "Should succeed via mistral (3rd fallback)");
        assertEquals("mistral", result.provider());
    }

    @Test
    @DisplayName("Returns failure only when all fallbacks are exhausted")
    void failsOnlyWhenAllExhausted() {
        when(registry.getCriticAdapter("gemini")).thenReturn(Optional.empty());
        when(registry.getAllAdapters()).thenReturn(Map.of()); // no adapters at all
        when(registry.getAdaptersForCurrentMode()).thenReturn(Map.of()); // no adapters in current mode

        CriticResult result = criticEngine.critique(buildRequest());

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().contains("All critic providers failed"));
    }

    @Test
    @DisplayName("Skips disabled adapters in fallback chain")
    void skipsDisabledAdapters() {
        when(registry.getCriticAdapter("gemini")).thenReturn(Optional.empty());

        // gemini disabled
        LlmAdapter gemini = mock(LlmAdapter.class);
        when(gemini.providerName()).thenReturn("gemini");
        when(gemini.isEnabled()).thenReturn(false);

        // deepseek disabled
        LlmAdapter deepseek = mock(LlmAdapter.class);
        when(deepseek.providerName()).thenReturn("deepseek");
        when(deepseek.isEnabled()).thenReturn(false);

        // mistral enabled and succeeds
        LlmAdapter mistral = mock(LlmAdapter.class);
        when(mistral.providerName()).thenReturn("mistral");
        when(mistral.modelName()).thenReturn("mistral-large");
        when(mistral.isEnabled()).thenReturn(true);
        when(mistral.generateCritique(any())).thenReturn(
                CriticResult.success("mistral", "mistral-large",
                        "OK", 0.0, Map.of(), List.of(), List.of(), List.of(), 200, "raw"));

        Map<String, LlmAdapter> adapters = Map.of(
                "gemini", gemini,
                "deepseek", deepseek,
                "mistral", mistral
        );
        when(registry.getAllAdapters()).thenReturn(adapters);
        when(registry.getAdaptersForCurrentMode()).thenReturn(adapters);

        CriticResult result = criticEngine.critique(buildRequest());

        assertTrue(result.isSuccess());
        assertEquals("mistral", result.provider());
        // Verify disabled adapters were never called
        verify(gemini, never()).generateCritique(any());
        verify(deepseek, never()).generateCritique(any());
    }

    @Test
    @DisplayName("Primary success skips the fallback chain entirely")
    void primarySuccessSkipsFallback() {
        LlmAdapter gemini = mock(LlmAdapter.class);
        when(gemini.providerName()).thenReturn("gemini");
        when(gemini.modelName()).thenReturn("gemini-flash");
        when(gemini.isEnabled()).thenReturn(true);
        CriticResult geminiResult = CriticResult.success("gemini", "gemini-flash",
                "Primary critique", 0.1, Map.of(), List.of(), List.of(), List.of(), 200, "raw");
        when(gemini.generateCritique(any())).thenReturn(geminiResult);

        when(registry.getCriticAdapter("gemini")).thenReturn(Optional.of(gemini));

        // Shouldn't need this but set it up to verify it's never called
        LlmAdapter deepseek = mock(LlmAdapter.class);
        when(deepseek.providerName()).thenReturn("deepseek");
        when(deepseek.isEnabled()).thenReturn(true);
        Map<String, LlmAdapter> adapters = Map.of("gemini", gemini, "deepseek", deepseek);
        when(registry.getAllAdapters()).thenReturn(adapters);
        when(registry.getAdaptersForCurrentMode()).thenReturn(adapters);

        CriticResult result = criticEngine.critique(buildRequest());

        assertTrue(result.isSuccess());
        assertEquals("gemini", result.provider());
        verify(deepseek, never()).generateCritique(any());
    }
}

