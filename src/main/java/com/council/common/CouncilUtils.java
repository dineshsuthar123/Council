package com.council.common;

/**
 * Small, side-effect-free utility methods shared across packages.
 */
public final class CouncilUtils {

    private CouncilUtils() {}

    /** Clamp a double to [0.0, 1.0]. */
    public static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Round to three decimal places. */
    public static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** Null-safe truncation for log / storage contexts. */
    public static String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}

