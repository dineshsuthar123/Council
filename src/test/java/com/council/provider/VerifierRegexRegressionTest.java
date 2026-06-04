package com.council.provider;

import com.council.config.CouncilProperties;
import com.council.json.JsonExtractor;
import com.council.json.JsonResponseNormalizer;
import com.council.json.SchemaValidator;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.DraftResult;
import com.council.model.VerifierBatchRequest;
import com.council.model.VerifierBatchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifierRegexRegressionTest {

    @Test
    @DisplayName("Verifier live path does not return INTERNAL_ERROR for large malicious payload")
    void verifierLivePathLargePayloadNoInternalError() {
        ObjectMapper mapper = new ObjectMapper();
        JsonResponseNormalizer normalizer = new JsonResponseNormalizer(mapper, new JsonExtractor(), new SchemaValidator());
        ResponseMapper responseMapper = new ResponseMapper();
        ProviderCallExecutor callExecutor = mock(ProviderCallExecutor.class);
        OrchestrationMetrics metrics = new OrchestrationMetrics(new SimpleMeterRegistry());

        CouncilProperties properties = new CouncilProperties();
        CouncilProperties.ProviderConfig providerConfig = new CouncilProperties.ProviderConfig();
        providerConfig.setEnabled(true);
        providerConfig.setModel("test-model");
        providerConfig.setApiKey("dummy");
        Map<String, CouncilProperties.ProviderConfig> providers = new HashMap<>();
        providers.put("test-provider", providerConfig);
        properties.setProviders(providers);

        when(callExecutor.execute(eq("test-provider"), any())).thenReturn(buildLargeVerifierPayloadWithMissingComma());

        TestAdapter adapter = new TestAdapter(
                "test-provider",
                properties,
                mapper,
                normalizer,
                responseMapper,
                callExecutor,
                metrics
        );

        DraftResult draft = DraftResult.success(
                "deepseek",
                "deepseek-v3",
                "answer",
                "summary",
                List.of(),
                List.of(),
                0.9,
                5,
                "raw"
        );

        VerifierBatchRequest request = new VerifierBatchRequest("trace-1", "query", List.of(draft));

        VerifierBatchResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> adapter.generateBatchVerification(request)
        );

        assertFalse(result.isInternalError());
        assertNotNull(result.verdictForProvider("deepseek"));
        verify(callExecutor, times(1)).execute(eq("test-provider"), any());
    }

    private String buildLargeVerifierPayloadWithMissingComma() {
        String hostileValue = "\\\\".repeat(40_000);
        String escaped = hostileValue.replace("\\", "\\\\");

        return """
                {
                  "verdicts": {
                    "deepseek": {
                      "valid": true,
                      "note": "%s"
                      "fatal": false,
                      "math": {
                        "steps": [
                          {
                            "op": "add",
                            "a": 1,
                            "b": 2,
                            "result": 3,
                            "ok": true
                          }
                        ],
                        "allOk": true
                      },
                      "consistency": {
                        "throughputValid": true,
                        "storageValid": true,
                        "latencyValid": true
                      },
                      "errors": []
                    }
                  }
                }
                """.formatted(escaped);
    }

    private static final class TestAdapter extends AbstractLlmAdapter {

        TestAdapter(String provider,
                    CouncilProperties properties,
                    ObjectMapper mapper,
                    JsonResponseNormalizer normalizer,
                    ResponseMapper responseMapper,
                    ProviderCallExecutor callExecutor,
                    OrchestrationMetrics metrics) {
            super(provider, properties, mapper, normalizer, responseMapper, callExecutor, metrics);
        }

        @Override
        protected String callApi(String prompt) {
            return "{}";
        }
    }
}
