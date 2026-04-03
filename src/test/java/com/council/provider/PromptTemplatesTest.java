package com.council.provider;

import com.council.model.DraftResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplatesTest {

    @Test
    @DisplayName("Draft prompt contains user query and JSON schema instructions")
    void buildDraftPrompt_containsQueryAndSchema() {
        String prompt = PromptTemplates.buildDraftPrompt("What is gravity?");

        assertTrue(prompt.contains("What is gravity?"));
        assertTrue(prompt.contains("\"answer\""));
        assertTrue(prompt.contains("\"summary\""));
        assertTrue(prompt.contains("\"assumptions\""));
        assertTrue(prompt.contains("\"uncertainties\""));
        assertTrue(prompt.contains("\"confidence\""));
        assertTrue(prompt.contains("STRICT JSON"));
    }

    @Test
    @DisplayName("Critic prompt contains user query, drafts, and critic schema")
    void buildCriticPrompt_containsQueryDraftsAndSchema() {
        List<DraftResult> drafts = List.of(
                DraftResult.success("gemini", "gemini-2.5-pro",
                        "Gravity is a force", "Force between masses",
                        List.of("Newtonian"), List.of("quantum effects"), 0.85, 500, "raw"),
                DraftResult.success("claude", "claude-test",
                        "Gravity curves spacetime", "GR explanation",
                        List.of("GR applies"), List.of("edge cases"), 0.90, 600, "raw2")
        );

        String prompt = PromptTemplates.buildCriticPrompt("What is gravity?", drafts);

        assertTrue(prompt.contains("What is gravity?"));
        assertTrue(prompt.contains("gemini"));
        assertTrue(prompt.contains("claude"));
        assertTrue(prompt.contains("Gravity is a force"));
        assertTrue(prompt.contains("Gravity curves spacetime"));
        assertTrue(prompt.contains("\"globalSummary\""));
        assertTrue(prompt.contains("\"contradictionSeverity\""));
        assertTrue(prompt.contains("\"contradictionsFound\""));
        assertTrue(prompt.contains("\"missingPoints\""));
        assertTrue(prompt.contains("\"riskyClaims\""));
        assertTrue(prompt.contains("critic"));
    }

    @Test
    @DisplayName("Critic prompt includes draft separator between multiple drafts")
    void buildCriticPrompt_separatesDrafts() {
        List<DraftResult> drafts = List.of(
                DraftResult.success("a", "m", "ans1", "sum1", List.of(), List.of(), 0.8, 100, "r"),
                DraftResult.success("b", "m", "ans2", "sum2", List.of(), List.of(), 0.7, 200, "r")
        );

        String prompt = PromptTemplates.buildCriticPrompt("q", drafts);
        assertTrue(prompt.contains("---"), "Drafts should be separated by ---");
    }

    @Test
    @DisplayName("Draft prompt includes confidence range instruction")
    void buildDraftPrompt_includesConfidenceRange() {
        String prompt = PromptTemplates.buildDraftPrompt("test");
        assertTrue(prompt.contains("0.0"));
        assertTrue(prompt.contains("1.0"));
    }
}

