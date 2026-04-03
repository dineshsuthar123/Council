package com.council.api.controller;

import com.council.api.dto.ErrorResponse;
import com.council.api.dto.ProviderStatusResponse;
import com.council.provider.LlmAdapter;
import com.council.provider.ProviderRegistry;
import com.council.resilience.ProviderCircuitBreaker;
import com.council.resilience.ProviderCooldownState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public HealthController(ProviderRegistry registry,
                            ProviderCircuitBreaker circuitBreaker) {
        this.registry = registry;
        this.circuitBreaker = circuitBreaker;
    }

    /* ── GET /providers/status ─────────────────────────────────────── */

    @GetMapping("/providers/status")
    public ResponseEntity<List<ProviderStatusResponse>> providerStatus() {
        List<ProviderStatusResponse> statuses = registry.getAllAdapters().entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    LlmAdapter adapter = entry.getValue();
                    ProviderCooldownState state = circuitBreaker.getState(name);
                    return new ProviderStatusResponse(
                            name,
                            adapter.modelName(),
                            adapter.isEnabled(),
                            state.isInCooldown(),
                            formatInstant(state.getCooldownUntil()),
                            state.getConsecutive429Count(),
                            state.getRecentFailureRate(),
                            state.getTotalSuccesses(),
                            state.getTotalFailures(),
                            formatInstant(state.getLastSuccessAt()),
                            formatInstant(state.getLastFailureAt())
                    );
                })
                .toList();
        return ResponseEntity.ok(statuses);
    }

    /* ── GET /health ───────────────────────────────────────────────── */

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        List<LlmAdapter> available = registry.getAvailableDraftProviders();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", available.isEmpty() ? "DEGRADED" : "UP");
        body.put("availableProviders", available.stream().map(LlmAdapter::providerName).toList());

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
