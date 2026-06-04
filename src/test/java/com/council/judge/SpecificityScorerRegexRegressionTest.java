package com.council.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests proving {@link SpecificityScorer} regex patterns are
 * not susceptible to catastrophic backtracking (ReDoS) on adversarial
 * LLM output.
 *
 * <p>Each test is hard-capped at 3 seconds and asserts a much tighter
 * wall-clock budget (500 ms) via a manual timer. The scorer is no longer
 * on the production hot path (confirmed by {@code DeterministicJudge}),
 * but the class remains a Spring bean whose public methods are reachable,
 * so its regex surface must stay ReDoS-safe as defense-in-depth.
 */
class SpecificityScorerRegexRegressionTest {

    private static final long MAX_WALL_MS = 500L;
    private final SpecificityScorer scorer = new SpecificityScorer();

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    @DisplayName("countNumericalMatches completes fast on hostile arithmetic-shaped input")
    void arithmeticPattern_noCatastrophicBacktracking() {
        // The arithmetic pattern previously used nested (…)* which, combined with
        // an input that "almost" matches but never terminates with '=', is the
        // classic ReDoS trigger. We construct such an input here: many chained
        // "N x" operands with no final "= N" terminator.
        StringBuilder hostile = new StringBuilder(60_000);
        hostile.append("throughput estimation: ");
        for (int i = 0; i < 10_000; i++) {
            hostile.append("123 x ");
        }
        hostile.append("and no terminator so the match must fail after full scan.");

        String input = hostile.toString();

        long start = System.nanoTime();
        int matches = assertDoesNotThrow(() -> scorer.countNumericalMatches(input));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < MAX_WALL_MS,
                "countNumericalMatches took " + elapsedMs + "ms on hostile input (budget " + MAX_WALL_MS + "ms)");
        // Matches count is incidental — we only care the call terminates quickly.
        assertTrue(matches >= 0);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    @DisplayName("countNumericalMatches completes fast on hostile 'ms' context-run input")
    void msPattern_noCatastrophicBacktracking() {
        // The "ms" context patterns previously used [^.]{0,30} which, on a long
        // single-line input with many "ms" occurrences and no terminating period,
        // can cause pathological scan cost. The hardened patterns use [^.\n]{0,30}+
        // with possessive quantifiers — this input must now complete in ms, not s.
        StringBuilder hostile = new StringBuilder(60_000);
        hostile.append("latency ");
        for (int i = 0; i < 5_000; i++) {
            hostile.append("abcdefghij");   // 10 chars, no period, no newline
        }
        hostile.append(" 999 ms");

        String input = hostile.toString();

        long start = System.nanoTime();
        int matches = assertDoesNotThrow(() -> scorer.countNumericalMatches(input));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < MAX_WALL_MS,
                "countNumericalMatches took " + elapsedMs + "ms on hostile ms-context input (budget " + MAX_WALL_MS + "ms)");
        assertTrue(matches >= 0);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    @DisplayName("score() with BACKEND_ARCHITECTURE terminates fast on hostile oversize input")
    void score_oversizeInput_bounded() {
        // Input well beyond MAX_REGEX_INPUT_CHARS (16 384). The scorer must
        // bound regex work via prefix truncation. contains() scans still run
        // on the full string, but those are linear and safe.
        StringBuilder hostile = new StringBuilder(200_000);
        for (int i = 0; i < 20_000; i++) {
            hostile.append("123 x 456 x ");
        }

        String input = hostile.toString();

        long start = System.nanoTime();
        double score = assertDoesNotThrow(() -> scorer.score(input, TaskType.BACKEND_ARCHITECTURE));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < MAX_WALL_MS,
                "score() took " + elapsedMs + "ms on oversize input (budget " + MAX_WALL_MS + "ms)");
        assertTrue(score >= 0.0 && score <= 1.0, "score out of range: " + score);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    @DisplayName("countFluff completes fast on hostile oversize input")
    void countFluff_oversizeInput_bounded() {
        StringBuilder hostile = new StringBuilder(200_000);
        for (int i = 0; i < 10_000; i++) {
            hostile.append("best practices leverage seamless ");
        }
        String input = hostile.toString().toLowerCase();

        long start = System.nanoTime();
        int count = assertDoesNotThrow(() -> scorer.countFluff(input));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < MAX_WALL_MS,
                "countFluff took " + elapsedMs + "ms on oversize input (budget " + MAX_WALL_MS + "ms)");
        // With prefix truncation to 16 384 chars, at least one fluff pattern must still match.
        assertTrue(count >= 1, "expected at least one fluff match within the bounded prefix, got " + count);
    }
}
