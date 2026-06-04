package com.council.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.council.common.exception.JsonNormalizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
                                            "riskyClaims": ["unverified stat"],
                                            "mathCorrectnessScore": 0.88,
                                            "feasibilityScore": 0.77,
                                            "failureDepthScore": 0.66
                    }
                    """;
            JsonNode node = normalizer.normalizeCritic("test", raw);
            assertEquals("Draft A is stronger", node.get("globalSummary").asText());
            assertEquals(0.3, node.get("contradictionSeverity").asDouble(), 0.001);
            assertEquals(1, node.get("contradictionCountPerDraft").get("gemini").asInt());
        }

                @Test
                @DisplayName("Extracts and parses critic JSON when markdown preamble exists")
                void parsesCriticWithMarkdownPreamble() {
                        String raw = """
                                        **Verdict**
                                        {
                                            "globalSummary": "Draft A is stronger",
                                            "contradictionSeverity": 0.3,
                                            "contradictionCountPerDraft": { "gemini": 1, "deepseek": 2 },
                                            "contradictionsFound": [
                                                { "draftA": "gemini", "draftB": "deepseek", "issue": "different dates" }
                                            ],
                                            "missingPoints": ["context"],
                                            "riskyClaims": ["unverified stat"],
                                            "mathCorrectnessScore": 0.88,
                                            "feasibilityScore": 0.77,
                                            "failureDepthScore": 0.66
                                        }
                                        """;

                        JsonNode node = normalizer.normalizeCritic("test", raw);
                        assertEquals("Draft A is stronger", node.get("globalSummary").asText());
                        assertEquals(0.88, node.get("mathCorrectnessScore").asDouble(), 0.001);
                }
    }

        @Nested
        @DisplayName("Verifier batch normalization")
        class VerifierBatchTests {

                @Test
                @DisplayName("Parses valid verifier batch JSON")
                void parsesValidVerifierBatch() {
                        String raw = """
                                        {
                                            "verdicts": {
                                                "deepseek": {
                                                    "valid": false,
                                                    "fatal": true,
                                                    "math": {
                                                        "steps": [
                                                            {
                                                                "op": "multiply",
                                                                "a": 50000,
                                                                "b": 86400,
                                                                "result": 123,
                                                                "ok": false
                                                            }
                                                        ],
                                                        "allOk": false
                                                    },
                                                    "consistency": {
                                                        "throughputValid": false,
                                                        "storageValid": true,
                                                        "latencyValid": true
                                                    },
                                                    "errors": ["throughput exceeds capacity"]
                                                },
                                                "gemini": {
                                                    "valid": true,
                                                    "fatal": false,
                                                    "math": {
                                                        "steps": [
                                                            {
                                                                "op": "add",
                                                                "a": 100,
                                                                "b": 20,
                                                                "result": 120,
                                                                "ok": true
                                                            }
                                                        ],
                                                        "allOk": true
                                                    },
                                                    "consistency": {
                                                        "throughputValid": true,
                                                        "storageValid": true,
                                                        "latencyValid": true
                                                    },
                                                    "errors": []
                                                }
                                            }
                                        }
                                        """;

                        JsonNode node = normalizer.normalizeVerifierBatch("test", raw);
                        assertTrue(node.get("verdicts").has("deepseek"));
                        assertFalse(node.get("verdicts").get("deepseek").get("consistency").get("throughputValid").asBoolean());
                }

                                @Test
                                @DisplayName("Parses final enforcer pass/reject verifier JSON")
                                void parsesFinalEnforcerVerifierBatch() {
                                                String raw = """
                                                                                {
                                                                                    "verdicts": {
                                                                                        "deepseek": {
                                                                                            "valid": false,
                                                                                            "reason": "constraint violation"
                                                                                        },
                                                                                        "gemini": {
                                                                                            "valid": true
                                                                                        }
                                                                                    }
                                                                                }
                                                                                """;

                                                JsonNode node = normalizer.normalizeVerifierBatch("test", raw);
                                                assertFalse(node.get("verdicts").get("deepseek").get("valid").asBoolean());
                                                assertEquals("constraint violation", node.get("verdicts").get("deepseek").get("reason").asText());
                                                assertTrue(node.get("verdicts").get("gemini").get("valid").asBoolean());
                                }

                @Test
                @DisplayName("Handles large malicious verifier payload without backtracking timeout")
                void largeMaliciousVerifierPayloadDoesNotTimeout() {
                    String raw = buildLargeVerifierPayloadWithMissingComma();

                    JsonNode node = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> normalizer.normalizeVerifierBatch("test", raw));

                    assertTrue(node.get("verdicts").has("deepseek"));
                }
        }

                @Nested
                @DisplayName("Synthesis normalization")
                class SynthesisTests {

                @Test
                @DisplayName("Parses valid synthesis JSON")
                void parsesValidSynthesis() {
                    String raw = """
                        {
                          "synthesizedAnswer": "Use PostgreSQL for ledger and Kafka for async pipelines.",
                          "summary": "Strong consistency for money, decoupled async processing for scale.",
                          "decisions": ["PostgreSQL ledger", "Kafka event bus"],
                          "mergedStrengths": ["Correct math", "Robust retries"],
                          "discardedClaims": ["Eventually consistent ledger writes"],
                          "assumptions": ["Single-region launch"],
                          "uncertainties": ["Cross-region failover latency"],
                          "confidence": 0.82
                        }
                        """;

                    JsonNode node = normalizer.normalizeSynthesis("test", raw);
                    assertEquals("Use PostgreSQL for ledger and Kafka for async pipelines.",
                        node.get("synthesizedAnswer").asText());
                    assertEquals(0.82, node.get("confidence").asDouble(), 0.001);
                }
                }

                @Test
                @DisplayName("Rejects oversized payload before normalization")
                void rejectsOversizedPayloadBeforeNormalization() {
                    String huge = "x".repeat(1_000_001);

                                JsonNormalizationException ex = assertThrows(JsonNormalizationException.class,
                                                () -> normalizer.normalizeVerifierBatch("test", huge));

                                assertTrue(ex.getMessage().contains("Response exceeds max allowed length"));
                }

                private String buildLargeVerifierPayloadWithMissingComma() {
                                String hostileValue = "\\\\".repeat(40_000);
                                String escaped = hostileValue.replace("\\", "\\\\");

                                return """
                                                                {
                                                                    "verdicts": {
                                                                        "deepseek": {
                                                                            "valid": true,
                                                                            "note": "%s"
                                                                            "fatal": false,
                                                                            "math": {
                                                                                "steps": [
                                                                                    {
                                                                                        "op": "add",
                                                                                        "a": 1,
                                                                                        "b": 2,
                                                                                        "result": 3,
                                                                                        "ok": true
                                                                                    }
                                                                                ],
                                                                                "allOk": true
                                                                            },
                                                                            "consistency": {
                                                                                "throughputValid": true,
                                                                                "storageValid": true,
                                                                                "latencyValid": true
                                                                            },
                                                                            "errors": []
                                                                        }
                                                                    }
                                                                }
                                                                """.formatted(escaped);
                }
}


