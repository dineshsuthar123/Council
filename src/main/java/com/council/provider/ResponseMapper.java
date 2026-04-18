package com.council.provider;

import com.council.model.Contradiction;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.VerifierResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mapping logic: validated {@link JsonNode} → domain DTOs.
 * <p>
 * Extracted from {@link AbstractLlmAdapter} so the adapter stays focused
 * on the HTTP call template and this class owns all JSON→record conversion.
 */
@Component
public class ResponseMapper {

    public DraftResult mapToDraftResult(String provider, String model,
                                        JsonNode node, String rawResponse, long latencyMs) {
        return DraftResult.success(
                provider, model,
                node.path("answer").asText(""),
                node.path("summary").asText(""),
                jsonArrayToList(node.path("assumptions")),
                jsonArrayToList(node.path("uncertainties")),
                node.path("confidence").asDouble(0.5),
                latencyMs,
                rawResponse
        );
    }

    public CriticResult mapToCriticResult(String provider, String model,
                                           JsonNode node, String rawResponse, long latencyMs) {
        Map<String, Integer> contradictionCounts = new LinkedHashMap<>();
        JsonNode countsNode = node.path("contradictionCountPerDraft");
        if (countsNode.isObject()) {
            countsNode.fields().forEachRemaining(entry ->
                    contradictionCounts.put(entry.getKey(), entry.getValue().asInt(0)));
        }

        List<Contradiction> contradictions = new ArrayList<>();
        JsonNode contradictionsNode = node.path("contradictionsFound");
        if (contradictionsNode.isArray()) {
            for (JsonNode c : contradictionsNode) {
                contradictions.add(new Contradiction(
                        c.path("draftA").asText(""),
                        c.path("draftB").asText(""),
                        c.path("issue").asText("")
                ));
            }
        }

        // Parse anti-generic quality signals (gracefully default if absent)
        double mathCorrectnessScore = node.path("mathCorrectnessScore").asDouble(0.0);
        double feasibilityScore = node.path("feasibilityScore").asDouble(0.0);
        double failureDepthScore = node.path("failureDepthScore").asDouble(0.0);
        double genericnessPenalty = node.path("genericnessPenalty").asDouble(0.0);
        List<String> missingFailureModes = jsonArrayToList(node.path("missingFailureModes"));
        boolean weakTradeoffAnalysis = node.path("weakTradeoffAnalysis").asBoolean(false);
        boolean missingMathJustification = node.path("missingMathJustification").asBoolean(false);
        String winnerRationale = node.path("winnerRationale").asText("");

        return CriticResult.successFull(
                provider, model,
                node.path("globalSummary").asText(""),
                node.path("contradictionSeverity").asDouble(0.0),
                contradictionCounts, contradictions,
                jsonArrayToList(node.path("missingPoints")),
                jsonArrayToList(node.path("riskyClaims")),
                mathCorrectnessScore,
                feasibilityScore,
                failureDepthScore,
                genericnessPenalty,
                missingFailureModes,
                weakTradeoffAnalysis,
                missingMathJustification,
                winnerRationale,
                latencyMs,
                rawResponse
        );
    }

    public VerifierResult mapToVerifierResult(JsonNode node) {
        String reason = node.path("fatalErrorReason").isNull()
                ? null
                : node.path("fatalErrorReason").asText(null);
        if (reason != null && reason.isBlank()) {
            reason = null;
        }

        return new VerifierResult(
                node.path("containsFatalMathError").asBoolean(false),
                node.path("containsConsistencyViolation").asBoolean(false),
                reason
        );
    }

    public List<String> jsonArrayToList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            result.add(element.asText(""));
        }
        return List.copyOf(result);
    }
}

