package com.council.api.controller;

import com.council.api.dto.ErrorResponse;
import com.council.api.dto.ProviderStatusResponse;
import com.council.api.dto.ProviderScorecardResponse;
import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
import com.council.metrics.ProviderScorecardService;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderPreflightAware;
import com.council.provider.ProviderRegistry;
import com.council.provider.ProviderStatusAware;
import com.council.provider.ProviderStatusDetails;
import com.council.provider.routing.ProviderConcurrencyLimiter;
import com.council.provider.routing.ProviderDescriptor;
import com.council.resilience.ProviderCircuitBreaker;
import com.council.resilience.ProviderCooldownState;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operational endpoints: health status, custom metrics summary, and provider status.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operations", description = "Health, metrics, and provider management")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final ProviderRegistry registry;
    private final ProviderCircuitBreaker circuitBreaker;
    private final ProviderConcurrencyLimiter concurrencyLimiter;
    private final ProviderScorecardService scorecardService;
    private final CouncilProperties properties;

    public HealthController(ProviderRegistry registry,
                            ProviderCircuitBreaker circuitBreaker,
                            ProviderConcurrencyLimiter concurrencyLimiter,
                            ProviderScorecardService scorecardService,
                            CouncilProperties properties) {
        this.registry = registry;
        this.circuitBreaker = circuitBreaker;
        this.concurrencyLimiter = concurrencyLimiter;
        this.scorecardService = scorecardService;
        this.properties = properties;
    }

    /* ── GET /providers/status ─────────────────────────────────────── */

    @GetMapping("/providers/status")
    public ResponseEntity<List<ProviderStatusResponse>> providerStatus(
            @RequestParam(defaultValue = "false") boolean refreshPreflight) {
        // Build descriptor map for routing metadata (if routing enabled)
        Map<String, ProviderDescriptor> descriptorMap = registry.isRoutingEnabled()
                ? registry.buildDescriptors().stream()
                    .collect(Collectors.toMap(ProviderDescriptor::name, Function.identity()))
                : Map.of();

        List<ProviderStatusResponse> statuses = registry.getAllAdapters().entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    LlmAdapter adapter = entry.getValue();
                    ProviderCooldownState state = circuitBreaker.getState(name);
                    ProviderDescriptor desc = descriptorMap.get(name);
                    ProviderStatusDetails details = providerStatusDetails(adapter, refreshPreflight);
                    boolean enabled = details == null ? adapter.isEnabled() : details.enabled();

                    if (desc != null) {
                        return new ProviderStatusResponse(
                                name,
                                adapter.modelName(),
                                enabled,
                                state.isInCooldown(),
                                formatInstant(state.getCooldownUntil()),
                                state.getConsecutive429Count(),
                                state.getRecentFailureRate(),
                                state.getTotalSuccesses(),
                                state.getTotalFailures(),
                                formatInstant(state.getLastSuccessAt()),
                                formatInstant(state.getLastFailureAt()),
                                desc.roles().stream().map(Enum::name).toList(),
                                desc.priority(),
                                desc.maxConcurrency(),
                                concurrencyLimiter.availablePermits(name),
                                desc.fallbackProviders(),
                                desc.isAvailableForRouting(),
                                details == null ? null : details.displayName(),
                                details == null ? null : details.configured(),
                                details == null ? null : details.available(),
                                details == null ? null : details.baseUrl(),
                                details == null ? null : details.failureReason(),
                                details == null ? null : details.timeoutMsConfigured(),
                                details == null ? null : details.timeoutSource(),
                                details == null ? null : details.configWarnings(),
                                details == null ? null : details.preflightStatus(),
                                details == null ? null : details.preflightFailureCategory(),
                                details == null ? null : details.preflightSafeMessage(),
                                details == null ? null : details.preflightCheckedAt(),
                                details == null ? null : details.preflightLatencyMs(),
                                details == null ? null : details.providerType(),
                                details == null ? null : details.modelInstalled(),
                                details == null ? null : details.remediation()
                        );
                    } else {
                        return new ProviderStatusResponse(
                                name,
                                adapter.modelName(),
                                enabled,
                                state.isInCooldown(),
                                formatInstant(state.getCooldownUntil()),
                                state.getConsecutive429Count(),
                                state.getRecentFailureRate(),
                                state.getTotalSuccesses(),
                                state.getTotalFailures(),
                                formatInstant(state.getLastSuccessAt()),
                                formatInstant(state.getLastFailureAt()),
                                null, null, null, null, null, null,
                                details == null ? null : details.displayName(),
                                details == null ? null : details.configured(),
                                details == null ? null : details.available(),
                                details == null ? null : details.baseUrl(),
                                details == null ? null : details.failureReason(),
                                details == null ? null : details.timeoutMsConfigured(),
                                details == null ? null : details.timeoutSource(),
                                details == null ? null : details.configWarnings(),
                                details == null ? null : details.preflightStatus(),
                                details == null ? null : details.preflightFailureCategory(),
                                details == null ? null : details.preflightSafeMessage(),
                                details == null ? null : details.preflightCheckedAt(),
                                details == null ? null : details.preflightLatencyMs(),
                                details == null ? null : details.providerType(),
                                details == null ? null : details.modelInstalled(),
                                details == null ? null : details.remediation()
                        );
                    }
                })
                .toList();
        return ResponseEntity.ok(statuses);
    }

    public ResponseEntity<List<ProviderStatusResponse>> providerStatus() {
        return providerStatus(false);
    }

    @PostMapping("/providers/preflight")
    public ResponseEntity<List<ProviderStatusResponse>> refreshProviderPreflight() {
        return providerStatus(true);
    }

    @GetMapping("/providers/scorecards")
    public ResponseEntity<List<ProviderScorecardResponse>> providerScorecards(
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(scorecardService.scorecards(limit));
    }

    /* ── GET /health ───────────────────────────────────────────────── */

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        List<LlmAdapter> available = registry.getAvailableDraftProviders();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", available.isEmpty() ? "DEGRADED" : "UP");
        body.put("availableProviders", available.stream().map(LlmAdapter::providerName).toList());
        body.put("providerMode", properties.getProviderMode().configValue());
        body.put("routingEnabled", registry.isRoutingEnabled());
        body.put("research", researchAvailability());
        body.put("blackbox", blackboxAvailability());
        Map<String, Object> ollamaMap = ollamaAvailability();
        body.put("ollama", ollamaMap);

        if (available.isEmpty() && properties.getProviderMode() == ProviderMode.LOCAL_ONLY) {
            String degradedReason = buildLocalOnlyDegradedReason(ollamaMap);
            if (degradedReason != null) {
                body.put("degradedReason", degradedReason);
            }
        }

        Map<String, Object> cooldowns = new LinkedHashMap<>();
        circuitBreaker.getAllStates().forEach((name, state) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("inCooldown", state.isInCooldown());
            s.put("consecutive429s", state.getConsecutive429Count());
            s.put("cooldownUntil", state.getCooldownUntil().toString());
            s.put("failureRate", state.getRecentFailureRate());
            cooldowns.put(name, s);
        });
        body.put("cooldownStates", cooldowns);

        return ResponseEntity.ok(body);
    }

    private Map<String, Object> blackboxAvailability() {
        Map<String, Object> providers = new LinkedHashMap<>();
        registry.getAllAdapters().forEach((providerId, adapter) -> {
            if (!(adapter instanceof ProviderStatusAware statusAware)
                    || !providerId.startsWith("blackbox-")) {
                return;
            }
            ProviderStatusDetails details = statusAware.providerStatusDetails();
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("displayName", details.displayName());
            provider.put("configured", details.configured());
            provider.put("enabled", details.enabled());
            provider.put("available", details.available());
            provider.put("model", adapter.modelName());
            provider.put("baseUrl", details.baseUrl());
            provider.put("failureReason", details.failureReason());
            provider.put("timeoutMsConfigured", details.timeoutMsConfigured());
            provider.put("timeoutSource", details.timeoutSource());
            provider.put("configWarnings", details.configWarnings());
            provider.put("preflightStatus", details.preflightStatus());
            provider.put("preflightFailureCategory", details.preflightFailureCategory());
            provider.put("preflightSafeMessage", details.preflightSafeMessage());
            provider.put("preflightCheckedAt", details.preflightCheckedAt());
            provider.put("preflightLatencyMs", details.preflightLatencyMs());
            providers.put(providerId, provider);
        });
        return providers;
    }

    private Map<String, Object> ollamaAvailability() {
        Map<String, Object> providers = new LinkedHashMap<>();
        registry.getAllAdapters().forEach((providerId, adapter) -> {
            if (!(adapter instanceof ProviderStatusAware statusAware)
                    || !providerId.startsWith("ollama-")) {
                return;
            }
            ProviderStatusDetails details = statusAware.providerStatusDetails();
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("displayName", details.displayName());
            provider.put("providerType", details.providerType());
            provider.put("configured", details.configured());
            provider.put("enabled", details.enabled());
            provider.put("available", details.available());
            provider.put("model", adapter.modelName());
            provider.put("baseUrl", details.baseUrl());
            provider.put("modelInstalled", details.modelInstalled());
            provider.put("failureReason", details.failureReason());
            provider.put("remediation", details.remediation());
            provider.put("timeoutMsConfigured", details.timeoutMsConfigured());
            provider.put("timeoutSource", details.timeoutSource());
            provider.put("preflightStatus", details.preflightStatus());
            provider.put("preflightFailureCategory", details.preflightFailureCategory());
            provider.put("preflightSafeMessage", details.preflightSafeMessage());
            provider.put("preflightCheckedAt", details.preflightCheckedAt());
            provider.put("preflightLatencyMs", details.preflightLatencyMs());
            providers.put(providerId, provider);
        });
        return providers;
    }

    private String buildLocalOnlyDegradedReason(Map<String, Object> ollamaProviders) {
        if (ollamaProviders.isEmpty()) {
            return "Provider mode is LOCAL_ONLY but no local (ollama-*) providers are configured. " +
                    "Enable Ollama (council.providers.ollama.enabled=true) and add models, or change providerMode.";
        }
        for (Map.Entry<String, Object> entry : ollamaProviders.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) entry.getValue();
            Boolean enabled = (Boolean) provider.get("enabled");
            Boolean available = (Boolean) provider.get("available");
            Boolean modelInstalled = (Boolean) provider.get("modelInstalled");
            String failureReason = (String) provider.get("failureReason");
            String remediation = (String) provider.get("remediation");
            String model = (String) provider.get("model");
            if (Boolean.TRUE.equals(enabled) && !Boolean.TRUE.equals(available)) {
                if (Boolean.FALSE.equals(modelInstalled)) {
                    return "Provider mode is LOCAL_ONLY but configured Ollama model '" + model +
                            "' is not installed. Run: ollama pull " + model;
                }
                if (failureReason != null) {
                    String msg = "Provider mode is LOCAL_ONLY but Ollama provider '" + entry.getKey() +
                            "' is unavailable: " + failureReason;
                    if (remediation != null) {
                        msg += ". " + remediation;
                    }
                    return msg;
                }
            }
        }
        return "Provider mode is LOCAL_ONLY but all local (ollama-*) providers are disabled. " +
                "Enable at least one Ollama model configuration.";
    }

    private ProviderStatusDetails providerStatusDetails(LlmAdapter adapter, boolean refreshPreflight) {
        if (refreshPreflight && adapter instanceof ProviderPreflightAware preflightAware) {
            return preflightAware.runPreflight();
        }
        return adapter instanceof ProviderStatusAware statusAware ? statusAware.providerStatusDetails() : null;
    }

    private Map<String, Object> researchAvailability() {
        CouncilProperties.ResearchConfig research = properties.getResearch();
        boolean enabled = research.isEnabled();
        boolean configured = research.getApiKey() != null && !research.getApiKey().isBlank();
        boolean available = enabled && configured;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("provider", research.getProvider());
        body.put("configured", configured);
        body.put("available", available);
        body.put("timeoutSeconds", research.getTimeoutSeconds());
        body.put("maxResults", research.getMaxResults());
        if (!enabled) {
            body.put("reason", "Research mode is disabled");
        } else if (!configured) {
            body.put("reason", "TAVILY_API_KEY is not configured");
        } else {
            body.put("reason", "Research provider is configured");
        }
        return body;
    }

    /* ── GET /metrics ───────────────────────────────────────────────── */

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Use /actuator/prometheus for full Prometheus metrics");

        Map<String, Object> providers = new LinkedHashMap<>();
        registry.getAllAdapters().forEach((name, adapter) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("enabled", adapter.isEnabled());
            p.put("inCooldown", circuitBreaker.isInCooldown(name));
            ProviderCooldownState state = circuitBreaker.getState(name);
            p.put("consecutive429s", state.getConsecutive429Count());
            p.put("failureRate", state.getRecentFailureRate());
            providers.put(name, p);
        });
        body.put("providers", providers);

        return ResponseEntity.ok(body);
    }

    /* ── POST /providers/{name}/reset-cooldown ─────────────────────── */

    @PostMapping("/providers/{name}/reset-cooldown")
    public ResponseEntity<?> resetCooldown(@PathVariable String name) {
        if (registry.getAdapter(name).isEmpty() && !registry.getAllAdapters().containsKey(name)) {
            return ResponseEntity.status(404)
                    .body(ErrorResponse.of("NOT_FOUND", "Unknown provider: " + name));
        }
        circuitBreaker.getState(name).resetCooldown();
        log.info("[admin] Cooldown reset for provider '{}'", name);
        return ResponseEntity.ok(Map.of(
                "provider", name,
                "cooldownReset", true,
                "message", "Cooldown cleared for " + name
        ));
    }

    private static String formatInstant(Instant instant) {
        if (instant == null || Instant.EPOCH.equals(instant)) return null;
        return instant.toString();
    }
}
