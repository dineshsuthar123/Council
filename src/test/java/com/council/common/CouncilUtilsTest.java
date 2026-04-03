package com.council.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CouncilUtilsTest {

    /* ── clamp01 ───────────────────────────────────────────────────── */

    @Test
    @DisplayName("clamp01 returns value when within [0, 1]")
    void clamp01_inRange() {
        assertEquals(0.5, CouncilUtils.clamp01(0.5));
        assertEquals(0.0, CouncilUtils.clamp01(0.0));
        assertEquals(1.0, CouncilUtils.clamp01(1.0));
    }

    @Test
    @DisplayName("clamp01 clamps negative values to 0")
    void clamp01_negative() {
        assertEquals(0.0, CouncilUtils.clamp01(-0.5));
        assertEquals(0.0, CouncilUtils.clamp01(-100));
    }

    @Test
    @DisplayName("clamp01 clamps values above 1 to 1")
    void clamp01_above() {
        assertEquals(1.0, CouncilUtils.clamp01(1.5));
        assertEquals(1.0, CouncilUtils.clamp01(100));
    }

    /* ── round3 ────────────────────────────────────────────────────── */

    @Test
    @DisplayName("round3 rounds to three decimal places")
    void round3_basic() {
        assertEquals(0.123, CouncilUtils.round3(0.12345));
        assertEquals(0.9, CouncilUtils.round3(0.9));
        assertEquals(1.0, CouncilUtils.round3(1.0));
        assertEquals(0.667, CouncilUtils.round3(0.6666));
    }

    /* ── truncate ──────────────────────────────────────────────────── */

    @Test
    @DisplayName("truncate returns null for null input")
    void truncate_null() {
        assertNull(CouncilUtils.truncate(null, 10));
    }

    @Test
    @DisplayName("truncate returns original when under limit")
    void truncate_underLimit() {
        assertEquals("hello", CouncilUtils.truncate("hello", 10));
    }

    @Test
    @DisplayName("truncate cuts and adds ellipsis when over limit")
    void truncate_overLimit() {
        String result = CouncilUtils.truncate("hello world", 5);
        assertEquals(6, result.length()); // 5 chars + ellipsis
        assertTrue(result.startsWith("hello"));
    }

    @Test
    @DisplayName("truncate returns exact length when equal to limit")
    void truncate_exactLimit() {
        assertEquals("hello", CouncilUtils.truncate("hello", 5));
    }
}

