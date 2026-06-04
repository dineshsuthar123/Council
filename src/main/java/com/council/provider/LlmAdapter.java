package com.council.provider;

import com.council.model.CriticRequest;
import com.council.model.CriticResult;
import com.council.model.DraftRequest;
import com.council.model.DraftResult;
import com.council.model.SynthesisRequest;
import com.council.model.SynthesisResult;
import com.council.model.VerifierBatchRequest;
import com.council.model.VerifierBatchResult;
import com.council.model.VerifierRequest;
import com.council.model.VerifierResult;

/**
 * Common interface implemented by every LLM provider adapter.
 */
public interface LlmAdapter {

    /** Short lowercase name, e.g. "claude", "gemini", "deepseek". */
    String providerName();

    /** Model identifier, e.g. "claude-sonnet-4-20250514". */
    String modelName();

    /** Whether this adapter is configured and enabled. */
    boolean isEnabled();

    /** Generate a draft answer. Must never throw – returns a failure result on error. */
    DraftResult generateDraft(DraftRequest request);

    /** Generate a critique of multiple drafts. Must never throw. */
    CriticResult generateCritique(CriticRequest request);

    /**
     * Generate a verifier verdict for a single draft.
     * Default implementation is fail-open so legacy adapters still compile.
     */
    default VerifierResult generateVerification(VerifierRequest request) {
        return VerifierResult.internalError("Verification is not implemented by adapter " + providerName());
    }

    /**
     * Generate verifier verdicts for all successful drafts in a single batch call.
     * Default implementation is fail-open so legacy adapters still compile.
     */
    default VerifierBatchResult generateBatchVerification(VerifierBatchRequest request) {
        return VerifierBatchResult.internalError(
                "Batch verification is not implemented by adapter " + providerName());
    }

    /**
     * Generate a synthesized final answer from all validated drafts.
     */
    default SynthesisResult generateSynthesis(SynthesisRequest request) {
        return SynthesisResult.failure(providerName(), modelName(),
                "Synthesis is not implemented by adapter " + providerName(), 0);
    }
}

