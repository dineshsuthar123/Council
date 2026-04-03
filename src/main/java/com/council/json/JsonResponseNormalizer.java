package com.council.json;

import com.council.common.exception.JsonNormalizationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * Deterministic pipeline that converts raw LLM text into a validated {@link JsonNode}.
 * <p>
 * Flow: raw → trim → strict parse → strip fences → retry → extract fragment → retry → validate → done / fail.
 * <p>
 * NO LLM re-calls are made to repair malformed JSON.
 */
@Component
public class JsonResponseNormalizer {

    private static final Logger log = LoggerFactory.getLogger(JsonResponseNormalizer.class);

    private final ObjectMapper mapper;
    private final JsonExtractor extractor;
    private final SchemaValidator validator;

    public JsonResponseNormalizer(ObjectMapper mapper, JsonExtractor extractor, SchemaValidator validator) {
        this.mapper = mapper;
        this.extractor = extractor;
        this.validator = validator;
    }

    /**
     * Normalize a raw draft response into a validated {@link JsonNode}.
     *
     * @throws JsonNormalizationException if the response cannot be parsed or fails schema validation
     */
    public JsonNode normalizeDraft(String provider, String raw) {
        return normalize(provider, raw, validator::validateDraft);
    }

    /**
     * Normalize a raw critic response into a validated {@link JsonNode}.
     */
    public JsonNode normalizeCritic(String provider, String raw) {
        return normalize(provider, raw, validator::validateCritic);
    }

    /* ── core pipeline ─────────────────────────────────────────────── */

    private JsonNode normalize(String provider, String raw, Function<JsonNode, List<String>> schemaCheck) {
        if (raw == null || raw.isBlank()) {
            throw new JsonNormalizationException(provider, "Empty response", raw);
        }

        String trimmed = raw.strip();

        // 1. strict parse
        JsonNode node = tryParse(trimmed);

        // 2. strip markdown fences and retry
        if (node == null) {
            String stripped = extractor.stripMarkdownFences(trimmed);
            node = tryParse(stripped);
        }

        // 3. extract JSON object fragment and retry
        if (node == null) {
            String fragment = extractor.extractJsonObject(trimmed);
            if (fragment != null) {
                node = tryParse(fragment);
            }
        }

        // 4. still null → fail
        if (node == null) {
            throw new JsonNormalizationException(provider,
                    "Unable to extract valid JSON from provider response", raw);
        }

        // 5. schema validation
        List<String> violations = schemaCheck.apply(node);
        if (!violations.isEmpty()) {
            String msg = "Schema validation failed: " + String.join("; ", violations);
            log.warn("[{}] {}", provider, msg);
            throw new JsonNormalizationException(provider, msg, raw);
        }

        return node;
    }

    private JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
