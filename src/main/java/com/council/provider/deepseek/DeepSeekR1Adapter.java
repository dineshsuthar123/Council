package com.council.provider.deepseek;

import com.council.config.CouncilProperties;
import com.council.config.RestClientFactory;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.provider.ProviderCallExecutor;
import com.council.provider.ResponseMapper;
import com.council.provider.openai.OpenAiCompatibleAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adapter for the DeepSeek Official API targeting {@code deepseek-reasoner} (DeepSeek-R1).
 * <p>
 * Registered under provider key {@code "deepseek-r1"} so it reads the
 * {@code council.providers.deepseek-r1} YAML block independently of the legacy
 * {@code deepseek-chat} entry.
 *
 * <p><b>ESCALATION role:</b> R1 is the chain-of-thought model — it does <em>not</em> support
 * {@code response_format: json_object}, so we omit it in {@link #buildRequestBody}.
 * The JSON normalizer downstream handles free-form prose extraction.
 */
@Component
public class DeepSeekR1Adapter extends OpenAiCompatibleAdapter {

    private static final String PROVIDER = "deepseek-r1";

    public DeepSeekR1Adapter(CouncilProperties properties,
                             ObjectMapper mapper,
                             JsonResponseNormalizer normalizer,
                             ResponseMapper responseMapper,
                             ProviderCallExecutor callExecutor,
                             OrchestrationMetrics metrics,
                             RestClientFactory restClientFactory) {
        super(PROVIDER, properties, mapper, normalizer, responseMapper, callExecutor, metrics,
                restClientFactory, Map.of());
    }

    /**
     * deepseek-reasoner (R1) does not accept {@code response_format: json_object}.
     * Omit it; the normalizer extracts JSON from the chain-of-thought output.
     */
    @Override
    protected Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "model",      config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "messages",   List.of(Map.of("role", "user", "content", prompt))
        );
    }
}
