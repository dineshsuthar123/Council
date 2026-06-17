# Runtime Routing and Budgets

Council uses task-aware routing plus bounded execution so interactive requests do
not wait for every possible provider when the marginal value is low.

## Research Mode

Research mode is enabled by `council.research.enabled=true` and requires
`TAVILY_API_KEY` for live evidence packs. Docker Compose passes the key through
as an optional environment variable:

```bash
TAVILY_API_KEY=tvly-...
```

If the key is missing, the app stays up and health reports research as
unavailable/degraded. The public product can still answer from model memory, but
research-required prompts will carry a `ResearchPack` with the unavailable
reason.

## Default Budgets

Configured under `council.orchestrator`:

| Task type | Request | Draft phase | Provider deadline | Draft fan-out | Early-stop threshold |
| --- | ---: | ---: | ---: | ---: | ---: |
| `GENERAL_REASONING` | 45s | 18s | 12s | 2 | 0.84 |
| `CODING` | 60s | 22s | 15s | 2 | 0.86 |
| `DEBUGGING` | 70s | 25s | 18s | 3 | 0.86 |
| `SYSTEM_DESIGN` | 90s | 30s | 22s | 3 | 0.88 |
| `BACKEND_ARCHITECTURE` | 90s | 30s | 22s | 3 | 0.88 |

Global fallback flags:

- `request-timeout-seconds`: overall wall-clock request budget.
- `draft-timeout-seconds`: maximum draft phase duration.
- `per-provider-deadline-seconds`: cap for any single draft provider.
- `early-stop-enabled`: enables cancellation of pending drafts.
- `early-stop-quality-threshold`: confidence threshold for stopping once a
  sufficient draft has arrived.
- `early-stop-min-improvement`: minimum expected improvement needed to keep
  waiting on pending providers.

## Routing Decisions

When routing is enabled, draft providers are selected by:

1. DRAFT role eligibility.
2. Enabled state and circuit-breaker cooldown state.
3. Concurrency availability.
4. Task-aware ordering.
5. Model diversity.
6. Task-specific `max-draft-providers` cap.
7. Configured fallback providers if the selected set is too small.

General and coding prompts use smaller fan-out for latency. Debugging and
architecture prompts keep deeper fan-out because disagreement and failure-mode
coverage matter more.

## Degraded Mode

Budget stops and provider cancellations are persisted as failed `DraftResult`
entries with explicit reasons, such as:

- `Draft generation timed out after per-provider deadline`
- `Draft skipped: early stop after sufficient draft confidence ...`
- `Draft skipped: remaining providers were unlikely to materially improve ...`

Metrics are emitted through:

- `council.orchestration.budget_stops`
- `council.orchestration.degraded`

The final answer still preserves the existing quality breakdown fields:
answer quality, winner confidence, model agreement, and rubric dimensions.

Trace redaction, retention, export outbox, operator auth, and browser smoke test
expectations are documented in [TRACE_OPERATIONS.md](TRACE_OPERATIONS.md).
