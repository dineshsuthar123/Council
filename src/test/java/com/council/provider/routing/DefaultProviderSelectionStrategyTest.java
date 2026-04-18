package com.council.provider.routing;

import com.council.config.CouncilProperties;
import com.council.provider.LlmAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultProviderSelectionStrategyTest {

    private CouncilProperties properties;
    private ProviderConcurrencyLimiter concurrencyLimiter;
    private DefaultProviderSelectionStrategy strategy;
    private Map<String, LlmAdapter> adapters;

    @BeforeEach
    void setUp() {
        properties = new CouncilProperties();
        properties.getRouting().setEnabled(true);
        properties.getRouting().setMaxDraftProviders(3);
        properties.getRouting().setMaxEscalationProviders(1);
        properties.getRouting().setEscalationConfidenceThreshold(0.45);
        properties.getRouting().setEscalationContradictionThreshold(0.70);

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

    private ProviderDescriptor desc(String name, boolean enabled, List<ProviderRole> roles,
                                     int priority, double reliability, boolean inCooldown) {
        return new ProviderDescriptor(name, name, name + "-model", enabled, roles, priority,
                reliability, 30, 5, List.of(), inCooldown, 0.0);
    }

    private ProviderDescriptor descWithFallbacks(String name, List<ProviderRole> roles,
                                                  int priority, List<String> fallbacks) {
        return new ProviderDescriptor(name, name, name + "-model", true, roles, priority,
                0.80, 30, 5, fallbacks, false, 0.0);
    }

    /* ══════════════════════════════════════════════════════════════════
       Draft Selection
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Draft provider selection")
    class DraftSelectionTests {

        @Test
        @DisplayName("Selects up to maxDraftProviders sorted by priority")
        void selectsDraftsByPriority() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("groq", true, List.of(ProviderRole.DRAFT), 3, 0.80, false),
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false),
                    desc("openrouter", true, List.of(ProviderRole.DRAFT), 2, 0.80, false),
                    desc("together", true, List.of(ProviderRole.DRAFT), 4, 0.78, false),
                    desc("mistral", true, List.of(ProviderRole.DRAFT), 5, 0.82, false)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-1");

            assertEquals(3, selected.size());
            assertEquals("deepseek", selected.get(0).providerName());
            assertEquals("openrouter", selected.get(1).providerName());
            assertEquals("groq", selected.get(2).providerName());
        }

        @Test
        @DisplayName("Skips disabled providers")
        void skipsDisabledProviders() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false),
                    desc("openrouter", false, List.of(ProviderRole.DRAFT), 2, 0.80, false),
                    desc("groq", true, List.of(ProviderRole.DRAFT), 3, 0.80, false)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-2");

            assertEquals(2, selected.size());
            assertTrue(selected.stream().noneMatch(a -> a.providerName().equals("openrouter")));
        }

        @Test
        @DisplayName("Skips providers in cooldown")
        void skipsProvidersInCooldown() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, true),
                    desc("openrouter", true, List.of(ProviderRole.DRAFT), 2, 0.80, false),
                    desc("groq", true, List.of(ProviderRole.DRAFT), 3, 0.80, false)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-3");

            assertEquals(2, selected.size());
            assertTrue(selected.stream().noneMatch(a -> a.providerName().equals("deepseek")));
        }

        @Test
        @DisplayName("Skips providers without DRAFT role")
        void skipsNonDraftProviders() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false),
                    desc("gemini", true, List.of(ProviderRole.CRITIC), 10, 0.92, false),
                    desc("groq", true, List.of(ProviderRole.DRAFT), 3, 0.80, false)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-4");

            assertEquals(2, selected.size());
            assertTrue(selected.stream().noneMatch(a -> a.providerName().equals("gemini")));
        }

        @Test
        @DisplayName("Returns empty when no draft providers available")
        void emptyWhenNoDraftProviders() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.CRITIC), 10, 0.92, false)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-5");

            assertTrue(selected.isEmpty());
        }

        @Test
        @DisplayName("Skips providers at max concurrency")
        void skipsMaxConcurrency() {
            // Register deepseek with max concurrency of 1 and exhaust it
            concurrencyLimiter.register("deepseek", 1);
            concurrencyLimiter.tryAcquire("deepseek"); // exhaust

            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false),
                    desc("openrouter", true, List.of(ProviderRole.DRAFT), 2, 0.80, false),
                    desc("groq", true, List.of(ProviderRole.DRAFT), 3, 0.80, false)
            );

            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-6");

            assertEquals(2, selected.size());
            assertTrue(selected.stream().noneMatch(a -> a.providerName().equals("deepseek")));

            concurrencyLimiter.release("deepseek"); // cleanup
        }

        @Test
        @DisplayName("Uses fallback providers when not enough drafts")
        void usesFallbackProviders() {
            properties.getRouting().setMaxDraftProviders(3);
            List<ProviderDescriptor> descriptors = List.of(
                    descWithFallbacks("deepseek", List.of(ProviderRole.DRAFT), 1,
                            List.of("openrouter", "mistral")),
                    descWithFallbacks("openrouter", List.of(ProviderRole.DRAFT), 2, List.of()),
                    descWithFallbacks("mistral", List.of(ProviderRole.DRAFT), 5, List.of())
            );

            // Only 2 would be selected by priority+maxDrafts=3, but 3 are available
            List<LlmAdapter> selected = strategy.selectDraftProviders(descriptors, adapters, "trace-7");

            assertEquals(3, selected.size());
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       Critic Selection
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Critic provider selection")
    class CriticSelectionTests {

        @Test
        @DisplayName("Selects highest-priority available critic")
        void selectsHighestPriorityCritic() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.CRITIC), 10, 0.92, false),
                    desc("deepseek", true, List.of(ProviderRole.DRAFT, ProviderRole.CRITIC), 1, 0.85, false),
                    desc("kimi", true, List.of(ProviderRole.CRITIC), 11, 0.78, false)
            );

            Optional<LlmAdapter> critic = strategy.selectCriticProvider(descriptors, adapters, "trace-10");

            assertTrue(critic.isPresent());
            assertEquals("deepseek", critic.get().providerName());
        }

        @Test
        @DisplayName("Falls back when preferred critic is in cooldown")
        void fallsBackWhenCriticInCooldown() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.CRITIC), 1, 0.85, true),
                    desc("gemini", true, List.of(ProviderRole.CRITIC), 10, 0.92, false),
                    desc("kimi", true, List.of(ProviderRole.CRITIC), 11, 0.78, false)
            );

            Optional<LlmAdapter> critic = strategy.selectCriticProvider(descriptors, adapters, "trace-11");

            assertTrue(critic.isPresent());
            assertEquals("gemini", critic.get().providerName());
        }

        @Test
        @DisplayName("Returns empty when no critic providers available")
        void emptyWhenNoCritic() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false)
            );

            Optional<LlmAdapter> critic = strategy.selectCriticProvider(descriptors, adapters, "trace-12");

            assertTrue(critic.isEmpty());
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       Premium Escalation
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Premium escalation selection")
    class EscalationTests {

        @Test
        @DisplayName("Triggers escalation when confidence below threshold")
        void triggersOnLowConfidence() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.PREMIUM_ESCALATION), 10, 0.92, false),
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false)
            );

            List<LlmAdapter> escalation = strategy.selectEscalationProviders(
                    descriptors, adapters, 0.30, 0.10, List.of("deepseek"), "trace-20");

            assertEquals(1, escalation.size());
            assertEquals("gemini", escalation.get(0).providerName());
        }

        @Test
        @DisplayName("Triggers escalation when contradiction severity above threshold")
        void triggersOnHighContradiction() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.PREMIUM_ESCALATION), 10, 0.92, false)
            );

            List<LlmAdapter> escalation = strategy.selectEscalationProviders(
                    descriptors, adapters, 0.80, 0.85, List.of("deepseek"), "trace-21");

            assertEquals(1, escalation.size());
        }

        @Test
        @DisplayName("Does NOT trigger when confidence OK and contradiction low")
        void doesNotTriggerWhenOk() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.PREMIUM_ESCALATION), 10, 0.92, false)
            );

            List<LlmAdapter> escalation = strategy.selectEscalationProviders(
                    descriptors, adapters, 0.80, 0.20, List.of("deepseek"), "trace-22");

            assertTrue(escalation.isEmpty());
        }

        @Test
        @DisplayName("Skips already-used providers")
        void skipsAlreadyUsed() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.PREMIUM_ESCALATION), 10, 0.92, false)
            );

            List<LlmAdapter> escalation = strategy.selectEscalationProviders(
                    descriptors, adapters, 0.30, 0.10, List.of("gemini"), "trace-23");

            assertTrue(escalation.isEmpty());
        }

        @Test
        @DisplayName("Triggers when all free providers failed (empty already-used)")
        void triggersWhenAllFailed() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.PREMIUM_ESCALATION), 10, 0.92, false)
            );

            List<LlmAdapter> escalation = strategy.selectEscalationProviders(
                    descriptors, adapters, 0.80, 0.10, List.of(), "trace-24");

            assertEquals(1, escalation.size());
        }

        @Test
        @DisplayName("Respects max escalation providers cap")
        void respectsMaxCap() {
            properties.getRouting().setMaxEscalationProviders(1);
            List<ProviderDescriptor> descriptors = List.of(
                    desc("gemini", true, List.of(ProviderRole.PREMIUM_ESCALATION), 10, 0.92, false),
                    desc("claude", true, List.of(ProviderRole.PREMIUM_ESCALATION), 12, 0.90, false)
            );
            adapters.put("claude", mockAdapter("claude"));

            List<LlmAdapter> escalation = strategy.selectEscalationProviders(
                    descriptors, adapters, 0.30, 0.10, List.of("deepseek"), "trace-25");

            assertEquals(1, escalation.size());
        }
    }

    /* ══════════════════════════════════════════════════════════════════
       Routing Decision Observability
       ══════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Routing decision observability")
    class ObservabilityTests {

        @Test
        @DisplayName("Records routing decision with skipped providers and reasons")
        void recordsRoutingDecision() {
            List<ProviderDescriptor> descriptors = List.of(
                    desc("deepseek", true, List.of(ProviderRole.DRAFT), 1, 0.85, false),
                    desc("openrouter", false, List.of(ProviderRole.DRAFT), 2, 0.80, false),
                    desc("gemini", true, List.of(ProviderRole.CRITIC), 10, 0.92, false),
                    desc("groq", true, List.of(ProviderRole.DRAFT), 3, 0.80, true)
            );

            strategy.selectDraftProviders(descriptors, adapters, "trace-30");
            RoutingDecision decision = strategy.getLastRoutingDecision();

            assertNotNull(decision);
            assertEquals("trace-30", decision.traceId());
            assertEquals(List.of("deepseek"), decision.selectedDraftProviders());
            assertFalse(decision.skippedProviders().isEmpty());

            List<String> skippedNames = decision.skippedProviders().stream()
                    .map(RoutingDecision.SkippedProvider::provider).toList();
            assertTrue(skippedNames.contains("openrouter"));
            assertTrue(skippedNames.contains("gemini"));
            assertTrue(skippedNames.contains("groq"));
        }
    }
}

