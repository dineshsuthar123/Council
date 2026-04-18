# Council — Multi-Model Reasoning Orchestration Engine

Council is a **multi-model reasoning orchestration engine** built with Spring Boot 3 and Java 21.  
It sends a user query to multiple LLM providers in parallel, critically evaluates the responses, and selects the best answer using a deterministic scoring algorithm.

## Architecture

```
User Query
    │
    ▼
┌──────────────────────────────────┐
│        ReasoningOrchestrator     │
│  ┌─────┬─────────┬────────────┐  │
│  │Draft│  Critic  │   Judge    │  │
│  │Phase│  Phase   │   Phase    │  │
│  └──┬──┴────┬────┴─────┬─────┘  │
│     │       │          │         │
│  ┌──▼──┐ ┌──▼──┐  ┌───▼───┐    │
│  │Claude│ │Critic│  │Determ.│    │
│  │Gemini│ │Engine│  │ Judge │    │
│  │Deep  │ │      │  │       │    │
│  │Seek  │ │      │  │       │    │
│  └──────┘ └──────┘  └───────┘    │
└──────────────────────────────────┘
    │
    ▼
  FinalResponse + async Trace persistence
```

### Pipeline Phases

| Phase | Description |
|-------|-------------|
| **Draft** | Sends the query to all enabled providers in parallel (via virtual threads). Each returns a structured JSON answer with confidence score. |
| **Critic** | A designated model reviews all drafts, identifies contradictions, weak logic, and risky claims. |
| **Judge** | A deterministic scoring algorithm (`confidence × 0.40 + reliability × 0.30 − contradictionPenalty × 0.30`) ranks all drafts and selects the winner. |

## Tech Stack

- **Java 21** (virtual threads, records, sealed classes)
- **Spring Boot 3.3** (Web, JPA, Actuator, Validation)
- **PostgreSQL** + **Flyway** migrations
- **Resilience4j** (retry with exponential backoff)
- **Micrometer + Prometheus** metrics
- **H2** for tests

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL 15+ (or use Docker Compose)

### Environment Variables

| Variable | Description |
|----------|-------------|
| `CLAUDE_API_KEY` | Anthropic Claude API key |
| `GEMINI_API_KEY` | Google Gemini API key |
| `DEEPSEEK_API_KEY` | DeepSeek API key |
| `DB_USERNAME` | PostgreSQL username (default: `council`) |
| `DB_PASSWORD` | PostgreSQL password (default: `council`) |

### Run with Docker Compose

```bash
# Set your API keys
export CLAUDE_API_KEY=sk-...
export GEMINI_API_KEY=AI...
export DEEPSEEK_API_KEY=sk-...

# Start PostgreSQL + Council
docker compose up --build
```

### Run Locally

```bash
# Start PostgreSQL (e.g. via Docker)
docker run -d --name council-db \
  -e POSTGRES_DB=council \
  -e POSTGRES_USER=council \
  -e POSTGRES_PASSWORD=council \
  -p 5432:5432 postgres:16-alpine

# Build and run
mvn clean package -DskipTests
java -jar target/council-0.1.0-SNAPSHOT.jar
```

### Run Tests

```bash
mvn test
```

## API Endpoints

### Reasoning

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/reason` | Submit a reasoning query |

**Request:**
```json
{
  "query": "Explain the CAP theorem and its practical implications"
}
```

**Response:**
```json
{
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "finalAnswer": "The CAP theorem states that...",
  "judgeReason": "Ranking: gemini=0.62, deepseek=0.59. Winner 'gemini' selected.",
  "usedProviders": ["gemini", "deepseek", "claude"],
  "failedProviders": [],
  "confidence": 0.85
}
```

### Traces

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/traces?page=0&size=20` | Paginated trace listing (most recent first) |
| `GET` | `/api/v1/traces/{traceId}` | Retrieve a trace by ID |
| `GET` | `/api/v1/traces/{traceId}/debug` | Detailed debug view with all pipeline artefacts |

### Evaluation / Benchmarking

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/evaluate` | Start an evaluation run (returns run ID, async execution) |
| `GET` | `/api/v1/evaluations/{runId}` | Get full evaluation results with per-prompt metrics |
| `GET` | `/api/v1/evaluations?page=0&size=20` | List evaluation runs |

**Evaluation Request:**
```json
{
  "name": "reasoning-benchmark-v1",
  "tags": ["reasoning", "coding"],
  "providerSubset": ["gemini", "claude"],
  "runBaselines": true,
  "prompts": [
    {
      "prompt": "Explain the CAP theorem",
      "expectedAnswer": "CAP theorem states that a distributed system cannot simultaneously provide consistency, availability, and partition tolerance",
      "expectedKeywords": ["consistency", "availability", "partition tolerance"]
    }
  ]
}
```

**Evaluation Response** includes:
- Per-prompt: Council answer, confidence, latency, winner, contradiction severity, keyword match score
- Per-prompt baselines: each provider's single-model answer, confidence, latency, keyword match
- Aggregates: average latency, confidence, contradiction severity, winner frequency, provider success/failure counts, baseline comparisons

### Operations

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/health` | Service health with provider availability |
| `GET` | `/api/v1/providers/status` | Per-provider health, cooldown state, failure rates |
| `POST` | `/api/v1/providers/{name}/reset-cooldown` | Admin: manually clear a provider's cooldown |
| `GET` | `/api/v1/metrics` | Summary metrics (use `/actuator/prometheus` for full export) |

### Actuator & Docs

| Path | Description |
|------|-------------|
| `/swagger-ui.html` | Interactive Swagger UI |
| `/v3/api-docs` | OpenAPI 3.0 JSON spec |
| `/actuator/health` | Spring Boot health check |
| `/actuator/prometheus` | Prometheus-format metrics |

## Resilience Features

- **Retry with exponential backoff** — configurable max retries per provider
- **Rate-limit circuit breaker** — consecutive 429s trigger a cooldown period
- **Per-provider cooldown tracking** — thread-safe atomic state
- **Graceful degradation** — pipeline continues if some providers fail
- **Async trace persistence** — user response is never delayed by DB writes

## Configuration

All configuration is in `application.yml` under the `council` prefix:

```yaml
council:
  providers:
    claude:
      enabled: true
      api-key: ${CLAUDE_API_KEY:}
      model: claude-sonnet-4-20250514
      reliability: 0.90
    gemini:
      enabled: true
      api-key: ${GEMINI_API_KEY:}
      model: gemini-2.5-pro
      reliability: 0.85
    deepseek:
      enabled: true
      api-key: ${DEEPSEEK_API_KEY:}
      model: deepseek-chat
      reliability: 0.75
  critic:
    provider: claude
  orchestrator:
    max-retries: 2
    cooldown-minutes: 15
    consecutive-429-threshold: 3
    draft-timeout-seconds: 90
    critic-timeout-seconds: 120
```

## Project Structure

```
src/main/java/com/council/
├── api/                    # REST controllers and DTOs
│   ├── controller/
│   └── dto/
├── common/                 # Constants, utils, enums, exceptions
├── config/                 # Spring configuration, properties
├── critic/                 # Critic engine
├── evaluation/             # Benchmarking & evaluation layer
│   └── dto/                # Evaluation request/response DTOs
├── json/                   # JSON normalization pipeline
├── judge/                  # Deterministic scoring judge
├── metrics/                # Micrometer metrics facade
├── model/                  # Domain records (DraftResult, CriticResult, etc.)
├── orchestrator/           # Main reasoning pipeline
├── provider/               # LLM adapter framework
│   ├── claude/
│   ├── deepseek/
│   └── gemini/
├── resilience/             # Circuit breaker, cooldown state
└── trace/                  # Async trace persistence (JPA)
```




#   C o u n c i l 
 
 