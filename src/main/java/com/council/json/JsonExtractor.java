package com.council.json;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic utilities for extracting JSON from noisy LLM output.
 * <p>
 * Strategies (applied in order):
 * <ol>
 *   <li>Strip markdown fences ({@code ```json ... ```})</li>
 *   <li>Extract the first balanced {@code { ... }} or {@code [ ... ]} block</li>
 * </ol>
 * No LLM re-calls are ever made.
 */
@Component
public class JsonExtractor {

    private static final Pattern MARKDOWN_FENCE =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);

    /**
     * Strip markdown code fences and return inner content.
     */
    public String stripMarkdownFences(String raw) {
        if (raw == null) return null;
        Matcher m = MARKDOWN_FENCE.matcher(raw);
        if (m.find()) {
            return m.group(1).trim();
        }
        return raw;
    }

    /**
     * Extract the first balanced JSON object ({@code { ... }}) from arbitrary text.
     * Returns {@code null} if no valid JSON fragment is found.
     */
    public String extractJsonObject(String text) {
        return extractBalanced(text, '{', '}');
    }

    /**
     * Extract the first balanced JSON array ({@code [ ... ]}) from arbitrary text.
     */
    public String extractJsonArray(String text) {
        return extractBalanced(text, '[', ']');
    }

    private String extractBalanced(String text, char open, char close) {
        if (text == null) return null;

        int start = text.indexOf(open);
        if (start == -1) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == open) depth++;
            else if (c == close) depth--;

            if (depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null; // unbalanced
    }
}

