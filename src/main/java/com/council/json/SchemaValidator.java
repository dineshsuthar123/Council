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
            "missingPoints", "riskyClaims",
            "mathCorrectnessScore", "feasibilityScore", "failureDepthScore"
    );

        /* ── Verifier answer schema ────────────────────────────────────── */

        private static final Set<String> VERIFIER_REQUIRED = Set.of(
            "containsFatalMathError",
            "containsConsistencyViolation",
            "fatalErrorReason"
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
        validateIsNumber(node, "mathCorrectnessScore", errors);
        validateIsNumber(node, "feasibilityScore", errors);
        validateIsNumber(node, "failureDepthScore", errors);

        validateInUnitRange(node, "contradictionSeverity", errors);
        validateInUnitRange(node, "mathCorrectnessScore", errors);
        validateInUnitRange(node, "feasibilityScore", errors);
        validateInUnitRange(node, "failureDepthScore", errors);
        return errors;
    }

    /**
     * Validate a verifier JSON node.
     */
    public List<String> validateVerifier(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add("Root must be a JSON object");
            return errors;
        }

        for (String field : VERIFIER_REQUIRED) {
            if (!node.has(field)) {
                errors.add("Missing required field: " + field);
            }
        }

        validateIsBoolean(node, "containsFatalMathError", errors);
        validateIsBoolean(node, "containsConsistencyViolation", errors);

        JsonNode reasonNode = node.get("fatalErrorReason");
        if (reasonNode != null && !reasonNode.isNull() && !reasonNode.isTextual()) {
            errors.add("fatalErrorReason must be a string or null");
        }

        boolean fatalMath = node.path("containsFatalMathError").asBoolean(false);
        boolean consistencyViolation = node.path("containsConsistencyViolation").asBoolean(false);
        String reason = (reasonNode == null || reasonNode.isNull()) ? null : reasonNode.asText("").trim();

        if ((fatalMath || consistencyViolation) && (reason == null || reason.isBlank())) {
            errors.add("fatalErrorReason must be non-empty when a violation flag is true");
        }
        if (!fatalMath && !consistencyViolation && reason != null && !reason.isBlank()) {
            errors.add("fatalErrorReason must be null when no violations are present");
        }

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

    private void validateIsBoolean(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isBoolean()) {
            errors.add(field + " must be a boolean");
        }
    }

    private void validateIsObject(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isObject()) {
            errors.add(field + " must be an object");
        }
    }

    private void validateInUnitRange(JsonNode root, String field, List<String> errors) {
        if (root.has(field) && !root.get(field).isNull() && root.get(field).isNumber()) {
            double v = root.get(field).asDouble();
            if (v < 0.0 || v > 1.0) {
                errors.add(field + " must be between 0.0 and 1.0, got " + v);
            }
        }
    }
}

