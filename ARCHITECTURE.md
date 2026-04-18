# Council Architecture Deep Dive

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        USER REQUEST                                     │
│                      (Reasoning Query)                                  │
└────────────────────────────┬────────────────────────────────────────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │ ReasonController     │
                  │ - Validate input     │
                  │ - Create traceId     │
                  └──────────┬───────────┘
                             │
                             ▼
        ┌────────────────────────────────────────────────┐
        │     ReasoningOrchestrator.reason()             │
        │                                                │
        │  ┌──────────────────────────────────────────┐ │
        │  │ 1. Classify prompt → TaskType            │ │
        │  │    (SYSTEM_DESIGN, DEBUGGING, CODING)    │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 2. Select draft providers (3-5)          │ │
        │  │    via ProviderSelectionStrategy         │ │
        │  │    - Filter enabled + healthy            │ │
        │  │    - Skip in cooldown                     │ │
        │  │    - Respect priority + concurrency       │ │
        │  │    - Task-aware selection                 │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 3. DRAFT PHASE (Parallel)                │ │
        │  │    Virtual Threads / StructuredTaskScope │ │
        │  │                                           │ │
        │  │    For each provider:                     │ │
        │  │    ├─ ProviderCallExecutor               │ │
        │  │    │  ├─ Check circuit breaker           │ │
        │  │    │  ├─ Make request                     │ │
        │  │    │  ├─ Retry on 429/5xx (max 2)        │ │
        │  │    │  ├─ Exponential backoff + jitter    │ │
        │  │    │  └─ Return or fail                   │ │
        │  │    ├─ JsonResponseNormalizer              │ │
        │  │    │  ├─ Trim whitespace                  │ │
        │  │    │  ├─ Try strict parse                 │ │
        │  │    │  ├─ Strip markdown fences            │ │
        │  │    │  ├─ Extract JSON fragment            │ │
        │  │    │  └─ Retry parse                      │ │
        │  │    ├─ SchemaValidator                     │ │
        │  │    │  ├─ Validate response schema         │ │
        │  │    │  └─ Fail cleanly if invalid          │ │
        │  │    └─ DraftResult (success/failure)       │ │
        │  │                                           │ │
        │  │    Timeout per draft: 90 seconds          │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 4. CRITIC PHASE (if drafts exist)         │ │
        │  │    Single provider (Gemini / DeepSeek)    │ │
        │  │                                           │ │
        │  │    CriticEngine.critique():               │ │
        │  │    ├─ Select critic provider              │ │
        │  │    ├─ Send all successful drafts          │ │
        │  │    ├─ Parse structured response           │ │
        │  │    └─ Extract contradictions              │ │
        │  │        - contradictionSeverity (0-1)      │ │
        │  │        - contradictionCountPerDraft       │ │
        │  │        - riskyClaims[]                    │ │
        │  │        - missingPoints[]                  │ │
        │  │        - genericnessPenalty               │ │
        │  │                                           │ │
        │  │    Timeout: 120 seconds                   │ │
        │  │    Fallback: Gemini→DeepSeek→Mistral     │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 5. JUDGE PHASE (Task-Aware Scoring)       │ │
        │  │                                           │ │
        │  │    DeterministicJudge.evaluate():         │ │
        │  │    ├─ For each draft:                     │ │
        │  │    │  ├─ Base score =                     │ │
        │  │    │  │   (confidence × 0.40)             │ │
        │  │    │  │ + (reliability × 0.30)             │ │
        │  │    │  │ - (contradictionPenalty × 0.30)    │ │
        │  │    │  │                                    │ │
        │  │    │  ├─ Task-aware adjustment:           │ │
        │  │    │  │  - SYSTEM_DESIGN:                 │ │
        │  │    │  │    ↓ specificity scoring          │ │
        │  │    │  │    ↑ penalty for generic          │ │
        │  │    │  │  - DEBUGGING:                     │ │
        │  │    │  │    ↑ root-cause depth             │ │
        │  │    │  │    ↑ mitigation realism           │ │
        │  │    │  │  - CODING:                        │ │
        │  │    │  │    ↑ confidence signal            │ │
        │  │    │  │    ↓ penalty for vagueness        │ │
        │  │    │  │                                    │ │
        │  │    │  └─ Final score (0.0-1.0)            │ │
        │  │    ├─ Rank drafts by score                │ │
        │  │    └─ Select winner (highest score)       │ │
        │  │                                           │ │
        │  │    SpecificityScorer checks for:          │ │
        │  │    - idempotency, ledger, outbox          │ │
        │  │    - circuit breaker, dead letter queue   │ │
        │  │    - consensus algorithm, quorum          │ │
        │  │    - etc. (domain-specific keywords)      │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 6. CHECK ESCALATION CRITERIA (Optional)   │ │
        │  │                                           │ │
        │  │    If shouldEscalate():                   │ │
        │  │    ├─ confidence < 0.45 OR                │ │
        │  │    ├─ contradictionSeverity > 0.70        │ │
        │  │    └─ call Gemini/Claude for re-review    │ │
        │  │       (repeat critic + judge)             │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 7. BUILD FINAL RESPONSE                   │ │
        │  │    ├─ traceId                             │ │
        │  │    ├─ finalAnswer (winner draft text)     │ │
        │  │    ├─ judgeReason (explanation)           │ │
        │  │    ├─ usedProviders[]                     │ │
        │  │    ├─ failedProviders[]                   │ │
        │  │    └─ confidence (winner score)           │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 8. ASYNC TRACE PERSISTENCE                │ │
        │  │    (@Async, never blocks response)        │ │
        │  │    ├─ Save trace to PostgreSQL            │ │
        │  │    ├─ Store all requests/responses        │ │
        │  │    ├─ Store normalized DTOs               │ │
        │  │    ├─ Store judge result                  │ │
        │  │    └─ Update metrics                      │ │
        │  └──────────┬───────────────────────────────┘ │
        │             │                                  │
        │  ┌──────────▼───────────────────────────────┐ │
        │  │ 9. COLLECT METRICS                        │ │
        │  │    ├─ Total latency                       │ │
        │  │    ├─ Per-provider latency                │ │
        │  │    ├─ Success/failure rates               │ │
        │  │    ├─ Contradiction severity              │ │
        │  │    ├─ Judge decision distribution         │ │
        │  │    └─ Micrometer counters/timers          │ │
        │  └──────────┬───────────────────────────────┘ │
        └─────────────┼──────────────────────────────────┘
                      │
                      ▼
            ┌─────────────────────────┐
            │  FinalResponse (JSON)    │
            │  Returned to user        │
            │  traceId + answer        │
            └─────────────────────────┘
```

---

## Package Structure & Responsibility Matrix

### api/
**Responsibility:** REST API boundaries

```
ReasonController
├─ POST /api/v1/reason
│  ├─ Validate ReasonRequest
│  ├─ Call ReasoningOrchestrator.reason()
│  └─ Return FinalResponse

TraceController
├─ GET /api/v1/traces
├─ GET /api/v1/traces/{id}
└─ GET /api/v1/traces/{id}/debug

HealthController
├─ GET /api/v1/health
├─ GET /api/v1/providers/status
└─ POST /api/v1/providers/{name}/reset-cooldown

EvaluationController
├─ POST /api/v1/evaluate
├─ GET /api/v1/evaluations/{runId}
└─ GET /api/v1/evaluations

GlobalExceptionHandler
├─ Maps exceptions to ErrorResponse
└─ Returns proper HTTP status codes
```

### config/
**Responsibility:** Spring configuration & infrastructure

```
CouncilProperties
├─ council.providers.* → ProviderConfig
├─ council.critic.provider → critic selection
├─ council.orchestrator.* → pipeline config
├─ council.routing.* → provider routing config
└─ Typed, validated YAML properties

AsyncConfig
├─ ThreadPoolTaskExecutor (async persistence)
└─ Graceful shutdown with awaitTermination()

MdcFilter
├─ Adds requestId to MDC
├─ Propagates traceId through request
└─ All logs include context

RestClientFactory
├─ Creates RestClient bean
├─ Configures timeout
└─ Handles SSL/TLS

OpenApiConfig
├─ Swagger UI configuration
└─ OpenAPI 3.0 documentation
```

### model/
**Responsibility:** Domain data models (immutable records)

```
DraftRequest       → User query + metadata
DraftResult        → Provider response (success/failure)
CriticRequest      → List of drafts to review
CriticResult       → Critic evaluation
Contradiction      → Single issue between drafts
JudgeResult        → Winner + ranking
JudgeRanking       → Per-provider score
```

### provider/
**Responsibility:** LLM provider abstraction

```
LlmAdapter (interface)
├─ generateDraft(DraftRequest) → DraftResult
└─ generateCritique(CriticRequest) → CriticResult

AbstractLlmAdapter (base class)
├─ Default retry/timeout handling
├─ JSON normalization integration
└─ RestClient usage

ProviderRegistry
├─ Registry of all adapters
├─ Get adapter by name
└─ List all available adapters

ProviderCallExecutor
├─ Retry logic (exponential backoff + jitter)
├─ Circuit breaker check
├─ Rate-limit handling
└─ Timeout enforcement

ResponseMapper
├─ Maps raw response → DraftResult/CriticResult
├─ Handles different response formats
└─ Null-safe

PromptTemplates
├─ Draft prompt template
├─ Critic prompt template
└─ Variable substitution

Adapters (Claude, Gemini, DeepSeek, etc.)
├─ Provider-specific request/response mapping
├─ Auth header construction
├─ Model-specific adjustments
└─ Failure handling

routing/
├─ ProviderSelectionStrategy (interface)
├─ DefaultProviderSelectionStrategy
│  ├─ Select draft providers (3-5)
│  ├─ Select critic provider (1)
│  ├─ Select escalation providers (0-1)
│  └─ Task-aware selection
├─ ProviderDescriptor (metadata model)
├─ ProviderRole (enum: DRAFT, CRITIC, etc.)
├─ ProviderConcurrencyLimiter (per-provider cap)
└─ RoutingDecision (logs selection)
```

### json/
**Responsibility:** JSON validation & normalization

```
JsonResponseNormalizer
├─ Trim whitespace
├─ Try strict JSON parse
├─ Strip markdown (```json ... ```)
├─ Extract JSON fragment
├─ Retry parse
└─ Return normalized JSON or null

JsonExtractor
├─ Find JSON object in text
├─ Handle multiple objects
└─ Return first valid JSON

SchemaValidator
├─ Validate against expected schema
├─ Check required fields
├─ Validate types
└─ Return validation result
```

### resilience/
**Responsibility:** Fault tolerance & resilience

```
ProviderCircuitBreaker
├─ Per-provider state
├─ Track consecutive 429s
├─ Manage cooldown periods
├─ Thread-safe atomic updates
└─ Check before every call

ProviderCooldownState
├─ cooldownUntil (timestamp)
├─ consecutive429Count
├─ recentFailureRate
├─ lastSuccess/lastFailure
└─ Atomic operations
```

### orchestrator/
**Responsibility:** Main pipeline orchestration

```
ReasoningOrchestrator
├─ reason(userQuery) → FinalResponse
├─ selectDraftProviders(taskType)
├─ selectEscalationProviders()
├─ runDraftPhase(providers, request)
├─ runCriticPhase(request)
├─ runJudgePhase(drafts, criticResult, taskType)
├─ shouldEscalate()
└─ persistTrace() [async]
```

### critic/
**Responsibility:** Critical analysis

```
CriticEngine
├─ critique(criticRequest) → CriticResult
├─ Select critic provider
├─ Call provider
├─ Normalize response
├─ Extract structured fields
│  ├─ contradictionSeverity
│  ├─ contradictionsFound[]
│  ├─ riskyClaims[]
│  ├─ missingPoints[]
│  ├─ genericnessPenalty
│  └─ missingFailureModes
└─ Return CriticResult or null
```

### judge/
**Responsibility:** Deterministic scoring

```
DeterministicJudge
├─ evaluate(drafts, criticResult, taskType)
│  ├─ Score each draft
│  ├─ Rank by score
│  └─ Return JudgeResult
├─ scoreDraft(draft, criticResult, taskType)
│  └─ Apply task-aware formula
└─ rankDrafts(scores)

PromptClassifier
├─ classify(query) → TaskType
├─ Heuristic rule-based
├─ Identifies:
│  ├─ SYSTEM_DESIGN
│  ├─ BACKEND_ARCHITECTURE
│  ├─ DEBUGGING
│  ├─ CODING
│  └─ GENERAL_REASONING
└─ Cache classifications

SpecificityScorer
├─ scoreSpecificity(answer, domain) → score
├─ Checks for domain keywords
│  ├─ payments: idempotency, ledger, etc.
│  ├─ distributed: consensus, quorum, etc.
│  └─ debugging: root-cause, mitigation, etc.
├─ Penalizes generic/buzzword-heavy
└─ Domain-aware scoring

TaskAwareWeights
├─ Get weights for task type
├─ confidenceWeight
├─ reliabilityWeight
├─ contradictionPenaltyWeight
└─ specificityScoreWeight
```

### trace/
**Responsibility:** Persistence & auditability

```
TraceEntity (JPA)
├─ traceId
├─ prompt
├─ draftRequests[] (JSON)
├─ draftResponses[] (JSON)
├─ parsedDrafts[] (JSON)
├─ criticRequest (JSON)
├─ criticResponse (JSON)
├─ judgeResult (JSON)
├─ finalResponse (JSON)
├─ usedProviders[]
├─ failedProviders[]
├─ latencyMs
├─ createdAt
└─ status

TraceRepository
├─ JPA repository
├─ findById(traceId)
├─ findAll paginated
└─ save(trace)

TraceService (@Service)
├─ saveTraceAsync() [@Async]
├─ getTrace(traceId)
├─ listTraces(page, size)
└─ Graceful shutdown

TraceMapper
├─ DraftResult → JSON
├─ CriticResult → JSON
├─ JudgeResult → JSON
└─ FinalResponse → JSON
```

### evaluation/
**Responsibility:** Benchmarking & quality metrics

```
EvaluationService
├─ runEvaluation(request) → async
├─ For each prompt:
│  ├─ Run orchestration pipeline
│  ├─ Optionally run baselines
│  └─ Collect metrics
├─ Calculate aggregates
└─ Persist evaluation run

BaselineRunner
├─ runBaseline(prompt, provider)
├─ Call single provider directly
└─ Return single answer + latency

KeywordMatcher
├─ score(answer, expectedKeywords)
├─ Heuristic scoring (not accuracy)
├─ Check keyword presence
└─ Return match percentage

EvaluationMetricsCalculator
├─ Calculate averages
├─ Winner frequency
├─ Failure rates
├─ Confidence distribution
└─ Latency percentiles

EvaluationController
├─ POST /api/v1/evaluate
├─ GET /api/v1/evaluations/{runId}
└─ GET /api/v1/evaluations

EvaluationRunEntity / EvaluationPromptResultEntity
├─ JPA persistence
└─ Async writing
```

### metrics/
**Responsibility:** Observability

```
OrchestrationMetrics
├─ recordProviderLatency(provider, ms)
├─ recordDraftSuccess(provider)
├─ recordDraftFailure(provider)
├─ recordCriticLatency(ms)
├─ recordJudgeLatency(ms)
├─ recordContradictionSeverity(severity)
├─ recordCooldownActivation(provider)
└─ Micrometer counters/timers/gauges
```

### common/
**Responsibility:** Shared utilities & constants

```
CouncilConstants
├─ Default timeouts
├─ Model names
├─ API endpoints
└─ Other constants

CouncilUtils
├─ UUID generation
├─ JSON utilities
└─ Validation helpers

TraceStatus (enum)
├─ IN_PROGRESS
├─ COMPLETED
├─ FAILED
└─ PARTIALLY_FAILED

ProviderResultStatus (enum)
├─ SUCCESS
├─ FAILURE
├─ RATE_LIMITED
└─ TIMEOUT

Exceptions
├─ ProviderException
├─ RateLimitException
└─ JsonNormalizationException
```

---

## Data Flow: From Request to Response

```
HTTP Request (POST /api/v1/reason)
    ↓
ReasonController
    ├─ Validate: @NotBlank query
    ├─ Create: requestId = UUID
    ├─ Create: traceId = UUID
    └─ MDC.put("traceId", traceId)
    ↓
ReasoningOrchestrator.reason()
    ├─ Create: taskType = PromptClassifier.classify(query)
    ├─ Create: draftProviders = SelectionStrategy.selectDraft(taskType)
    ├─ Parallel draft calls (virtual threads)
    │   ├─ Provider 1: Request → ProviderCallExecutor
    │   │   ├─ Check circuit breaker
    │   │   ├─ Retry loop (up to 2 times)
    │   │   ├─ Normalize JSON
    │   │   ├─ Validate schema
    │   │   └─ Return DraftResult
    │   ├─ Provider 2: (same)
    │   ├─ Provider 3: (same)
    │   └─ Collect all results
    │
    ├─ Filter: successfulDrafts = only successful ones
    ├─ Create: criticResult = CriticEngine.critique(successfulDrafts)
    │   ├─ Select critic provider
    │   ├─ Call with all drafts
    │   ├─ Parse structured response
    │   └─ Extract contradictions
    │
    ├─ Create: judgeResult = Judge.evaluate(successfulDrafts, criticResult, taskType)
    │   ├─ Score each draft (base + task-aware)
    │   ├─ Rank by score
    │   └─ Return ranking + winner
    │
    ├─ Check: shouldEscalate?
    │   └─ If yes: escalationDrafts = call Gemini/Claude
    │      └─ Re-run critic + judge with combined drafts
    │
    └─ Build: FinalResponse
        ├─ finalAnswer = winner draft text
        ├─ judgeReason = explanation
        ├─ usedProviders = [list of providers called]
        ├─ failedProviders = [list of failures]
        └─ confidence = winner score

    ↓ (return immediately)

FinalResponse → JSON → HTTP 200
    └─ Including traceId (so user can retrieve details)

    ↓ (async, doesn't block response)

TraceService.saveTraceAsync()
    ├─ Serialize all inputs/outputs
    ├─ Call TraceRepository.save()
    ├─ Persist to PostgreSQL
    ├─ Record latency
    └─ Update metrics

    ↓ (async, doesn't block response)

OrchestrationMetrics.record()
    ├─ Provider latencies
    ├─ Success/failure rates
    ├─ Judge decision distribution
    └─ Micrometer update
```

---

## Error Handling Paths

```
Provider Request Fails
    ↓
ProviderCallExecutor catches exception
    ├─ Is it 429? → Update consecutive429Count
    │   └─ If > 3: ProviderCircuitBreaker.tripCooldown()
    │
    ├─ Is it 5xx or network error? → Retry with backoff
    │   └─ Max retries: 2
    │
    └─ Is it timeout? → Log and fail provider
    
    ↓
    
DraftResult.failure(provider, error, rawResponse)
    ↓
ReasoningOrchestrator catches in draft phase
    ├─ Log failure
    ├─ Add to failedProviders[]
    └─ Continue with successful drafts
    
    ↓
    
If 0 successful drafts:
    └─ Return FinalResponse.error("All providers failed")
    
If 1+ successful drafts but critic fails:
    └─ Continue without critic signal
        └─ Judge scores on confidence alone
    
If all drafts fail AND escalation used:
    └─ Return FinalResponse.error("Escalation failed")

    ↓
    
GlobalExceptionHandler
    ├─ Catches any uncaught exception
    ├─ Logs error
    ├─ Returns ErrorResponse (never stack trace)
    └─ HTTP 500 or 400
```

---

## Resilience Pattern: Circuit Breaker

```
Provider State Machine:

HEALTHY
    ├─ consecutive429Count = 0
    ├─ cooldownUntil = null
    ├─ recentFailureRate = 0%
    └─ All requests allowed
    
    ↓ (3 consecutive 429s)
    
COOLING_DOWN
    ├─ consecutive429Count = 3
    ├─ cooldownUntil = now + 15 minutes
    ├─ recentFailureRate = high
    └─ All requests REJECTED (not even sent)
    
    ↓ (cooldown expires)
    
HEALTHY (retry)
    ├─ consecutive429Count reset
    ├─ cooldownUntil cleared
    └─ Requests allowed again
    
Note: On success, consecutive429Count resets to 0
```

---

## Scaling Considerations

### Virtual Threads
- Each provider call runs on dedicated virtual thread
- Can handle 100s of concurrent requests efficiently
- No thread pool exhaustion
- Graceful timeout handling

### Database Persistence
- Async writes never block API response
- ThreadPoolTaskExecutor handles persistence pool
- Graceful shutdown waits for pending writes

### Provider Concurrency Limits
- Per-provider max-concurrency cap
- ProviderConcurrencyLimiter enforces cap
- Prevents overwhelming any single provider

### Rate-Limiting
- ProviderCircuitBreaker tracks 429s
- Automatic cooldown prevents repeated rate limit hits
- Manual reset available via admin endpoint

---

## Monitoring Points

```
Metrics Collected:

Per Provider:
├─ latency (min/max/avg)
├─ success_count
├─ failure_count
├─ rate_limited_count (429s)
├─ cooldown_activations
└─ invalid_json_count

Per Pipeline Run:
├─ total_latency
├─ draft_phase_latency
├─ critic_latency
├─ judge_latency
├─ confidence_distribution
├─ contradiction_severity
└─ judge_decision_distribution

Micrometer Export:
├─ /actuator/prometheus (pull)
├─ Grafana compatible
└─ Prometheus scraping
```

---

**Last Updated:** April 16, 2026  
**Version:** Council MVP 0.1.0  
**Status:** Production-Ready Architecture

