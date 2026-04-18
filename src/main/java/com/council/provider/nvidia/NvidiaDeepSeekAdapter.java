package com.council.provider.nvidia;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.council.provider.openai.OpenAiCompatibleAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adapter for the NVIDIA NIM API running deepseek-ai/deepseek-v3.2.
 * Uses the same NVIDIA base URL as {@link NvidiaAdapter} but is registered
 * under the provider key {@code "nvidia-deepseek"} so it picks up its own
 * YAML block ({@code council.providers.nvidia-deepseek}).
 */
@Component
public class NvidiaDeepSeekAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "nvidia-deepseek";

    public NvidiaDeepSeekAdapter(CouncilProperties properties,
                                 ObjectMapper mapper,
                                 JsonResponseNormalizer normalizer,
                                 ResponseMapper responseMapper,
                                 ProviderCallExecutor callExecutor,
                                 OrchestrationMetrics metrics,
                                 RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
            restClientFactory, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE));
    }

    /**
     * The base-url already contains /v1 (https://integrate.api.nvidia.com/v1).
     */
    @Override
    protected String chatCompletionsPath() {
        return "/chat/completions";
    }

    /**
     * NVIDIA NIM does not support {@code response_format: json_object} for all models.
     */
    @Override
    protected Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "model",      config.getModel(),
                "max_tokens", 8192,
                "messages",   List.of(Map.of("role", "user", "content", prompt))
        );
    }
}
