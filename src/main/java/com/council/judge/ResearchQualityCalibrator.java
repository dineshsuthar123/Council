package com.council.judge;

import com.council.common.CouncilUtils;
import com.council.judge.invariant.InvariantCriticResult;
import com.council.judge.invariant.InvariantDomain;
import com.council.research.ResearchPack;
import com.council.research.ResearchSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic rubric for research-aware answers.
 */
public final class ResearchQualityCalibrator {

    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");

    private ResearchQualityCalibrator() {}

    public record QualityScore(double score, Map<String, Double> dimensions, List<String> reasons) {
        public boolean applied() {
            return !dimensions.isEmpty() || !reasons.isEmpty();
        }
    }

    public static QualityScore qualityScore(String answer, ResearchPack pack, double fallbackScore) {
        if (pack == null || !pack.required()) {
            return new QualityScore(CouncilUtils.clamp01(fallbackScore), Map.of(), List.of());
        }

        Map<String, Double> dimensions = new LinkedHashMap<>();
        if (!pack.hasSources()) {
            dimensions.put("source_quality", 0.0);
            dimensions.put("citation_accuracy", 0.0);
            dimensions.put("recency", 0.10);
            dimensions.put("evidence_coverage", 0.0);
            dimensions.put("unsupported_claim_penalty", 0.45);
            dimensions.put("conflict_handling", 0.50);
            dimensions.put("answer_completeness", 0.50);
            return new QualityScore(Math.min(CouncilUtils.clamp01(fallbackScore), 0.72),
                    Map.copyOf(dimensions),
                    List.of("external research was required but no sources were available"));
        }

        Set<String> citations = citationIds(answer);
        Set<String> registeredIds = pack.sourceIds();
        int sourceCount = pack.sources().size();
        long invalidCitations = citations.stream().filter(id -> !registeredIds.contains(id)).count();

        dimensions.put("source_quality", scoreSourceQuality(pack.sources()));
        dimensions.put("citation_accuracy", scoreCitationAccuracy(answer, pack, citations, invalidCitations, sourceCount));
        dimensions.put("recency", scoreRecency(pack));
        dimensions.put("evidence_coverage", scoreEvidenceCoverage(citations, sourceCount));
        dimensions.put("unsupported_claim_penalty", scoreSupportedness(answer, citations));
        dimensions.put("conflict_handling", scoreConflictHandling(answer));
        dimensions.put("answer_completeness", scoreAnswerCompleteness(answer, citations));

        double weighted = (dimensions.get("source_quality") * 0.18)
                + (dimensions.get("citation_accuracy") * 0.22)
                + (dimensions.get("recency") * 0.14)
                + (dimensions.get("evidence_coverage") * 0.16)
                + (dimensions.get("unsupported_claim_penalty") * 0.14)
                + (dimensions.get("conflict_handling") * 0.06)
                + (dimensions.get("answer_completeness") * 0.10);

        double cap = 1.0;
        if (citations.isEmpty()) {
            cap = 0.72;
        }
        if (invalidCitations > 0) {
            cap = Math.min(cap, 0.55);
        }
        if (citesHighInjectionRiskSource(answer, pack, citations)) {
            cap = Math.min(cap, 0.45);
        }
        if (mentionsSourcesWithoutIds(answer, citations)) {
            cap = Math.min(cap, 0.70);
        }

        return new QualityScore(CouncilUtils.clamp01(Math.min(weighted, cap)),
                Map.copyOf(dimensions),
                reasons(answer, citations, invalidCitations, pack));
    }

    public static QualityScore qualityScore(String answer,
                                            ResearchPack pack,
                                            double fallbackScore,
                                            InvariantCriticResult invariantResult) {
        QualityScore base = qualityScore(answer, pack, fallbackScore);
        if (invariantResult == null || !invariantResult.evaluated()) {
            return base;
        }

        double cap = invariantResult.capForDomains(InvariantDomain.RESEARCH_EVIDENCE);
        Map<String, Double> dimensions = new LinkedHashMap<>(base.dimensions());
        dimensions.putAll(invariantResult.dimensionScoresForDomains(InvariantDomain.RESEARCH_EVIDENCE));

        List<String> reasons = new ArrayList<>(base.reasons());
        reasons.addAll(invariantResult.violationReasonsForDomains(InvariantDomain.RESEARCH_EVIDENCE));

        return new QualityScore(
                CouncilUtils.clamp01(Math.min(base.score(), cap)),
                Map.copyOf(dimensions),
                List.copyOf(reasons));
    }

    private static double scoreSourceQuality(List<ResearchSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0.0;
        }
        double authority = sources.stream().mapToDouble(ResearchSource::authorityScore).average().orElse(0.0);
        double recency = sources.stream().mapToDouble(ResearchSource::recencyScore).average().orElse(0.0);
        long officialOrTrace = sources.stream()
                .filter(s -> s.sourceType() == com.council.research.SourceType.OFFICIAL_DOC || s.isInternalTrace())
                .count();
        long highRisk = sources.stream().filter(ResearchSource::hasHighInjectionRisk).count();
        double countScore = sources.size() >= 6 ? 0.12 : sources.size() >= 3 ? 0.08 : 0.04;
        double authorityBonus = officialOrTrace > 0 ? 0.08 : 0.0;
        double injectionPenalty = highRisk > 0 ? 0.12 : 0.0;
        return CouncilUtils.clamp01((authority * 0.62) + (recency * 0.25)
                + countScore + authorityBonus - injectionPenalty);
    }

    private static double scoreCitationAccuracy(String answer,
                                                ResearchPack pack,
                                                Set<String> citations,
                                                long invalidCitations,
                                                int sourceCount) {
        if (invalidCitations > 0) {
            return 0.20;
        }
        if (citations.isEmpty()) {
            return 0.30;
        }
        if (citesHighInjectionRiskSource(answer, pack, citations)) {
            return 0.25;
        }
        if (officialPricingAvailableButNotCited(answer, pack, citations)) {
            return 0.45;
        }
        int expected = Math.min(2, sourceCount);
        if (citations.size() >= expected) {
            return 0.92;
        }
        return 0.72;
    }

    private static double scoreRecency(ResearchPack pack) {
        String reason = normalize(pack.reason());
        boolean timeSensitive = reason.contains("current") || reason.contains("recent")
                || reason.contains("time-sensitive");
        long dated = pack.sources().stream()
                .filter(source -> source.publishedAt() != null && !source.publishedAt().isBlank())
                .count();
        if (!timeSensitive) {
            return dated > 0 ? 0.86 : 0.78;
        }
        if (dated >= 2) {
            return 0.88;
        }
        if (dated == 1) {
            return 0.72;
        }
        return 0.58;
    }

    private static double scoreEvidenceCoverage(Set<String> citations, int sourceCount) {
        if (citations.isEmpty()) {
            return 0.20;
        }
        double coverage = citations.size() / (double) Math.max(1, Math.min(sourceCount, 4));
        return CouncilUtils.clamp01(0.50 + (coverage * 0.45));
    }

    private static double scoreSupportedness(String answer, Set<String> citations) {
        String text = normalize(answer);
        boolean makesCurrentClaim = containsAny(text, "latest", "currently", "as of", "today",
                "now", "recent", "price", "release", "announced");
        if (makesCurrentClaim && citations.isEmpty()) {
            return 0.25;
        }
        if (citations.isEmpty()) {
            return 0.45;
        }
        return makesCurrentClaim ? 0.88 : 0.82;
    }

    private static double scoreConflictHandling(String answer) {
        String text = normalize(answer);
        if (containsAny(text, "conflict", "conflicting", "disagree", "source differs", "sources differ")) {
            return 0.86;
        }
        return 0.70;
    }

    private static double scoreAnswerCompleteness(String answer, Set<String> citations) {
        String text = answer == null ? "" : answer.trim();
        if (text.length() >= 900 && !citations.isEmpty()) {
            return 0.88;
        }
        if (text.length() >= 450 && !citations.isEmpty()) {
            return 0.78;
        }
        if (text.length() >= 250) {
            return 0.62;
        }
        return 0.42;
    }

    private static Set<String> citationIds(String answer) {
        Set<String> ids = new HashSet<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            ids.add("S" + matcher.group(1));
        }
        return ids;
    }

    private static boolean citesHighInjectionRiskSource(String answer, ResearchPack pack, Set<String> citations) {
        if (pack == null || citations.isEmpty()) {
            return false;
        }
        String text = normalize(answer);
        boolean callsOutRisk = containsAny(text, "prompt-injection", "prompt injection", "hostile",
                "untrusted", "do not obey", "not an instruction", "source data");
        if (callsOutRisk) {
            return false;
        }
        return pack.sources().stream()
                .anyMatch(source -> citations.contains(source.id()) && source.hasHighInjectionRisk());
    }

    private static boolean officialPricingAvailableButNotCited(String answer,
                                                               ResearchPack pack,
                                                               Set<String> citations) {
        String text = normalize(answer);
        if (!containsAny(text, "price", "pricing", "cost", "cheaper", "migration", "recommend")) {
            return false;
        }
        boolean officialAvailable = pack.sources().stream()
                .anyMatch(source -> source.sourceType() == com.council.research.SourceType.OFFICIAL_DOC);
        boolean officialCited = pack.sources().stream()
                .anyMatch(source -> source.sourceType() == com.council.research.SourceType.OFFICIAL_DOC
                        && citations.contains(source.id()));
        return officialAvailable && !officialCited;
    }

    private static boolean mentionsSourcesWithoutIds(String answer, Set<String> citations) {
        String text = normalize(answer);
        return citations.isEmpty() && containsAny(text, "sources say", "the sources", "evidence says",
                "according to sources", "provided sources");
    }

    private static List<String> reasons(String answer,
                                        Set<String> citations,
                                        long invalidCitations,
                                        ResearchPack pack) {
        List<String> reasons = new ArrayList<>();
        if (citations.isEmpty()) {
            reasons.add("research answer did not cite the provided evidence pack");
        }
        if (invalidCitations > 0) {
            reasons.add("answer cited source IDs not present in the evidence registry");
        }
        if (citesHighInjectionRiskSource(answer, pack, citations)) {
            reasons.add("answer cited a high prompt-injection-risk source");
        }
        return List.copyOf(reasons);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
