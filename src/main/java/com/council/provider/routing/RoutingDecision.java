package com.council.provider.routing;

import java.util.List;

/**
 * Immutable snapshot of routing decisions made for a single request.
 * Used for observability logging and trace enrichment.
 *
 * @param traceId              request trace id
 * @param selectedDraftProviders  providers chosen for draft phase
 * @param selectedCriticProvider  provider chosen for critic phase (null if none)
 * @param escalationTriggered     whether premium escalation was triggered
 * @param escalationProviders     providers used for escalation (empty if not triggered)
 * @param skippedProviders        providers skipped and reasons
 */
public record RoutingDecision(
        String traceId,
        List<String> selectedDraftProviders,
        String selectedCriticProvider,
        boolean escalationTriggered,
        List<String> escalationProviders,
        List<SkippedProvider> skippedProviders
) {

    /**
     * A provider that was skipped during selection, with the reason.
     */
    public record SkippedProvider(String provider, String reason) {}
}

