package com.council.provider;

import com.council.model.DraftResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt templates for draft generation and critic evaluation.
 * All templates produce instructions that demand strict JSON output.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    public static String buildDraftPrompt(String userQuery) {
        return """
                You are a reasoning model participating in a multi-model evaluation system.
                
                Task:
                Answer the user's query with depth, correctness, and clarity.
                
                Rules:
                - Output STRICT JSON only.
                - Do not include markdown fences.
                - Do not include explanations outside JSON.
                - Be concise but complete.
                - If uncertain, explicitly say so.
                - Confidence must be a number between 0.0 (completely uncertain) and 1.0 (absolutely certain).
                
                Required JSON schema:
                {
                  "answer": "string",
                  "summary": "string",
                  "assumptions": ["string"],
                  "uncertainties": ["string"],
                  "confidence": 0.0
                }
                
                User Query:
                """ + userQuery;
    }

    public static String buildCriticPrompt(String userQuery, List<DraftResult> drafts) {
        String draftSummaries = drafts.stream()
                .map(d -> formatDraftForCritic(d))
                .collect(Collectors.joining("\n\n---\n\n"));

        return """
                You are the critic in a multi-model reasoning system.
                
                You will receive multiple candidate answers to the same user query.
                
                Your job:
                - find contradictions
                - identify weak logic
                - identify risky claims
                - identify missing important points
                - summarize which answer is strongest and why
                
                Rules:
                - Output STRICT JSON only.
                - Do not include markdown fences.
                - Do not include explanations outside JSON.
                - Evaluate fairly and critically.
                
                Required JSON schema:
                {
                  "globalSummary": "string",
                  "contradictionSeverity": 0.0,
                  "contradictionCountPerDraft": {
                    "providerName": 0
                  },
                  "contradictionsFound": [
                    {
                      "draftA": "string",
                      "draftB": "string",
                      "issue": "string"
                    }
                  ],
                  "missingPoints": ["string"],
                  "riskyClaims": ["string"]
                }
                
                User Query:
                """ + userQuery + "\n\nCandidate Drafts:\n\n" + draftSummaries;
    }

    private static String formatDraftForCritic(DraftResult draft) {
        return "Draft from %s (confidence: %.2f):\nAnswer: %s\nSummary: %s\nAssumptions: %s\nUncertainties: %s"
                .formatted(
                        draft.provider(),
                        draft.confidence(),
                        draft.answer(),
                        draft.summary(),
                        String.join(", ", draft.assumptions()),
                        String.join(", ", draft.uncertainties())
                );
    }
}

