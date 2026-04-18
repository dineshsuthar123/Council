package com.council.critic;

import com.council.config.CouncilProperties;
import com.council.model.CriticRequest;
import com.council.model.CriticResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.provider.routing.ProviderDescriptor;
import com.council.provider.routing.ProviderRole;
import com.council.provider.routing.ProviderSelectionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Sends successful drafts to a single critic model and returns structured feedback.
 * <p>
 * When routing is enabled, critic selection is delegated to {@link ProviderSelectionStrategy}.
 * Otherwise falls back to the legacy preferred-provider approach.
 * <p>
 * <b>Aggressive fallback:</b> If the primary critic fails, tries a hardcoded chain
 * of strong reasoning providers (gemini → deepseek → mistral → kimi) so that
 * the critic is almost never absent for important prompts.
 */
@Service
public class CriticEngine {

    private static final Logger log = LoggerFactory.getLogger(CriticEngine.class);

    /**
     * Aggressive fallback chain for critic – ordered by reasoning strength.
     * These are tried in order if the primary critic selection fails or errors out.
     */
    private static final List<String> CRITIC_FALLBACK_CHAIN =
            List.of("gemini", "deepseek", "mistral", "kimi");

    private final ProviderRegistry registry;
    private final ProviderSelectionStrategy selectionStrategy;
    private final String preferredProvider;

    public CriticEngine(ProviderRegistry registry,
                        CouncilProperties properties,
                        ProviderSelectionStrategy selectionStrategy) {
        this.registry = registry;
        this.selectionStrategy = selectionStrategy;
        this.preferredProvider = properties.getCritic().getProvider();
    }

    /**
     * Generate a critique of the supplied drafts.
     * Never throws – returns a failure result on any error.
     * <p>
     * Tries the primary critic first, then aggressively falls back through
     * the fallback chain to ensure a critic result is almost always available.
     */
    public CriticResult critique(CriticRequest request) {
        // 1. Try the primary critic selection
        Optional<LlmAdapter> adapter = selectPrimaryCritic(request.traceId());

        if (adapter.isPresent()) {
            CriticResult result = attemptCritique(adapter.get(), request);
            if (result.isSuccess()) {
                return result;
            }
            log.warn("[critic] Primary critic '{}' failed: {}, trying fallback chain",
                    adapter.get().providerName(), result.errorMessage());
        } else {
            log.warn("[critic] No primary critic available, trying fallback chain");
        }

        // 2. Aggressive fallback chain
        String primaryName = adapter.map(LlmAdapter::providerName).orElse("");
        return tryFallbackChain(request, primaryName);
    }

    /* ── Private helpers ─────────────────────────────────────────────── */

    private Optional<LlmAdapter> selectPrimaryCritic(String traceId) {
        if (registry.isRoutingEnabled()) {
            return selectionStrategy.selectCriticProvider(
                    registry.buildDescriptors(),
                    registry.getAllAdapters(),
                    traceId);
        }
        return registry.getCriticAdapter(preferredProvider);
    }

    private CriticResult attemptCritique(LlmAdapter critic, CriticRequest request) {
        log.info("[critic] Using provider '{}' model '{}' for critique",
                critic.providerName(), critic.modelName());
        try {
            return critic.generateCritique(request);
        } catch (Exception e) {
            log.warn("[critic] Exception during critique from '{}': {}",
                    critic.providerName(), e.getMessage());
            return CriticResult.failure(critic.providerName(), critic.modelName(),
                    e.getMessage(), 0);
        }
    }

    /**
     * Walk the fallback chain, skipping the already-tried primary.
     */
    private CriticResult tryFallbackChain(CriticRequest request, String alreadyTriedProvider) {
        Map<String, LlmAdapter> allAdapters = registry.getAllAdapters();

        for (String fallbackName : CRITIC_FALLBACK_CHAIN) {
            if (fallbackName.equals(alreadyTriedProvider)) continue;

            LlmAdapter fallback = allAdapters.get(fallbackName);
            if (fallback == null || !fallback.isEnabled()) continue;

            log.info("[critic] Trying fallback critic: {}", fallbackName);
            CriticResult result = attemptCritique(fallback, request);
            if (result.isSuccess()) {
                log.info("[critic] Fallback critic '{}' succeeded", fallbackName);
                return result;
            }
            log.warn("[critic] Fallback critic '{}' failed: {}", fallbackName, result.errorMessage());
        }

        log.error("[critic] All critic providers exhausted – no critique available");
        return CriticResult.failure("none", "none",
                "All critic providers failed (tried primary + fallback chain)", 0);
    }
}
