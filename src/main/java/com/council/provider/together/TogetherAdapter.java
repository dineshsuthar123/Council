package com.council.provider.together;

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
 * Adapter for the Together AI API (OpenAI-compatible).
 */
@Component
public class TogetherAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "together";

    public TogetherAdapter(CouncilProperties properties,
                           ObjectMapper mapper,
                           JsonResponseNormalizer normalizer,
                           ResponseMapper responseMapper,
                           ProviderCallExecutor callExecutor,
                           OrchestrationMetrics metrics,
                           RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
                restClientFactory, Map.of());
    }
}

