package com.council.provider;

import com.council.model.Contradiction;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.SynthesisResult;
import com.council.model.VerifierBatchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponseMapperTest {

    private ResponseMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = new ResponseMapper();
    }

    @Test
    @DisplayName("mapToDraftResult extracts all fields correctly")
    void mapToDraftResult_allFields() throws Exception {
        String json = """
                {
                  "answer": "Java is a programming language",
                  "summary": "Brief overview of Java",
                  "assumptions": ["JDK installed", "basic knowledge"],
                  "uncertainties": ["version specifics"],
                  "confidence": 0.87
                }
                """;
        JsonNode node = objectMapper.readTree(json);

        DraftResult result = mapper.mapToDraftResult("gemini", "gemini-2.5-pro",
                node, "raw-text", 1200);

        assertTrue(result.isSuccess());
        assertEquals("gemini", result.provider());
        assertEquals("gemini-2.5-pro", result.model());
        assertEquals("Java is a programming language", result.answer());
        assertEquals("Brief overview of Java", result.summary());
        assertEquals(2, result.assumptions().size());
        assertEquals(1, result.uncertainties().size());
        assertEquals(0.87, result.confidence(), 0.001);
        assertEquals(1200, result.latencyMs());
        assertEquals("raw-text", result.rawResponse());
    }

    @Test
    @DisplayName("mapToDraftResult defaults confidence to 0.5 when missing")
    void mapToDraftResult_missingConfidence() throws Exception {
        String json = """
                {
                  "answer": "test",
                  "summary": "s",
                  "assumptions": [],
                  "uncertainties": []
                }
                """;
        JsonNode node = objectMapper.readTree(json);
        DraftResult result = mapper.mapToDraftResult("deepseek", "model", node, "", 100);
        assertEquals(0.5, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("mapToCriticResult extracts contradictions and counts")
    void mapToCriticResult_fullPayload() throws Exception {
        String json = """
                {
                  "globalSummary": "Gemini stronger overall",
                  "contradictionSeverity": 0.45,
                  "contradictionCountPerDraft": {
                    "gemini": 1,
                    "deepseek": 3
                  },
                  "contradictionsFound": [
                    {"draftA": "gemini", "draftB": "deepseek", "issue": "conflicting dates"}
                  ],
                  "missingPoints": ["historical context"],
                  "riskyClaims": ["unverified stat"],
                  "mathCorrectnessScore": 0.88,
                  "feasibilityScore": 0.76,
                  "failureDepthScore": 0.69,
                  "genericnessPenalty": 0.25,
                  "missingFailureModes": ["no dlq handling"],
                  "weakTradeoffAnalysis": false,
                  "missingMathJustification": false,
                  "winnerRationale": "gemini included explicit partition math"
                }
                """;
        JsonNode node = objectMapper.readTree(json);

        CriticResult result = mapper.mapToCriticResult("claude", "claude-sonnet",
                node, "raw-critic", 800);

        assertTrue(result.isSuccess());
        assertEquals("claude", result.provider());
        assertEquals("Gemini stronger overall", result.globalSummary());
        assertEquals(0.45, result.contradictionSeverity(), 0.001);
        assertEquals(1, result.contradictionCountPerDraft().get("gemini"));
        assertEquals(3, result.contradictionCountPerDraft().get("deepseek"));
        assertEquals(1, result.contradictionsFound().size());

        Contradiction c = result.contradictionsFound().getFirst();
        assertEquals("gemini", c.draftA());
        assertEquals("deepseek", c.draftB());
        assertEquals("conflicting dates", c.issue());

        assertEquals(1, result.missingPoints().size());
        assertEquals(1, result.riskyClaims().size());
        assertEquals(0.88, result.mathCorrectnessScore(), 0.001);
        assertEquals(0.76, result.feasibilityScore(), 0.001);
        assertEquals(0.69, result.failureDepthScore(), 0.001);
        assertEquals(0.25, result.genericnessPenalty(), 0.001);
        assertEquals("gemini included explicit partition math", result.winnerRationale());
    }

    @Test
    @DisplayName("mapToCriticResult handles empty contradictions")
    void mapToCriticResult_emptyContradictions() throws Exception {
        String json = """
                {
                  "globalSummary": "All agree",
                  "contradictionSeverity": 0.0,
                  "contradictionCountPerDraft": {},
                  "contradictionsFound": [],
                  "missingPoints": [],
                  "riskyClaims": [],
                  "mathCorrectnessScore": 0.0,
                  "feasibilityScore": 0.0,
                  "failureDepthScore": 0.0
                }
                """;
        JsonNode node = objectMapper.readTree(json);
        CriticResult result = mapper.mapToCriticResult("claude", "m", node, "", 100);

        assertTrue(result.contradictionCountPerDraft().isEmpty());
        assertTrue(result.contradictionsFound().isEmpty());
        assertEquals(0.0, result.contradictionSeverity());
    }

    @Test
    @DisplayName("jsonArrayToList returns empty for null/non-array")
    void jsonArrayToList_edgeCases() throws Exception {
        assertTrue(mapper.jsonArrayToList(null).isEmpty());
        assertTrue(mapper.jsonArrayToList(objectMapper.readTree("42")).isEmpty());
        assertEquals(2, mapper.jsonArrayToList(objectMapper.readTree("[\"a\",\"b\"]")).size());
    }

    @Test
    @DisplayName("mapToVerifierBatchResult extracts per-provider verdicts with throughput contradiction")
    void mapToVerifierBatchResult_allFields() throws Exception {
        String json = """
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
                            "result": 111,
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
                      "errors": ["50k TPS exceeds partition processing capacity"]
                    },
                    "gemini": {
                      "valid": true,
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
                """;
        JsonNode node = objectMapper.readTree(json);

        VerifierBatchResult result = mapper.mapToVerifierBatchResult(node, List.of(
                DraftResult.success("deepseek", "m1", "a", "s", List.of(), List.of(), 0.9, 10, "raw"),
                DraftResult.success("gemini", "m2", "a", "s", List.of(), List.of(), 0.9, 10, "raw")
        ));

        assertTrue(result.verdictForProvider("deepseek").containsThroughputContradiction());
  assertTrue(result.verdictForProvider("deepseek").containsFatalMathError());
        assertFalse(result.verdictForProvider("gemini").containsThroughputContradiction());
        assertEquals("50k TPS exceeds partition processing capacity",
                result.verdictForProvider("deepseek").fatalErrorReason());
    }

    @Test
    @DisplayName("mapToVerifierBatchResult supports final enforcer pass/reject schema")
    void mapToVerifierBatchResult_finalEnforcerSchema() throws Exception {
        String json = """
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
        JsonNode node = objectMapper.readTree(json);

        VerifierBatchResult result = mapper.mapToVerifierBatchResult(node, List.of(
                DraftResult.success("deepseek", "m1", "a", "s", List.of(), List.of(), 0.9, 10, "raw"),
                DraftResult.success("gemini", "m2", "a", "s", List.of(), List.of(), 0.9, 10, "raw")
        ));

        assertTrue(result.verdictForProvider("deepseek").containsConsistencyViolation());
        assertEquals("constraint violation", result.verdictForProvider("deepseek").fatalErrorReason());
        assertFalse(result.verdictForProvider("gemini").containsConsistencyViolation());
    }

      @Test
      @DisplayName("mapToSynthesisResult extracts synthesized payload fields")
      void mapToSynthesisResult_allFields() throws Exception {
        String json = """
            {
              "synthesizedAnswer": "Final architecture with validated math and retries.",
              "summary": "Use Postgres ledger and Kafka-driven async workers.",
              "decisions": ["PostgreSQL ledger", "Kafka queue"],
              "mergedStrengths": ["Exact throughput math", "Strong failure mitigation"],
              "discardedClaims": ["Cassandra as core ledger"],
              "assumptions": ["Single-region deployment"],
              "uncertainties": ["Peak burst beyond tested limits"],
              "confidence": 0.86
            }
            """;
        JsonNode node = objectMapper.readTree(json);

        SynthesisResult result = mapper.mapToSynthesisResult(
            "openrouter", "nvidia/llama-3.1-nemotron-70b-instruct", node, "raw-synthesis", 620);

        assertTrue(result.isSuccess());
        assertEquals("Final architecture with validated math and retries.", result.synthesizedAnswer());
        assertEquals("Use Postgres ledger and Kafka-driven async workers.", result.summary());
        assertEquals(2, result.decisions().size());
        assertEquals(0.86, result.confidence(), 0.001);
        assertEquals(620, result.latencyMs());
        assertEquals("raw-synthesis", result.rawResponse());
      }
}

