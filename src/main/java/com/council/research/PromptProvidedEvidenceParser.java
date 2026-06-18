package com.council.research;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses first-class evidence sources embedded directly in a user prompt.
 * Source content is treated as untrusted data; it never becomes instructions.
 */
@Component
public class PromptProvidedEvidenceParser {

    private static final Pattern SOURCE_HEADING = Pattern.compile(
            "(?im)^\\s*(?:source\\s*(?:\\[(\\d+)]|(\\d+))|\\[s(\\d+)]|s(\\d+))\\s*:\\s*(.*)$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"']+");
    private static final Pattern DATE = Pattern.compile("\\b(20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}|20\\d{2})\\b");

    public boolean hasPromptSources(String prompt) {
        return SOURCE_HEADING.matcher(prompt == null ? "" : prompt).find();
    }

    public List<ResearchSource> parse(String prompt) {
        String raw = prompt == null ? "" : prompt;
        Matcher matcher = SOURCE_HEADING.matcher(raw);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) {
            int sourceNumber = Integer.parseInt(firstNonBlank(
                    matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
            headings.add(new Heading(sourceNumber, matcher.start(), matcher.end(), matcher.group(5)));
        }
        if (headings.isEmpty()) {
            return List.of();
        }

        List<ResearchSource> sources = new ArrayList<>();
        String providedAt = Instant.now().toString();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int contentEnd = i + 1 < headings.size() ? headings.get(i + 1).start : raw.length();
            String headingTail = heading.tail == null ? "" : heading.tail.trim();
            String content = (headingTail + "\n" + raw.substring(heading.end, contentEnd)).trim();
            if (content.isBlank()) {
                content = headingTail;
            }
            sources.add(toSource(heading.number, content, providedAt));
        }
        return List.copyOf(sources);
    }

    private ResearchSource toSource(int number, String content, String providedAt) {
        String id = "S" + number;
        String title = title(content, id);
        String url = firstUrl(content);
        String domain = domain(url);
        SourceType sourceType = sourceType(content);
        EvidenceOrigin origin = sourceType == SourceType.INTERNAL_TRACE
                ? EvidenceOrigin.INTERNAL_TRACE
                : EvidenceOrigin.PROMPT_PROVIDED;
        InjectionRisk injectionRisk = injectionRisk(content);
        String updatedAt = updatedAt(content);
        double authorityScore = authorityScore(sourceType, injectionRisk, content);
        double recencyScore = recencyScore(content, updatedAt);
        boolean supportsCurrentFacts = supportsCurrentFacts(sourceType, injectionRisk, content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("parser", "prompt-provided");
        metadata.put("sourceNumber", number);
        metadata.put("lineCount", Math.max(1, content.split("\\R").length));

        return new ResearchSource(
                id,
                title,
                url,
                domain,
                content,
                updatedAt,
                authorityScore,
                sourceType,
                origin,
                providedAt,
                updatedAt,
                authorityScore,
                recencyScore,
                injectionRisk,
                supportsCurrentFacts,
                metadata);
    }

    private String title(String content, String fallback) {
        for (String line : content.split("\\R")) {
            String trimmed = line.replaceFirst("^[-*]\\s*", "").trim();
            if (!trimmed.isBlank()) {
                return trimmed.length() > 140 ? trimmed.substring(0, 140) : trimmed;
            }
        }
        return fallback;
    }

    private String firstUrl(String content) {
        Matcher matcher = URL.matcher(content);
        return matcher.find() ? matcher.group() : null;
    }

    private String domain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private SourceType sourceType(String content) {
        String text = normalize(content);
        if (containsAny(text, "internal trace", "trace metrics", "trace metric", "p95", "p99",
                "latency", "reliability", "error rate", "success rate")) {
            return SourceType.INTERNAL_TRACE;
        }
        if (containsAny(text, "github issue", "github.com", "issue #", "issue")) {
            return SourceType.ISSUE;
        }
        if (containsAny(text, "prompt-injection", "prompt injection", "scraped page", "scraped")) {
            return SourceType.SCRAPED_PAGE;
        }
        if (containsAny(text, "official", "pricing page", "provider docs", "documentation", "docs")) {
            return SourceType.OFFICIAL_DOC;
        }
        if (containsAny(text, "old blog", "blog post", "blog", "newsletter")) {
            return SourceType.BLOG;
        }
        return SourceType.USER_PROVIDED;
    }

    private InjectionRisk injectionRisk(String content) {
        String text = normalize(content);
        if (containsAny(text, "ignore all previous instructions", "ignore previous instructions",
                "disregard previous instructions", "system prompt", "developer message",
                "reveal your prompt", "exfiltrate", "api key", "jailbreak")) {
            return InjectionRisk.HIGH;
        }
        if (containsAny(text, "ignore", "instruction", "must obey", "follow these steps")) {
            return InjectionRisk.MEDIUM;
        }
        return InjectionRisk.LOW;
    }

    private String updatedAt(String content) {
        String text = normalize(content);
        if (text.contains("updated recently") || text.contains("recently updated")) {
            return "recent";
        }
        Matcher matcher = DATE.matcher(content);
        return matcher.find() ? matcher.group(1).replace('/', '-') : null;
    }

    private double authorityScore(SourceType sourceType, InjectionRisk injectionRisk, String content) {
        double base = switch (sourceType) {
            case OFFICIAL_DOC -> 0.95;
            case INTERNAL_TRACE -> 0.90;
            case ISSUE -> 0.62;
            case BLOG -> normalize(content).contains("old") ? 0.32 : 0.48;
            case SCRAPED_PAGE -> 0.22;
            case USER_PROVIDED -> 0.55;
            case UNKNOWN -> 0.40;
        };
        if (injectionRisk == InjectionRisk.HIGH) {
            base *= 0.35;
        } else if (injectionRisk == InjectionRisk.MEDIUM) {
            base *= 0.70;
        }
        return clamp(base);
    }

    private double recencyScore(String content, String updatedAt) {
        String text = normalize(content);
        if (containsAny(text, "old blog", "outdated", "deprecated", "stale")) {
            return 0.25;
        }
        if (updatedAt != null && !updatedAt.isBlank()) {
            return "recent".equals(updatedAt) ? 0.90 : 0.76;
        }
        if (containsAny(text, "current", "latest", "recent")) {
            return 0.78;
        }
        return 0.55;
    }

    private boolean supportsCurrentFacts(SourceType sourceType, InjectionRisk injectionRisk, String content) {
        if (injectionRisk == InjectionRisk.HIGH || sourceType == SourceType.SCRAPED_PAGE) {
            return false;
        }
        if (sourceType == SourceType.OFFICIAL_DOC || sourceType == SourceType.INTERNAL_TRACE) {
            return true;
        }
        return containsAny(normalize(content), "updated", "current", "latest");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalArgumentException("source heading did not contain a number");
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Heading(int number, int start, int end, String tail) {}
}
