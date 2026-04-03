package com.council.critic;

import com.council.config.CouncilProperties;
import com.council.model.CriticRequest;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
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

class CriticEngineTest {

    private ProviderRegistry registry;
    private CouncilProperties properties;
    private CriticEngine criticEngine;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);
        properties = new CouncilProperties();
        properties.getCritic().setProvider("claude");
        criticEngine = new CriticEngine(registry, properties);
    }

    @Test
    @DisplayName("Returns failure when no critic provider is available")
    void noCriticProvider_returnsFailure() {
        when(registry.getCriticAdapter("claude")).thenReturn(Optional.empty());

        List<DraftResult> drafts = List.of(
                DraftResult.success("gemini", "model", "ans", "sum",
                        List.of(), List.of(), 0.8, 100, "raw")
        );
        CriticRequest request = new CriticRequest("trace-1", "query", drafts);

        CriticResult result = criticEngine.critique(request);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().contains("No critic provider available"));
    }

    @Test
    @DisplayName("Delegates to adapter and returns result on success")
    void criticAvailable_delegatesToAdapter() {
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.providerName()).thenReturn("claude");
        when(adapter.modelName()).thenReturn("claude-test");
        when(registry.getCriticAdapter("claude")).thenReturn(Optional.of(adapter));

        CriticResult expected = CriticResult.success(
                "claude", "claude-test", "All drafts agree",
                0.1, Map.of("gemini", 0), List.of(),
                List.of(), List.of(), 200, "raw-critic"
        );
        when(adapter.generateCritique(any())).thenReturn(expected);

        List<DraftResult> drafts = List.of(
                DraftResult.success("gemini", "model", "ans", "sum",
                        List.of(), List.of(), 0.8, 100, "raw")
        );
        CriticRequest request = new CriticRequest("trace-1", "query", drafts);

        CriticResult result = criticEngine.critique(request);

        assertTrue(result.isSuccess());
        assertEquals("All drafts agree", result.globalSummary());
        verify(adapter).generateCritique(request);
    }
}

