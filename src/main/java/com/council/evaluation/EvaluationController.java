package com.council.evaluation;

import com.council.api.dto.ErrorResponse;
import com.council.evaluation.dto.EvaluationRequest;
import com.council.evaluation.dto.EvaluationResponse;
import com.council.evaluation.dto.EvaluationRunSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Evaluation / benchmarking endpoints.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Evaluation", description = "Benchmark Council against single-model baselines")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @Operation(summary = "Start an evaluation run",
               description = "Submit a batch of prompts to evaluate the Council pipeline. "
                       + "Returns immediately with a run ID. Poll GET /evaluations/{runId} for results.")
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@Valid @RequestBody EvaluationRequest request) {
        String runId = evaluationService.startEvaluation(request);
        return ResponseEntity.accepted().body(Map.of(
                "runId", runId,
                "status", "RUNNING",
                "totalPrompts", request.prompts().size(),
                "message", "Evaluation started. Poll GET /api/v1/evaluations/" + runId + " for results."
        ));
    }

    @Operation(summary = "Get evaluation run results",
               description = "Retrieve full results for an evaluation run including per-prompt "
                       + "results, aggregate metrics, and baseline comparisons.")
    @GetMapping("/evaluations/{runId}")
    public ResponseEntity<?> getEvaluation(@PathVariable String runId) {
        Optional<EvaluationResponse> result = evaluationService.findByRunId(runId);
        if (result.isPresent()) {
            return ResponseEntity.ok(result.get());
        }
        return ResponseEntity.status(404)
                .body(ErrorResponse.of("NOT_FOUND", "Evaluation run not found: " + runId));
    }

    @Operation(summary = "List evaluation runs",
               description = "Paginated listing of evaluation runs, most recent first.")
    @GetMapping("/evaluations")
    public ResponseEntity<Page<EvaluationRunSummary>> listEvaluations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(evaluationService.listRuns(page, size));
    }
}

