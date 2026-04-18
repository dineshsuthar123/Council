package com.council.provider.openrouter;

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
 * Adapter for OpenRouter routing to qwen/qwen-2.5-72b-instruct (DRAFT role).
 * <p>
 * Registered under provider key {@code "openrouter-qwen"} so it picks up its own
 * YAML block and API key independently of the Nemotron critic adapter.
 */
@Component
public class OpenRouterQwenAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "openrouter-qwen";

    public OpenRouterQwenAdapter(CouncilProperties properties,
                                 ObjectMapper mapper,
                                 JsonResponseNormalizer normalizer,
                                 ResponseMapper responseMapper,
                                 ProviderCallExecutor callExecutor,
                                 OrchestrationMetrics metrics,
                                 RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
                restClientFactory,
                Map.of("HTTP-Referer", "https://council.app",
                       "X-Title",      "Council"));
    }
}
