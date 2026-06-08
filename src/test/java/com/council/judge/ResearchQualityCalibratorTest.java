package com.council.judge;

import com.council.research.ResearchPack;
import com.council.research.ResearchSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResearchQualityCalibratorTest {

    @Test
    @DisplayName("Research answer without citations is capped")
    void researchAnswerWithoutCitationsIsCapped() {
        ResearchPack pack = packWithSources();

        ResearchQualityCalibrator.QualityScore score = ResearchQualityCalibrator.qualityScore(
                "The latest model router update improves routing quality and cost, but this answer cites nothing.",
                pack,
                0.95);

        assertTrue(score.score() <= 0.72);
        assertTrue(score.dimensions().get("citation_accuracy") <= 0.30);
        assertFalse(score.reasons().isEmpty());
    }

    @Test
    @DisplayName("Research answer with valid citations scores strongly")
    void researchAnswerWithValidCitationsScoresStrongly() {
        ResearchPack pack = packWithSources();

        ResearchQualityCalibrator.QualityScore score = ResearchQualityCalibrator.qualityScore(
                """
                Current model routing systems optimize quality, latency, and cost by selecting between
                candidate models at request time [S1]. Evaluation systems use rubric-style scoring to
                turn subjective answer quality into repeatable dimensions [S2]. The practical design is
                to separate answer quality from routing confidence, persist source evidence, and show
                unsupported claims as a scoring risk.
                """,
                pack,
                0.95);

        assertTrue(score.score() >= 0.78);
        assertTrue(score.dimensions().get("citation_accuracy") >= 0.90);
        assertTrue(score.dimensions().get("evidence_coverage") >= 0.70);
    }

    @Test
    @DisplayName("Research required but unavailable caps quality")
    void researchRequiredButUnavailableCapsQuality() {
        ResearchPack pack = ResearchPack.unavailable(
                "Prompt asks for current information.",
                List.of("latest model routing"),
                "TAVILY_API_KEY is not configured");

        ResearchQualityCalibrator.QualityScore score = ResearchQualityCalibrator.qualityScore(
                "Here are current facts with no source evidence.",
                pack,
                0.95);

        assertTrue(score.score() <= 0.72);
        assertEquals(0.0, score.dimensions().get("source_quality"));
    }

    private ResearchPack packWithSources() {
        return ResearchPack.withSources(
                "Prompt asks for current information.",
                List.of("model routing evals"),
                List.of(
                        new ResearchSource("S1", "Routing docs", "https://example.com/routing",
                                "example.com", "Routing selects models dynamically.", "2026-01-01", 0.9),
                        new ResearchSource("S2", "Evaluation docs", "https://example.com/evals",
                                "example.com", "Rubrics evaluate model outputs.", "2026-01-02", 0.8)
                ));
    }
}
