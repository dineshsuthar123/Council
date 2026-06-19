package com.council.judge.invariant;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contract emitted by {@link InvariantViolationCritic}.
 *
 * JSON shape:
 * {
 *   "evaluated": true,
 *   "checked": [{ "domain": "URL_SHORTENER", "id": "...", "scoreCap": 0.75 }],
 *   "violations": [{ "domain": "URL_SHORTENER", "invariantId": "...", "evidence": "..." }],
 *   "domainCaps": { "URL_SHORTENER": 0.75 },
 *   "overallCap": 0.75
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvariantCriticResult(
        boolean evaluated,
        List<InvariantDefinition> checked,
        List<InvariantViolation> violations,
        Map<InvariantDomain, Double> domainCaps,
        double overallCap
) {
    public InvariantCriticResult {
        checked = checked == null ? List.of() : List.copyOf(checked);
        violations = violations == null ? List.of() : List.copyOf(violations);
        domainCaps = domainCaps == null ? Map.of() : Map.copyOf(domainCaps);
        overallCap = clamp01(overallCap <= 0.0 ? 1.0 : overallCap);
    }

    public static InvariantCriticResult notEvaluated() {
        return new InvariantCriticResult(false, List.of(), List.of(), Map.of(), 1.0);
    }

    public static InvariantCriticResult from(List<InvariantDefinition> checked,
                                             List<InvariantViolation> violations) {
        Map<InvariantDomain, Double> caps = new EnumMap<>(InvariantDomain.class);
        double overall = 1.0;
        for (InvariantViolation violation : violations == null ? List.<InvariantViolation>of() : violations) {
            caps.merge(violation.domain(), violation.scoreCap(), Math::min);
            overall = Math.min(overall, violation.scoreCap());
        }
        return new InvariantCriticResult(true, checked, violations, caps, overall);
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    public double capForDomains(InvariantDomain... domains) {
        if (domains == null || domains.length == 0 || domainCaps.isEmpty()) {
            return 1.0;
        }
        double cap = 1.0;
        for (InvariantDomain domain : domains) {
            Double domainCap = domainCaps.get(domain);
            if (domainCap != null) {
                cap = Math.min(cap, domainCap);
            }
        }
        return clamp01(cap);
    }

    /**
     * Returns the actual cap contributed by a specific violated invariant, or {@code null} when it did not apply.
     */
    public Double capForInvariant(String invariantId) {
        if (invariantId == null || invariantId.isBlank()) {
            return null;
        }
        return violations.stream()
                .filter(violation -> invariantId.equals(violation.invariantId()))
                .map(InvariantViolation::scoreCap)
                .min(Double::compareTo)
                .orElse(null);
    }

    public Map<String, Double> dimensionScoresForDomains(InvariantDomain... domains) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (domains == null || domains.length == 0) {
            return scores;
        }
        for (InvariantDomain domain : domains) {
            double cap = domainCaps.getOrDefault(domain, 1.0);
            boolean checkedDomain = checked.stream().anyMatch(definition -> definition.domain() == domain);
            if (checkedDomain) {
                scores.put("invariant_" + domain.name().toLowerCase(Locale.ROOT), clamp01(cap));
            }
        }
        return scores;
    }

    public List<String> violationReasonsForDomains(InvariantDomain... domains) {
        if (violations.isEmpty() || domains == null || domains.length == 0) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (InvariantViolation violation : violations) {
            for (InvariantDomain domain : domains) {
                if (violation.domain() == domain) {
                    reasons.add("invariant violation " + violation.invariantId()
                            + ": " + violation.title());
                    break;
                }
            }
        }
        return List.copyOf(reasons);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
