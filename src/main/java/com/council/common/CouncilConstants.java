package com.council.common;

/**
 * Application-wide constants.
 */
public final class CouncilConstants {

    private CouncilConstants() {}

    /* ── Scoring weights ───────────────────────────────────────────── */
    public static final double W_CONFIDENCE    = 0.40;
    public static final double W_RELIABILITY   = 0.30;
    public static final double W_CONTRADICTION = 0.30;

    /** Score penalty applied per contradiction found for a draft. */
    public static final double PENALTY_PER_CONTRADICTION = 0.20;

    /* ── MDC keys ──────────────────────────────────────────────────── */
    public static final String MDC_TRACE_ID  = "traceId";
    public static final String MDC_PROVIDER  = "provider";
    public static final String MDC_REQUEST_ID = "requestId";

    /* ── HTTP headers ──────────────────────────────────────────────── */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /* ── Defaults ──────────────────────────────────────────────────── */
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final double DEFAULT_RELIABILITY = 0.50;
}

