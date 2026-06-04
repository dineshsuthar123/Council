package com.council;

import com.council.model.DraftRequest;
import com.council.model.DraftResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live integration test to isolate and verify a single provider's HTTP connectivity,
 * JSON parsing, and adapter wiring, without running the full orchestrator pipeline.
 *
 * <p><b>This test hits real APIs and requires live API keys.</b><br>
 * It is tagged {@code "live"} so it is excluded from normal CI builds.
 *
 * <p>Run it explicitly:
 * <pre>{@code
 * mvn test -Dtest=TestProviderDirectly -Dlive.provider.tests=true
 * mvn test -Dtest=TestProviderDirectly -Dlive.provider.tests=true -Dlive.provider.name=claude
 * }</pre>
 *
 * <p>What to look for in the console output:
 * <ul>
 *   <li>Provider/model identification</li>
 *   <li>Success/failure and latency</li>
 *   <li>A short answer preview</li>
 * </ul>
 */
@Tag("live")
@EnabledIfSystemProperty(named = "live.provider.tests", matches = "true")
@SpringBootTest
@ActiveProfiles("test")   // use H2 + flyway-disabled — no Postgres needed
@TestPropertySource(properties = {
        "council.providers.nvidia.enabled=true",
        "council.providers.nvidia.api-key=${NVIDIA_LLAMA_API_KEY:}",
        "council.providers.nvidia.base-url=https://integrate.api.nvidia.com/v1",
        "council.providers.nvidia.model=meta/llama-3.3-70b-instruct",
        "council.providers.nvidia.timeout-seconds=45",
        "council.providers.nvidia.max-tokens=512",
        "council.providers.claude.enabled=true",
        "council.providers.claude.api-key=${CLAUDE_API_KEY:}",
        "council.providers.claude.base-url=https://api.blackbox.ai",
        "council.providers.claude.model=blackboxai/anthropic/claude-sonnet-4.6",
        "council.providers.claude.timeout-seconds=45",
        "council.providers.claude.max-tokens=4096",
        "council.providers.openrouter.enabled=true",
        "council.providers.openrouter.api-key=${OPENROUTER_NEMOTRON_API_KEY:}",
        "council.providers.openrouter.base-url=https://openrouter.ai/api",
        "council.providers.openrouter.model=nvidia/llama-3.1-nemotron-70b-instruct",
        "council.providers.openrouter.timeout-seconds=45",
        "council.providers.openrouter.max-tokens=512",
        "council.providers.gemini.enabled=true",
        "council.providers.gemini.api-key=${GEMINI_API_KEY:}",
        "council.providers.gemini.base-url=https://generativelanguage.googleapis.com",
        "council.providers.gemini.model=gemini-2.5-flash",
        "council.providers.gemini.timeout-seconds=45",
        "council.providers.gemini.max-tokens=512",
        "council.orchestrator.max-retries=0",
        "logging.level.com.council.provider=DEBUG"
})
class TestProviderDirectly {

    @Autowired
    private ProviderRegistry registry;

    /**
     * Calls one configured provider with a complex systems prompt to verify:
     * 1. The RestClient is built correctly (correct base URL, Bearer token)
     * 2. The HTTP POST reaches the provider endpoint
     * 3. The response JSON is parsed into a {@link DraftResult}
     */
    @Test
    void directProvider_connectivityCheck_complexArchitecturePrompt() {
        String providerName = System.getProperty("live.provider.name", "nvidia");
        LlmAdapter provider = registry.getAdapter(providerName)
                .orElseThrow(() -> new AssertionError(
                        "Adapter not found or not enabled: " + providerName +
                        ". Check the provider's API key environment variable."));

        System.out.println("=== Provider: " + provider.providerName());
        System.out.println("=== Model:    " + provider.modelName());

        String prompt = System.getProperty("live.provider.prompt", """
                In 5 concise bullets, explain how to keep payment ledger writes idempotent during provider retries.
                Include one tiny equation and one provider-outage runbook step.
                """);

        DraftResult result = provider.generateDraft(DraftRequest.of("live-test-complex-001", prompt));

        System.out.println("=== Success:  " + result.isSuccess());
        System.out.println("=== Latency:  " + result.latencyMs() + " ms");
        System.out.println("=== Answer:   " + preview(result.answer()));
        if (!result.isSuccess()) {
            System.out.println("=== Error:    " + result.errorMessage());
        }

        assertThat(result.isSuccess())
                .as("Expected a successful response from " + provider.providerName() + ".\n" +
                    "If it failed, check the DEBUG logs above for the raw HTTP status and body.\n" +
                    "Error: " + result.errorMessage())
                .isTrue();

        assertThat(result.answer())
                .as("Expected a non-empty answer from the model")
                .isNotBlank();
    }

    private String preview(String answer) {
        if (answer == null) {
            return "";
        }
        String compact = answer.replaceAll("\\s+", " ").trim();
        return compact.length() <= 600 ? compact : compact.substring(0, 600) + "...";
    }
}
