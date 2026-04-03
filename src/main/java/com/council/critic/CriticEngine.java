package com.council.critic;

import com.council.config.CouncilProperties;
import com.council.model.CriticRequest;
import com.council.model.CriticResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Sends successful drafts to a single critic model and returns structured feedback.
 * <p>
 * If the preferred critic provider is unavailable, falls back to any available provider.
 * If no providers are available or the critic itself fails, a failure result is returned
 * so the judge can still score drafts (without contradiction penalties).
 */
@Service
public class CriticEngine {

    private static final Logger log = LoggerFactory.getLogger(CriticEngine.class);

    private final ProviderRegistry registry;
    private final String preferredProvider;

    public CriticEngine(ProviderRegistry registry, CouncilProperties properties) {
        this.registry = registry;
        this.preferredProvider = properties.getCritic().getProvider();
    }

    /**
     * Generate a critique of the supplied drafts.
     * Never throws – returns a failure result on any error.
     */
    public CriticResult critique(CriticRequest request) {
        Optional<LlmAdapter> adapter = registry.getCriticAdapter(preferredProvider);

        if (adapter.isEmpty()) {
            log.warn("[critic] No critic provider available – skipping critique");
            return CriticResult.failure("none", "none",
                    "No critic provider available", 0);
        }

        LlmAdapter critic = adapter.get();
        log.info("[critic] Using provider '{}' model '{}' for critique",
                critic.providerName(), critic.modelName());

        return critic.generateCritique(request);
    }
}

