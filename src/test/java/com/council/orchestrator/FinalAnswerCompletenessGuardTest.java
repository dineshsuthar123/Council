package com.council.orchestrator;

import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinalAnswerCompletenessGuardTest {

    @Test
    @DisplayName("Repairs URL shortener answer that promises pseudocode but omits it")
    void repairsMissingPseudocodeForUrlShortenerIncident() {
        String repaired = FinalAnswerCompletenessGuard.repair(urlShortenerPrompt(),
                """
                Return 404 or 410. Use deleted_at/version/tombstone in the primary database, Redis
                negative-cache tombstones, primary reads during replica lag, singleflight, and separate
                Kafka analytics lag from redirect correctness. A concrete algorithm/pseudocode is provided below.
                """);

        assertTrue(repaired.contains("Concrete redirect algorithm"));
        assertTrue(repaired.contains("RedirectResult resolve"));
        assertTrue(repaired.contains("cached == DELETED"));
        assertTrue(repaired.contains("primaryDb.findByAlias"));
        assertTrue(repaired.contains("singleflight"));
    }

    @Test
    @DisplayName("Does not modify answers that already include concrete pseudocode")
    void doesNotModifyCompletePseudocode() {
        String answer = """
                Pseudocode:
                if (cached == DELETED) return 404;
                if (active.version >= minKnownDeleteVersion(alias)) return redirect(active.longUrl);
                return singleflight(alias, () -> primaryDb.findByAlias(alias));
                tombstone primary analytics kafka redis postgres deleted redirect
                """;

        assertEquals(answer, FinalAnswerCompletenessGuard.repair(urlShortenerPrompt(), answer));
    }

    @Test
    @DisplayName("Does not repair unrelated prompts")
    void doesNotRepairUnrelatedPrompt() {
        String answer = "A concrete algorithm is provided below.";

        assertEquals(answer, FinalAnswerCompletenessGuard.repair("Explain gravity", answer));
    }

    @Test
    @DisplayName("Composes URL shortener answer into operator-readable template")
    void composesUrlShortenerTemplate() {
        String composed = FinalAnswerCompletenessGuard.compose(urlShortenerPrompt(),
                "Return 404/410 because deleted aliases must not redirect. Use tombstones, primary reads, and singleflight.",
                ResearchPack.notRequired());

        assertTemplateSections(composed);
        assertTrue(composed.contains("Return `404 Not Found` or `410 Gone`"));
        assertTrue(composed.contains("RedirectResult resolve"));
        assertTrue(composed.contains("Trusting Redis TTL"));
    }

    @Test
    @DisplayName("Composes payment transfer answer into structured template")
    void composesPaymentTemplate() {
        String composed = FinalAnswerCompletenessGuard.compose("""
                A wallet payment transfer uses PostgreSQL, Redis, Kafka, debit/credit, and idempotency key handling.
                Explain same idempotency key with different body.
                """,
                "Use request hashes, a single transaction, row locks, and transactional outbox events.",
                ResearchPack.notRequired());

        assertTemplateSections(composed);
        assertTrue(composed.contains("idempotency key with a different request body"));
        assertTrue(composed.contains("TransferResult transfer"));
        assertTrue(composed.contains("Publishing Kafka events before commit"));
    }

    @Test
    @DisplayName("Composes research answer with citation/source-pack warning")
    void composesResearchTemplate() {
        String composed = FinalAnswerCompletenessGuard.compose("What is the latest status today?",
                "The current status is mixed across sources.",
                ResearchPack.withSources("Current prompt", List.of("latest status"), List.of(
                        new ResearchSource("S1", "Source", "https://example.com", "example.com",
                                "snippet", "2026-01-01", 0.9))));

        assertTemplateSections(composed);
        assertTrue(composed.contains("Use the registered source IDs"));
        assertTrue(composed.contains("Concrete Evidence Check"));
    }

    private void assertTemplateSections(String answer) {
        assertTrue(answer.contains("### Decision"));
        assertTrue(answer.contains("### Core Safety Reasoning"));
        assertTrue(answer.contains("### Tradeoffs"));
        assertTrue(answer.contains("### Concrete"));
        assertTrue(answer.contains("### Common Mistakes"));
    }

    private String urlShortenerPrompt() {
        return """
                A URL-shortener uses Redis, PostgreSQL replicas, Kafka analytics, and redirects.
                The URL was deleted 1 second ago. Give pseudocode for the redirect algorithm.
                """;
    }
}
