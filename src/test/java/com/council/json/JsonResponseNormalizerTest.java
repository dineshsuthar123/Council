package com.council.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.council.common.exception.JsonNormalizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonResponseNormalizerTest {

    private JsonResponseNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        JsonExtractor extractor = new JsonExtractor();
        SchemaValidator validator = new SchemaValidator();
        normalizer = new JsonResponseNormalizer(mapper, extractor, validator);
    }

    /* ── Draft normalization ──────────────────────────────────────── */

    @Nested
    @DisplayName("Draft normalization")
    class DraftTests {

        @Test
        @DisplayName("Parses clean JSON")
        void parsesCleanJson() {
            String raw = """
                    {
                      "answer": "42",
                      "summary": "The answer is 42",
                      "assumptions": ["none"],
                      "uncertainties": [],
                      "confidence": 0.95
                    }
                    """;
            JsonNode node = normalizer.normalizeDraft("test", raw);
            assertEquals("42", node.get("answer").asText());
            assertEquals(0.95, node.get("confidence").asDouble(), 0.001);
        }

        @Test
        @DisplayName("Strips markdown fences")
        void stripsMarkdownFences() {
            String raw = """
                    ```json
                    {
                      "answer": "hello",
                      "summary": "greeting",
                      "assumptions": [],
                      "uncertainties": [],
                      "confidence": 0.8
                    }
                    ```
                    """;
            JsonNode node = normalizer.normalizeDraft("test", raw);
            assertEquals("hello", node.get("answer").asText());
        }

        @Test
        @DisplayName("Extracts JSON from surrounding text")
        void extractsFromSurroundingText() {
            String raw = """
                    Here is my answer:
                    {"answer":"extracted","summary":"s","assumptions":[],"uncertainties":[],"confidence":0.7}
                    Hope that helps!
                    """;
            JsonNode node = normalizer.normalizeDraft("test", raw);
            assertEquals("extracted", node.get("answer").asText());
        }

        @Test
        @DisplayName("Rejects missing required fields")
        void rejectsMissingFields() {
            String raw = """
                    { "answer": "incomplete" }
                    """;
            assertThrows(JsonNormalizationException.class,
                    () -> normalizer.normalizeDraft("test", raw));
        }

        @Test
        @DisplayName("Rejects empty response")
        void rejectsEmpty() {
            assertThrows(JsonNormalizationException.class,
                    () -> normalizer.normalizeDraft("test", ""));
        }

        @Test
        @DisplayName("Rejects null response")
        void rejectsNull() {
            assertThrows(JsonNormalizationException.class,
                    () -> normalizer.normalizeDraft("test", null));
        }

        @Test
        @DisplayName("Rejects garbage text")
        void rejectsGarbage() {
            assertThrows(JsonNormalizationException.class,
                    () -> normalizer.normalizeDraft("test", "this is not json at all"));
        }
    }

    /* ── Critic normalization ─────────────────────────────────────── */

    @Nested
    @DisplayName("Critic normalization")
    class CriticTests {

        @Test
        @DisplayName("Parses valid critic JSON")
        void parsesValidCritic() {
            String raw = """
                    {
                      "globalSummary": "Draft A is stronger",
                      "contradictionSeverity": 0.3,
                      "contradictionCountPerDraft": { "gemini": 1, "deepseek": 2 },
                      "contradictionsFound": [
                        { "draftA": "gemini", "draftB": "deepseek", "issue": "different dates" }
                      ],
                      "missingPoints": ["context"],
                      "riskyClaims": ["unverified stat"]
                    }
                    """;
            JsonNode node = normalizer.normalizeCritic("test", raw);
            assertEquals("Draft A is stronger", node.get("globalSummary").asText());
            assertEquals(0.3, node.get("contradictionSeverity").asDouble(), 0.001);
            assertEquals(1, node.get("contradictionCountPerDraft").get("gemini").asInt());
        }
    }
}


