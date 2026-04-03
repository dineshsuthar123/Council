package com.council.json;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonExtractorTest {

    private JsonExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JsonExtractor();
    }

    /* ── stripMarkdownFences ──────────────────────────────────────── */

    @Test
    @DisplayName("Strips ```json fences")
    void stripJsonFences() {
        String input = "```json\n{\"answer\": \"hello\"}\n```";
        String result = extractor.stripMarkdownFences(input);
        assertEquals("{\"answer\": \"hello\"}", result);
    }

    @Test
    @DisplayName("Strips bare ``` fences without language tag")
    void stripBareFences() {
        String input = "```\n{\"key\": \"val\"}\n```";
        String result = extractor.stripMarkdownFences(input);
        assertEquals("{\"key\": \"val\"}", result);
    }

    @Test
    @DisplayName("Returns input unchanged when no fences present")
    void noFencesReturnsInput() {
        String input = "{\"key\": \"val\"}";
        assertEquals(input, extractor.stripMarkdownFences(input));
    }

    @Test
    @DisplayName("Returns null for null input")
    void nullInput() {
        assertNull(extractor.stripMarkdownFences(null));
    }

    /* ── extractJsonObject ────────────────────────────────────────── */

    @Test
    @DisplayName("Extracts JSON object from surrounding text")
    void extractObjectFromText() {
        String input = "Here is the result: {\"answer\": \"42\"} hope this helps!";
        String result = extractor.extractJsonObject(input);
        assertEquals("{\"answer\": \"42\"}", result);
    }

    @Test
    @DisplayName("Extracts nested JSON object correctly")
    void extractNestedObject() {
        String input = "prefix {\"outer\": {\"inner\": 1}} suffix";
        String result = extractor.extractJsonObject(input);
        assertEquals("{\"outer\": {\"inner\": 1}}", result);
    }

    @Test
    @DisplayName("Returns null when no object found")
    void noObjectReturnsNull() {
        assertNull(extractor.extractJsonObject("no json here"));
        assertNull(extractor.extractJsonObject(null));
    }

    @Test
    @DisplayName("Handles strings with braces inside quoted values")
    void handlesQuotedBraces() {
        String input = "{\"text\": \"a { b } c\"}";
        String result = extractor.extractJsonObject(input);
        assertEquals(input, result);
    }

    /* ── extractJsonArray ─────────────────────────────────────────── */

    @Test
    @DisplayName("Extracts JSON array from surrounding text")
    void extractArray() {
        String input = "result: [1, 2, 3] done";
        String result = extractor.extractJsonArray(input);
        assertEquals("[1, 2, 3]", result);
    }

    @Test
    @DisplayName("Returns null when no array found")
    void noArrayReturnsNull() {
        assertNull(extractor.extractJsonArray("no array here"));
    }

    @Test
    @DisplayName("Handles unbalanced braces gracefully")
    void unbalancedReturnsNull() {
        assertNull(extractor.extractJsonObject("{\"key\": \"value\""));
    }
}

