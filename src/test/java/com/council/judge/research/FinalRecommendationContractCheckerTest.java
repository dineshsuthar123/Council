package com.council.judge.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FinalRecommendationContractCheckerTest {

    private final FinalRecommendationContractChecker checker = new FinalRecommendationContractChecker();

    @Test
    void detectsJFinalRecommendationSectionAndSevenSentencesFail() {
        var result = checker.evaluate("Final recommendation must be 8-12 sentences.", """
                A. Earlier section. This should not count.
                I. Pseudocode:
                ```
                // do not count this comment.
                ```
                J. Final Recommendation
                One. Two. Three. Four. Five. Six. Seven.
                """);

        assertTrue(result.applicable());
        assertEquals(7, result.sentenceCount());
        assertFalse(result.satisfied());
    }

    @Test
    void eightAndTwelveSentencesPassButThirteenFails() {
        assertTrue(checker.evaluate(prompt(), sectionWithSentences(8)).satisfied());
        assertTrue(checker.evaluate(prompt(), sectionWithSentences(12)).satisfied());
        assertFalse(checker.evaluate(prompt(), sectionWithSentences(13)).satisfied());
    }

    @Test
    void doesNotSplitCommonAbbreviationsOrSemicolons() {
        var result = checker.evaluate(prompt(), """
                Final user-facing recommendation:
                Use Provider A for p95 SLOs, e.g. keep the current default route.
                Use Provider B only in canary; keep rollback ready.
                Confirm i.e. official pricing before expansion.
                Sentence four is complete.
                Sentence five is complete.
                Sentence six is complete.
                Sentence seven is complete.
                Sentence eight is complete.
                """);

        assertEquals(8, result.sentenceCount());
        assertTrue(result.satisfied());
    }

    @Test
    void fallsBackToWholeAnswerWhenNoFinalHeadingExists() {
        var result = checker.evaluate(prompt(), """
                One. Two. Three. Four. Five. Six. Seven. Eight.
                """);

        assertEquals(8, result.sentenceCount());
        assertTrue(result.satisfied());
    }

    private String prompt() {
        return "Give the final user-facing recommendation in 8-12 sentences.";
    }

    private String sectionWithSentences(int count) {
        StringBuilder answer = new StringBuilder("J. Final Recommendation\n");
        for (int i = 1; i <= count; i++) {
            answer.append("Sentence ").append(i).append(" is complete. ");
        }
        return answer.toString();
    }
}
