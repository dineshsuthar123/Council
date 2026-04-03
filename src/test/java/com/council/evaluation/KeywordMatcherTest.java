package com.council.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordMatcherTest {

    private KeywordMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new KeywordMatcher();
    }

    @Nested
    @DisplayName("scoreKeywords")
    class ScoreKeywordsTests {

        @Test
        @DisplayName("Returns null when no keywords provided")
        void nullKeywords() {
            assertNull(matcher.scoreKeywords("any answer", null));
            assertNull(matcher.scoreKeywords("any answer", List.of()));
        }

        @Test
        @DisplayName("Returns 0.0 when answer is blank")
        void blankAnswer() {
            assertEquals(0.0, matcher.scoreKeywords("", List.of("keyword")));
            assertEquals(0.0, matcher.scoreKeywords(null, List.of("keyword")));
        }

        @Test
        @DisplayName("Returns 1.0 when all keywords match")
        void allMatch() {
            assertEquals(1.0, matcher.scoreKeywords(
                    "The CAP theorem states that consistency and availability trade off",
                    List.of("CAP", "consistency", "availability")));
        }

        @Test
        @DisplayName("Returns partial score for partial match")
        void partialMatch() {
            double score = matcher.scoreKeywords(
                    "The CAP theorem is important",
                    List.of("CAP", "consistency", "availability"));
            assertEquals(1.0 / 3.0, score, 0.01);
        }

        @Test
        @DisplayName("Case-insensitive matching")
        void caseInsensitive() {
            assertEquals(1.0, matcher.scoreKeywords(
                    "DISTRIBUTED SYSTEMS use consensus",
                    List.of("distributed", "consensus")));
        }

        @Test
        @DisplayName("Skips blank keywords in the list")
        void skipsBlankKeywords() {
            assertEquals(1.0, matcher.scoreKeywords(
                    "hello world",
                    Arrays.asList("hello", "", null, "   ")));
        }
    }

    @Nested
    @DisplayName("scoreExpectedAnswer")
    class ScoreExpectedAnswerTests {

        @Test
        @DisplayName("Returns null when expected answer is blank")
        void nullExpected() {
            assertNull(matcher.scoreExpectedAnswer("answer", null));
            assertNull(matcher.scoreExpectedAnswer("answer", ""));
        }

        @Test
        @DisplayName("Returns 0.0 when actual answer is blank")
        void blankActual() {
            assertEquals(0.0, matcher.scoreExpectedAnswer(null, "expected answer"));
            assertEquals(0.0, matcher.scoreExpectedAnswer("", "expected answer"));
        }

        @Test
        @DisplayName("Returns high score for matching words")
        void matchingWords() {
            double score = matcher.scoreExpectedAnswer(
                    "The gravitational force pulls objects together",
                    "Gravity is the force that pulls objects toward each other");
            assertTrue(score > 0.3, "Score should be > 0.3 for overlapping words, got " + score);
        }

        @Test
        @DisplayName("Returns low score for unrelated text")
        void unrelatedText() {
            double score = matcher.scoreExpectedAnswer(
                    "Banana smoothie recipe with oats",
                    "Quantum entanglement in particle physics");
            assertTrue(score < 0.2, "Score should be < 0.2 for unrelated text, got " + score);
        }
    }

    @Nested
    @DisplayName("combinedScore")
    class CombinedScoreTests {

        @Test
        @DisplayName("Returns null when neither keywords nor expected answer")
        void bothNull() {
            assertNull(matcher.combinedScore("answer", null, null));
        }

        @Test
        @DisplayName("Returns keyword score when only keywords provided")
        void onlyKeywords() {
            Double score = matcher.combinedScore("hello world", null, List.of("hello", "world"));
            assertNotNull(score);
            assertEquals(1.0, score);
        }

        @Test
        @DisplayName("Returns expected answer score when only expected answer provided")
        void onlyExpected() {
            Double score = matcher.combinedScore("hello world", "hello world", null);
            assertNotNull(score);
            assertTrue(score > 0.0);
        }

        @Test
        @DisplayName("Averages both scores when both provided")
        void bothProvided() {
            Double score = matcher.combinedScore(
                    "The sky is blue and vast",
                    "The sky is blue",
                    List.of("sky", "blue"));
            assertNotNull(score);
            assertTrue(score > 0.5, "Combined score should be > 0.5, got " + score);
        }
    }
}



