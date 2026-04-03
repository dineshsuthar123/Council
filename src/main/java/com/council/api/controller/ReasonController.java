package com.council.api.controller;

import com.council.api.dto.FinalResponse;
import com.council.api.dto.ReasonRequest;
import com.council.orchestrator.ReasoningOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Main reasoning endpoint.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reasoning", description = "Multi-model reasoning pipeline")
public class ReasonController {

    private static final Logger log = LoggerFactory.getLogger(ReasonController.class);

    private final ReasoningOrchestrator orchestrator;

    public ReasonController(ReasoningOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Operation(summary = "Submit a reasoning query",
               description = "Sends the query to all enabled LLM providers in parallel, "
                       + "runs critic evaluation, and returns the best answer selected by the judge.")
    @PostMapping("/reason")
    public ResponseEntity<FinalResponse> reason(@Valid @RequestBody ReasonRequest request) {
        log.info("[api] Received reasoning request, queryLength={}", request.query().length());
        FinalResponse response = orchestrator.reason(request.query());
        return ResponseEntity.ok(response);
    }
}

