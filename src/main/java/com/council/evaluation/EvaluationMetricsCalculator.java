package com.council.evaluation;

import com.council.evaluation.dto.BaselineAggregateResponse;
import com.council.evaluation.dto.BaselineResultResponse;
import com.council.evaluation.dto.EvaluationAggregateResponse;
import com.council.evaluation.dto.EvaluationPromptResponse;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes aggregate metrics across all prompt results in an evaluation run.
 * Pure computation — no I/O, no side effects.
 */
@Component
public class EvaluationMetricsCalculator {

    /**
     * Calculate aggregate metrics from a list of prompt results.
     */
    public EvaluationAggregateResponse calculate(List<EvaluationPromptResponse> results) {
        List<EvaluationPromptResponse> successful = results.stream()
                .filter(r -> "COMPLETED".equals(r.status()) || "PARTIAL_FAILURE".equals(r.status()))
                .filter(r -> r.councilAnswer() != null)
                .toList();

        int totalPrompts = results.size();
        int successfulPrompts = (int) results.stream()
                .filter(r -> "COMPLETED".equals(r.status())).count();
        int failedPrompts = (int) results.stream()
                .filter(r -> "FAILED".equals(r.status())).count();

        double avgLatency = successful.stream()
                .filter(r -> r.councilLatencyMs() != null)
                .mapToLong(EvaluationPromptResponse::councilLatencyMs)
                .average().orElse(0.0);

        double avgConfidence = successful.stream()
                .filter(r -> r.councilConfidence() != null)
                .mapToDouble(EvaluationPromptResponse::councilConfidence)
                .average().orElse(0.0);

        double avgContradiction = successful.stream()
                .filter(r -> r.councilContradictionSeverity() != null)
                .mapToDouble(EvaluationPromptResponse::councilContradictionSeverity)
                .average().orElse(0.0);

        double avgAnswerLength = successful.stream()
                .filter(r -> r.councilAnswerLength() != null)
                .mapToInt(EvaluationPromptResponse::councilAnswerLength)
                .average().orElse(0.0);

        Double avgKeywordMatch = averageNullableDouble(
                successful.stream().map(EvaluationPromptResponse::keywordMatchScore).toList());

        Map<String, Integer> winnerFrequency = successful.stream()
                .filter(r -> r.councilWinnerProvider() != null)
                .collect(Collectors.groupingBy(
                        EvaluationPromptResponse::councilWinnerProvider,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        Map<String, Integer> providerSuccesses = new LinkedHashMap<>();
        Map<String, Integer> providerFailures = new LinkedHashMap<>();
        for (EvaluationPromptResponse r : successful) {
            if (r.councilUsedProviders() != null) {
                for (String p : r.councilUsedProviders()) {
                    providerSuccesses.merge(p, 1, Integer::sum);
                }
            }
            if (r.councilFailedProviders() != null) {
                for (String p : r.councilFailedProviders()) {
                    providerFailures.merge(p, 1, Integer::sum);
                }
            }
        }

        // Baseline aggregates
        Map<String, BaselineAggregateResponse> baselineAggregates = computeBaselineAggregates(results);

        return new EvaluationAggregateResponse(
                totalPrompts, successfulPrompts, failedPrompts,
                round2(avgLatency), round2(avgConfidence), round2(avgContradiction),
                round2(avgAnswerLength), avgKeywordMatch != null ? round2(avgKeywordMatch) : null,
                winnerFrequency, providerSuccesses, providerFailures,
                baselineAggregates.isEmpty() ? null : baselineAggregates
        );
    }

    /* ── baseline aggregation ──────────────────────────────────────── */

    private Map<String, BaselineAggregateResponse> computeBaselineAggregates(
            List<EvaluationPromptResponse> results) {

        // Collect all baseline entries across all prompts
        Map<String, List<BaselineResultResponse>> byProvider = new LinkedHashMap<>();
        for (EvaluationPromptResponse r : results) {
            if (r.baselines() == null) continue;
            r.baselines().forEach((provider, baseline) ->
                    byProvider.computeIfAbsent(provider, k -> new ArrayList<>()).add(baseline));
        }

        Map<String, BaselineAggregateResponse> aggregates = new LinkedHashMap<>();
        for (var entry : byProvider.entrySet()) {
            String provider = entry.getKey();
            List<BaselineResultResponse> bList = entry.getValue();

            List<BaselineResultResponse> ok = bList.stream()
                    .filter(b -> b.error() == null).toList();
            int failures = (int) bList.stream().filter(b -> b.error() != null).count();

            double avgLat = ok.stream()
                    .filter(b -> b.latencyMs() != null)
                    .mapToLong(BaselineResultResponse::latencyMs)
                    .average().orElse(0.0);
            double avgConf = ok.stream()
                    .filter(b -> b.confidence() != null)
                    .mapToDouble(BaselineResultResponse::confidence)
                    .average().orElse(0.0);
            double avgLen = ok.stream()
                    .filter(b -> b.answerLength() != null)
                    .mapToInt(BaselineResultResponse::answerLength)
                    .average().orElse(0.0);
            Double avgKw = averageNullableDouble(
                    ok.stream().map(BaselineResultResponse::keywordMatchScore).toList());

            aggregates.put(provider, new BaselineAggregateResponse(
                    provider, round2(avgLat), round2(avgConf), round2(avgLen),
                    avgKw != null ? round2(avgKw) : null,
                    ok.size(), failures
            ));
        }
        return aggregates;
    }

    /* ── helpers ────────────────────────────────────────────────────── */

    private Double averageNullableDouble(List<Double> values) {
        List<Double> nonNull = values.stream()
                .filter(Objects::nonNull).toList();
        if (nonNull.isEmpty()) return null;
        return nonNull.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

