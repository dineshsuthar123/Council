package com.council.json;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates that a parsed {@link JsonNode} conforms to the expected schema.
 * Pure deterministic validation – no LLM calls.
 */
@Component
public class SchemaValidator {

    /* ── Draft answer schema ───────────────────────────────────────── */

    private static final Set<String> DRAFT_REQUIRED = Set.of(
            "answer", "summary", "assumptions", "uncertainties", "confidence"
    );

    /* ── Critic answer schema ──────────────────────────────────────── */

    private static final Set<String> CRITIC_REQUIRED = Set.of(
            "globalSummary", "contradictionSeverity",
            "contradictionCountPerDraft", "contradictionsFound",
            "missingPoints", "riskyClaims"
    );

    /**
     * Validate a draft JSON node.  Returns a list of human-readable violations (empty = valid).
     */
    public List<String> validateDraft(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add("Root must be a JSON object");
            return errors;
        }
        for (String field : DRAFT_REQUIRED) {
            if (!node.has(field) || node.get(field).isNull()) {
                errors.add("Missing required field: " + field);
            }
        }
        validateIsString(node, "answer", errors);
        validateIsString(node, "summary", errors);
        validateIsArray(node, "assumptions", errors);
        validateIsArray(node, "uncertainties", errors);
        validateIsNumber(node, "confidence", errors);

        if (node.has("confidence") && node.get("confidence").isNumber()) {
            double c = node.get("confidence").asDouble();
            if (c < 0.0 || c > 1.0) {
                errors.add("confidence must be between 0.0 and 1.0, got " + c);
            }
        }
        return errors;
    }

    /**
     * Validate a critic JSON node.
     */
    public List<String> validateCritic(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add("Root must be a JSON object");
            return errors;
        }
        for (String field : CRITIC_REQUIRED) {
            if (!node.has(field) || node.get(field).isNull()) {
                errors.add("Missing required field: " + field);
            }
        }
        validateIsString(node, "globalSummary", errors);
        validateIsNumber(node, "contradictionSeverity", errors);
        validateIsObject(node, "contradictionCountPerDraft", errors);
        validateIsArray(node, "contradictionsFound", errors);
        validateIsArray(node, "missingPoints", errors);
        validateIsArray(node, "riskyClaims", errors);
        return errors;
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    private void validateIsString(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isTextual()) {
            errors.add(field + " must be a string");
        }
    }

    private void validateIsNumber(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isNumber()) {
            errors.add(field + " must be a number");
        }
    }

    private void validateIsArray(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isArray()) {
            errors.add(field + " must be an array");
        }
    }

    private void validateIsObject(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isObject()) {
            errors.add(field + " must be an object");
        }
    }
}

