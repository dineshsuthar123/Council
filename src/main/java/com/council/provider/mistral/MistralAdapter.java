package com.council.provider.mistral;

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
 * Adapter for the Mistral AI API (OpenAI-compatible).
 */
@Component
public class MistralAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "mistral";

    public MistralAdapter(CouncilProperties properties,
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

