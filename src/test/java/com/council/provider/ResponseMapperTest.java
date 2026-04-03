package com.council.provider;

import com.council.model.Contradiction;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                  "riskyClaims": ["unverified stat"]
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
                  "riskyClaims": []
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
}

