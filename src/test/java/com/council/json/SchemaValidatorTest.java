package com.council.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaValidator();
    }

    @Nested
    @DisplayName("Draft validation")
    class DraftTests {

        @Test
        @DisplayName("Valid draft passes validation")
        void validDraft() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                        "answer": "42",
                        "summary": "The meaning of life",
                        "assumptions": ["none"],
                        "uncertainties": [],
                        "confidence": 0.85
                    }
                    """);
            List<String> errors = validator.validateDraft(node);
            assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
        }

        @Test
        @DisplayName("Missing required fields reported")
        void missingFields() throws Exception {
            JsonNode node = mapper.readTree("""
                    { "answer": "only answer" }
                    """);
            List<String> errors = validator.validateDraft(node);
            assertTrue(errors.size() >= 4, "Expected at least 4 missing field errors");
            assertTrue(errors.stream().anyMatch(e -> e.contains("summary")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("confidence")));
        }

        @Test
        @DisplayName("Confidence out of range is reported")
        void confidenceOutOfRange() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                        "answer": "a", "summary": "s",
                        "assumptions": [], "uncertainties": [],
                        "confidence": 1.5
                    }
                    """);
            List<String> errors = validator.validateDraft(node);
            assertTrue(errors.stream().anyMatch(e -> e.contains("confidence must be between")));
        }

        @Test
        @DisplayName("Wrong types are reported")
        void wrongTypes() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                        "answer": 123,
                        "summary": true,
                        "assumptions": "not-array",
                        "uncertainties": "not-array",
                        "confidence": "high"
                    }
                    """);
            List<String> errors = validator.validateDraft(node);
            assertTrue(errors.stream().anyMatch(e -> e.contains("answer must be a string")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("summary must be a string")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("assumptions must be an array")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("confidence must be a number")));
        }

        @Test
        @DisplayName("Null root is reported")
        void nullRoot() {
            List<String> errors = validator.validateDraft(null);
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("Root must be a JSON object"));
        }
    }

    @Nested
    @DisplayName("Critic validation")
    class CriticTests {

        @Test
        @DisplayName("Valid critic passes validation")
        void validCritic() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                        "globalSummary": "Drafts are consistent",
                        "contradictionSeverity": 0.1,
                        "contradictionCountPerDraft": {"gemini": 0},
                        "contradictionsFound": [],
                        "missingPoints": [],
                        "riskyClaims": ["claim about future"],
                        "mathCorrectnessScore": 0.84,
                        "feasibilityScore": 0.79,
                        "failureDepthScore": 0.71
                    }
                    """);
            List<String> errors = validator.validateCritic(node);
            assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
        }

        @Test
        @DisplayName("Missing critic fields reported")
        void missingCriticFields() throws Exception {
            JsonNode node = mapper.readTree("{}");
            List<String> errors = validator.validateCritic(node);
            assertTrue(errors.size() >= 6, "Expected at least 6 missing field errors");
        }

        @Test
        @DisplayName("Wrong critic field types reported")
        void wrongCriticTypes() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                        "globalSummary": 123,
                        "contradictionSeverity": "low",
                        "contradictionCountPerDraft": [],
                        "contradictionsFound": "none",
                        "missingPoints": "none",
                        "riskyClaims": "none",
                        "mathCorrectnessScore": "bad",
                        "feasibilityScore": "bad",
                        "failureDepthScore": "bad"
                    }
                    """);
            List<String> errors = validator.validateCritic(node);
            assertTrue(errors.stream().anyMatch(e -> e.contains("globalSummary must be a string")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("contradictionSeverity must be a number")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("contradictionCountPerDraft must be an object")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("mathCorrectnessScore must be a number")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("feasibilityScore must be a number")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("failureDepthScore must be a number")));
        }
    }

    @Nested
    @DisplayName("Verifier batch validation")
    class VerifierBatchTests {

        @Test
        @DisplayName("Valid verifier batch passes validation")
        void validVerifierBatch() throws Exception {
            JsonNode node = mapper.readTree("""
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
                                                    "errors": ["Input rate exceeds processing capacity"]
                        },
                        "gemini": {
                                                    "valid": true,
                                                    "fatal": false,
                                                    "math": {
                                                                                                            "steps": [
                                                                                                                {
                                                                                                                    "op": "add",
                                                                                                                    "a": 1000,
                                                                                                                    "b": 2000,
                                                                                                                    "result": 3000,
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
                    """);

            List<String> errors = validator.validateVerifierBatch(node);
            assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
        }

        @Test
                @DisplayName("Missing consistency field fails verifier batch validation")
                void missingConsistencyFieldFails() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                      "verdicts": {
                        "deepseek": {
                                                    "valid": false,
                                                    "fatal": false,
                                                    "math": {
                                                        "steps": [],
                                                        "allOk": false
                                                    },
                                                    "consistency": {
                                                        "storageValid": true,
                                                        "latencyValid": true
                                                    },
                                                    "errors": ["missing throughput validation"]
                        }
                      }
                    }
                    """);

            List<String> errors = validator.validateVerifierBatch(node);
                        assertTrue(errors.stream().anyMatch(e -> e.contains("consistency.throughputValid")));
        }

                @Test
                @DisplayName("throughputValid=false requires fatal=true")
                void throughputInvalidRequiresFatal() throws Exception {
                        JsonNode node = mapper.readTree("""
                                        {
                                            "verdicts": {
                                                "deepseek": {
                                                    "valid": false,
                                                    "fatal": false,
                                                    "math": {
                                                        "steps": [
                                                            {
                                                                "op": "add",
                                                                "a": 1,
                                                                "b": 1,
                                                                "result": 2,
                                                                "ok": true
                                                            }
                                                        ],
                                                        "allOk": true
                                                    },
                                                    "consistency": {
                                                        "throughputValid": false,
                                                        "storageValid": true,
                                                        "latencyValid": true
                                                    },
                                                    "errors": ["throughput exceeds system capacity"]
                                                }
                                            }
                                        }
                                        """);

                        List<String> errors = validator.validateVerifierBatch(node);
                        assertTrue(errors.stream().anyMatch(e -> e.contains("fatal must be true when throughputValid is false")));
                }

                                @Test
                                @DisplayName("batch/message inconsistency error requires fatal=true")
                                void batchMessageInconsistencyRequiresFatal() throws Exception {
                                                JsonNode node = mapper.readTree("""
                                                                                {
                                                                                    "verdicts": {
                                                                                        "deepseek": {
                                                                                            "valid": false,
                                                                                            "fatal": false,
                                                                                            "math": {
                                                                                                "steps": [
                                                                                                    {
                                                                                                        "op": "add",
                                                                                                        "a": 1,
                                                                                                        "b": 1,
                                                                                                        "result": 2,
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
                                                                                            "errors": ["batch/message inconsistency"]
                                                                                        }
                                                                                    }
                                                                                }
                                                                                """);

                                                List<String> errors = validator.validateVerifierBatch(node);
                                                assertTrue(errors.stream().anyMatch(e ->
                                                                e.contains("fatal must be true when errors include batch/message inconsistency")));
                                }

                                @Test
                                @DisplayName("DLQ overload error requires fatal=true")
                                void dlqOverloadRequiresFatal() throws Exception {
                                                JsonNode node = mapper.readTree("""
                                                                                {
                                                                                    "verdicts": {
                                                                                        "deepseek": {
                                                                                            "valid": false,
                                                                                            "fatal": false,
                                                                                            "math": {
                                                                                                "steps": [
                                                                                                    {
                                                                                                        "op": "add",
                                                                                                        "a": 1,
                                                                                                        "b": 1,
                                                                                                        "result": 2,
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
                                                                                            "errors": ["DLQ overload"]
                                                                                        }
                                                                                    }
                                                                                }
                                                                                """);

                                                List<String> errors = validator.validateVerifierBatch(node);
                                                assertTrue(errors.stream().anyMatch(e ->
                                                                e.contains("fatal must be true when errors include DLQ overload")));
                                }

                                @Test
                                @DisplayName("Final enforcer PASS verdict shape is accepted")
                                void finalEnforcerPassShapeAccepted() throws Exception {
                                                JsonNode node = mapper.readTree("""
                                                                                {
                                                                                    "verdicts": {
                                                                                        "deepseek": {
                                                                                            "valid": true
                                                                                        }
                                                                                    }
                                                                                }
                                                                                """);

                                                List<String> errors = validator.validateVerifierBatch(node);
                                                assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
                                }

                                @Test
                                @DisplayName("Final enforcer REJECT verdict shape is accepted")
                                void finalEnforcerRejectShapeAccepted() throws Exception {
                                                JsonNode node = mapper.readTree("""
                                                                                {
                                                                                    "verdicts": {
                                                                                        "deepseek": {
                                                                                            "valid": false,
                                                                                            "reason": "constraint violation"
                                                                                        }
                                                                                    }
                                                                                }
                                                                                """);

                                                List<String> errors = validator.validateVerifierBatch(node);
                                                assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
                                }

                                @Test
                                @DisplayName("Final enforcer REJECT requires exact reason")
                                void finalEnforcerRejectRequiresConstraintViolationReason() throws Exception {
                                                JsonNode node = mapper.readTree("""
                                                                                {
                                                                                    "verdicts": {
                                                                                        "deepseek": {
                                                                                            "valid": false,
                                                                                            "reason": "bad math"
                                                                                        }
                                                                                    }
                                                                                }
                                                                                """);

                                                List<String> errors = validator.validateVerifierBatch(node);
                                                assertTrue(errors.stream().anyMatch(e ->
                                                                e.contains("reason must be 'constraint violation' when valid=false")));
                                }
    }

    @Nested
    @DisplayName("Synthesis validation")
    class SynthesisTests {

        @Test
        @DisplayName("Valid synthesis payload passes validation")
        void validSynthesis() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                      "synthesizedAnswer": "Final production plan with exact capacity math.",
                      "summary": "Use PostgreSQL for ledger, Kafka for async events, and enforce jittered retries.",
                      "decisions": ["PostgreSQL ledger", "Kafka event bus"],
                      "mergedStrengths": ["Correct unit math", "Strong failure handling"],
                      "discardedClaims": ["NoSQL ledger writes"],
                      "assumptions": ["Single region deployment"],
                      "uncertainties": ["Provider p99 latency under peak traffic"],
                      "confidence": 0.84
                    }
                    """);

            List<String> errors = validator.validateSynthesis(node);
            assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
        }

        @Test
        @DisplayName("Missing synthesizedAnswer fails validation")
        void missingSynthesisFieldFails() throws Exception {
            JsonNode node = mapper.readTree("""
                    {
                      "summary": "x",
                      "decisions": [],
                      "mergedStrengths": [],
                      "discardedClaims": [],
                      "assumptions": [],
                      "uncertainties": [],
                      "confidence": 0.5
                    }
                    """);

            List<String> errors = validator.validateSynthesis(node);
            assertTrue(errors.stream().anyMatch(e -> e.contains("synthesizedAnswer")));
        }
    }
}

