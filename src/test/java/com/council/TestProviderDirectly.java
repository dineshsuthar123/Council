package com.council;

import com.council.model.DraftRequest;
import com.council.model.DraftResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * mvn test -Dtest=TestProviderDirectly -Dgroups=live
 * }</pre>
 *
 * <p>What to look for in the console output:
 * <ul>
 *   <li>DEBUG lines from {@code NvidiaAdapter} showing the outbound request JSON and masked key</li>
 *   <li>DEBUG lines showing the raw HTTP 200 response body</li>
 *   <li>The extracted answer printed at the end</li>
 * </ul>
 */
@Tag("live")
@SpringBootTest
@ActiveProfiles("test")   // use H2 + flyway-disabled — no Postgres needed
@TestPropertySource(properties = {
        // Re-enable nvidia on top of the test profile (TestPropertySource wins over yml)
        "council.providers.nvidia.enabled=true",
        "council.providers.nvidia.api-key=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",
        "council.providers.nvidia.base-url=https://integrate.api.nvidia.com/v1",
        "council.providers.nvidia.model=meta/llama-3.3-70b-instruct",
        "council.providers.nvidia.timeout-seconds=60",
        "council.providers.nvidia.max-tokens=512",
        // Verbose adapter logging
        "logging.level.com.council.provider=DEBUG"
})
class TestProviderDirectly {

    @Autowired
    private ProviderRegistry registry;

    /**
     * Calls the NVIDIA Llama-3.3-70B model with a trivial prompt to verify:
     * 1. The RestClient is built correctly (correct base URL, Bearer token)
     * 2. The HTTP POST reaches the NVIDIA NIM endpoint
     * 3. The response JSON is parsed into a {@link DraftResult}
     */
    @Test
    void nvidia_llama_connectivityCheck_simpleArithmetic() {
        LlmAdapter nvidia = registry.getAdapter("nvidia")
                .orElseThrow(() -> new AssertionError(
                        "NVIDIA adapter not found in registry. " +
                        "Is council.providers.nvidia.enabled=true and NVIDIA_LLAMA_API_KEY set?"));

        System.out.println("=== Provider: " + nvidia.providerName());
        System.out.println("=== Model:    " + nvidia.modelName());

        DraftResult result = nvidia.generateDraft(DraftRequest.of("live-test-001", "What is 2+2?"));

        System.out.println("=== Success:  " + result.isSuccess());
        System.out.println("=== Latency:  " + result.latencyMs() + " ms");
        System.out.println("=== Answer:   " + result.answer());
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
}
