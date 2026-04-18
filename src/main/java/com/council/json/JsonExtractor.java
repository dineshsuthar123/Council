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

    private static final Pattern MARKDOWN_FENCE_BLOCK =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Strip markdown code fences and return inner content.
     */
    public String stripMarkdownFences(String raw) {
        if (raw == null) return null;
        String cleaned = raw;

        // If we have a full fenced block, prefer the inner payload.
        Matcher blockMatcher = MARKDOWN_FENCE_BLOCK.matcher(cleaned);
        if (blockMatcher.find()) {
            cleaned = blockMatcher.group(1);
        }

        // Aggressively remove any remaining fence markers.
        cleaned = cleaned.replaceAll("(?im)^\\s*```(?:json)?\\s*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*```\\s*$", "");
        cleaned = cleaned.replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "");

        return cleaned.trim();
    }

    /**
     * Extract the first balanced JSON object ({@code { ... }}) from arbitrary text.
     * Returns {@code null} if no valid JSON fragment is found.
     */
    public String extractJsonObject(String text) {
        return extractBalanced(text, '{', '}');
    }

    /**
     * Extract the widest object-like JSON block by taking text between the first '{'
     * and the last '}' (inclusive). Useful when models prepend markdown/prose such as
     * "**Verdict**" before the actual JSON object.
     */
    public String extractBetweenOuterBraces(String text) {
        if (text == null) return null;

        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first == -1 || last == -1 || first > last) {
            return null;
        }
        return text.substring(first, last + 1);
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

