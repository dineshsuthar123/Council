package com.council.provider;

import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.VerifierBatchResult;
import com.council.model.VerifierVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertTrue(prompt.contains("PRINCIPAL ENGINEER CONSTRAINTS"));
        assertTrue(prompt.contains("FINANCIAL INTEGRITY"));
        assertTrue(prompt.contains("PRODUCTION MATH"));
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
        assertTrue(prompt.contains("\"mathCorrectnessScore\""));
        assertTrue(prompt.contains("\"feasibilityScore\""));
        assertTrue(prompt.contains("\"failureDepthScore\""));
        assertTrue(prompt.contains("CRITICAL: YOU MUST OUTPUT ONLY VALID JSON"));
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

    @Test
    @DisplayName("Verifier prompt contains strict math and consistency checks")
    void buildVerifierPrompt_containsStrictChecks() {
        DraftResult draft = DraftResult.success("deepseek", "deepseek-chat",
                "4,000,000,000 KB = 4 GB", "summary", List.of(), List.of(), 0.9, 100, "raw");

        String prompt = PromptTemplates.buildVerifierPrompt("Validate this architecture", draft);

        assertTrue(prompt.contains("You are a FINAL CONSTRAINT ENFORCER"));
        assertTrue(prompt.contains("You MUST ONLY decide VALID or REJECT"));
        assertTrue(prompt.contains("RULE 1: THROUGHPUT LIMIT"));
        assertTrue(prompt.contains("required_partitions = ceil(inputTPS / max_per_partition)"));
        assertTrue(prompt.contains("RULE 2: DLQ CAPACITY"));
        assertTrue(prompt.contains("dlq_load_per_partition = dlq_tps / dlq_partitions"));
        assertTrue(prompt.contains("RULE 3: LATENCY REALITY"));
        assertTrue(prompt.contains("RULE 4: INTERNAL CONSISTENCY"));
        assertTrue(prompt.contains("RULE 5: FAIL FAST"));
        assertTrue(prompt.contains("\"valid\""));
        assertTrue(prompt.contains("\"reason\""));
        assertTrue(prompt.contains("\"constraint violation\""));
        assertTrue(prompt.contains("\"verdicts\""));
        assertTrue(prompt.contains("\"drafts\""));
        assertTrue(prompt.contains("\"id\""));
        assertTrue(prompt.contains("\"content\""));
        assertTrue(prompt.contains("ONLY PASS or REJECT"));
    }

        @Test
        @DisplayName("Synthesizer prompt contains merge rules and required JSON schema")
        void buildSynthesizerPrompt_containsRulesAndSchema() {
        List<DraftResult> drafts = List.of(
            DraftResult.success("gemini", "gemini-2.5-pro",
                "Use PostgreSQL ledger", "strong consistency",
                List.of("single region"), List.of(), 0.91, 400, "raw-a"),
            DraftResult.success("deepseek", "deepseek-chat",
                "Use Kafka with retry jitter", "robust async processing",
                List.of(), List.of("unknown peak"), 0.86, 450, "raw-b")
        );

        VerifierBatchResult verifier = VerifierBatchResult.success(Map.of(
            "gemini", VerifierVerdict.passed(),
            "deepseek", VerifierVerdict.passed()
        ));

        CriticResult critic = CriticResult.success(
            "openrouter",
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "Both drafts have value; combine ledger consistency with stronger failure handling.",
            0.2,
            Map.of("gemini", 0, "deepseek", 1),
            List.of(),
            List.of(),
            List.of("throughput estimate is under-explained"),
            300,
            "raw-critic"
        );

        String prompt = PromptTemplates.buildSynthesizerPrompt("Design payment architecture", drafts, verifier, critic);

        assertTrue(prompt.contains("You are the SYNTHESIZER"));
        assertTrue(prompt.contains("Merge, do not vote"));
        assertTrue(prompt.contains("Hard reject invalid content"));
        assertTrue(prompt.contains("Resolve conflicts decisively"));
        assertTrue(prompt.contains("synthesizedAnswer"));
        assertTrue(prompt.contains("mergedStrengths"));
        assertTrue(prompt.contains("discardedClaims"));
        assertTrue(prompt.contains("confidence"));
        }
}

