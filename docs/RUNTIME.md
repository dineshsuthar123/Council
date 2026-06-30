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

## Blackbox AI Provider Family

Blackbox AI is configured as a provider family under
`council.providers.blackbox`. Each enabled logical model becomes a separate
Council provider, so it participates independently in drafting, ranking,
scorecards, traces, circuit breaking, and provider diagnostics.

Start with an entry in `.env` (which is ignored by Git):

```bash
BLACKBOX_GPT55_ENABLED=true
BLACKBOX_GPT55_API_KEY=your-key-here
BLACKBOX_GPT55_MODEL=blackboxai/openai/gpt-5.5
```

The full placeholder list is in `.env.example`. One Blackbox key can be used
for multiple logical models, while separate keys may be used for intentional
quota isolation. Do not use multiple keys or accounts to bypass provider rate
limits, and never commit a real key.

The model identifiers in the default configuration are examples only. Confirm
their availability in the Blackbox account/dashboard before enabling them.
Qwen remains an OpenRouter provider unless it is explicitly available and
configured in Blackbox. An enabled Blackbox model without a key remains visible
in provider status as unavailable with `API_KEY_MISSING`; it does not fail app
startup or receive routed traffic.

### Blackbox Preflight And Timeouts

Blackbox live preflight is disabled by default so app startup, local tests, and
forked PR CI do not consume quota:

```bash
BLACKBOX_PREFLIGHT_ENABLED=false
BLACKBOX_PREFLIGHT_TIMEOUT_MS=10000
BLACKBOX_PREFLIGHT_MAX_TOKENS=8
```

Operators can trigger a bounded validation pass from the admin API:

```bash
curl -u admin:your-password -X POST http://localhost:8080/api/v1/providers/preflight
```

The preflight uses a tiny "Reply with OK." request and persists only safe
metadata: provider id, model id, status, failure category, checked-at timestamp,
latency, and config warnings. API keys, Authorization headers, prompt bodies,
and raw upstream responses are not stored or rendered.

Long-running Blackbox routes can be tuned independently:

```bash
BLACKBOX_GPT55_TIMEOUT_MS=60000
BLACKBOX_GPT55_PRO_TIMEOUT_MS=90000
BLACKBOX_CLAUDE_SONNET_TIMEOUT_MS=60000
BLACKBOX_CLAUDE_OPUS_TIMEOUT_MS=90000
BLACKBOX_GEMINI_TIMEOUT_MS=45000
BLACKBOX_NEMOTRON_TIMEOUT_MS=60000
```

Provider status and traces expose the configured timeout, timeout source, and
request-size diagnostics so a 30s timeout is distinguishable from a model ID,
quota, schema, or auth problem.
