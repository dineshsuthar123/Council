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
 * Adapter for the OpenRouter API (OpenAI-compatible with HTTP-Referer header).
 */
@Component
public class OpenRouterAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "openrouter";

    public OpenRouterAdapter(CouncilProperties properties,
                             ObjectMapper mapper,
                             JsonResponseNormalizer normalizer,
                             ResponseMapper responseMapper,
                             ProviderCallExecutor callExecutor,
                             OrchestrationMetrics metrics,
                             RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
                restClientFactory,
                Map.of("HTTP-Referer", "https://council.app",
                       "X-Title", "Council"));
    }
}

