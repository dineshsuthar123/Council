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
            "valid",
            "fatal",
            "math",
            "consistency",
            "errors"
        );

        private static final Set<String> LEGACY_VERIFIER_REQUIRED = Set.of(
            "containsFatalMathError",
            "containsConsistencyViolation",
            "containsThroughputContradiction",
            "fatalErrorReason"
        );

        private static final Set<String> ALLOWED_MATH_OPERATIONS = Set.of(
            "multiply", "divide", "add", "subtract"
        );

    /* ── Verifier batch schema ────────────────────────────────────── */

    private static final Set<String> VERIFIER_BATCH_REQUIRED = Set.of("verdicts");

    /* ── Synthesis schema ─────────────────────────────────────────── */

    private static final Set<String> SYNTHESIS_REQUIRED = Set.of(
            "synthesizedAnswer",
            "summary",
            "decisions",
            "mergedStrengths",
            "discardedClaims",
            "assumptions",
            "uncertainties",
            "confidence"
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

        if (node.has("containsFatalMathError")
                || node.has("containsConsistencyViolation")
                || node.has("containsThroughputContradiction")) {
            return validateLegacyVerifier(node);
        }

        if (isFinalConstraintVerdict(node)) {
            return validateFinalConstraintVerdict(node);
        }

        for (String field : VERIFIER_REQUIRED) {
            if (!node.has(field) || node.get(field).isNull()) {
                errors.add("Missing required field: " + field);
            }
        }

        validateIsBoolean(node, "valid", errors);
        validateIsBoolean(node, "fatal", errors);
        validateIsObject(node, "math", errors);
        validateIsObject(node, "consistency", errors);
        validateIsArray(node, "errors", errors);

        JsonNode math = node.get("math");
        boolean anyStepNotOk = false;
        boolean allStepsOk = true;
        if (math != null && math.isObject()) {
            if (!math.has("steps") || math.get("steps").isNull()) {
                errors.add("math.steps is required");
            }
            if (!math.has("allOk") || math.get("allOk").isNull()) {
                errors.add("math.allOk is required");
            }

            validateIsArray(math, "steps", errors, "math.");
            validateIsBoolean(math, "allOk", errors, "math.");

            JsonNode steps = math.get("steps");
            if (steps != null && steps.isArray()) {
                if (steps.size() > 20) {
                    errors.add("math.steps must contain at most 20 entries");
                }
                for (int i = 0; i < steps.size(); i++) {
                    JsonNode step = steps.get(i);
                    String prefix = "math.steps[" + i + "].";
                    if (step == null || !step.isObject()) {
                        errors.add(prefix + "must be an object");
                        continue;
                    }

                    if (!step.has("op") || step.get("op").isNull()) {
                        errors.add(prefix + "op is required");
                    }
                    if (!step.has("a") || step.get("a").isNull()) {
                        errors.add(prefix + "a is required");
                    }
                    if (!step.has("b") || step.get("b").isNull()) {
                        errors.add(prefix + "b is required");
                    }
                    if (!step.has("result") || step.get("result").isNull()) {
                        errors.add(prefix + "result is required");
                    }
                    if (!step.has("ok") || step.get("ok").isNull()) {
                        errors.add(prefix + "ok is required");
                    }

                    validateIsString(step, "op", errors, prefix);
                    validateIsNumber(step, "a", errors, prefix);
                    validateIsNumber(step, "b", errors, prefix);
                    validateIsNumber(step, "result", errors, prefix);
                    validateIsBoolean(step, "ok", errors, prefix);

                    JsonNode operationNode = step.get("op");
                    if (operationNode != null && operationNode.isTextual()) {
                        String op = operationNode.asText("");
                        if (!ALLOWED_MATH_OPERATIONS.contains(op)) {
                            errors.add(prefix + "op must be one of multiply|divide|add|subtract");
                        }
                    }

                    JsonNode okNode = step.get("ok");
                    if (okNode != null && okNode.isBoolean()) {
                        boolean ok = okNode.asBoolean();
                        if (!ok) {
                            anyStepNotOk = true;
                            allStepsOk = false;
                        }
                    }
                }

                if (steps.isEmpty()) {
                    allStepsOk = false;
                }
            } else {
                allStepsOk = false;
            }

            JsonNode allOkNode = math.get("allOk");
            if (allOkNode != null && allOkNode.isBoolean() && allOkNode.asBoolean() != allStepsOk) {
                errors.add("math.allOk must match step ok aggregation");
            }
        }

        JsonNode consistency = node.get("consistency");
        if (consistency != null && consistency.isObject()) {
            if (!consistency.has("throughputValid") || consistency.get("throughputValid").isNull()) {
                errors.add("consistency.throughputValid is required");
            }
            if (!consistency.has("storageValid") || consistency.get("storageValid").isNull()) {
                errors.add("consistency.storageValid is required");
            }
            if (!consistency.has("latencyValid") || consistency.get("latencyValid").isNull()) {
                errors.add("consistency.latencyValid is required");
            }

            validateIsBoolean(consistency, "throughputValid", errors, "consistency.");
            validateIsBoolean(consistency, "storageValid", errors, "consistency.");
            validateIsBoolean(consistency, "latencyValid", errors, "consistency.");
        }

        JsonNode errorList = node.get("errors");
        if (errorList != null && errorList.isArray()) {
            if (errorList.size() > 20) {
                errors.add("errors must contain at most 20 entries");
            }
            for (int i = 0; i < errorList.size(); i++) {
                if (!errorList.get(i).isTextual()) {
                    errors.add("errors[" + i + "] must be a string");
                    continue;
                }
                String value = errorList.get(i).asText("");
                if (value.length() > 200) {
                    errors.add("errors[" + i + "] must be <= 200 chars");
                }
                if (containsRegexLikePattern(value)) {
                    errors.add("errors[" + i + "] must not contain regex-like patterns");
                }
            }
        }

        boolean valid = node.path("valid").asBoolean(false);
        boolean fatal = node.path("fatal").asBoolean(false);
        boolean allOk = node.path("math").path("allOk").asBoolean(false);
        boolean throughputValid = node.path("consistency").path("throughputValid").asBoolean(true);
        boolean storageValid = node.path("consistency").path("storageValid").asBoolean(true);
        boolean latencyValid = node.path("consistency").path("latencyValid").asBoolean(true);
        boolean hasBatchMessageInconsistencyError = hasErrorMessage(errorList, "batch/message inconsistency");
        boolean hasDlqOverloadError = hasErrorMessage(errorList, "DLQ overload");

        if (fatal && valid) {
            errors.add("fatal cannot be true when valid is true");
        }
        if (!allOk && fatal && !anyStepNotOk) {
            errors.add("fatal must be backed by failed math steps");
        }
        if (anyStepNotOk && !fatal) {
            errors.add("fatal must be true when any math step is incorrect");
        }
        if (!throughputValid && !fatal) {
            errors.add("fatal must be true when throughputValid is false");
        }
        if (hasBatchMessageInconsistencyError && !fatal) {
            errors.add("fatal must be true when errors include batch/message inconsistency");
        }
        if (hasDlqOverloadError && !fatal) {
            errors.add("fatal must be true when errors include DLQ overload");
        }
        if (valid && !allOk) {
            errors.add("valid must be false when math.allOk is false");
        }
        if ((!throughputValid || !storageValid || !latencyValid) && valid) {
            errors.add("valid must be false when any consistency check is false");
        }
        if (!valid && (errorList == null || !errorList.isArray() || errorList.isEmpty())) {
            errors.add("errors must include at least one reason when valid=false");
        }
        if (valid && errorList != null && errorList.isArray() && !errorList.isEmpty()) {
            errors.add("errors must be empty when valid=true");
        }

        return errors;
    }

    private List<String> validateLegacyVerifier(JsonNode node) {
        List<String> errors = new ArrayList<>();

        for (String field : LEGACY_VERIFIER_REQUIRED) {
            if (!node.has(field)) {
                errors.add("Missing required field: " + field);
            }
        }

        validateIsBoolean(node, "containsFatalMathError", errors);
        validateIsBoolean(node, "containsConsistencyViolation", errors);
        validateIsBoolean(node, "containsThroughputContradiction", errors);

        JsonNode reasonNode = node.get("fatalErrorReason");
        if (reasonNode != null && !reasonNode.isNull() && !reasonNode.isTextual()) {
            errors.add("fatalErrorReason must be a string or null");
        }

        boolean fatalMath = node.path("containsFatalMathError").asBoolean(false);
        boolean consistencyViolation = node.path("containsConsistencyViolation").asBoolean(false);
        boolean throughputContradiction = node.path("containsThroughputContradiction").asBoolean(false);
        String reason = (reasonNode == null || reasonNode.isNull()) ? null : reasonNode.asText("").trim();

        if ((fatalMath || consistencyViolation || throughputContradiction) && (reason == null || reason.isBlank())) {
            errors.add("fatalErrorReason must be non-empty when a violation flag is true");
        }
        if (!fatalMath && !consistencyViolation && !throughputContradiction && reason != null && !reason.isBlank()) {
            errors.add("fatalErrorReason must be null when no violations are present");
        }
        return errors;
    }

    private List<String> validateFinalConstraintVerdict(JsonNode node) {
        List<String> errors = new ArrayList<>();

        validateIsBoolean(node, "valid", errors);

        JsonNode reasonNode = node.get("reason");
        boolean valid = node.path("valid").asBoolean(false);

        if (valid) {
            if (reasonNode != null && !reasonNode.isNull()) {
                if (!reasonNode.isTextual()) {
                    errors.add("reason must be a string when provided");
                } else if (!reasonNode.asText("").isBlank()) {
                    errors.add("reason must be omitted when valid=true");
                }
            }
        } else {
            if (reasonNode == null || reasonNode.isNull()) {
                errors.add("reason is required when valid=false");
            } else if (!reasonNode.isTextual()) {
                errors.add("reason must be a string");
            } else if (!"constraint violation".equalsIgnoreCase(reasonNode.asText("").trim())) {
                errors.add("reason must be 'constraint violation' when valid=false");
            }
        }

        return errors;
    }

    private boolean isFinalConstraintVerdict(JsonNode node) {
        return node != null
                && node.isObject()
                && node.has("valid")
                && !node.has("fatal")
                && !node.has("math")
                && !node.has("consistency")
                && !node.has("errors");
    }

    /**
     * Validate a batch verifier JSON node.
     */
    public List<String> validateVerifierBatch(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add("Root must be a JSON object");
            return errors;
        }

        for (String field : VERIFIER_BATCH_REQUIRED) {
            if (!node.has(field) || node.get(field).isNull()) {
                errors.add("Missing required field: " + field);
            }
        }

        JsonNode verdicts = node.get("verdicts");
        if (verdicts == null || !verdicts.isObject()) {
            errors.add("verdicts must be an object");
            return errors;
        }

        verdicts.fields().forEachRemaining(entry -> {
            String provider = entry.getKey();
            List<String> verdictErrors = validateVerifier(entry.getValue());
            verdictErrors.forEach(err -> errors.add("verdicts." + provider + ": " + err));
        });

        return errors;
    }

    /**
     * Validate a synthesis JSON node.
     */
    public List<String> validateSynthesis(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add("Root must be a JSON object");
            return errors;
        }

        for (String field : SYNTHESIS_REQUIRED) {
            if (!node.has(field) || node.get(field).isNull()) {
                errors.add("Missing required field: " + field);
            }
        }

        validateIsString(node, "synthesizedAnswer", errors);
        validateIsString(node, "summary", errors);
        validateIsArray(node, "decisions", errors);
        validateIsArray(node, "mergedStrengths", errors);
        validateIsArray(node, "discardedClaims", errors);
        validateIsArray(node, "assumptions", errors);
        validateIsArray(node, "uncertainties", errors);
        validateIsNumber(node, "confidence", errors);

        validateInUnitRange(node, "confidence", errors);
        return errors;
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    private void validateIsString(JsonNode root, String field, List<String> errors) {
        validateIsString(root, field, errors, "");
    }

    private void validateIsString(JsonNode root, String field, List<String> errors, String prefix) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isTextual()) {
            errors.add(prefix + field + " must be a string");
        }
    }

    private void validateIsNumber(JsonNode root, String field, List<String> errors) {
        validateIsNumber(root, field, errors, "");
    }

    private void validateIsNumber(JsonNode root, String field, List<String> errors, String prefix) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isNumber()) {
            errors.add(prefix + field + " must be a number");
        }
    }

    private void validateIsArray(JsonNode root, String field, List<String> errors) {
        validateIsArray(root, field, errors, "");
    }

    private void validateIsArray(JsonNode root, String field, List<String> errors, String prefix) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isArray()) {
            errors.add(prefix + field + " must be an array");
        }
    }

    private void validateIsBoolean(JsonNode root, String field, List<String> errors) {
        validateIsBoolean(root, field, errors, "");
    }

    private void validateIsBoolean(JsonNode root, String field, List<String> errors, String prefix) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isBoolean()) {
            errors.add(prefix + field + " must be a boolean");
        }
    }

    private void validateIsObject(JsonNode root, String field, List<String> errors) {
        validateIsObject(root, field, errors, "");
    }

    private void validateIsObject(JsonNode root, String field, List<String> errors, String prefix) {
        if (root.has(field) && !root.get(field).isNull() && !root.get(field).isObject()) {
            errors.add(prefix + field + " must be an object");
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

    private boolean containsRegexLikePattern(String value) {
        return value.contains(".*")
                || value.contains("\\d")
                || value.contains("\\w")
                || value.contains("\\s")
                || value.contains("[")
                || value.contains("]")
                || value.contains("{")
                || value.contains("}");
    }

    private boolean hasErrorMessage(JsonNode errorList, String expected) {
        if (errorList == null || !errorList.isArray()) {
            return false;
        }
        for (int i = 0; i < errorList.size(); i++) {
            JsonNode item = errorList.get(i);
            if (item != null && item.isTextual() && expected.equalsIgnoreCase(item.asText(""))) {
                return true;
            }
        }
        return false;
    }
}

