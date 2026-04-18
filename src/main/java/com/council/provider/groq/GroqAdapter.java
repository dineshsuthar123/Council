package com.council.provider.groq;

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
 * Adapter for the Groq API (OpenAI-compatible, ultra-fast inference).
 */
@Component
public class GroqAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "groq";

    public GroqAdapter(CouncilProperties properties,
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

