# Council — API Reference Card

## Quick API Reference

### Base URL
```
http://localhost:8080
```

### Authorization
No API key required for Council endpoints. API keys for LLM providers stored server-side.

---

## 1. Reasoning Endpoint

### Submit Reasoning Query
```http
POST /api/v1/reason
Content-Type: application/json

{
  "query": "Explain the CAP theorem and its practical implications"
}
```

**Response (200 OK):**
```json
{
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "finalAnswer": "The CAP theorem states that a distributed system can guarantee at most two of three properties: Consistency, Availability, and Partition tolerance. In practice, when network partitions occur, you must choose between serving stale data (AP) or returning errors (CP). Most real-world systems choose AP (like DynamoDB) or CP (like traditional databases), adjusting trade-offs based on requirements.",
  "judgeReason": "Ranking: deepseek=0.85, openrouter=0.78, groq=0.72. Winner 'deepseek' selected for best balance of clarity and accuracy.",
  "usedProviders": ["deepseek", "openrouter", "groq"],
  "failedProviders": [],
  "confidence": 0.85
}
```

**Error Response (400):**
```json
{
  "error": "Query cannot be blank",
  "timestamp": "2026-04-16T12:34:56Z"
}
```

**Error Response (500 - All providers failed):**
```json
{
  "traceId": "...",
  "judgeReason": "All providers failed",
  "usedProviders": [],
  "failedProviders": ["deepseek", "openrouter", "groq"],
  "confidence": 0.0
}
```

---

## 2. Trace Endpoints

### List All Traces (Paginated)
```http
GET /api/v1/traces?page=0&size=20
```

**Response (200 OK):**
```json
{
  "content": [
    {
      "traceId": "550e8400-...",
      "prompt": "Explain the CAP theorem...",
      "finalAnswer": "The CAP theorem states...",
      "usedProviders": ["deepseek", "openrouter"],
      "confidence": 0.85,
      "createdAt": "2026-04-16T12:34:56Z"
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "currentPage": 0
}
```

### Get Trace by ID
```http
GET /api/v1/traces/{traceId}
```

Example:
```http
GET /api/v1/traces/550e8400-e29b-41d4-a716-446655440000
```

**Response (200 OK):**
```json
{
  "traceId": "550e8400-...",
  "prompt": "Explain the CAP theorem",
  "finalAnswer": "The CAP theorem states...",
  "drafts": [
    {
      "provider": "deepseek",
      "model": "deepseek-chat",
      "status": "SUCCESS",
      "answer": "...",
      "confidence": 0.87,
      "latencyMs": 1520
    },
    {
      "provider": "openrouter",
      "model": "qwen-2.5-72b",
      "status": "SUCCESS",
      "answer": "...",
      "confidence": 0.79,
      "latencyMs": 1840
    }
  ],
  "criticResult": {
    "globalSummary": "DeepSeek provides clearer explanation with better structure",
    "contradictionSeverity": 0.15,
    "contradictionsFound": []
  },
  "judgeResult": {
    "winnerProvider": "deepseek",
    "winnerScore": 0.85,
    "rankings": [
      {"provider": "deepseek", "score": 0.85},
      {"provider": "openrouter", "score": 0.78}
    ]
  },
  "usedProviders": ["deepseek", "openrouter"],
  "failedProviders": [],
  "totalLatencyMs": 3400,
  "createdAt": "2026-04-16T12:34:56Z"
}
```

**Error Response (404):**
```json
{
  "error": "Trace not found: 550e8400-...",
  "timestamp": "2026-04-16T12:34:56Z"
}
```

### Get Trace Debug View (Detailed)
```http
GET /api/v1/traces/{traceId}/debug
```

**Response (200 OK):**
Contains full details including:
- Raw provider responses (before normalization)
- Normalized JSON payloads
- Provider retry history
- Circuit breaker state at time of request
- Token usage (if available)
- Performance metrics per phase

---

## 3. Provider Status Endpoints

### Get Provider Status
```http
GET /api/v1/providers/status
```

**Response (200 OK):**
```json
{
  "providers": [
    {
      "name": "deepseek",
      "displayName": "DeepSeek Chat",
      "model": "deepseek-chat",
      "enabled": true,
      "coolingDown": false,
      "cooldownUntil": null,
      "consecutive429Count": 0,
      "recentFailureRate": 0.05,
      "lastSuccess": "2026-04-16T12:30:15Z",
      "lastFailure": null,
      "reliability": 0.85,
      "priority": 1,
      "roles": ["DRAFT", "CRITIC"],
      "maxConcurrency": 2,
      "fallbackProviders": ["openrouter", "mistral"]
    },
    {
      "name": "gemini",
      "displayName": "Gemini Flash",
      "model": "gemini-2.5-flash",
      "enabled": true,
      "coolingDown": false,
      "cooldownUntil": null,
      "consecutive429Count": 0,
      "recentFailureRate": 0.02,
      "lastSuccess": "2026-04-16T12:31:45Z",
      "lastFailure": null,
      "reliability": 0.92,
      "priority": 10,
      "roles": ["CRITIC", "PREMIUM_ESCALATION"],
      "maxConcurrency": 1,
      "fallbackProviders": ["deepseek", "mistral"]
    }
  ],
  "healthSummary": {
    "totalProviders": 8,
    "enabledProviders": 8,
    "coolingDownProviders": 0,
    "averageReliability": 0.82
  }
}
```

### Reset Provider Cooldown (Admin)
```http
POST /api/v1/providers/{name}/reset-cooldown

{
  "reason": "Manual reset for testing"
}
```

**Response (200 OK):**
```json
{
  "provider": "deepseek",
  "message": "Cooldown cleared",
  "coolingDown": false
}
```

---

## 4. Health Check Endpoint

### Service Health
```http
GET /api/v1/health
```

**Response (200 OK):**
```json
{
  "status": "UP",
  "timestamp": "2026-04-16T12:34:56Z",
  "components": {
    "database": {
      "status": "UP",
      "database": "PostgreSQL",
      "validationQuery": "isValid()"
    },
    "providers": {
      "status": "UP",
      "enabledProviders": 8,
      "coolingDownProviders": 0,
      "averageFailureRate": 0.08
    }
  }
}
```

**Response (503 Service Unavailable):**
```json
{
  "status": "DOWN",
  "error": "Database connection failed",
  "timestamp": "2026-04-16T12:34:56Z"
}
```

---

## 5. Metrics Endpoint

### Get Summary Metrics
```http
GET /api/v1/metrics
```

**Response (200 OK):**
```json
{
  "orchestrationMetrics": {
    "totalRequests": 245,
    "successfulRequests": 238,
    "failedRequests": 7,
    "averageLatencyMs": 3420,
    "p99LatencyMs": 8900
  },
  "providerMetrics": {
    "deepseek": {
      "calls": 240,
      "successes": 238,
      "failures": 2,
      "averageLatencyMs": 1520,
      "rate429": 0.008
    },
    "gemini": {
      "calls": 120,
      "successes": 120,
      "failures": 0,
      "averageLatencyMs": 980,
      "rate429": 0.0
    }
  },
  "criticMetrics": {
    "calls": 245,
    "averageLatencyMs": 1240,
    "failureRate": 0.02
  },
  "judgeMetrics": {
    "calls": 245,
    "averageLatencyMs": 45,
    "winnerDistribution": {
      "deepseek": 140,
      "gemini": 85,
      "openrouter": 20
    }
  }
}
```

### Prometheus Metrics Export
```http
GET /actuator/prometheus
```

**Response:** Prometheus-format metrics (text)
```
# HELP council_provider_latency_seconds Provider request latency
# TYPE council_provider_latency_seconds summary
council_provider_latency_seconds{provider="deepseek",quantile="0.5"} 1.52
council_provider_latency_seconds{provider="deepseek",quantile="0.99"} 4.2
...
```

---

## 6. Evaluation Endpoints

### Start Evaluation Run
```http
POST /api/v1/evaluate
Content-Type: application/json

{
  "name": "reasoning-benchmark-v1",
  "tags": ["reasoning", "system-design"],
  "providerSubset": ["deepseek", "openrouter"],
  "runBaselines": true,
  "prompts": [
    {
      "prompt": "Design a payment system that handles idempotency",
      "expectedAnswer": "Use idempotency keys stored in database",
      "expectedKeywords": ["idempotency", "key", "deduplication"]
    },
    {
      "prompt": "Explain CAP theorem",
      "expectedKeywords": ["consistency", "availability", "partition"]
    }
  ]
}
```

**Response (202 Accepted):**
```json
{
  "evaluationId": "eval-550e8400-e29b-41d4-a716",
  "status": "IN_PROGRESS",
  "totalPrompts": 2,
  "processedPrompts": 0,
  "createdAt": "2026-04-16T12:34:56Z",
  "estimatedCompletionTime": "2026-04-16T12:40:00Z"
}
```

### Get Evaluation Results
```http
GET /api/v1/evaluations/{evaluationId}
```

**Response (200 OK):**
```json
{
  "evaluationId": "eval-550e8400-...",
  "name": "reasoning-benchmark-v1",
  "status": "COMPLETED",
  "createdAt": "2026-04-16T12:34:56Z",
  "completedAt": "2026-04-16T12:40:23Z",
  "totalPrompts": 2,
  "successfulPrompts": 2,
  "failedPrompts": 0,
  "prompts": [
    {
      "prompt": "Design a payment system...",
      "councilAnswer": "Use idempotency keys with a database deduplication table...",
      "councilConfidence": 0.88,
      "winnerProvider": "deepseek",
      "contradictionSeverity": 0.12,
      "latencyMs": 3420,
      "keywordMatchScore": 0.95,
      "baselines": {
        "deepseek": {
          "answer": "...",
          "confidence": 0.86,
          "latencyMs": 1520,
          "keywordMatchScore": 0.92
        },
        "openrouter": {
          "answer": "...",
          "confidence": 0.79,
          "latencyMs": 1840,
          "keywordMatchScore": 0.88
        }
      }
    }
  ],
  "aggregateMetrics": {
    "averageCouncilLatency": 3420,
    "averageBaselineLatency": 1680,
    "averageCouncilConfidence": 0.88,
    "averageBaselineConfidence": 0.82,
    "averageContradictionSeverity": 0.12,
    "providerSuccessRates": {
      "deepseek": 1.0,
      "openrouter": 1.0,
      "gemini": 1.0
    }
  }
}
```

### List Evaluations
```http
GET /api/v1/evaluations?page=0&size=20
```

**Response (200 OK):**
```json
{
  "content": [
    {
      "evaluationId": "eval-550e8400-...",
      "name": "reasoning-benchmark-v1",
      "status": "COMPLETED",
      "totalPrompts": 2,
      "successfulPrompts": 2,
      "createdAt": "2026-04-16T12:34:56Z",
      "completedAt": "2026-04-16T12:40:23Z"
    }
  ],
  "totalElements": 5,
  "totalPages": 1,
  "currentPage": 0
}
```

---

## 7. OpenAPI/Swagger Documentation

### Interactive API Explorer
```
GET /swagger-ui.html
```

Opens browser to interactive Swagger UI with:
- All endpoints documented
- Request/response examples
- Try-it-out functionality
- Schema documentation

### OpenAPI JSON Specification
```http
GET /v3/api-docs
```

Returns OpenAPI 3.0 JSON schema for integration with tools.

---

## Common Error Responses

### 400 Bad Request
```json
{
  "error": "Query cannot be blank",
  "timestamp": "2026-04-16T12:34:56Z"
}
```

### 404 Not Found
```json
{
  "error": "Trace not found: invalid-id",
  "timestamp": "2026-04-16T12:34:56Z"
}
```

### 500 Internal Server Error
```json
{
  "error": "An unexpected error occurred",
  "timestamp": "2026-04-16T12:34:56Z"
}
```

---

## Rate Limiting

No explicit rate limiting implemented. Providers have per-endpoint rate limits:
- Most providers: 100-1000 req/min
- Circuit breaker activates after 3 consecutive 429s
- Automatic cooldown: 15 minutes

---

## Authentication

No authentication required for Council endpoints.

**Security:** API keys for LLM providers stored server-side in environment variables, never exposed via API.

---

## Request/Response Details

### DraftResult Schema
```json
{
  "provider": "string",
  "model": "string",
  "status": "SUCCESS|FAILURE",
  "answer": "string",
  "summary": "string",
  "assumptions": ["string"],
  "uncertainties": ["string"],
  "confidence": 0.0,
  "latencyMs": 0,
  "rawResponse": "string (JSON)"
}
```

### CriticResult Schema
```json
{
  "provider": "string",
  "model": "string",
  "status": "SUCCESS|FAILURE",
  "globalSummary": "string",
  "contradictionSeverity": 0.0,
  "contradictionCountPerDraft": {
    "provider1": 1,
    "provider2": 3
  },
  "contradictionsFound": [
    {
      "draftA": "string",
      "draftB": "string",
      "issue": "string"
    }
  ],
  "missingPoints": ["string"],
  "riskyClaims": ["string"],
  "latencyMs": 0
}
```

### JudgeResult Schema
```json
{
  "winnerProvider": "string",
  "winnerModel": "string",
  "winnerScore": 0.0,
  "reason": "string",
  "rankings": [
    {
      "provider": "string",
      "score": 0.0
    }
  ]
}
```

---

## Pagination

All list endpoints support pagination:

```http
GET /api/v1/traces?page=0&size=20&sort=createdAt,desc
```

Parameters:
- `page` — 0-based page number (default: 0)
- `size` — page size (default: 20)
- `sort` — field and direction (default: createdAt,desc)

---

## Timestamps

All timestamps in ISO 8601 format (UTC):
```
2026-04-16T12:34:56Z
```

---

**Last Updated:** April 16, 2026  
**API Version:** v1  
**Status:** Production

