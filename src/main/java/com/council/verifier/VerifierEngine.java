package com.council.verifier;

import com.council.model.DraftResult;
import com.council.model.VerifierBatchRequest;
import com.council.model.VerifierBatchResult;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Runs a strict batch verifier pass over all successful drafts in one API call.
 *
 * The verifier is intentionally routed to models with stronger logical consistency.
 */
@Service
public class VerifierEngine {

    private static final Logger log = LoggerFactory.getLogger(VerifierEngine.class);

    private static final List<String> VERIFIER_PROVIDER_CHAIN = List.of(
            "openrouter-qwen", // qwen-2.5-72b
            "nvidia-deepseek", // deepseek-v3.x via NVIDIA
            "deepseek",
            "groq",
            "gemini",
            "nvidia"
    );

    private final ProviderRegistry registry;

    public VerifierEngine(ProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Verify all successful drafts in one batch call.
     * Fail-open: verifier transport failures do not disqualify drafts.
     */
    public VerifierBatchResult verify(String traceId, String userQuery, List<DraftResult> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return VerifierBatchResult.success(Map.of());
        }

        VerifierBatchRequest request = new VerifierBatchRequest(traceId, userQuery, drafts);
        Map<String, LlmAdapter> adapters = registry.getAllAdapters();

        for (String providerName : VERIFIER_PROVIDER_CHAIN) {
            LlmAdapter adapter = adapters.get(providerName);
            if (adapter == null || !adapter.isEnabled()) {
                continue;
            }

            log.info("[verifier] Using provider '{}' model '{}' for batch verification",
                    adapter.providerName(), adapter.modelName());
            VerifierBatchResult result = adapter.generateBatchVerification(request);
            if (result == null) {
                continue;
            }
            if (!result.isInternalError()) {
                return result;
            }

            log.warn("[verifier] Provider '{}' failed: {}",
                    adapter.providerName(), result.internalErrorReason());
        }

        log.warn("[verifier] No verifier provider succeeded; proceeding without disqualification for all drafts");
        return VerifierBatchResult.passedFor(drafts);
    }
}
