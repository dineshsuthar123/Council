# Self-Correcting Design Agent — Implementation Checklist

## Plan
Deterministic, config-driven, self-correcting payment-design agent wired into `ReasoningOrchestrator`.
Constraints: throughput, DLQ, latency, consistency, capacity. Max 5 iterations. Envelope: `{"status","design","constraintsCheck","confidence"}`.

## Config
- [x] Extend `CouncilProperties` with `DesignAgentConfig` (thresholds + maxIterations, default 5)
- [x] Defaults in `application.yml` under `council.design-agent`

## Core module (`com.council.designagent`)
- [x] `PaymentDesignInput` — primary-input record
- [x] `PaymentDesign` — fully-derived design record
- [x] `ConstraintCheck` — single-constraint record
- [x] `ConstraintReport` — aggregate with `allPass`, `firstFailingConstraint`
- [x] `DesignAgentResult` — output envelope (status, design, constraintsCheck, confidence, iterations)
- [x] `PaymentDesignVerifier` — pure Phase-2 verifier (5 constraints)
- [x] `PaymentDesignRepairer` — pure Phase-3 repairer (minimal deterministic fix per failing constraint)
- [x] `SelfCorrectingDesignAgent` — loop controller (GENERATE → VERIFY → REPAIR, ≤ maxIterations)

## Orchestrator wiring
- [x] Extract payment-design JSON from successful drafts (opportunistic — only when present)
- [x] Run self-correcting agent on parsed input; attach `DesignAgentResult` to trace
- [x] Surface `NO_VALID_DESIGN` via existing `constraintFailureResponse` path when agent fails
- [x] Non-payment queries: agent skipped, existing pipeline unchanged

## REST endpoint
- [x] `POST /api/v1/design/self-correct` for direct deterministic use (no LLM)
- [x] Request/Response DTOs

## Tests (new)
- [x] `PaymentDesignVerifierTest` — each constraint pass + fail + all-pass happy path
- [x] `PaymentDesignRepairerTest` — one per repair strategy
- [x] `SelfCorrectingDesignAgentTest` — valid, 1-iter, 2-iter, unrepairable, 50k TPS exact example, throughput+latency conflict
- [x] `DesignAgentControllerIntegrationTest` — VALID, repaired, NO_VALID_DESIGN paths

## Regression
- [x] Full `mvn test` still green (existing 74+ tests untouched)
