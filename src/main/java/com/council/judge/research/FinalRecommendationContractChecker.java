package com.council.judge.research;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checker for final recommendation sentence-count contracts.
 */
public final class FinalRecommendationContractChecker {

    private static final Pattern EIGHT_TO_TWELVE = Pattern.compile(
            "(?i)8\\s*(?:-|\\u2013|\\u2014|\\u00E2\\u20AC\\u201C|"
                    + "\\u00C3\\u00A2\\u00E2\\u201A\\u00AC\\u00E2\\u20AC\\u0153|to|through)"
                    + "\\s*12\\s+sentences?");
    private static final Pattern FINAL_HEADING = Pattern.compile(
            "(?im)^\\s*(?:#{1,6}\\s*)?(?:J\\s*[.)\\-:]\\s*)?"
                    + "(?:\\*\\*)?(?:final\\s+(?:user[- ]facing\\s+)?recommendation|"
                    + "final\\s+user[- ]facing\\s+recommendation)(?:\\*\\*)?\\s*:?\\s*");
    private static final Pattern NEXT_SECTION = Pattern.compile(
            "(?im)^\\s*(?:#{1,6}\\s+|[A-IK-Z]\\s*[.)\\-:]\\s+)");
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");

    public ContractResult evaluate(String prompt, String answer) {
        boolean applicable = requiresEightToTwelve(prompt);
        String section = finalRecommendationSection(answer);
        int count = sentenceCount(section);
        int min = applicable ? 8 : 0;
        int max = applicable ? 12 : 0;
        boolean satisfied = !applicable || (count >= min && count <= max);
        return new ContractResult(applicable, count, min, max, satisfied, preview(section));
    }

    public boolean requiresEightToTwelve(String prompt) {
        return EIGHT_TO_TWELVE.matcher(prompt == null ? "" : prompt).find();
    }

    public String finalRecommendationSection(String answer) {
        String value = answer == null ? "" : answer;
        Matcher matcher = FINAL_HEADING.matcher(value);
        if (!matcher.find()) {
            return value.trim();
        }
        String scoped = value.substring(matcher.end()).trim();
        Matcher next = NEXT_SECTION.matcher(scoped);
        if (next.find() && next.start() > 0) {
            scoped = scoped.substring(0, next.start()).trim();
        }
        return scoped;
    }

    public int sentenceCount(String value) {
        String text = stripNonRecommendationText(value);
        if (text.isBlank()) {
            return 0;
        }
        String protectedText = protectAbbreviations(text);
        Matcher matcher = Pattern.compile("[.!?](?=\\s|$)").matcher(protectedText);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String stripNonRecommendationText(String value) {
        if (value == null) {
            return "";
        }
        String noCode = FENCED_CODE.matcher(value).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String line : noCode.split("\\R")) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("//")
                    || trimmed.startsWith("#")
                    || lower.startsWith("pseudocode:")
                    || lower.startsWith("algorithm:")
                    || lower.startsWith("```")) {
                continue;
            }
            out.append(trimmed).append(' ');
        }
        return out.toString().trim();
    }

    private String protectAbbreviations(String text) {
        return text
                .replaceAll("(?i)e\\.g\\.", "eg")
                .replaceAll("(?i)i\\.e\\.", "ie")
                .replaceAll("(?i)etc\\.", "etc")
                .replaceAll("(?i)vs\\.", "vs");
    }

    private String preview(String section) {
        String normalized = section == null ? "" : section.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 217) + "...";
    }

    public record ContractResult(
            boolean applicable,
            int sentenceCount,
            int requiredMinSentences,
            int requiredMaxSentences,
            boolean satisfied,
            String sectionPreview
    ) {
    }
}
