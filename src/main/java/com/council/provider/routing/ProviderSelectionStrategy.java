package com.council.provider.routing;

import com.council.judge.TaskType;
import com.council.provider.LlmAdapter;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for selecting which providers to use in each phase of the pipeline.
 * <p>
 * Implementations decide based on provider metadata, health, cooldown state,
 * concurrency limits, priority, role assignments, and task type.
 */
public interface ProviderSelectionStrategy {

    /**
     * Select providers for the draft phase (task-unaware, for backward compatibility).
     */
    List<LlmAdapter> selectDraftProviders(List<ProviderDescriptor> descriptors,
                                          java.util.Map<String, LlmAdapter> adapters,
                                          String traceId);

    /**
     * Select providers for the draft phase with task-aware routing.
     *
     * @param descriptors all known provider descriptors (with live state)
     * @param adapters    map of provider name → adapter
     * @param traceId     current request trace id
     * @param taskType    the classified task type for this prompt
     * @return ordered list of adapters to call (capped by config)
     */
    default List<LlmAdapter> selectDraftProviders(List<ProviderDescriptor> descriptors,
                                                  java.util.Map<String, LlmAdapter> adapters,
                                                  String traceId, TaskType taskType) {
        // Default: ignore task type (backward compat)
        return selectDraftProviders(descriptors, adapters, traceId);
    }

    /**
     * Select a single provider for the critic phase.
     *
     * @param descriptors all known provider descriptors
     * @param adapters    map of provider name → adapter
     * @param traceId     current request trace id
     * @return the chosen critic adapter, or empty if none available
     */
    Optional<LlmAdapter> selectCriticProvider(List<ProviderDescriptor> descriptors,
                                              java.util.Map<String, LlmAdapter> adapters,
                                              String traceId);

    /**
     * Select providers for premium escalation, if warranted.
     *
     * @param descriptors           all known provider descriptors
     * @param adapters              map of provider name → adapter
     * @param currentBestConfidence the confidence of the current best draft
     * @param contradictionSeverity the severity from the critic phase
     * @param alreadyUsedProviders  providers already called in this request
     * @param traceId               current request trace id
     * @return adapters for escalation (empty if escalation not warranted)
     */
    List<LlmAdapter> selectEscalationProviders(List<ProviderDescriptor> descriptors,
                                               java.util.Map<String, LlmAdapter> adapters,
                                               double currentBestConfidence,
                                               double contradictionSeverity,
                                               List<String> alreadyUsedProviders,
                                               String traceId);

    /**
     * Build a routing decision snapshot for observability.
     */
    RoutingDecision getLastRoutingDecision();
}

