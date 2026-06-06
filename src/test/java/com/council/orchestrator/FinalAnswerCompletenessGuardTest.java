package com.council.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private String urlShortenerPrompt() {
        return """
                A URL-shortener uses Redis, PostgreSQL replicas, Kafka analytics, and redirects.
                The URL was deleted 1 second ago. Give pseudocode for the redirect algorithm.
                """;
    }
}
