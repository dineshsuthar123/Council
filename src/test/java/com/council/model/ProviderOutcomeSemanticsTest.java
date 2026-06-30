package com.council.model;

import com.council.common.exception.ProviderFailureCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOutcomeSemanticsTest {

    @Test
    void earlyStoppedProvidersAreSkippedNotFailed() {
        DraftResult skipped = DraftResult.skipped("blackbox-gpt55", "gpt-5.5",
                ProviderOutcomeStatus.SKIPPED_EARLY_STOP,
                "Draft skipped: early stop after valid draft confidence 0.95 >= 0.94");

        ProviderOutcome outcome = ProviderOutcome.from(skipped);

        assertEquals(ProviderOutcomeStatus.SKIPPED_EARLY_STOP, outcome.status());
        assertFalse(outcome.attempted());
        assertFalse(outcome.validDraftProduced());
        assertFalse(skipped.isFailure());
        assertTrue(skipped.isSkipped());
    }

    @Test
    void skippedProvidersDoNotAppearInFailedAttemptDiagnostics() {
        List<DraftResult> drafts = List.of(
                DraftResult.success("groq", "llama", "answer", "summary", List.of(), List.of(), 0.95, 10, "raw"),
                DraftResult.skipped("blackbox-gpt55", "gpt-5.5", ProviderOutcomeStatus.SKIPPED_EARLY_STOP,
                        "Draft skipped: sufficient diversity"));

        ProviderRunDiagnostics diagnostics = ProviderRunDiagnostics.from(drafts);

        assertEquals(0, diagnostics.failedAttempts());
        assertEquals(1, diagnostics.skippedEarlyStopProviders());
        assertEquals(0.5, diagnostics.providerCoverage());
        assertEquals(0.5, diagnostics.attemptCoverage());
    }

    @Test
    void attemptedProviderFailureAppearsInFailedList() {
        DraftResult failed = DraftResult.failure("blackbox-gemini", "gemini",
                "Provider request timed out", 1200,
                ProviderFailureDetails.local("blackbox-gemini", "gemini", ProviderFailureCategory.TIMEOUT,
                        "Provider request timed out", 1200));

        ProviderOutcome outcome = ProviderOutcome.from(failed);

        assertEquals(ProviderOutcomeStatus.FAILED, outcome.status());
        assertTrue(outcome.attempted());
        assertTrue(failed.isFailure());
    }

    @Test
    void missingKeyProviderIsUnavailableBeforeItIsReportedAsFailed() {
        DraftResult missingKey = DraftResult.failure("blackbox-claude", "claude",
                "API key not configured", 0,
                ProviderFailureDetails.local("blackbox-claude", "claude", ProviderFailureCategory.API_KEY_MISSING,
                        "API key not configured", 0));

        ProviderOutcome outcome = ProviderOutcome.from(missingKey);

        assertEquals(ProviderOutcomeStatus.UNAVAILABLE_API_KEY_MISSING, outcome.status());
        assertFalse(outcome.attempted());
        assertFalse(missingKey.isFailure());
    }

    @Test
    void usedProvidersContainOnlyValidDrafts() {
        List<ProviderOutcome> outcomes = ProviderOutcome.fromDraftResults(List.of(
                DraftResult.success("groq", "llama", "answer", "summary", List.of(), List.of(), 0.90, 10, "raw"),
                DraftResult.skipped("blackbox-gpt55", "gpt", ProviderOutcomeStatus.SKIPPED_EARLY_STOP, "early stop"),
                DraftResult.failure("blackbox-nemotron", "nemotron", "bad response", 11)
        ));

        assertEquals(List.of("groq"), outcomes.stream().filter(ProviderOutcome::validDraftProduced)
                .map(ProviderOutcome::providerId).toList());
    }
}
