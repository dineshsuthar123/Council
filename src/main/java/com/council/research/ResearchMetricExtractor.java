package com.council.research;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts deterministic provider-comparison facts from the registered evidence pack.
 * It intentionally recognizes a narrow, auditable vocabulary rather than inferring metrics with a model.
 */
public final class ResearchMetricExtractor {

    private static final Pattern SUCCESS_RATE = Pattern.compile(
            "(?i)\\bprovider\\s+([ab])\\s+success\\s+rate\\s*[:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern P95_LATENCY = Pattern.compile(
            "(?i)\\bprovider\\s+([ab])\\s+(?:p95\\s+latency|latency\\s+p95)\\s*[:=]?\\s*"
                    + "(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds?|s|seconds?)\\b");
    private static final Pattern COST_PER_1K = Pattern.compile(
            "(?i)\\bprovider\\s+([ab])\\s+(?:average\\s+)?cost\\s+per\\s+(?:1k|1000)\\s+requests?"
                    + "\\s*[:=]?\\s*\\$?\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern DEGRADED_WINDOWS = Pattern.compile(
            "(?i)\\bprovider\\s+([ab])\\s+had\\s+(no|zero|one|two|three|four|five|\\d+)"
                    + "(?:\\s+(\\d+)\\s*[- ]?minute)?\\s+(?:major\\s+)?degraded\\s+windows?");

    public ProviderEvidenceComparison extract(ResearchPack pack) {
        MutableProviderMetrics providerA = new MutableProviderMetrics("A");
        MutableProviderMetrics providerB = new MutableProviderMetrics("B");
        if (pack != null && pack.sources() != null) {
            for (ResearchSource source : pack.sources()) {
                parseSource(source, providerA, providerB);
            }
        }

        ProviderMetrics a = providerA.snapshot();
        ProviderMetrics b = providerB.snapshot();
        String cheaper = lowerCostProvider(a, b);
        String faster = lowerLatencyProvider(a, b);
        String moreReliable = moreReliableProvider(a, b);
        String higherRisk = higherRiskProvider(a, b);
        return new ProviderEvidenceComparison(a, b, cheaper, faster, moreReliable, higherRisk,
                evidenceSummary(a, b, cheaper, faster, moreReliable, higherRisk));
    }

    private void parseSource(ResearchSource source,
                             MutableProviderMetrics providerA,
                             MutableProviderMetrics providerB) {
        if (source == null) {
            return;
        }
        String text = source.snippet() == null ? "" : source.snippet();
        parseSuccessRates(text, source.id(), providerA, providerB);
        parseLatencies(text, source.id(), providerA, providerB);
        parseCosts(text, source.id(), providerA, providerB);
        parseDegradedWindows(text, source.id(), providerA, providerB);

        String normalized = text.toLowerCase(Locale.ROOT);
        boolean rateLimitRisk = containsAny(normalized, "429", "rate limit", "rate-limit", "throttl");
        boolean batchDelay = normalized.contains("batch")
                && containsAny(normalized, "delayed completion", "delay", "asynchronous completion");
        if (mentionsProvider(normalized, "a")) {
            providerA.addOperationalRisk(source.id(), rateLimitRisk, batchDelay);
        }
        if (mentionsProvider(normalized, "b")) {
            providerB.addOperationalRisk(source.id(), rateLimitRisk, batchDelay);
        }
    }

    private void parseSuccessRates(String text, String sourceId,
                                   MutableProviderMetrics providerA, MutableProviderMetrics providerB) {
        Matcher matcher = SUCCESS_RATE.matcher(text);
        while (matcher.find()) {
            metricFor(matcher.group(1), providerA, providerB).successRate = parseDouble(matcher.group(2));
            metricFor(matcher.group(1), providerA, providerB).addSource(sourceId);
        }
    }

    private void parseLatencies(String text, String sourceId,
                                MutableProviderMetrics providerA, MutableProviderMetrics providerB) {
        Matcher matcher = P95_LATENCY.matcher(text);
        while (matcher.find()) {
            double value = parseDouble(matcher.group(2));
            String unit = matcher.group(3).toLowerCase(Locale.ROOT);
            metricFor(matcher.group(1), providerA, providerB).p95LatencySeconds = unit.startsWith("m")
                    ? value / 1000.0
                    : value;
            metricFor(matcher.group(1), providerA, providerB).addSource(sourceId);
        }
    }

    private void parseCosts(String text, String sourceId,
                            MutableProviderMetrics providerA, MutableProviderMetrics providerB) {
        Matcher matcher = COST_PER_1K.matcher(text);
        while (matcher.find()) {
            metricFor(matcher.group(1), providerA, providerB).averageCostPer1KRequests = parseDouble(matcher.group(2));
            metricFor(matcher.group(1), providerA, providerB).addSource(sourceId);
        }
    }

    private void parseDegradedWindows(String text, String sourceId,
                                      MutableProviderMetrics providerA, MutableProviderMetrics providerB) {
        Matcher matcher = DEGRADED_WINDOWS.matcher(text);
        while (matcher.find()) {
            MutableProviderMetrics metrics = metricFor(matcher.group(1), providerA, providerB);
            metrics.degradedWindowCount = numberWord(matcher.group(2));
            metrics.degradedWindowMinutes = matcher.group(3) == null ? null : Integer.parseInt(matcher.group(3));
            metrics.addSource(sourceId);
        }
    }

    private MutableProviderMetrics metricFor(String provider,
                                              MutableProviderMetrics providerA,
                                              MutableProviderMetrics providerB) {
        return "A".equalsIgnoreCase(provider) ? providerA : providerB;
    }

    private String lowerCostProvider(ProviderMetrics a, ProviderMetrics b) {
        if (a.averageCostPer1KRequests() == null || b.averageCostPer1KRequests() == null) {
            return null;
        }
        return a.averageCostPer1KRequests() < b.averageCostPer1KRequests() ? "A"
                : b.averageCostPer1KRequests() < a.averageCostPer1KRequests() ? "B" : null;
    }

    private String lowerLatencyProvider(ProviderMetrics a, ProviderMetrics b) {
        if (a.p95LatencySeconds() == null || b.p95LatencySeconds() == null) {
            return null;
        }
        return a.p95LatencySeconds() < b.p95LatencySeconds() ? "A"
                : b.p95LatencySeconds() < a.p95LatencySeconds() ? "B" : null;
    }

    private String moreReliableProvider(ProviderMetrics a, ProviderMetrics b) {
        if (a.successRate() != null && b.successRate() != null
                && Math.abs(a.successRate() - b.successRate()) >= 0.25) {
            return a.successRate() > b.successRate() ? "A" : "B";
        }
        if (a.degradedWindowCount() != null && b.degradedWindowCount() != null
                && !a.degradedWindowCount().equals(b.degradedWindowCount())) {
            return a.degradedWindowCount() < b.degradedWindowCount() ? "A" : "B";
        }
        return null;
    }

    private String higherRiskProvider(ProviderMetrics a, ProviderMetrics b) {
        int aRisk = (a.rateLimitRisk() ? 2 : 0) + (a.degradedWindowCount() == null ? 0 : a.degradedWindowCount());
        int bRisk = (b.rateLimitRisk() ? 2 : 0) + (b.degradedWindowCount() == null ? 0 : b.degradedWindowCount());
        if (aRisk == bRisk) {
            return null;
        }
        return aRisk > bRisk ? "A" : "B";
    }

    private String evidenceSummary(ProviderMetrics a,
                                   ProviderMetrics b,
                                   String cheaper,
                                   String faster,
                                   String reliable,
                                   String riskier) {
        return "A(success=" + value(a.successRate(), "%")
                + ", p95=" + value(a.p95LatencySeconds(), "s")
                + ", cost/1K=" + value(a.averageCostPer1KRequests(), "")
                + ", degraded=" + a.degradedWindowCount() + ")"
                + "; B(success=" + value(b.successRate(), "%")
                + ", p95=" + value(b.p95LatencySeconds(), "s")
                + ", cost/1K=" + value(b.averageCostPer1KRequests(), "")
                + ", degraded=" + b.degradedWindowCount() + ")"
                + "; cheaper=" + nullableProvider(cheaper)
                + ", faster=" + nullableProvider(faster)
                + ", moreReliable=" + nullableProvider(reliable)
                + ", higherOperationalRisk=" + nullableProvider(riskier);
    }

    private String value(Double value, String suffix) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.3f%s", value, suffix);
    }

    private String nullableProvider(String provider) {
        return provider == null ? "n/a" : "provider " + provider;
    }

    private int numberWord(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "no", "zero" -> 0;
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            default -> Integer.parseInt(value);
        };
    }

    private double parseDouble(String value) {
        return Double.parseDouble(value);
    }

    private boolean mentionsProvider(String text, String provider) {
        return text.matches("(?s).*\\bprovider\\s+" + provider + "\\b.*");
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record ProviderEvidenceComparison(
            ProviderMetrics providerA,
            ProviderMetrics providerB,
            String cheaperProvider,
            String fasterProvider,
            String moreReliableProvider,
            String rateLimitRiskProvider,
            String evidenceSummary
    ) {}

    public record ProviderMetrics(
            Double successRate,
            Double p95LatencySeconds,
            Double averageCostPer1KRequests,
            Integer degradedWindowCount,
            Integer degradedWindowMinutes,
            boolean rateLimitRisk,
            boolean batchDelayCaveat,
            Set<String> sourceIds
    ) {
        public ProviderMetrics {
            sourceIds = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
        }
    }

    private static final class MutableProviderMetrics {
        private final String provider;
        private Double successRate;
        private Double p95LatencySeconds;
        private Double averageCostPer1KRequests;
        private Integer degradedWindowCount;
        private Integer degradedWindowMinutes;
        private boolean rateLimitRisk;
        private boolean batchDelayCaveat;
        private final Set<String> sourceIds = new LinkedHashSet<>();

        private MutableProviderMetrics(String provider) {
            this.provider = provider;
        }

        private void addOperationalRisk(String sourceId, boolean rateLimitRisk, boolean batchDelayCaveat) {
            this.rateLimitRisk |= rateLimitRisk;
            this.batchDelayCaveat |= batchDelayCaveat;
            if (rateLimitRisk || batchDelayCaveat) {
                addSource(sourceId);
            }
        }

        private void addSource(String sourceId) {
            if (sourceId != null && !sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
        }

        private ProviderMetrics snapshot() {
            return new ProviderMetrics(successRate, p95LatencySeconds, averageCostPer1KRequests,
                    degradedWindowCount, degradedWindowMinutes, rateLimitRisk, batchDelayCaveat, sourceIds);
        }
    }
}
