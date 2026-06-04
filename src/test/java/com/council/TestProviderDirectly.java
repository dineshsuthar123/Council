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
 * JSON parsing, and adapter wiring — without running the full orchestrator pipeline.
 *
 * <p><b>This test hits real APIs and requires live API keys.</b><br>
 * It is tagged {@code "live"} so it is excluded from normal CI builds.
 *
 * <p>Run it explicitly:
 * <pre>{@code
 * $env:NVIDIA_LLAMA_API_KEY="..."
 * mvn test -Dtest=TestProviderDirectly -Dlive.provider.tests=true
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
        // Re-enable nvidia on top of the test profile (TestPropertySource wins over yml)
        "council.providers.nvidia.enabled=true",
        "council.providers.nvidia.api-key=${NVIDIA_LLAMA_API_KEY:}",
        "council.providers.nvidia.base-url=https://integrate.api.nvidia.com/v1",
        "council.providers.nvidia.model=meta/llama-3.3-70b-instruct",
        "council.providers.nvidia.timeout-seconds=45",
        "council.providers.nvidia.max-tokens=512",
        "council.orchestrator.max-retries=0",
        "logging.level.com.council.provider=INFO"
})
class TestProviderDirectly {

    @Autowired
    private ProviderRegistry registry;

    /**
     * Calls the NVIDIA Llama-3.3-70B model with a complex systems prompt to verify:
     * 1. The RestClient is built correctly (correct base URL, Bearer token)
     * 2. The HTTP POST reaches the NVIDIA NIM endpoint
     * 3. The response JSON is parsed into a {@link DraftResult}
     */
    @Test
    void nvidia_llama_connectivityCheck_complexArchitecturePrompt() {
        LlmAdapter nvidia = registry.getAdapter("nvidia")
                .orElseThrow(() -> new AssertionError(
                        "NVIDIA adapter not found in registry. " +
                        "Is council.providers.nvidia.enabled=true and NVIDIA_LLAMA_API_KEY set?"));

        System.out.println("=== Provider: " + nvidia.providerName());
        System.out.println("=== Model:    " + nvidia.modelName());

        String prompt = System.getProperty("live.provider.prompt", """
                Analyze a payment orchestration service at 50,000 TPS across card, UPI, and wallet providers.
                Be concise but include ledger consistency, idempotency, Kafka partition math, DLQ capacity,
                p99 latency budget, and one concrete provider-outage runbook.
                """);

        DraftResult result = nvidia.generateDraft(DraftRequest.of("live-test-complex-001", prompt));

        System.out.println("=== Success:  " + result.isSuccess());
        System.out.println("=== Latency:  " + result.latencyMs() + " ms");
        System.out.println("=== Answer:   " + preview(result.answer()));
        if (!result.isSuccess()) {
            System.out.println("=== Error:    " + result.errorMessage());
        }

        assertThat(result.isSuccess())
                .as("Expected a successful response from NVIDIA NIM.\n" +
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
