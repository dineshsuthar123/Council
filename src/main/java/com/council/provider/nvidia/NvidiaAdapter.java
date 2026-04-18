package com.council.provider.nvidia;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.council.provider.openai.OpenAiCompatibleAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adapter for the NVIDIA NIM API (OpenAI-compatible).
 * <p>
 * Base URL: https://integrate.api.nvidia.com/v1
 * <p>
 * Two provider entries use this adapter class (via separate YAML blocks):
 * <ul>
 *   <li>{@code nvidia}        — meta/llama-3.3-70b-instruct  (DRAFT)</li>
 *   <li>{@code nvidia-deepseek} — deepseek-ai/deepseek-v3    (DRAFT)</li>
 * </ul>
 * NVIDIA does not accept {@code response_format: json_object} for all models,
 * so we override {@link #buildRequestBody} to omit that field.
 */
@Component
public class NvidiaAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "nvidia";

    public NvidiaAdapter(CouncilProperties properties,
                         ObjectMapper mapper,
                         JsonResponseNormalizer normalizer,
                         ResponseMapper responseMapper,
                         ProviderCallExecutor callExecutor,
                         OrchestrationMetrics metrics,
                         RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
                restClientFactory, Map.of());   // no extra headers required; Bearer is added by super
    }

    /**
     * The base-url already contains /v1 (https://integrate.api.nvidia.com/v1),
     * so the path must be /chat/completions — not /v1/chat/completions.
     */
    @Override
    protected String chatCompletionsPath() {
        return "/chat/completions";
    }

    /**
     * NVIDIA NIM rejects {@code response_format: json_object} for many hosted models.
     * Override the body builder to omit it; the orchestrator normalizer handles free-form text.
     */
    @Override
    protected java.util.Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "model",      config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "messages",   java.util.List.of(Map.of("role", "user", "content", prompt))
        );
    }
}
