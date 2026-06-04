package com.council.api.controller;

import com.council.designagent.DesignAgentResult;
import com.council.designagent.PaymentDesignInput;
import com.council.designagent.SelfCorrectingDesignAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct deterministic design-agent endpoint.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Design Agent", description = "Self-correcting deterministic design checks")
public class DesignAgentController {

    private final SelfCorrectingDesignAgent designAgent;

    public DesignAgentController(SelfCorrectingDesignAgent designAgent) {
        this.designAgent = designAgent;
    }

    @Operation(summary = "Run the self-correcting payment design agent")
    @PostMapping("/design/self-correct")
    public ResponseEntity<DesignAgentResult> selfCorrect(@RequestBody PaymentDesignInput input) {
        return ResponseEntity.ok(designAgent.correct(input));
    }
}
