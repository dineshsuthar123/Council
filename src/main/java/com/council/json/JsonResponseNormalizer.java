package com.council.json;

import com.council.common.exception.JsonNormalizationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern MISSING_COMMA_AFTER_STRING_VALUE = Pattern.compile(
        "(\\\"\\s*:\\s*\\\"(?:[^\\\"\\\\]|\\\\.)*\\\")\\s+(?=\\\")"
    );
    private static final Pattern MISSING_COMMA_AFTER_NUMBER_VALUE = Pattern.compile(
        "(\\\"\\s*:\\s*-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s+(?=\\\")"
    );
    private static final Pattern MISSING_COMMA_AFTER_LITERAL_VALUE = Pattern.compile(
        "(\\\"\\s*:\\s*(?:true|false|null))\\s+(?=\\\")"
    );

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

    /**
     * Normalize a raw verifier response into a validated {@link JsonNode}.
     */
    public JsonNode normalizeVerifier(String provider, String raw) {
        return normalize(provider, raw, validator::validateVerifier);
    }

    /* ── core pipeline ─────────────────────────────────────────────── */

    private JsonNode normalize(String provider, String raw, Function<JsonNode, List<String>> schemaCheck) {
        if (raw == null || raw.isBlank()) {
            throw new JsonNormalizationException(provider, "Empty response", raw);
        }

        String trimmed = raw.strip();
        String stripped = extractor.stripMarkdownFences(trimmed);
        String betweenOuterBraces = extractor.extractBetweenOuterBraces(stripped);

        // 1. Aggressive cleanup first: fences stripped + first '{' to last '}' extraction.
        JsonNode node = tryParse(betweenOuterBraces != null ? betweenOuterBraces : stripped);

        // 2. Retry stripped payload directly (covers cases where braces extraction is too broad).
        if (node == null) {
            node = tryParse(stripped);
        }

        // 3. Extract first balanced JSON object fragment and retry.
        if (node == null) {
            String fragment = extractor.extractJsonObject(stripped);
            if (fragment != null) {
                node = tryParse(fragment);
            }
        }

        // 4. Last resort: try original trimmed payload.
        if (node == null) {
            node = tryParse(trimmed);
        }

        // 5. still null -> fail
        if (node == null) {
            throw new JsonNormalizationException(provider,
                    "Unable to extract valid JSON from provider response", raw);
        }

        // 6. schema validation
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
            String repaired = repairMissingCommasBetweenPrimitiveFields(text);
            if (!repaired.equals(text)) {
                try {
                    return mapper.readTree(repaired);
                } catch (JsonProcessingException ignored) {
                    // Fall through and return null.
                }
            }
            return null;
        }
    }

    /**
     * Best-effort repair for malformed JSON that is missing commas between primitive fields.
     * Example: {"a":1 "b":2} -> {"a":1, "b":2}
     */
    private String repairMissingCommasBetweenPrimitiveFields(String text) {
        String repaired = text;
        repaired = insertMissingCommas(repaired, MISSING_COMMA_AFTER_STRING_VALUE);
        repaired = insertMissingCommas(repaired, MISSING_COMMA_AFTER_NUMBER_VALUE);
        repaired = insertMissingCommas(repaired, MISSING_COMMA_AFTER_LITERAL_VALUE);
        return repaired;
    }

    private String insertMissingCommas(String input, Pattern pattern) {
        String current = input;
        for (int i = 0; i < 3; i++) {
            Matcher matcher = pattern.matcher(current);
            String next = matcher.replaceAll("$1, ");
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }
}
