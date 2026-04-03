package com.council.evaluation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Simple heuristic keyword/phrase matching.
 * <p>
 * Returns a score in [0.0, 1.0] representing the fraction of expected keywords
 * found (case-insensitive) in the actual answer. This is an honest heuristic —
 * it does NOT claim to measure "accuracy".
 */
@Component
public class KeywordMatcher {

    /**
     * Compute match score based on expected keywords.
     *
     * @param answer           the actual answer text
     * @param expectedKeywords list of keywords/phrases that should appear
     * @return score in [0.0, 1.0], or null if no keywords are provided
     */
    public Double scoreKeywords(String answer, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return null;
        }
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        String lowerAnswer = answer.toLowerCase(Locale.ROOT);
        long matched = expectedKeywords.stream()
                .filter(kw -> kw != null && !kw.isBlank())
                .filter(kw -> lowerAnswer.contains(kw.toLowerCase(Locale.ROOT)))
                .count();

        long total = expectedKeywords.stream()
                .filter(kw -> kw != null && !kw.isBlank())
                .count();

        return total == 0 ? null : (double) matched / total;
    }

    /**
     * Compute a simple overlap score between expected and actual answers.
     * Splits both into words and computes Jaccard-like overlap.
     *
     * @param answer         the actual answer
     * @param expectedAnswer the expected answer
     * @return score in [0.0, 1.0], or null if expected is null/blank
     */
    public Double scoreExpectedAnswer(String answer, String expectedAnswer) {
        if (expectedAnswer == null || expectedAnswer.isBlank()) {
            return null;
        }
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        var expectedWords = tokenize(expectedAnswer);
        var actualWords = tokenize(answer);

        if (expectedWords.isEmpty()) return null;

        long matchCount = expectedWords.stream()
                .filter(actualWords::contains)
                .count();

        return (double) matchCount / expectedWords.size();
    }

    /**
     * Combine keyword score and expected-answer score into a single value.
     * Uses the average of whichever scores are available.
     */
    public Double combinedScore(String answer, String expectedAnswer, List<String> expectedKeywords) {
        Double kwScore = scoreKeywords(answer, expectedKeywords);
        Double ansScore = scoreExpectedAnswer(answer, expectedAnswer);

        if (kwScore == null && ansScore == null) return null;
        if (kwScore == null) return ansScore;
        if (ansScore == null) return kwScore;
        return (kwScore + ansScore) / 2.0;
    }

    private java.util.Set<String> tokenize(String text) {
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s]", " ")
                        .split("\\s+"))
                .filter(w -> w.length() > 2) // skip tiny words
                .collect(java.util.stream.Collectors.toSet());
    }
}

