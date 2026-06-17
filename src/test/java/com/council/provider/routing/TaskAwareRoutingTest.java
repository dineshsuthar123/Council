package com.council.provider.routing;

import com.council.config.CouncilProperties;
import com.council.judge.TaskType;
import com.council.provider.LlmAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests task-aware routing in DefaultProviderSelectionStrategy.
 */
class TaskAwareRoutingTest {

    private CouncilProperties properties;
    private ProviderConcurrencyLimiter concurrencyLimiter;
    private DefaultProviderSelectionStrategy strategy;
    private Map<String, LlmAdapter> adapters;

    @BeforeEach
    void setUp() {
        properties = new CouncilProperties();
        properties.getRouting().setEnabled(true);
        properties.getRouting().setMaxDraftProviders(3);

        concurrencyLimiter = new ProviderConcurrencyLimiter();
        strategy = new DefaultProviderSelectionStrategy(properties, concurrencyLimiter);

        adapters = new LinkedHashMap<>();
        adapters.put("deepseek", mockAdapter("deepseek"));
        adapters.put("openrouter", mockAdapter("openrouter"));
        adapters.put("groq", mockAdapter("groq"));
        adapters.put("together", mockAdapter("together"));
        adapters.put("mistral", mockAdapter("mistral"));
        adapters.put("gemini", mockAdapter("gemini"));
        adapters.put("kimi", mockAdapter("kimi"));
    }

    private LlmAdapter mockAdapter(String name) {
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.providerName()).thenReturn(name);
        when(adapter.modelName()).thenReturn(name + "-model");
        when(adapter.isEnabled()).thenReturn(true);
        return adapter;
    }

    private ProviderDescriptor desc(String name, int priority) {
        return new ProviderDescriptor(name, name, name + "-model", true,
                List.of(ProviderRole.DRAFT), priority, 0.80, 30, 5,
                List.of(), false, 0.0);
    }

    /* ══════════════════════════════════════════════════════════════════
       SYSTEM_DESIGN routing
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("SYSTEM_DESIGN routing preferences")
    class SystemDesignRoutingTests {

        @Test
        @DisplayName("SYSTEM_DESIGN prefers deepseek, openrouter, mistral over groq, together")
        void prefersStrongerProvidersForSystemDesign() {
            // All providers have DRAFT role, normal priorities
            List<ProviderDescriptor> descriptors = List.of(
                    desc("groq", 3),
                    desc("together", 4),
                    desc("deepseek", 1),
                    desc("openrouter", 2),
                    desc("mistral", 5)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-1", TaskType.SYSTEM_DESIGN);

            assertEquals(3, selected.size());
            // For SYSTEM_DESIGN, deepseek and openrouter should be selected first
            // even though groq has lower priority number than mistral
            assertEquals("deepseek", selected.get(0).providerName(),
                    "DeepSeek should be first for SYSTEM_DESIGN");
            assertEquals("openrouter", selected.get(1).providerName(),
                    "OpenRouter should be second for SYSTEM_DESIGN");
            assertEquals("mistral", selected.get(2).providerName(),
                    "Mistral should be third for SYSTEM_DESIGN (preferred over groq)");
        }

        @Test
        @DisplayName("SYSTEM_DESIGN picks kimi over together when available")
        void prefersKimiOverTogether() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", 1),
                    desc("together", 4),
                    desc("kimi", 11)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-2", TaskType.SYSTEM_DESIGN);

            assertEquals(3, selected.size());
            assertEquals("deepseek", selected.get(0).providerName());
            assertEquals("kimi", selected.get(1).providerName(),
                    "Kimi should be preferred over Together for SYSTEM_DESIGN");
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       DEBUGGING routing
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("DEBUGGING routing preferences")
    class DebuggingRoutingTests {

        @Test
        @DisplayName("DEBUGGING prefers deepseek, mistral, openrouter, groq")
        void prefersAnalyticalProvidersForDebugging() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("groq", 3),
                    desc("together", 4),
                    desc("deepseek", 1),
                    desc("openrouter", 2),
                    desc("mistral", 5)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-3", TaskType.DEBUGGING);

            assertEquals(3, selected.size());
            assertEquals("deepseek", selected.get(0).providerName(),
                    "DeepSeek should be first for DEBUGGING");
            assertEquals("mistral", selected.get(1).providerName(),
                    "Mistral should be second for DEBUGGING");
            assertEquals("openrouter", selected.get(2).providerName(),
                    "OpenRouter should be third for DEBUGGING");
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       GENERAL_REASONING routing (default priority ordering)
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("GENERAL_REASONING routing (default priority)")
    class GeneralReasoningRoutingTests {

        @Test
        @DisplayName("GENERAL_REASONING uses default priority ordering")
        void usesDefaultPriority() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("groq", 3),
                    desc("deepseek", 1),
                    desc("openrouter", 2),
                    desc("together", 4),
                    desc("mistral", 5)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-4", TaskType.GENERAL_REASONING);

            assertEquals(3, selected.size());
            assertEquals("deepseek", selected.get(0).providerName());
            assertEquals("openrouter", selected.get(1).providerName());
            assertEquals("groq", selected.get(2).providerName(),
                    "GENERAL_REASONING should use standard priority (groq=3 < together=4)");
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       CODING routing (default priority ordering)
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Task budget fan-out caps")
    class TaskBudgetFanOutTests {

        @Test
        @DisplayName("GENERAL_REASONING honors task budget fan-out cap")
        void generalReasoningHonorsTaskBudgetFanOutCap() {
            CouncilProperties.TaskBudgetConfig budget = new CouncilProperties.TaskBudgetConfig();
            budget.setMaxDraftProviders(2);
            properties.getOrchestrator().setTaskBudgets(Map.of("general-reasoning", budget));

            List<ProviderDescriptor> descriptors = List.of(
                    desc("groq", 3),
                    desc("deepseek", 1),
                    desc("openrouter", 2),
                    desc("together", 4)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-budget-1", TaskType.GENERAL_REASONING);

            assertEquals(2, selected.size());
            assertEquals("deepseek", selected.get(0).providerName());
            assertEquals("openrouter", selected.get(1).providerName());
        }

        @Test
        @DisplayName("DEBUGGING can keep deeper fan-out than GENERAL_REASONING")
        void debuggingKeepsDeeperFanOut() {
            CouncilProperties.TaskBudgetConfig general = new CouncilProperties.TaskBudgetConfig();
            general.setMaxDraftProviders(2);
            CouncilProperties.TaskBudgetConfig debugging = new CouncilProperties.TaskBudgetConfig();
            debugging.setMaxDraftProviders(3);
            properties.getOrchestrator().setTaskBudgets(Map.of(
                    "general-reasoning", general,
                    "debugging", debugging
            ));

            List<ProviderDescriptor> descriptors = List.of(
                    desc("groq", 3),
                    desc("deepseek", 1),
                    desc("openrouter", 2),
                    desc("mistral", 5)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-budget-2", TaskType.DEBUGGING);

            assertEquals(3, selected.size());
            assertEquals("deepseek", selected.get(0).providerName());
            assertEquals("mistral", selected.get(1).providerName());
            assertEquals("openrouter", selected.get(2).providerName());
        }
    }

    @Nested
    @DisplayName("CODING routing (default priority)")
    class CodingRoutingTests {

        @Test
        @DisplayName("CODING uses default priority ordering")
        void usesDefaultPriority() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("together", 4),
                    desc("deepseek", 1),
                    desc("groq", 3)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(
                    descriptors, adapters, "trace-5", TaskType.CODING);

            assertEquals(3, selected.size());
            assertEquals("deepseek", selected.get(0).providerName());
            assertEquals("groq", selected.get(1).providerName());
            assertEquals("together", selected.get(2).providerName());
        }
    }
}

