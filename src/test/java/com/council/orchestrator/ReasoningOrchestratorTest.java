package com.council.orchestrator;

import com.council.api.dto.FinalResponse;
import com.council.config.CouncilProperties;
import com.council.critic.CriticEngine;
import com.council.events.PipelineEventBroadcaster;
import com.council.judge.DeterministicJudge;
import com.council.judge.PromptClassifier;
import com.council.judge.SpecificityScorer;
import com.council.judge.invariant.InvariantLibrary;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.provider.routing.ProviderSelectionStrategy;
import com.council.research.ResearchPack;
import com.council.research.ResearchPromptAugmenter;
import com.council.research.ResearchService;
import com.council.research.ResearchSource;
import com.council.synthesizer.SynthesizerEngine;
import com.council.trace.TraceService;
import com.council.verifier.VerifierEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ReasoningOrchestratorTest {

    private ProviderRegistry registry;
    private CriticEngine criticEngine;
    private VerifierEngine verifierEngine;
    private SynthesizerEngine synthesizerEngine;
    private DeterministicJudge judge;
    private TraceService traceService;
    private OrchestrationMetrics metrics;
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
        eventBroadcaster = mock(PipelineEventBroadcaster.class);
        researchService = mock(ResearchService.class);
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
        // Routing disabled for legacy tests
        props.getRouting().setEnabled(false);

        ProviderSelectionStrategy selectionStrategy = mock(ProviderSelectionStrategy.class);
        ProviderConcurrencyLimiter concurrencyLimiter = new ProviderConcurrencyLimiter();

        judge = new DeterministicJudge(props, new SpecificityScorer());
        when(verifierEngine.verify(anyString(), anyString(), anyList()))
                .thenReturn(VerifierBatchResult.success(Map.of()));
        when(synthesizerEngine.synthesize(any()))
                .thenReturn(SynthesisResult.failure("none", "none", "synthesis unavailable", 0));
        when(researchService.buildEvidencePack(anyString(), any()))
                .thenReturn(ResearchPack.notRequired());

        orchestrator = new ReasoningOrchestrator(registry, criticEngine, verifierEngine, synthesizerEngine, judge,
                new PromptClassifier(), traceService, metrics, props, selectionStrategy, concurrencyLimiter,
                eventBroadcaster, researchService, new ResearchPromptAugmenter());
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
        verify(eventBroadcaster).publish(eq(response.traceId()), eq("COMPLETE"), eq("done"),
                anyString(), anyLong(), argThat(details -> details.containsKey("response")));
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
        assertNull(response.modelAgreement(),
                "Agreement is not meaningful when only one valid draft survived");
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
        assertEquals(List.of("gemini", "deepseek"), response.failedProviders());
        assertTrue(response.usedProviders().isEmpty());
        verify(traceService).persistAsync(anyString(), eq("Test query"),
                argThat(drafts -> drafts.size() == 2
                        && drafts.stream().allMatch(d -> d.errorMessage() != null && !d.errorMessage().isBlank())),
                any(), any(), any(), anyLong());
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

    @Test
    @DisplayName("Research context is persisted when no providers are available")
    void researchContextPersistsWhenNoProvidersAvailable() {
        String query = "What is the latest model routing architecture?";
        ResearchPack pack = ResearchPack.unavailable(
                "Prompt asks for current information.",
                List.of("latest model routing architecture"),
                "TAVILY_API_KEY is not configured");
        when(researchService.buildEvidencePack(anyString(), any())).thenReturn(pack);
        when(registry.getAvailableDraftProviders()).thenReturn(List.of());

        FinalResponse response = orchestrator.reason(query);

        assertNotNull(response);
        assertNull(response.finalAnswer());
        assertEquals(pack, response.research());

        ArgumentCaptor<FinalResponse> responseCaptor = ArgumentCaptor.forClass(FinalResponse.class);
        verify(traceService).persistAsync(anyString(), eq(query), anyList(), any(), any(),
                responseCaptor.capture(), anyLong());
        assertNotNull(responseCaptor.getValue().research());
        assertTrue(responseCaptor.getValue().research().required());
        assertEquals("TAVILY_API_KEY is not configured",
                responseCaptor.getValue().research().errorMessage());
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
        assertNull(response.modelAgreement(),
                "A single successful provider has no cross-model agreement measurement");
    }

    @Test
    @DisplayName("Research evidence pack is supplied to draft providers")
    void researchEvidencePackIsSuppliedToDraftProviders() {
        ResearchPack pack = ResearchPack.withSources(
                "Prompt asks for current information.",
                List.of("latest model routing"),
                List.of(new ResearchSource("S1", "Routing source", "https://example.com/routing",
                        "example.com", "Routing selects models dynamically.", "2026-01-01", 0.9)));
        when(researchService.buildEvidencePack(anyString(), any())).thenReturn(pack);

        LlmAdapter adapter = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "Use dynamic model routing for current provider selection [S1].",
                        "Uses cited evidence", List.of(), List.of(), 0.90, 700, "raw"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(adapter));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("critic", "critic-model", "Critic unavailable", 0));

        FinalResponse response = orchestrator.reason("What is the latest model routing architecture?");

        ArgumentCaptor<DraftRequest> captor = ArgumentCaptor.forClass(DraftRequest.class);
        verify(adapter).generateDraft(captor.capture());
        assertTrue(captor.getValue().userQuery().contains("SHARED EXTERNAL RESEARCH CONTEXT"));
        assertTrue(captor.getValue().userQuery().contains("[S1] Routing source"));
        assertNotNull(response.research());
        assertTrue(response.research().hasSources());
        assertTrue(response.dimensions().containsKey("citation_accuracy"));
    }

    @Test
    @DisplayName("Synthesis confidence is capped for unsafe stale redirect answers")
    void synthesisConfidenceIsCappedForUnsafeStaleRedirectAnswer() {
        String synthesizedAnswer = """
                For the deleted short URL redirect, Redis may be degraded, PostgreSQL replicas may lag,
                and Kafka analytics consumers may be behind. Use a cache-aside lease mechanism:
                if another request is loading the alias, return a cached lease response indicating
                the data might be stale. Query PostgreSQL primary or a read replica depending on the
                configured consistency level. Send an invalidation request to Redis when the owner deletes it.
                """;
        LlmAdapter adapter = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.success("gemini", "gemini-2.5-pro",
                        """
                        Return 404. Use deleted_at/version on the primary DB, write a Redis DELETED
                        tombstone, bypass replicas during the lag window, coalesce cache misses with
                        singleflight, and treat Kafka analytics lag as dashboard-only.
                        Pseudocode: if tombstone return 404; else read primary; else redirect active version.
                        """,
                        "Strong stale deletion answer",
                        List.of(), List.of(), 0.95, 700, "raw"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(adapter));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("critic", "critic-model", "critic unavailable", 0));
        when(synthesizerEngine.synthesize(any())).thenReturn(SynthesisResult.success(
                "synth", "synth-model", synthesizedAnswer, "Unsafe lease response",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                0.95, 100, "raw-synthesis"));

        FinalResponse response = orchestrator.reason(urlShortenerIncidentQuery());

        assertTrue(response.finalAnswer().contains("### Decision"));
        assertTrue(response.finalAnswer().contains("### Concrete Algorithm"));
        assertTrue(response.finalAnswer().contains("Use a cache-aside lease mechanism"));
        assertTrue(response.confidence() <= 0.55,
                "Final response confidence must be capped after unsafe synthesis, not restored to 0.95");
    }

    @Test
    @DisplayName("Final response separates answer quality, winner confidence, and model agreement")
    void finalResponseSeparatesQualityConfidenceAndAgreement() {
        String synthesizedAnswer = """
                During a traffic spike with Redis partially degraded, PostgreSQL read replicas 2 seconds behind,
                and Kafka consumers lagging, the redirect endpoint should return 404 Not Found because the
                short URL was deleted 1 second ago. To prevent stale redirects after deletion, write a
                deleted_at/version/tombstone to the primary database, invalidate or overwrite Redis immediately,
                and store a short-lived DELETED/negative-cache tombstone. To handle cache stampede during Redis
                degradation, implement singleflight/request coalescing/per-key lock/distributed lock. The redirect
                path should not trust a PostgreSQL replica that can be 2 seconds stale and instead use the Redis
                tombstone, a primary read, or a deletion/version check that is safe under lag. Prioritize redirect
                correctness over analytics freshness and do not return a cached lease or maybe stale response to
                the browser. The pseudocode for the redirect path should be: IF Redis has valid redirect THEN
                return redirect; ELSE IF Redis has tombstone THEN return 404; ELSE IF primary DB read shows active
                THEN return redirect; ELSE return 404. The tradeoffs should prioritize redirect correctness and
                consistency over analytics freshness and availability. Monitor Redis p95/p99 latency, replica lag,
                Kafka consumer lag, tombstone hits, primary fallback rate, lock wait, dashboard freshness, and
                stale-cache prevention. A weaker system would trust Redis TTL alone, trust lagging replicas, return
                maybe-stale lease responses, skip tombstones, skip coalescing, and confuse analytics with truth.
                """;
        LlmAdapter nvidia = mockAdapter("nvidia", "nvidia-model",
                DraftResult.success("nvidia", "nvidia-model", synthesizedAnswer, "summary",
                        List.of(), List.of(), 0.95, 700, "raw"));
        LlmAdapter groq = mockAdapter("groq", "groq-model",
                DraftResult.success("groq", "groq-model", synthesizedAnswer, "summary",
                        List.of(), List.of(), 0.95, 700, "raw"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(nvidia, groq));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("critic", "critic-model", "critic unavailable", 0));
        when(synthesizerEngine.synthesize(any())).thenReturn(SynthesisResult.success(
                "synth", "synth-model", synthesizedAnswer, "Strong but not perfect",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                0.95, 100, "raw-synthesis"));

        FinalResponse response = orchestrator.reason(urlShortenerIncidentQuery());

        assertEquals(0.75, response.answerQuality(), 0.001,
                "Invariant cap should bound active-cache-before-tombstone pseudocode");
        assertEquals(response.answerQuality(), response.confidence(), 0.001);
        assertNotNull(response.winnerConfidence());
        assertNotNull(response.modelAgreement());
        assertNotNull(response.dimensions());
        assertNotNull(response.invariants());
        assertTrue(response.invariants().violations().stream()
                .anyMatch(violation -> InvariantLibrary.URL_TOMBSTONE_PRECEDES_ACTIVE_CACHE
                        .equals(violation.invariantId())));
        assertEquals(0.75, response.dimensions().get("invariant_url_shortener"), 0.001);
        assertTrue(response.dimensions().get("pseudocode") <= 0.75,
                "Active-cache-before-tombstone pseudocode should be visible in the dimension score");
        assertTrue(response.dimensions().get("deletion_safety") >= 0.90);
        assertTrue(response.modelAgreement() >= 0.90, "Equal strong drafts should show high agreement");
    }

    @Test
    @DisplayName("Final response repairs missing promised pseudocode before quality calibration")
    void finalResponseRepairsMissingPromisedPseudocode() {
        String synthesizedAnswer = """
                The redirect endpoint should return 404 Not Found or 410 Gone because the short URL was deleted
                1 second ago. Prevent stale redirects by writing deleted_at/version/tombstone to the primary DB,
                writing a Redis DELETED negative-cache tombstone, and never trusting a 2-second-lag PostgreSQL
                replica during the deletion-safety window. Use primary reads or safe version checks, coalesce
                cache misses with singleflight/request coalescing/per-key lock, and separate Kafka analytics lag
                from redirect correctness. A concrete algorithm/pseudocode for the redirect path is provided below.
                """;
        LlmAdapter nvidia = mockAdapter("nvidia", "nvidia-model",
                DraftResult.success("nvidia", "nvidia-model", synthesizedAnswer, "summary",
                        List.of(), List.of(), 0.95, 700, "raw"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(nvidia));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("critic", "critic-model", "critic unavailable", 0));
        when(synthesizerEngine.synthesize(any())).thenReturn(SynthesisResult.success(
                "synth", "synth-model", synthesizedAnswer, "Strong answer missing promised pseudocode",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                0.95, 100, "raw-synthesis"));

        FinalResponse response = orchestrator.reason(urlShortenerIncidentQuery() + "\nGive concrete pseudocode.");

        assertTrue(response.finalAnswer().contains("Concrete redirect algorithm"));
        assertTrue(response.finalAnswer().contains("RedirectResult resolve"));
        assertNotNull(response.dimensions());
        assertTrue(response.dimensions().get("pseudocode") >= 0.85);
        assertTrue(response.answerQuality() >= 0.74,
                "Completeness repair should prevent a mostly strong answer from being treated like a 55% failure");
    }

    @Test
    @DisplayName("Global invalidity: returns NO_VALID_DESIGN and skips synthesis")
    void allDraftsInvalid_stopsPipelineWithConstraintError() {
        LlmAdapter geminiAdapter = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "Gemini answer", "summary", List.of(), List.of(), 0.85, 900, "raw"));

        LlmAdapter deepseekAdapter = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.success("deepseek", "deepseek-chat",
                        "DeepSeek answer", "summary", List.of(), List.of(), 0.80, 950, "raw"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(geminiAdapter, deepseekAdapter));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("claude", "claude-test", "Critic unavailable", 0));
        when(verifierEngine.verify(anyString(), anyString(), anyList())).thenReturn(
                VerifierBatchResult.success(Map.of(
                        "gemini", VerifierVerdict.disqualifiedConsistency("constraint violation"),
                        "deepseek", VerifierVerdict.disqualifiedThroughput("constraint violation")
                )));

        FinalResponse response = orchestrator.reason("Design a payment system");

        assertNotNull(response);
        assertNull(response.finalAnswer());
        assertEquals("NO_VALID_DESIGN", response.error());
        assertEquals("All generated designs violate system constraints. Regeneration required.", response.message());
        assertTrue(response.usedProviders().isEmpty());
        assertTrue(response.failedProviders().contains("gemini"));
        assertTrue(response.failedProviders().contains("deepseek"));
        verify(synthesizerEngine, never()).synthesize(any());
    }

    @Test
    @DisplayName("Partial invalidity: only valid drafts move forward")
    void partialInvalidity_filtersInvalidDraftsBeforeSynthesis() {
        LlmAdapter geminiAdapter = mockAdapter("gemini", "gemini-2.5-pro",
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "Gemini answer", "summary", List.of(), List.of(), 0.90, 800, "raw-gemini"));

        LlmAdapter deepseekAdapter = mockAdapter("deepseek", "deepseek-chat",
                DraftResult.success("deepseek", "deepseek-chat",
                        "DeepSeek answer", "summary", List.of(), List.of(), 0.80, 900, "raw-deepseek"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(geminiAdapter, deepseekAdapter));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("claude", "claude-test", "Critic unavailable", 0));
        when(verifierEngine.verify(anyString(), anyString(), anyList())).thenReturn(
                VerifierBatchResult.success(Map.of(
                        "deepseek", VerifierVerdict.disqualifiedConsistency("constraint violation")
                )));

        FinalResponse response = orchestrator.reason("Design event pipeline");

        assertNotNull(response);
        assertEquals("Gemini answer", response.finalAnswer());
        assertEquals(List.of("gemini"), response.usedProviders());
        assertTrue(response.failedProviders().contains("deepseek"));
        verify(synthesizerEngine).synthesize(argThat(request ->
                request != null
                        && request.drafts().size() == 1
                        && "gemini".equals(request.drafts().getFirst().provider())));
    }

    /* ── Helper ────────────────────────────────────────────────────── */

    @Test
    @DisplayName("Debugging questions keep verifier verdicts advisory")
    void debuggingQuestion_doesNotTurnVerifierDisqualificationIntoNoValidDesign() {
        LlmAdapter groqAdapter = mockAdapter("groq", "llama-3.3-70b-versatile",
                DraftResult.success("groq", "llama-3.3-70b-versatile",
                        "Groq debug answer", "summary", List.of(), List.of(), 0.88, 700, "raw-groq"));

        when(registry.getAvailableDraftProviders()).thenReturn(List.of(groqAdapter));
        when(criticEngine.critique(any())).thenReturn(
                CriticResult.failure("claude", "claude-test", "Critic unavailable", 0));
        when(verifierEngine.verify(anyString(), anyString(), anyList())).thenReturn(
                VerifierBatchResult.success(Map.of(
                        "groq", VerifierVerdict.disqualifiedConsistency("strict design constraint mismatch")
                )));

        FinalResponse response = orchestrator.reason(
                "Debug a payment event pipeline where Kafka lag and duplicate ledger writes happen during retries");

        assertNotNull(response);
        assertNull(response.error());
        assertEquals("Groq debug answer", response.finalAnswer());
        assertEquals(List.of("groq"), response.usedProviders());
        assertTrue(response.failedProviders().isEmpty());
        verify(synthesizerEngine).synthesize(argThat(request ->
                request != null
                        && request.drafts().size() == 1
                        && "groq".equals(request.drafts().getFirst().provider())
                        && !request.verifierBatchResult()
                                .verdictForProvider("groq")
                                .isDisqualified()));
    }

    @Test
    @DisplayName("Draft timeout returns promptly and marks slow provider failed")
    void draftTimeoutCancelsSlowProviderWait() {
        ReasoningOrchestrator timeoutOrchestrator = orchestratorWithDraftTimeoutSeconds(1);
        LlmAdapter slow = mock(LlmAdapter.class);
        when(slow.providerName()).thenReturn("slow");
        when(slow.modelName()).thenReturn("slow-model");
        when(slow.isEnabled()).thenReturn(true);
        when(slow.generateDraft(any())).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return DraftResult.success("slow", "slow-model",
                    "late answer", "summary", List.of(), List.of(), 0.5, 10_000, "raw");
        });

        long start = System.nanoTime();
        List<DraftResult> results = timeoutOrchestrator.runDraftPhase(
                List.of(slow), DraftRequest.of("trace-timeout", "slow query"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, results.size());
        assertFalse(results.getFirst().isSuccess());
        assertTrue(results.getFirst().errorMessage().contains("timed out"));
        assertTrue(elapsedMillis < 3_000, "draft phase should not wait for the sleeping provider");
    }

    @Test
    @DisplayName("Per-provider deadline fails slow provider before full draft budget expires")
    void perProviderDeadlineFailsSlowProviderBeforeDraftBudget() {
        ReasoningOrchestrator timeoutOrchestrator =
                orchestratorWithDraftBudget(10, 10, 1, false, 0.90);
        LlmAdapter slow = mock(LlmAdapter.class);
        when(slow.providerName()).thenReturn("slow");
        when(slow.modelName()).thenReturn("slow-model");
        when(slow.isEnabled()).thenReturn(true);
        when(slow.generateDraft(any())).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return DraftResult.success("slow", "slow-model",
                    "late answer", "summary", List.of(), List.of(), 0.5, 10_000, "raw");
        });

        long start = System.nanoTime();
        List<DraftResult> results = timeoutOrchestrator.runDraftPhase(
                List.of(slow), DraftRequest.of("trace-provider-timeout", "slow query"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, results.size());
        assertFalse(results.getFirst().isSuccess());
        assertTrue(results.getFirst().errorMessage().contains("per-provider deadline"));
        assertTrue(elapsedMillis < 3_000, "provider deadline should beat the larger draft budget");
    }

    @Test
    @DisplayName("Early stop cancels pending drafts when a sufficient answer arrives")
    void earlyStopCancelsPendingDraftsWhenQualityIsSufficient() {
        ReasoningOrchestrator earlyStopOrchestrator =
                orchestratorWithDraftBudget(10, 10, 10, true, 0.80);
        LlmAdapter fast = mockAdapter("fast", "fast-model",
                DraftResult.success("fast", "fast-model",
                        "fast strong answer", "summary", List.of(), List.of(), 0.92, 100, "raw"));
        LlmAdapter slow = mock(LlmAdapter.class);
        when(slow.providerName()).thenReturn("slow");
        when(slow.modelName()).thenReturn("slow-model");
        when(slow.isEnabled()).thenReturn(true);
        when(slow.generateDraft(any())).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return DraftResult.success("slow", "slow-model",
                    "late answer", "summary", List.of(), List.of(), 0.95, 10_000, "raw");
        });

        long start = System.nanoTime();
        List<DraftResult> results = earlyStopOrchestrator.runDraftPhase(
                List.of(fast, slow), DraftRequest.of("trace-early-stop", "simple query"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertTrue(results.get(1).errorMessage().contains("early stop"));
        assertTrue(elapsedMillis < 3_000, "early stop should cancel the pending slow provider");
    }

    private LlmAdapter mockAdapter(String name, String model, DraftResult result) {
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.providerName()).thenReturn(name);
        when(adapter.modelName()).thenReturn(model);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.generateDraft(any())).thenReturn(result);
        return adapter;
    }

    private String urlShortenerIncidentQuery() {
        return """
                A URL-shortener uses Redis for redirect cache, PostgreSQL read replicas, Kafka analytics,
                and redirects. Redis is degraded, replicas are 2 seconds behind, Kafka consumers lag,
                and the short URL was deleted 1 second ago.
                """;
    }

    private ReasoningOrchestrator orchestratorWithDraftTimeoutSeconds(int timeoutSeconds) {
        return orchestratorWithDraftBudget(timeoutSeconds, timeoutSeconds, timeoutSeconds, false, 0.90);
    }

    private ReasoningOrchestrator orchestratorWithDraftBudget(int draftTimeoutSeconds,
                                                              int requestTimeoutSeconds,
                                                              int perProviderDeadlineSeconds,
                                                              boolean earlyStopEnabled,
                                                              double earlyStopThreshold) {
        ProviderRegistry localRegistry = mock(ProviderRegistry.class);
        CriticEngine localCritic = mock(CriticEngine.class);
        VerifierEngine localVerifier = mock(VerifierEngine.class);
        SynthesizerEngine localSynthesizer = mock(SynthesizerEngine.class);
        TraceService localTraceService = mock(TraceService.class);
        OrchestrationMetrics localMetrics = new OrchestrationMetrics(new SimpleMeterRegistry());
        ProviderSelectionStrategy localSelectionStrategy = mock(ProviderSelectionStrategy.class);

        CouncilProperties props = new CouncilProperties();
        props.getOrchestrator().setDraftTimeoutSeconds(draftTimeoutSeconds);
        props.getOrchestrator().setRequestTimeoutSeconds(requestTimeoutSeconds);
        props.getOrchestrator().setPerProviderDeadlineSeconds(perProviderDeadlineSeconds);
        props.getOrchestrator().setEarlyStopEnabled(earlyStopEnabled);
        props.getOrchestrator().setEarlyStopQualityThreshold(earlyStopThreshold);
        props.getOrchestrator().setCriticTimeoutSeconds(1);
        props.getOrchestrator().setVerifierTimeoutSeconds(1);
        props.getOrchestrator().setSynthesisTimeoutSeconds(1);

        return new ReasoningOrchestrator(localRegistry, localCritic, localVerifier, localSynthesizer,
                new DeterministicJudge(props, new SpecificityScorer()), new PromptClassifier(),
                localTraceService, localMetrics, props, localSelectionStrategy, new ProviderConcurrencyLimiter(),
                mock(PipelineEventBroadcaster.class), mock(ResearchService.class), new ResearchPromptAugmenter());
    }
}


