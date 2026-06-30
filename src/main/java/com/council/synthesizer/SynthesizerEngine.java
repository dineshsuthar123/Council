package com.council.synthesizer;

import com.council.config.CouncilProperties;
import com.council.model.SynthesisRequest;
import com.council.model.SynthesisResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces a single synthesized final answer from drafts, verifier verdicts, and critic notes.
 */
@Service
public class SynthesizerEngine {

    private static final Logger log = LoggerFactory.getLogger(SynthesizerEngine.class);

    private static final List<String> SYNTHESIS_FALLBACK_CHAIN = List.of(
            "ollama-deepseek", "ollama-llama", "ollama-qwen-coder", "ollama-gemma",
            "openrouter", "claude", "gemini", "deepseek", "mistral", "kimi", "nvidia"
    );

    private final ProviderRegistry registry;
    private final String preferredProvider;

    public SynthesizerEngine(ProviderRegistry registry, CouncilProperties properties) {
        this.registry = registry;
        this.preferredProvider = properties.getSynthesizer().getProvider();
    }

    /**
     * Generate synthesized final answer. Never throws.
     */
    public SynthesisResult synthesize(SynthesisRequest request) {
        Optional<LlmAdapter> primary = registry.getCriticAdapter(preferredProvider);

        if (primary.isPresent()) {
            SynthesisResult result = attemptSynthesis(primary.get(), request);
            if (result.isSuccess()) {
                return result;
            }
            log.warn("[synthesizer] Primary synthesizer '{}' failed: {}, trying fallback chain",
                    primary.get().providerName(), result.errorMessage());
        } else {
            log.warn("[synthesizer] No primary synthesizer available, trying fallback chain");
        }

        String alreadyTried = primary.map(LlmAdapter::providerName).orElse("");
        return tryFallbackChain(request, alreadyTried);
    }

    private SynthesisResult attemptSynthesis(LlmAdapter adapter, SynthesisRequest request) {
        log.info("[synthesizer] Using provider '{}' model '{}' for synthesis",
                adapter.providerName(), adapter.modelName());
        try {
            return adapter.generateSynthesis(request);
        } catch (Exception e) {
            log.warn("[synthesizer] Exception during synthesis from '{}': {}",
                    adapter.providerName(), e.getMessage());
            return SynthesisResult.failure(adapter.providerName(), adapter.modelName(), e.getMessage(), 0);
        }
    }

    private SynthesisResult tryFallbackChain(SynthesisRequest request, String alreadyTriedProvider) {
        Map<String, LlmAdapter> allAdapters = registry.getAdaptersForCurrentMode();

        for (String fallbackName : SYNTHESIS_FALLBACK_CHAIN) {
            if (fallbackName.equals(alreadyTriedProvider)) {
                continue;
            }

            LlmAdapter fallback = allAdapters.get(fallbackName);
            if (fallback == null || !fallback.isEnabled()) {
                continue;
            }

            log.info("[synthesizer] Trying fallback synthesizer: {}", fallbackName);
            SynthesisResult result = attemptSynthesis(fallback, request);
            if (result.isSuccess()) {
                log.info("[synthesizer] Fallback synthesizer '{}' succeeded", fallbackName);
                return result;
            }

            log.warn("[synthesizer] Fallback synthesizer '{}' failed: {}",
                    fallbackName, result.errorMessage());
        }

        log.error("[synthesizer] All synthesizer providers exhausted");
        return SynthesisResult.failure("none", "none",
                "All synthesizer providers failed (tried primary + fallback chain)", 0);
    }
}
