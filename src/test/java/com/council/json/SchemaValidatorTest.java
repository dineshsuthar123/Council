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
                        "riskyClaims": ["claim about future"]
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
                        "riskyClaims": "none"
                    }
                    """);
            List<String> errors = validator.validateCritic(node);
            assertTrue(errors.stream().anyMatch(e -> e.contains("globalSummary must be a string")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("contradictionSeverity must be a number")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("contradictionCountPerDraft must be an object")));
        }
    }
}

