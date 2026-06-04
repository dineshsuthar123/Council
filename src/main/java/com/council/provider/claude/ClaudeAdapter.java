package com.council.provider.claude;

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
 * Adapter for Claude models routed through BLACKBOX AI's OpenAI-compatible API.
 *
 * The provider key intentionally remains "claude" so existing routing, metrics,
 * and tests continue to identify this as the Claude-quality slot. The
 * credential is supplied through CLAUDE_API_KEY, but the API host is Blackbox AI,
 * not Anthropic.
 */
@Component
public class ClaudeAdapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "claude";

    public ClaudeAdapter(CouncilProperties properties,
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

