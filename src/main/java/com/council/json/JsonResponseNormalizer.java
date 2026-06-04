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
    private static final int MAX_RAW_INPUT_CHARS = 1_000_000;
    private static final int ERROR_FRAGMENT_CHARS = 4_000;

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

    /**
     * Normalize a raw batch verifier response into a validated {@link JsonNode}.
     */
    public JsonNode normalizeVerifierBatch(String provider, String raw) {
        return normalize(provider, raw, validator::validateVerifierBatch);
    }

    /**
     * Normalize a raw synthesis response into a validated {@link JsonNode}.
     */
    public JsonNode normalizeSynthesis(String provider, String raw) {
        return normalize(provider, raw, validator::validateSynthesis);
    }

    /* ── core pipeline ─────────────────────────────────────────────── */

    private JsonNode normalize(String provider, String raw, Function<JsonNode, List<String>> schemaCheck) {
        if (raw == null || raw.isBlank()) {
            throw new JsonNormalizationException(provider, "Empty response", raw);
        }

        if (raw.length() > MAX_RAW_INPUT_CHARS) {
            throw new JsonNormalizationException(provider,
                    "Response exceeds max allowed length: " + MAX_RAW_INPUT_CHARS,
                    truncateForError(raw));
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
                    "Unable to extract valid JSON from provider response", truncateForError(raw));
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
        String current = text;
        for (int i = 0; i < 3; i++) {
            String next = insertMissingCommasWithoutRegex(current);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    private String insertMissingCommasWithoutRegex(String input) {
        StringBuilder out = new StringBuilder(input.length() + 32);

        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                char previous = previousNonWhitespace(out);
                if (shouldInsertCommaBeforeQuote(previous)) {
                    out.append(',');
                    out.append(' ');
                }
                inString = true;
                out.append(c);
                continue;
            }

            out.append(c);
        }
        return out.toString();
    }

    private char previousNonWhitespace(CharSequence value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }

    private boolean shouldInsertCommaBeforeQuote(char previous) {
        if (previous == '\0') {
            return false;
        }
        if (previous == '{' || previous == '[' || previous == ',' || previous == ':') {
            return false;
        }
        return true;
    }

    private String truncateForError(String raw) {
        if (raw == null || raw.length() <= ERROR_FRAGMENT_CHARS) {
            return raw;
        }
        return raw.substring(0, ERROR_FRAGMENT_CHARS);
    }
}
