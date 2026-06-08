package com.council.research;

import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Creates deterministic search queries from the user prompt.
 */
@Component
public class ResearchQueryPlanner {

    public List<String> plan(String userQuery) {
        String cleaned = clean(userQuery);
        if (cleaned.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(trimToWords(cleaned, 18));

        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "latest", "current", "today", "recent", "as of")) {
            queries.add(trimToWords(cleaned + " " + Year.now(), 20));
        }
        if (containsAny(lower, "law", "regulation", "policy", "standard")) {
            queries.add(trimToWords(cleaned + " official documentation", 20));
        }
        if (containsAny(lower, "benchmark", "leaderboard", "pricing", "price")) {
            queries.add(trimToWords(cleaned + " official source", 20));
        }

        return queries.stream().limit(3).toList();
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String clean(String value) {
        return value == null ? "" : value
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String trimToWords(String value, int maxWords) {
        String[] parts = clean(value).split("\\s+");
        if (parts.length <= maxWords) {
            return clean(value);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
