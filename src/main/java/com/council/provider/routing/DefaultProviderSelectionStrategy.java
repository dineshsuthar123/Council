package com.council.provider.routing;

import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
import com.council.judge.TaskType;
import com.council.provider.LlmAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Default routing strategy that selects providers based on:
 * <ul>
 *   <li>Role assignment (draft / critic / premium-escalation / etc.)</li>
 *   <li>Enabled + not-in-cooldown</li>
 *   <li>Priority ordering (lower number = higher priority)</li>
 *   <li>Concurrency availability</li>
 *   <li>Configured fallback chains</li>
 *   <li>Max draft provider cap</li>
 *   <li>Escalation thresholds</li>
 *   <li><b>Task type</b> – for technical prompts, prefer stronger reasoning providers</li>
 * </ul>
 */
@Component
public class DefaultProviderSelectionStrategy implements ProviderSelectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultProviderSelectionStrategy.class);

    /**
     * For SYSTEM_DESIGN and BACKEND_ARCHITECTURE prompts, these providers are
     * preferred (in order) because they produce stronger reasoning / system-design answers.
     */
    private static final List<String> TECHNICAL_PREFERRED_PROVIDERS =
            List.of("ollama-deepseek", "ollama-qwen-coder", "ollama-llama",
                    "deepseek", "openrouter", "mistral", "kimi", "gemini");

    /**
     * For DEBUGGING prompts, prefer providers with strong analytical capabilities.
     */
    private static final List<String> DEBUGGING_PREFERRED_PROVIDERS =
            List.of("ollama-deepseek", "ollama-qwen-coder", "ollama-llama",
                    "deepseek", "mistral", "openrouter", "groq");

    private final CouncilProperties.RoutingConfig routingConfig;
    private final CouncilProperties.OrchestratorConfig orchestratorConfig;
    private final ProviderMode providerMode;
    private final ProviderConcurrencyLimiter concurrencyLimiter;

    /* last decision for observability (per-thread safe via volatile) */
    private volatile RoutingDecision lastDecision;

    public DefaultProviderSelectionStrategy(CouncilProperties properties,
                                            ProviderConcurrencyLimiter concurrencyLimiter) {
        this.routingConfig = properties.getRouting();
        this.orchestratorConfig = properties.getOrchestrator();
        this.providerMode = ProviderMode.safe(properties.getProviderMode());
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /* ── Draft selection ────────────────────────────────────────────── */

    @Override
    public List<LlmAdapter> selectDraftProviders(List<ProviderDescriptor> descriptors,
                                                 Map<String, LlmAdapter> adapters,
                                                 String traceId) {
        return selectDraftProviders(descriptors, adapters, traceId, TaskType.GENERAL_REASONING);
    }

    @Override
    public List<LlmAdapter> selectDraftProviders(List<ProviderDescriptor> descriptors,
                                                 Map<String, LlmAdapter> adapters,
                                                 String traceId, TaskType taskType) {
        List<RoutingDecision.SkippedProvider> skipped = new ArrayList<>();
        int maxDrafts = maxDraftProviders(taskType);

        // Filter to draft-eligible, available, and concurrency-ok
        List<ProviderDescriptor> candidates = descriptors.stream()
                .filter(d -> {
                    if (!d.hasRole(ProviderRole.DRAFT)) {
                        skipped.add(new RoutingDecision.SkippedProvider(d.name(), "no DRAFT role"));
                        return false;
                    }
                    if (!d.isAvailableForRouting()) {
                        String reason = !d.enabled() ? "disabled" : "in cooldown";
                        skipped.add(new RoutingDecision.SkippedProvider(d.name(), reason));
                        return false;
                    }
                    if (!concurrencyLimiter.tryAcquire(d.name())) {
                        skipped.add(new RoutingDecision.SkippedProvider(d.name(), "max concurrency reached"));
                        return false;
                    }
                    // release immediately — actual acquire happens in orchestrator execution
                    concurrencyLimiter.release(d.name());
                    return true;
                })
                .toList();

        candidates = applyProviderModeCandidatePolicy(candidates);
        if (providerMode == ProviderMode.LOCAL_ONLY && !candidates.isEmpty()) {
            maxDrafts = Math.max(maxDrafts, candidates.size());
        }

        // Apply task-aware ordering
        List<ProviderDescriptor> ordered = applyTaskAwareOrdering(candidates, taskType);

        // Pick up to maxDrafts, favouring diversity (don't pick identical models)
        List<ProviderDescriptor> selected = selectDiverse(ordered, maxDrafts);

        // If we got fewer than desired, try fallbacks from selected providers
        if (selected.size() < maxDrafts) {
            Set<String> selectedNames = selected.stream().map(ProviderDescriptor::name).collect(Collectors.toSet());
            for (ProviderDescriptor s : new ArrayList<>(selected)) {
                if (selected.size() >= maxDrafts) break;
                for (String fallback : s.fallbackProviders()) {
                    if (selected.size() >= maxDrafts) break;
                    if (selectedNames.contains(fallback)) continue;
                    descriptors.stream()
                            .filter(d -> d.name().equals(fallback) && d.isAvailableForRouting()
                                    && d.hasRole(ProviderRole.DRAFT))
                            .findFirst()
                            .ifPresent(fb -> {
                                selected.add(fb);
                                selectedNames.add(fb.name());
                            });
                }
            }
        }

        List<String> selectedNames = selected.stream().map(ProviderDescriptor::name).toList();
        List<LlmAdapter> result = selected.stream()
                .map(d -> adapters.get(d.name()))
                .filter(Objects::nonNull)
                .toList();

        log.info("[routing] traceId={} taskType={} draft providers selected: {} (skipped: {})",
                traceId, taskType, selectedNames,
                skipped.stream().map(s -> s.provider() + "=" + s.reason()).toList());

        // Store partial decision (critic + escalation filled later)
        this.lastDecision = new RoutingDecision(traceId, selectedNames, null,
                false, List.of(), skipped);

        return result;
    }

    /**
     * Reorder candidates based on task type.
     * For technical prompts, move preferred providers to the front while
     * still respecting availability (candidates already filtered).
     */
    private List<ProviderDescriptor> applyTaskAwareOrdering(List<ProviderDescriptor> candidates,
                                                             TaskType taskType) {
        List<String> preferredOrder = switch (taskType) {
            case CODING -> List.of("ollama-qwen-coder", "ollama-deepseek", "ollama-llama",
                    "deepseek", "openrouter-qwen", "groq");
            case SYSTEM_DESIGN, BACKEND_ARCHITECTURE -> TECHNICAL_PREFERRED_PROVIDERS;
            case DEBUGGING -> DEBUGGING_PREFERRED_PROVIDERS;
            default -> List.of(); // use default priority ordering
        };

        if (preferredOrder.isEmpty()) {
            // Default: sort by priority then reliability
            return candidates.stream()
                    .sorted(modeAwareComparator())
                    .toList();
        }

        // Build a preference index: providers in the preferred list get their list position,
        // others get a high value so they sort after preferred ones
        Map<String, Integer> preferenceIndex = new HashMap<>();
        for (int i = 0; i < preferredOrder.size(); i++) {
            preferenceIndex.put(preferredOrder.get(i), i);
        }

        return candidates.stream()
                .sorted(Comparator.comparingInt(
                                (ProviderDescriptor d) -> preferenceIndex.getOrDefault(d.name(), 1000))
                        .thenComparing(this::providerModeRank)
                        .thenComparingInt(ProviderDescriptor::priority)
                        .thenComparing(Comparator.comparingDouble(ProviderDescriptor::reliability).reversed()))
                .toList();
    }

    private List<ProviderDescriptor> applyProviderModeCandidatePolicy(List<ProviderDescriptor> candidates) {
        List<ProviderDescriptor> allowed = candidates.stream()
                .filter(d -> providerMode.allowsProvider(d.name()))
                .toList();
        if (providerMode == ProviderMode.FREE_FIRST) {
            List<ProviderDescriptor> local = allowed.stream()
                    .filter(d -> ProviderMode.isLocalProvider(d.name()))
                    .toList();
            if (!local.isEmpty()) {
                return local;
            }
        }
        return allowed;
    }

    private Comparator<ProviderDescriptor> modeAwareComparator() {
        return Comparator.comparingInt(this::providerModeRank)
                .thenComparingInt(ProviderDescriptor::priority)
                .thenComparing(Comparator.comparingDouble(ProviderDescriptor::reliability).reversed());
    }

    private int providerModeRank(ProviderDescriptor descriptor) {
        boolean local = ProviderMode.isLocalProvider(descriptor.name());
        return switch (providerMode) {
            case LOCAL_ONLY -> local ? 0 : 100;
            case FREE_FIRST -> local ? 0 : 10;
            case HYBRID -> 0;
            case PREMIUM -> local ? 10 : 0;
        };
    }

    private int maxDraftProviders(TaskType taskType) {
        int fallback = Math.max(1, routingConfig.getMaxDraftProviders());
        Map<String, CouncilProperties.TaskBudgetConfig> budgets = orchestratorConfig.getTaskBudgets();
        CouncilProperties.TaskBudgetConfig budget = budgets == null ? null : budgets.get(taskKey(taskType));
        if (budget == null || budget.getMaxDraftProviders() <= 0) {
            return fallback;
        }
        return Math.min(fallback, budget.getMaxDraftProviders());
    }

    private String taskKey(TaskType taskType) {
        TaskType type = taskType == null ? TaskType.GENERAL_REASONING : taskType;
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /* ── Critic selection ───────────────────────────────────────────── */

    @Override
    public Optional<LlmAdapter> selectCriticProvider(List<ProviderDescriptor> descriptors,
                                                     Map<String, LlmAdapter> adapters,
                                                     String traceId) {
        Optional<ProviderDescriptor> critic = descriptors.stream()
                .filter(d -> providerMode.allowsProvider(d.name()))
                .filter(d -> d.hasRole(ProviderRole.CRITIC))
                .filter(ProviderDescriptor::isAvailableForRouting)
                .sorted(modeAwareComparator())
                .findFirst();

        if (critic.isPresent()) {
            String name = critic.get().name();
            log.info("[routing] traceId={} critic provider selected: {}", traceId, name);

            // Update last decision with critic
            if (lastDecision != null && traceId.equals(lastDecision.traceId())) {
                this.lastDecision = new RoutingDecision(traceId,
                        lastDecision.selectedDraftProviders(), name,
                        lastDecision.escalationTriggered(),
                        lastDecision.escalationProviders(),
                        lastDecision.skippedProviders());
            }

            return Optional.ofNullable(adapters.get(name));
        }

        // Fallback: try providers with CRITIC role from fallback chains
        for (ProviderDescriptor d : descriptors) {
            if (!d.hasRole(ProviderRole.CRITIC) || !d.isAvailableForRouting()) continue;
            if (!providerMode.allowsProvider(d.name())) continue;
            for (String fb : d.fallbackProviders()) {
                ProviderDescriptor fallback = descriptors.stream()
                        .filter(fd -> fd.name().equals(fb) && fd.isAvailableForRouting()
                                && providerMode.allowsProvider(fd.name()))
                        .findFirst().orElse(null);
                if (fallback != null && adapters.containsKey(fallback.name())) {
                    log.info("[routing] traceId={} critic fallback selected: {}", traceId, fallback.name());
                    return Optional.of(adapters.get(fallback.name()));
                }
            }
        }

        log.warn("[routing] traceId={} no critic provider available", traceId);
        return Optional.empty();
    }

    /* ── Escalation selection ───────────────────────────────────────── */

    @Override
    public List<LlmAdapter> selectEscalationProviders(List<ProviderDescriptor> descriptors,
                                                      Map<String, LlmAdapter> adapters,
                                                      double currentBestConfidence,
                                                      double contradictionSeverity,
                                                      List<String> alreadyUsedProviders,
                                                      String traceId) {
        double confThreshold = routingConfig.getEscalationConfidenceThreshold();
        double sevThreshold = routingConfig.getEscalationContradictionThreshold();

        boolean shouldEscalate =
                currentBestConfidence <= confThreshold
                        || contradictionSeverity >= sevThreshold
                        || alreadyUsedProviders.isEmpty(); // all free providers failed

        if (!shouldEscalate) {
            log.info("[routing] traceId={} escalation NOT triggered (confidence={}, severity={})",
                    traceId, currentBestConfidence, contradictionSeverity);
            return List.of();
        }

        Set<String> alreadyUsed = new HashSet<>(alreadyUsedProviders);

        List<ProviderDescriptor> escalation = descriptors.stream()
                .filter(d -> providerMode.allowsProvider(d.name()))
                .filter(d -> d.hasRole(ProviderRole.PREMIUM_ESCALATION))
                .filter(ProviderDescriptor::isAvailableForRouting)
                .filter(d -> !alreadyUsed.contains(d.name()))
                .sorted(Comparator.comparingInt(ProviderDescriptor::priority)
                        .thenComparing(Comparator.comparingDouble(ProviderDescriptor::reliability).reversed()))
                .limit(routingConfig.getMaxEscalationProviders())
                .toList();

        List<String> names = escalation.stream().map(ProviderDescriptor::name).toList();
        List<LlmAdapter> result = escalation.stream()
                .map(d -> adapters.get(d.name()))
                .filter(Objects::nonNull)
                .toList();

        log.info("[routing] traceId={} escalation TRIGGERED (confidence={}, severity={}) → providers: {}",
                traceId, currentBestConfidence, contradictionSeverity, names);

        // Update decision
        if (lastDecision != null && traceId.equals(lastDecision.traceId())) {
            this.lastDecision = new RoutingDecision(traceId,
                    lastDecision.selectedDraftProviders(),
                    lastDecision.selectedCriticProvider(),
                    true, names,
                    lastDecision.skippedProviders());
        }

        return result;
    }

    @Override
    public RoutingDecision getLastRoutingDecision() {
        return lastDecision;
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    /**
     * Select up to {@code max} providers, preferring model diversity.
     * Avoids picking providers that use the exact same model string.
     */
    private List<ProviderDescriptor> selectDiverse(List<ProviderDescriptor> sorted, int max) {
        List<ProviderDescriptor> result = new ArrayList<>();
        Set<String> usedModels = new HashSet<>();

        // First pass: pick one per unique model
        for (ProviderDescriptor d : sorted) {
            if (result.size() >= max) break;
            if (!usedModels.contains(d.model())) {
                result.add(d);
                usedModels.add(d.model());
            }
        }

        // Second pass: fill remaining slots even if model duplicates
        if (result.size() < max) {
            Set<String> picked = result.stream().map(ProviderDescriptor::name).collect(Collectors.toSet());
            for (ProviderDescriptor d : sorted) {
                if (result.size() >= max) break;
                if (!picked.contains(d.name())) {
                    result.add(d);
                    picked.add(d.name());
                }
            }
        }

        return result;
    }
}

