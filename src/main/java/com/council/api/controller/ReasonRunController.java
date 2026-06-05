package com.council.api.controller;

import com.council.api.dto.ReasonRequest;
import com.council.api.dto.ReasonRunResponse;
import com.council.events.PipelineEventBroadcaster;
import com.council.orchestrator.ReasoningOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Async reasoning run API for real-time frontend progress.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reasoning Runs", description = "Asynchronous reasoning runs with server-sent progress events")
public class ReasonRunController {

    private static final Logger log = LoggerFactory.getLogger(ReasonRunController.class);

    private final ReasoningOrchestrator orchestrator;
    private final PipelineEventBroadcaster eventBroadcaster;
    private final Executor reasoningRunExecutor;

    public ReasonRunController(ReasoningOrchestrator orchestrator,
                               PipelineEventBroadcaster eventBroadcaster,
                               @Qualifier("reasoningRunExecutor") Executor reasoningRunExecutor) {
        this.orchestrator = orchestrator;
        this.eventBroadcaster = eventBroadcaster;
        this.reasoningRunExecutor = reasoningRunExecutor;
    }

    @Operation(summary = "Start an asynchronous reasoning run")
    @PostMapping("/reason/runs")
    public ResponseEntity<ReasonRunResponse> startRun(@Valid @RequestBody ReasonRequest request) {
        String traceId = UUID.randomUUID().toString();
        eventBroadcaster.publish(traceId, "ACCEPTED", "done",
                "Reasoning run accepted", 0, Map.of("queryLength", request.query().length()));

        CompletableFuture.runAsync(() -> {
            try {
                orchestrator.reason(request.query(), traceId);
            } catch (Exception e) {
                log.error("[api] Async reasoning run failed, traceId={}: {}", traceId, e.getMessage(), e);
                eventBroadcaster.publish(traceId, "ERROR", "failed",
                        "Async reasoning run failed: " + e.getMessage(), 0, Map.of());
            }
        }, reasoningRunExecutor);

        return ResponseEntity.accepted().body(new ReasonRunResponse(
                traceId,
                "RUNNING",
                "/api/v1/reason/runs/" + traceId + "/events"
        ));
    }

    @Operation(summary = "Stream progress events for a reasoning run")
    @GetMapping(value = "/reason/runs/{traceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRunEvents(@PathVariable String traceId) {
        return eventBroadcaster.subscribe(traceId);
    }
}
