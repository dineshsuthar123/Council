package com.council.provider;

import com.council.common.CouncilConstants;
import com.council.common.exception.JsonNormalizationException;
import com.council.common.exception.ProviderException;
import com.council.config.CouncilProperties;
import com.council.json.JsonResponseNormalizer;
import com.council.metrics.OrchestrationMetrics;
import com.council.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Template base class for all LLM adapters.
 * <p>
 * Subclasses only implement {@link #callApi(String)} which sends a prompt
 * to the provider and returns the raw text content from the response.
 * <p>
 * Resilience (retry, circuit-breaker) is delegated to {@link ProviderCallExecutor}.
 * JSON→DTO mapping is delegated to {@link ResponseMapper}.
 */
public abstract class AbstractLlmAdapter implements LlmAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final String provider;
    protected final CouncilProperties.ProviderConfig config;
    protected final ObjectMapper mapper;
    private final JsonResponseNormalizer normalizer;
    private final ResponseMapper responseMapper;
    private final ProviderCallExecutor callExecutor;
    private final OrchestrationMetrics metrics;

    protected AbstractLlmAdapter(String provider,
                                 CouncilProperties properties,
                                 ObjectMapper mapper,
                                 JsonResponseNormalizer normalizer,
                                 ResponseMapper responseMapper,
                                 ProviderCallExecutor callExecutor,
                                 OrchestrationMetrics metrics) {
        this.provider = provider;
        this.config = properties.getProviders().getOrDefault(provider,
                new CouncilProperties.ProviderConfig());
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.responseMapper = responseMapper;
        this.callExecutor = callExecutor;
        this.metrics = metrics;
    }

    /* ── public contract (never throws) ────────────────────────────── */

    @Override
    public String providerName() { return provider; }

    @Override
    public String modelName() { return config.getModel(); }

    @Override
    public boolean isEnabled() { return config.isUsable(); }

    @Override
    public DraftResult generateDraft(DraftRequest request) {
        long start = System.currentTimeMillis();
        MDC.put(CouncilConstants.MDC_PROVIDER, provider);
        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
        try {
            String prompt = PromptTemplates.buildDraftPrompt(request.userQuery());
            String rawResponse = callExecutor.execute(provider, () -> callApi(prompt));
            long latency = System.currentTimeMillis() - start;

            JsonNode node = normalizer.normalizeDraft(provider, rawResponse);
            DraftResult result = responseMapper.mapToDraftResult(provider, config.getModel(),
                    node, rawResponse, latency);

            metrics.recordProviderCall(provider, "SUCCESS", latency);
            log.info("[{}] Draft generated in {}ms, confidence={}", provider, latency, result.confidence());
            return result;

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            metrics.recordProviderCall(provider, "FAILURE", latency);
            if (e instanceof JsonNormalizationException) {
                metrics.recordInvalidJson(provider);
            }
            log.warn("[{}] Draft generation failed after {}ms: {}", provider, latency, e.getMessage());
            return DraftResult.failure(provider, config.getModel(), e.getMessage(), latency);
        } finally {
            MDC.remove(CouncilConstants.MDC_PROVIDER);
        }
    }

    @Override
    public CriticResult generateCritique(CriticRequest request) {
        long start = System.currentTimeMillis();
        MDC.put(CouncilConstants.MDC_PROVIDER, provider);
        MDC.put(CouncilConstants.MDC_TRACE_ID, request.traceId());
        try {
            String prompt = PromptTemplates.buildCriticPrompt(request.userQuery(), request.drafts());
            String rawResponse = callExecutor.execute(provider, () -> callApi(prompt));
            long latency = System.currentTimeMillis() - start;

            JsonNode node = normalizer.normalizeCritic(provider, rawResponse);
            CriticResult result = responseMapper.mapToCriticResult(provider, config.getModel(),
                    node, rawResponse, latency);

            metrics.recordProviderCall(provider, "SUCCESS", latency);
            metrics.recordCriticLatency(latency);
            log.info("[{}] Critique generated in {}ms", provider, latency);
            return result;

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            metrics.recordProviderCall(provider, "FAILURE", latency);
            if (e instanceof JsonNormalizationException) {
                metrics.recordInvalidJson(provider);
            }
            log.warn("[{}] Critique failed after {}ms: {}", provider, latency, e.getMessage());
            return CriticResult.failure(provider, config.getModel(), e.getMessage(), latency);
        } finally {
            MDC.remove(CouncilConstants.MDC_PROVIDER);
        }
    }

    /* ── subclass hook ─────────────────────────────────────────────── */

    /**
     * Send the prompt to the provider's HTTP API and return the raw text
     * content from the model's response. Implementations should throw:
     * <ul>
     *   <li>{@link com.council.common.exception.RateLimitException} on HTTP 429</li>
     *   <li>{@link ProviderException} on 5xx / network / timeout</li>
     * </ul>
     */
    protected abstract String callApi(String prompt);
}

