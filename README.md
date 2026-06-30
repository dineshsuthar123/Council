# Council

Council is a production-oriented multi-model AI orchestration and evaluation platform built with Spring Boot 3.3 and Java 21.

The product goal is not to be another single-model chat wrapper. Council routes hard prompts across multiple providers, gathers independent drafts, critiques them, verifies safety constraints, scores the final answer with deterministic rubrics, and persists an auditable trace so operators can understand exactly why a result was trusted, degraded, or rejected.

## What Council Does

- Runs task-aware multi-provider reasoning across enabled LLM adapters.
- Separates answer quality, winner confidence, and model agreement so a tie or single-provider fallback is not mistaken for low answer quality.
- Handles provider failures explicitly with safe diagnostics for auth, quota, timeout, bad schema, missing keys, invalid config, and circuit-breaker state.
- Supports Blackbox AI logical providers as independent models with per-model keys, model IDs, timeouts, preflight validation, and config warnings.
- Adds research-aware prompting with source packs, citation validation, source-boundary checks, prompt-injection handling, and evidence-grounded scoring.
- Uses invariant-based evaluation for production failure modes such as payment idempotency, URL-shortener deletion consistency, cache stampede, stale replicas, and research citation quality.
- Persists traces, debug artifacts, score dimensions, invariant findings, provider outcomes, and run diagnostics.
- Serves an operator console with readable structured answers, dimension cards, provider status, traces, and admin-protected operational surfaces.

## Current Architecture

```text
User prompt
  -> Prompt classifier
  -> Research need detector and evidence pack builder
  -> Task-aware provider selection
  -> Bounded draft execution with early-stop policy
  -> Critic and verifier passes
  -> Deterministic judge and invariant critic
  -> Structured final answer composer
  -> Trace persistence, scorecards, metrics, and operator UI
```

Key modules:

- `orchestrator`: end-to-end reasoning workflow, budgets, early stop, final quality calibration.
- `provider`: provider adapters, routing descriptors, Blackbox provider family, safe failure details.
- `judge`: deterministic scoring, task-aware weights, domain calibrators, invariant critic, mutation harness.
- `research`: Tavily integration, prompt-provided evidence parsing, source ranking, source-boundary handling.
- `trace`: trace persistence, redaction, retention, and export outbox.
- `security`: admin boundary for operational endpoints.
- `src/main/resources/static`: static operator console.

## Tech Stack

- Java 21
- Spring Boot 3.3
- PostgreSQL 16 with Flyway
- Spring Security
- Micrometer and Prometheus
- Resilience4j retry/circuit-breaker support
- JaCoCo, Maven Enforcer, OWASP Dependency Check, CycloneDX SBOM
- Playwright and axe for browser smoke/accessibility tests
- Docker Compose for local runtime parity

## Local Setup

### Prerequisites

- Docker Desktop
- Java 21 and Maven 3.9 if running outside Docker
- Node.js if running browser tests

### Configure Environment

Copy `.env.example` to `.env` and set only the providers you actually want to run.

Important local defaults:

- Public product endpoints: `/`, `/api/v1/health`, `/api/v1/reason`, `/api/v1/reason/runs`
- Admin endpoints: traces, provider status, metrics, actuator, evaluations, and design APIs
- Admin username defaults to `admin`
- Admin password must be set with `COUNCIL_ADMIN_PASSWORD` for shared environments

The legacy `CLAUDE_API_KEY` slot is currently routed through Blackbox AI in this project, not Anthropic directly. Use a Blackbox key there only if you intentionally enable that compatibility provider.

### Run With Docker Compose

```bash
docker compose up -d --build
```

Open:

```text
http://localhost:8080
```

Health:

```bash
curl http://localhost:8080/api/v1/health
```

Admin endpoints use HTTP Basic auth:

```bash
curl -u admin:your-password http://localhost:8080/api/v1/providers/status
```

### Run Without Docker

```bash
docker run -d --name council-db \
  -e POSTGRES_DB=council \
  -e POSTGRES_USER=council \
  -e POSTGRES_PASSWORD=council \
  -p 5433:5432 postgres:16-alpine

mvn clean package
java -jar target/council-0.1.0-SNAPSHOT.jar
```

## Provider Configuration

Most providers are disabled until a key is supplied. Blackbox AI has a provider-family configuration where each logical model appears as a separate Council provider.

Example:

```bash
BLACKBOX_ENABLED=true
BLACKBOX_GPT55_ENABLED=true
BLACKBOX_GPT55_API_KEY=your-blackbox-key
BLACKBOX_GPT55_MODEL=blackboxai/openai/gpt-5.5
BLACKBOX_GPT55_TIMEOUT_MS=60000
```

Preflight validation is intentionally disabled by default so CI, startup, and forked PRs do not consume quota:

```bash
BLACKBOX_PREFLIGHT_ENABLED=false
BLACKBOX_PREFLIGHT_TIMEOUT_MS=10000
BLACKBOX_PREFLIGHT_MAX_TOKENS=8
```

To manually validate configured Blackbox models from the admin API:

```bash
curl -u admin:your-password -X POST http://localhost:8080/api/v1/providers/preflight
```

The status response exposes model ID, enabled/configured booleans, preflight status, safe failure category, timeout source, and config warnings. It never exposes API keys or raw upstream responses.

## Main API

### Submit Reasoning Request

```http
POST /api/v1/reason
Content-Type: application/json
```

```json
{
  "query": "A payment service retries the same idempotency key after a crash. Design the safe transfer path."
}
```

Important response fields:

- `finalAnswer`: structured answer returned to the user.
- `answerQuality`: rubric-calibrated quality score.
- `winnerConfidence`: selection certainty.
- `modelAgreement`: agreement across valid drafts, or `null` when only one valid draft exists.
- `providerOutcomes`: succeeded, failed, skipped, and unavailable providers.
- `runDiagnostics`: selected, attempted, valid, failed, skipped, unavailable, coverage, and run-health fields.
- `dimensions`: domain scoring dimensions and contract metadata.
- `invariants`: invariant critic checks and violations.
- `research`: source pack and citation context when research is required.

## Operations API

Admin protected:

- `GET /api/v1/providers/status`
- `GET /api/v1/providers/status?refreshPreflight=true`
- `POST /api/v1/providers/preflight`
- `POST /api/v1/providers/{name}/reset-cooldown`
- `GET /api/v1/providers/scorecards`
- `GET /api/v1/traces`
- `GET /api/v1/traces/{traceId}/debug`
- `GET /api/v1/metrics`
- `/actuator/**`

Public:

- `GET /`
- `GET /api/v1/health`
- `POST /api/v1/reason`
- `POST /api/v1/reason/runs`
- `GET /api/v1/reason/runs/{id}/events`

## Validation

Standard deterministic checks:

```bash
mvn test
mvn verify
node --check src/main/resources/static/app.js
npm run test:ui
```

If Maven is not installed on the host, run the Java checks in the project-standard Java 21 container:

```powershell
docker run --rm -v "${PWD}:/workspace" -v "${env:USERPROFILE}\.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-21 mvn test
```

Live provider tests are gated and should not run in default PR CI. Enable them only in trusted environments with explicit credentials and quota:

```bash
mvn test -Dlive.provider.tests=true
```

## Security And Data Handling

- Operational endpoints require admin authentication.
- Unknown API routes return structured 404 JSON.
- Health details are restricted by authorization.
- Provider logs avoid prompt bodies, response bodies, API keys, and Authorization headers.
- Trace debug artifacts are redacted before persistence.
- Raw debug retention and trace export retention are configurable.
- Research source packs preserve citation IDs and source-boundary metadata for auditability.

## Documentation

- Runtime routing, budgets, and provider diagnostics: `docs/RUNTIME.md`
- Trace redaction, retention, and export operations: `docs/TRACE_OPERATIONS.md`
- Evaluator design notes: `docs/EVALUATOR.md`
- Historical implementation notes: `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_SUMMARY.md`

## Project Progress

Council is currently in production-hardening mode. Recent completed work includes:

- Security boundary for admin/operator endpoints.
- Structured 404 error contract for unknown API paths.
- CI/CD baseline with `mvn verify`, Docker build validation, JaCoCo, Maven Enforcer, Dependency Check, SBOM, Dependabot, and dependency review hygiene.
- Research-mode parity in Docker Compose with explicit Tavily health reporting.
- Task-aware routing, bounded execution, early-stop policy, degraded-mode trace diagnostics, and provider outcome semantics.
- Invariant-based evaluator for payment transfer, URL-shortener deletion consistency, and research evidence quality.
- Mutation regression harness for adversarial hard-prompt variants.
- Structured final answer templates and stronger pseudocode validation.
- Operator UI improvements for provider outcomes, dimension cards, trace debug views, readable code blocks, and model-agreement `N/A` semantics.
- Trace redaction, retention, and export outbox controls.
- Blackbox model preflight validation, model/config warnings, per-provider timeout overrides, and safer research final-recommendation contract checks.

Near-term focus:

- Calibrate live Blackbox model IDs against account availability without changing user-managed model config automatically.
- Continue expanding golden hard-question suites across distributed systems, payments, research, and security.
- Improve answer synthesis templates for more domains while preserving deterministic evaluation and traceability.
