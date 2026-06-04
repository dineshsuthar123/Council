package com.council.json;

import org.springframework.stereotype.Component;

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

    /**
     * Strip markdown code fences and return inner content.
     */
    public String stripMarkdownFences(String raw) {
        if (raw == null) return null;
        String cleaned = raw;

        // If we have a fenced block, prefer content between the first opening and closing fences.
        int openingFence = cleaned.indexOf("```");
        if (openingFence >= 0) {
            int openingLineBreak = indexOfLineBreak(cleaned, openingFence);
            int payloadStart = openingLineBreak == -1 ? openingFence + 3 : openingLineBreak + 1;
            int closingFence = cleaned.indexOf("```", payloadStart);
            if (closingFence >= payloadStart) {
                cleaned = cleaned.substring(payloadStart, closingFence);
            }
        }

        // Remove any remaining fence markers without regex.
        cleaned = stripFenceLines(cleaned);
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

    private int indexOfLineBreak(String text, int start) {
        for (int i = Math.max(0, start); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    private String stripFenceLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int lineStart = i;
            while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                i++;
            }
            int lineEnd = i;

            int newlineStart = i;
            if (i < text.length() && text.charAt(i) == '\r') {
                i++;
            }
            if (i < text.length() && text.charAt(i) == '\n') {
                i++;
            }

            String line = text.substring(lineStart, lineEnd);
            if (!isFenceLine(line)) {
                out.append(line);
                out.append(text, newlineStart, i);
            }
        }
        return out.toString();
    }

    private boolean isFenceLine(String line) {
        String trimmed = line.trim();
        return "```".equals(trimmed) || "```json".equalsIgnoreCase(trimmed);
    }
}

