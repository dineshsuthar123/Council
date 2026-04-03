package com.council.model;

import java.util.List;

/**
 * Request sent to the critic adapter.
 */
public record CriticRequest(
        String traceId,
        String userQuery,
        List<DraftResult> drafts
) {}

