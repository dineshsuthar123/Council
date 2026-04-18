package com.council.provider.huggingface;

import com.council.common.exception.ProviderException;
import com.council.common.exception.RateLimitException;
import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.AbstractLlmAdapter;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Adapter for the Hugging Face Inference API.
 * Uses the HF serverless inference endpoint (non-OpenAI format).
 */
@Component
public class HuggingFaceAdapter extends AbstractLlmAdapter {

    private static final String PROVIDER = "huggingface";
    private final RestClient restClient;

    public HuggingFaceAdapter(CouncilProperties properties,
                              ObjectMapper mapper,
                              JsonResponseNormalizer normalizer,
                              ResponseMapper responseMapper,
                              ProviderCallExecutor callExecutor,
                              OrchestrationMetrics metrics,
                              RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics);

        if (config.isUsable()) {
            this.restClient = restClientFactory.create(
                    config.getBaseUrl(),
                    config.getTimeoutSeconds(),
                    Map.of("Authorization", "Bearer " + config.getApiKey()));
        } else {
            this.restClient = null;
        }
    }

    @Override
    protected String callApi(String prompt) {
        if (restClient == null) {
            throw new ProviderException(PROVIDER, "HuggingFace adapter is not configured");
        }
        try {
            // HF Inference API uses /models/{model} with {"inputs": "..."} body
            Map<String, Object> body = Map.of(
                    "inputs", prompt,
                    "parameters", Map.of(
                            "max_new_tokens", config.getMaxTokens(),
                            "return_full_text", false
                    )
            );

            String uri = "/models/" + config.getModel();

            String responseBody = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        throw new RateLimitException(PROVIDER);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new ProviderException(PROVIDER,
                                "Server error " + resp.getStatusCode());
                    })
                    .body(String.class);

            return extractText(responseBody);

        } catch (ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ProviderException(PROVIDER, "Network/timeout error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProviderException(PROVIDER, "Unexpected error: " + e.getMessage(), e);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            // HF returns [{"generated_text": "..."}]
            if (root.isArray() && !root.isEmpty()) {
                return root.get(0).path("generated_text").asText("");
            }
            // Some models return {"generated_text": "..."}
            if (root.has("generated_text")) {
                return root.path("generated_text").asText("");
            }
            throw new ProviderException(PROVIDER, "No content in HuggingFace response");
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(PROVIDER,
                    "Failed to parse HuggingFace response: " + e.getMessage(), e);
        }
    }
}

