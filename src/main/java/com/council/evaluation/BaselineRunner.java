package com.council.evaluation;

import com.council.evaluation.dto.BaselineResultResponse;
import com.council.model.DraftRequest;
import com.council.model.DraftResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Runs single-model baselines by directly calling individual provider adapters.
 * Reuses the existing {@link LlmAdapter#generateDraft} abstraction.
 */
@Component
public class BaselineRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineRunner.class);

    private final ProviderRegistry registry;
    private final KeywordMatcher keywordMatcher;

    public BaselineRunner(ProviderRegistry registry, KeywordMatcher keywordMatcher) {
        this.registry = registry;
        this.keywordMatcher = keywordMatcher;
    }

    /**
     * Run single-model baselines for a given prompt.
     *
     * @param prompt           the user query
     * @param providerSubset   if non-empty, only run these providers; otherwise run all enabled
     * @param expectedAnswer   optional expected answer for scoring
     * @param expectedKeywords optional keywords for scoring
     * @return map of provider name → baseline result
     */
    public Map<String, BaselineResultResponse> runBaselines(
            String prompt,
            List<String> providerSubset,
            String expectedAnswer,
            List<String> expectedKeywords) {

        List<LlmAdapter> adapters = resolveAdapters(providerSubset);
        Map<String, BaselineResultResponse> results = new LinkedHashMap<>();

        for (LlmAdapter adapter : adapters) {
            String provider = adapter.providerName();
            try {
                String traceId = UUID.randomUUID().toString();
                DraftRequest request = DraftRequest.of(traceId, prompt);

                long start = System.currentTimeMillis();
                DraftResult draft = adapter.generateDraft(request);
                long latency = System.currentTimeMillis() - start;

                if (draft.isSuccess()) {
                    Double kwScore = keywordMatcher.combinedScore(
                            draft.answer(), expectedAnswer, expectedKeywords);

                    results.put(provider, new BaselineResultResponse(
                            provider,
                            draft.answer(),
                            draft.confidence(),
                            latency,
                            draft.answer() != null ? draft.answer().length() : 0,
                            kwScore,
                            null
                    ));
                } else {
                    results.put(provider, new BaselineResultResponse(
                            provider, null, null, latency, null, null,
                            draft.errorMessage()
                    ));
                }

                log.debug("[baseline] {} completed in {}ms, success={}",
                        provider, latency, draft.isSuccess());

            } catch (Exception e) {
                log.warn("[baseline] {} failed: {}", provider, e.getMessage());
                results.put(provider, new BaselineResultResponse(
                        provider, null, null, null, null, null, e.getMessage()));
            }
        }

        return results;
    }

    private List<LlmAdapter> resolveAdapters(List<String> providerSubset) {
        if (providerSubset != null && !providerSubset.isEmpty()) {
            return providerSubset.stream()
                    .map(name -> registry.getAdapter(name))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
        return registry.getAvailableDraftProviders();
    }
}

