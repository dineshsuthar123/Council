package com.council;

import com.council.api.dto.FinalResponse;
import com.council.orchestrator.ReasoningOrchestrator;
import com.council.trace.TraceEntity;
import com.council.trace.TraceRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gated live end-to-end test for the full reasoning pipeline.
 *
 * <p>Run explicitly:
 * <pre>{@code
 * mvn test -Dtest=LiveComplexReasoningTest -Dlive.provider.tests=true
 * }</pre>
 */
@Tag("live")
@EnabledIfSystemProperty(named = "live.provider.tests", matches = "true")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "council.orchestrator.max-retries=0",
        "council.orchestrator.draft-timeout-seconds=50",
        "council.orchestrator.critic-timeout-seconds=50",
        "council.orchestrator.verifier-timeout-seconds=40",
        "council.orchestrator.synthesis-timeout-seconds=50",

        "council.providers.nvidia.enabled=false",
        "council.providers.nvidia.api-key=${NVIDIA_LLAMA_API_KEY:}",
        "council.providers.nvidia.base-url=https://integrate.api.nvidia.com/v1",
        "council.providers.nvidia.model=meta/llama-3.3-70b-instruct",
        "council.providers.nvidia.timeout-seconds=45",
        "council.providers.nvidia.max-tokens=768",

        "council.providers.nvidia-deepseek.enabled=false",
        "council.providers.nvidia-deepseek.api-key=${NVIDIA_DEEPSEEK_API_KEY:}",
        "council.providers.nvidia-deepseek.base-url=https://integrate.api.nvidia.com/v1",
        "council.providers.nvidia-deepseek.model=deepseek-ai/deepseek-v3.2",
        "council.providers.nvidia-deepseek.timeout-seconds=30",
        "council.providers.nvidia-deepseek.max-tokens=512",

        "council.providers.gemini.enabled=true",
        "council.providers.gemini.api-key=${GEMINI_API_KEY:}",
        "council.providers.gemini.base-url=https://generativelanguage.googleapis.com",
        "council.providers.gemini.model=gemini-2.5-flash",
        "council.providers.gemini.timeout-seconds=45",
        "council.providers.gemini.max-tokens=2048",

        "council.providers.deepseek.enabled=false",
        "council.providers.deepseek.api-key=${DEEPSEEK_API_KEY:}",
        "council.providers.deepseek.base-url=https://api.deepseek.com",
        "council.providers.deepseek.model=deepseek-chat",
        "council.providers.deepseek.timeout-seconds=30",
        "council.providers.deepseek.max-tokens=512",

        "council.providers.groq.enabled=true",
        "council.providers.groq.api-key=${GROQ_API_KEY:}",
        "council.providers.groq.base-url=https://api.groq.com/openai",
        "council.providers.groq.model=llama-3.3-70b-versatile",
        "council.providers.groq.timeout-seconds=30",
        "council.providers.groq.max-tokens=2048",

        "council.providers.openrouter.enabled=true",
        "council.providers.openrouter.api-key=${OPENROUTER_NEMOTRON_API_KEY:}",
        "council.providers.openrouter.base-url=https://openrouter.ai/api",
        "council.providers.openrouter.model=nvidia/llama-3.1-nemotron-70b-instruct",
        "council.providers.openrouter.timeout-seconds=45",
        "council.providers.openrouter.max-tokens=768",

        "council.providers.openrouter-qwen.enabled=true",
        "council.providers.openrouter-qwen.api-key=${OPENROUTER_QWEN_API_KEY:}",
        "council.providers.openrouter-qwen.base-url=https://openrouter.ai/api",
        "council.providers.openrouter-qwen.model=qwen/qwen-2.5-72b-instruct",
        "council.providers.openrouter-qwen.timeout-seconds=40",
        "council.providers.openrouter-qwen.max-tokens=512",

        "council.providers.claude.enabled=true",
        "council.providers.claude.api-key=${CLAUDE_API_KEY:}",
        "council.providers.claude.base-url=https://api.blackbox.ai",
        "council.providers.claude.model=blackboxai/anthropic/claude-sonnet-4.6",
        "council.providers.claude.timeout-seconds=45",
        "council.providers.claude.max-tokens=4096",

        "council.critic.provider=openrouter",
        "council.synthesizer.provider=openrouter",
        "council.routing.enabled=true",
        "council.routing.max-draft-providers=3",
        "council.routing.max-escalation-providers=1",

        "council.routing.provider-routes.nvidia.roles[0]=DRAFT",
        "council.routing.provider-routes.nvidia.priority=1",
        "council.routing.provider-routes.nvidia.max-concurrency=1",
        "council.routing.provider-routes.nvidia-deepseek.roles[0]=EXPERIMENTAL",
        "council.routing.provider-routes.nvidia-deepseek.priority=20",
        "council.routing.provider-routes.nvidia-deepseek.max-concurrency=1",
        "council.routing.provider-routes.deepseek.roles[0]=EXPERIMENTAL",
        "council.routing.provider-routes.deepseek.priority=20",
        "council.routing.provider-routes.deepseek.max-concurrency=1",
        "council.routing.provider-routes.gemini.roles[0]=DRAFT",
        "council.routing.provider-routes.gemini.priority=1",
        "council.routing.provider-routes.gemini.max-concurrency=1",
        "council.routing.provider-routes.groq.roles[0]=DRAFT",
        "council.routing.provider-routes.groq.roles[1]=CRITIC",
        "council.routing.provider-routes.groq.priority=2",
        "council.routing.provider-routes.groq.max-concurrency=1",
        "council.routing.provider-routes.openrouter.roles[0]=CRITIC",
        "council.routing.provider-routes.openrouter.priority=10",
        "council.routing.provider-routes.openrouter.max-concurrency=1",
        "council.routing.provider-routes.openrouter-qwen.roles[0]=DRAFT",
        "council.routing.provider-routes.openrouter-qwen.priority=3",
        "council.routing.provider-routes.openrouter-qwen.max-concurrency=1",
        "council.routing.provider-routes.claude.roles[0]=CRITIC",
        "council.routing.provider-routes.claude.roles[1]=PREMIUM_ESCALATION",
        "council.routing.provider-routes.claude.priority=12",
        "council.routing.provider-routes.claude.max-concurrency=1",

        "logging.level.com.council.provider=INFO"
})
class LiveComplexReasoningTest {

    private static final String COMPLEX_PROMPT = """
            Debug a payment event pipeline where Kafka consumer lag spikes every hour,
            duplicate ledger writes appear during provider retries, and p99 latency rises
            from 350 ms to 2.5 s at 18k TPS. Provide root-cause hypotheses, concrete math,
            idempotency and ledger-consistency fixes, observability checks, rollout steps,
            and one provider-outage runbook.
            """;

    @Autowired
    private ReasoningOrchestrator orchestrator;

    @Autowired
    private TraceRepository traceRepository;

    @Test
    void complexDebuggingQuestionRunsThroughLivePipelineAndPersistsTrace() throws Exception {
        long start = System.currentTimeMillis();
        FinalResponse response = orchestrator.reason(COMPLEX_PROMPT);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("=== Live E2E traceId: " + response.traceId());
        System.out.println("=== Live E2E latency: " + elapsed + " ms");
        System.out.println("=== Live E2E usedProviders: " + response.usedProviders());
        System.out.println("=== Live E2E failedProviders: " + response.failedProviders());
        System.out.println("=== Live E2E confidence: " + response.confidence());
        System.out.println("=== Live E2E error: " + response.error());
        System.out.println("=== Live E2E answer: " + preview(response.finalAnswer()));

        assertThat(response.traceId()).isNotBlank();
        assertThat(response.error()).isNull();
        assertThat(response.usedProviders()).isNotEmpty();
        assertThat(response.finalAnswer()).isNotBlank();
        assertThat(response.confidence()).isGreaterThan(0.0);

        TraceEntity trace = waitForTrace(response.traceId());
        assertThat(trace.getFinalAnswer()).isNotBlank();
        assertThat(trace.getUsedProviders()).isNotBlank();
        assertThat(trace.getTotalLatencyMs()).isPositive();
    }

    private TraceEntity waitForTrace(String traceId) throws InterruptedException {
        UUID uuid = UUID.fromString(traceId);
        for (int i = 0; i < 20; i++) {
            var trace = traceRepository.findByTraceId(uuid);
            if (trace.isPresent()) {
                return trace.get();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Trace was not persisted for traceId=" + traceId);
    }

    private String preview(String answer) {
        if (answer == null) {
            return "";
        }
        String compact = answer.replaceAll("\\s+", " ").trim();
        return compact.length() <= 700 ? compact : compact.substring(0, 700) + "...";
    }
}
