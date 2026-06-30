# Contributing to Council

Thanks for helping improve Council. This guide is meant to get contributors productive without requiring paid LLM credits, local model downloads, or prior knowledge of the orchestration internals.

Most changes can be developed and tested with deterministic unit tests, mocked providers, fake HTTP servers, H2, Playwright API fixtures, and the `test` Spring profile. Ollama and live hosted providers are optional.

## Project Overview

Council is a Spring Boot 3.3 / Java 21 multi-provider AI orchestration and evaluation platform. It routes prompts across configured providers, gathers independent drafts, critiques and verifies them, judges final answer quality with deterministic rubrics, persists traces, and exposes an operator console.

Core product themes:

- Multi-provider orchestration with routing, bounded execution, fallback, and early-stop behavior.
- Deterministic evaluation for answer quality, winner confidence, model agreement, research quality, and domain invariants.
- Safe provider diagnostics for timeouts, bad schemas, missing keys, quota, unavailable models, and circuit-breaker state.
- Trace persistence with redaction, retention, export outbox, and operator-facing debug views.
- Optional local Ollama providers for free local execution, without making local model installs mandatory for contributors.

## Repository Structure

Important top-level files:

- `pom.xml`: Maven build, Java 21, Spring Boot, JaCoCo, Maven Enforcer, OWASP Dependency Check, CycloneDX SBOM.
- `package.json`: Playwright UI test scripts.
- `docker-compose.yml`: local PostgreSQL plus Council app runtime.
- `Dockerfile`: multi-stage Java 21 container build.
- `.env.example`: local environment template.
- `.github/workflows/ci.yml`: CI pipeline for syntax check, `mvn verify`, Docker build validation, artifacts, dependency review, and dependency-check.
- `docs/CI.md`: CI and supply-chain expectations.
- `docs/RUNTIME.md`: routing, provider modes, budgets, Ollama, and Blackbox runtime notes.
- `docs/TRACE_OPERATIONS.md`: trace redaction, retention, and export operations.
- `e2e/council-console.spec.js`: Playwright smoke and accessibility checks with mocked API responses.

Important source folders:

- `src/main/java/com/council/api`: REST controllers, DTOs, and global exception handling.
- `src/main/java/com/council/security`: Spring Security boundary for admin/operator endpoints.
- `src/main/java/com/council/orchestrator`: end-to-end reasoning workflow, budgets, early stop, and final scoring.
- `src/main/java/com/council/provider`: provider adapter interface, base adapter, registry, routing, status, and provider families.
- `src/main/java/com/council/provider/ollama`: optional local Ollama provider family.
- `src/main/java/com/council/provider/blackbox`: Blackbox AI logical provider family and preflight handling.
- `src/main/java/com/council/judge`: deterministic judge, calibrators, invariant critic, and mutation harness.
- `src/main/java/com/council/research`: Tavily integration, prompt-provided evidence, source ranking, and research health.
- `src/main/java/com/council/trace`: trace persistence, redaction, retention, and export outbox.
- `src/main/resources/static`: static operator console.
- `src/main/resources/db/migration`: Flyway database migrations.
- `src/test/java/com/council`: deterministic backend test suite.
- `src/test/resources/application-test.yml`: test profile using H2 and disabled live providers.

## Prerequisites

Required for normal backend work:

- Java 21
- Maven 3.9+

Required for Docker runtime parity:

- Docker Desktop or compatible Docker runtime

Required for UI smoke tests:

- Node.js
- npm

Optional:

- Ollama, only if you explicitly want to run local models.
- Provider API keys, only if you explicitly run gated live provider tests or live external-provider demos.

## Local Setup

Clone the repository and install Node dependencies if you plan to run browser tests:

```bash
npm ci
```

Copy the environment template when running the app locally:

```bash
cp .env.example .env
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Do not commit `.env` or real credentials.

## Environment Configuration

The app reads configuration from Spring properties and `.env` through `spring-dotenv`. Tests disable dotenv through `src/test/resources/application-test.yml`.

Common local variables:

- `DB_USERNAME` and `DB_PASSWORD`: PostgreSQL credentials used by Docker Compose.
- `COUNCIL_ADMIN_USERNAME`: admin username, defaults to `admin`.
- `COUNCIL_ADMIN_PASSWORD`: admin password for protected endpoints. Set this for any shared environment.
- `COUNCIL_PROVIDER_MODE`: provider posture. Valid values are `local_only`, `free_first`, `hybrid`, and `premium`.
- `TAVILY_API_KEY`: optional research provider key.
- `OLLAMA_ENABLED`: enables the optional Ollama provider family.
- `OLLAMA_BASE_URL`: `http://localhost:11434` for direct host runs, or `http://host.docker.internal:11434` when Council runs in Docker and Ollama runs on the host.

Provider keys such as `GROQ_API_KEY`, `OPENROUTER_QWEN_API_KEY`, `BLACKBOX_GPT55_API_KEY`, and others are optional. Keep hosted providers disabled unless you intentionally test them.

The `CLAUDE_API_KEY` compatibility slot is routed through Blackbox AI in this project, not Anthropic directly.

## Running the App

### Docker Compose

Use Docker Compose for the closest local runtime parity:

```bash
docker compose up -d --build
```

Open:

```text
http://localhost:8080
```

Public health:

```bash
curl http://localhost:8080/api/v1/health
```

Admin endpoints use HTTP Basic auth:

```bash
curl -u admin:your-password http://localhost:8080/api/v1/providers/status
```

### Direct Spring Boot

Start PostgreSQL separately, then run:

```bash
mvn spring-boot:run
```

Or build and run the jar:

```bash
mvn clean package
java -jar target/council-0.1.0-SNAPSHOT.jar
```

### Docker Build Only

CI validates the Docker image with:

```bash
docker build --pull --tag council-ci:local .
```

## Running Tests

Default deterministic backend tests:

```bash
mvn test
```

Full CI-like Maven verification:

```bash
mvn verify
```

Static frontend syntax check:

```bash
node --check src/main/resources/static/app.js
```

Playwright UI smoke tests:

```bash
npm run test:ui
```

Headed Playwright run:

```bash
npm run test:ui:headed
```

If Maven is not installed locally, the README documents this Java 21 Docker path for Windows PowerShell:

```powershell
docker run --rm -v "${PWD}:/workspace" -v "${env:USERPROFILE}\.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-21 mvn test
```

## Running Tests Without Ollama

You do not need Ollama or local model files for normal contribution work.

Default tests do not require Ollama:

```bash
mvn test
mvn verify
```

Why this works:

- `src/test/resources/application-test.yml` uses H2 and disables live providers.
- Ollama adapter tests use an in-process fake HTTP server instead of a real Ollama daemon.
- Provider tests use mocks, fake adapters, fake HTTP responses, or test-only configs.
- Playwright tests intercept `/api/v1/**` and return fixture responses.
- Live provider tests are gated behind explicit system properties.

Do not run this unless you intentionally want a local Ollama live smoke:

```bash
mvn test -Dlocal.provider.tests=true
```

Do not run quota-consuming live hosted provider tests unless you own the credentials and budget:

```bash
mvn test -Dlive.provider.tests=true
```

For code changes that touch Ollama behavior, prefer tests like:

- `src/test/java/com/council/provider/ollama/OllamaAdapterTest.java`
- `src/test/java/com/council/provider/ollama/OllamaModelAvailabilityServiceTest.java`
- `src/test/java/com/council/provider/ollama/OllamaProviderPropertiesTest.java`
- `src/test/java/com/council/api/controller/OllamaProviderStatusTest.java`

## Working With Provider Integrations

Provider integrations should be production-safe by default.

When changing or adding provider code:

- Keep app startup resilient when the provider is down or misconfigured.
- Never log API keys, Authorization headers, prompt bodies, or raw provider responses.
- Convert provider failures into safe categories such as timeout, bad schema, missing key, missing model, or network error.
- Add status details that help operators fix the issue without exposing secrets.
- Use deterministic adapter tests with fake HTTP servers or mocked clients.
- Keep live tests gated with `@EnabledIfSystemProperty` and make them skip safely when credentials or local services are unavailable.

Useful provider files:

- `src/main/java/com/council/provider/LlmAdapter.java`
- `src/main/java/com/council/provider/AbstractLlmAdapter.java`
- `src/main/java/com/council/provider/ProviderRegistry.java`
- `src/main/java/com/council/provider/routing/DefaultProviderSelectionStrategy.java`
- `src/main/java/com/council/provider/blackbox`
- `src/main/java/com/council/provider/ollama`

## Code Style and Conventions

General conventions:

- Use Java 21 features only when they improve clarity.
- Follow the existing Spring Boot style: constructor injection, small services, explicit DTOs, and testable units.
- Keep edits scoped to the feature or bug being changed.
- Prefer existing abstractions over new framework layers.
- Add comments only where the code needs orientation.
- Avoid changing generated output, logs, or migrations unless the behavior requires it.

Safety conventions:

- Do not weaken the admin boundary in `src/main/java/com/council/security`.
- Unknown `/api/v1/**` paths should return structured 404 JSON, not 500.
- Provider logs must stay safe for production.
- Trace debug artifacts should remain redacted and retention-controlled.
- Research source packs must preserve citation/source-boundary semantics.

Frontend conventions:

- Static UI lives in `src/main/resources/static`.
- Run `node --check src/main/resources/static/app.js` after JavaScript edits.
- Update `e2e/council-console.spec.js` when changing user-visible score cards, provider status, auth flows, trace views, or accessibility-sensitive markup.

Database conventions:

- Schema changes go in `src/main/resources/db/migration` as new Flyway migrations.
- Do not edit old migrations after they have shipped.
- Add repository/service tests for trace, evaluation, retention, or export behavior when relevant.

## Adding a New Feature

Before coding:

1. Identify the owning package and nearby tests.
2. Decide whether the change affects runtime behavior, trace persistence, scoring, UI rendering, or provider contracts.
3. Prefer a deterministic unit or integration test before touching live providers.
4. Keep external network calls out of default tests.

Common starting points:

- New provider: `provider`, `ProviderRegistry`, routing tests, provider status DTOs, and fake HTTP adapter tests.
- New scoring rule: `judge`, calibrator tests, invariant tests, hard-question calibration tests.
- New research behavior: `research`, `ResearchServiceIntegrationTest`, source/citation tests.
- New trace field: migration, `TraceEntity`, `TraceMapper`, trace DTOs, trace tests, UI rendering.
- New operator UI state: `index.html`, `app.js`, `styles.css`, Playwright fixture tests.

## Adding or Updating Tests

Match the test to the risk:

- Pure Java logic: focused unit test near the package.
- Provider transport: fake HTTP server or mocked client, no real provider.
- Orchestrator behavior: mock `LlmAdapter`, `ProviderRegistry`, critic, verifier, synthesizer, or research service.
- Security boundary: MockMvc integration tests with anonymous and authenticated requests.
- Trace persistence: repository/service tests with H2 and the test profile.
- UI behavior: Playwright route fixtures in `e2e/council-console.spec.js`.

Useful test examples:

- `ReasoningOrchestratorTest`
- `DefaultProviderSelectionStrategyTest`
- `ProviderRegistryTest`
- `OllamaAdapterTest`
- `BlackboxAdapterTest`
- `ResearchServiceIntegrationTest`
- `InvariantViolationCriticTest`
- `TraceRetentionServiceTest`
- `ReasonControllerIntegrationTest`
- `e2e/council-console.spec.js`

## Validating Changes Before a PR

For documentation-only changes:

```bash
git diff --check
```

For frontend JavaScript changes:

```bash
node --check src/main/resources/static/app.js
npm run test:ui
```

For backend changes:

```bash
mvn test
```

For changes that should match CI:

```bash
mvn verify
docker build --pull --tag council-ci:local .
```

CI runs:

- `node --check src/main/resources/static/app.js`
- `mvn -B -ntp verify`
- Docker build validation
- Dependency review for PRs
- OWASP dependency-check for trusted runs
- artifact uploads for Surefire, JaCoCo, and CycloneDX SBOMs

## Debugging Common Setup Problems

### Admin endpoints return 401

Set `COUNCIL_ADMIN_PASSWORD` in `.env`, restart the app, and use HTTP Basic auth.

### No providers are available

This is expected if no hosted providers are enabled and Ollama is disabled or unavailable. You can still run deterministic tests. For live local reasoning, install Ollama and pull models, or enable a hosted provider intentionally.

### Ollama is not running

Normal tests do not need Ollama. If you intentionally run local-only mode, start Ollama and verify:

```bash
curl http://localhost:11434/api/tags
```

### Docker app cannot reach host Ollama

Use:

```bash
OLLAMA_BASE_URL=http://host.docker.internal:11434
```

### Playwright cannot find browsers

Run `npm ci` first. If Playwright reports missing browsers, follow the command it prints for your platform.

### Maven is not installed

Use the Docker Maven command shown in this guide or install Maven 3.9+ locally.

## Pull Request Checklist

Before opening a PR:

- The change is scoped and described clearly.
- No real credentials are committed.
- Default tests do not require Ollama, hosted providers, or live network quota.
- New behavior has tests or a clear reason tests were not added.
- Provider errors are safe and actionable.
- Logs do not include prompt bodies, response bodies, API keys, or Authorization headers.
- Trace/debug changes preserve redaction and retention expectations.
- UI changes pass `node --check` and update Playwright tests when user-visible behavior changes.
- `mvn test` or the relevant focused Maven test set passes.
- `mvn verify` passes for CI-level changes.
- Docker build impact is considered when changing runtime config.

## Issue Contribution Workflow

1. Pick an issue with a scope that matches your comfort level.
2. Comment on the issue if you want to claim it.
3. Create a branch with a descriptive name.
4. Read the linked files in the issue before editing.
5. Add or update tests first when practical.
6. Run the validation commands relevant to the change.
7. Open a PR that links the issue and explains behavior, tests, and any tradeoffs.

Difficulty labels used by this project:

- `good first issue`: small, well-scoped docs, tests, or local refactors.
- `medium`: requires understanding one subsystem.
- `large`: cross-cutting feature or architecture work.

If an issue asks for live provider validation, default to mocks first and keep live checks optional and gated.
