package com.council.api.controller;

import com.council.api.dto.ErrorResponse;
import com.council.api.dto.TracePageResponse;
import com.council.api.dto.TraceDebugResponse;
import com.council.api.dto.TraceResponse;
import com.council.trace.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Trace lookup, listing, and debug endpoints.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Traces", description = "Reasoning trace lookup, listing, and debug views")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @Operation(summary = "List traces", description = "Paginated listing of traces, most recent first")
    @GetMapping("/traces")
    public ResponseEntity<TracePageResponse> listTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(TracePageResponse.from(traceService.findAll(page, size)));
    }

    @Operation(summary = "Get trace by ID", description = "Retrieve full trace payload by trace UUID")
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<?> getTrace(@PathVariable String traceId) {
        Optional<TraceResponse> trace = traceService.findByTraceId(traceId);
        if (trace.isPresent()) {
            return ResponseEntity.ok(trace.get());
        }
        return ResponseEntity.status(404)
                .body(ErrorResponse.of("NOT_FOUND", "Trace not found: " + traceId));
    }

    @Operation(summary = "Get trace debug view",
               description = "Detailed debug view including all pipeline artefacts (drafts, critic, judge)")
    @GetMapping("/traces/{traceId}/debug")
    public ResponseEntity<?> getTraceDebug(@PathVariable String traceId) {
        Optional<TraceDebugResponse> debug = traceService.findDebugByTraceId(traceId);
        if (debug.isPresent()) {
            return ResponseEntity.ok(debug.get());
        }
        return ResponseEntity.status(404)
                .body(ErrorResponse.of("NOT_FOUND", "Trace not found: " + traceId));
    }
}
