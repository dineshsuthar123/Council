package com.council.provider;

import com.council.model.CriticRequest;
import com.council.model.CriticResult;
import com.council.model.DraftRequest;
import com.council.model.DraftResult;
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
}

