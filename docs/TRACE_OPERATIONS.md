# Trace Operations and Operator UX

Council treats trace/debug artifacts as operator data. Public users can run the
reasoning endpoint and see the final answer, but trace lists, trace debug,
provider status, provider scorecards, metrics, actuator, and provider reset
operations require admin Basic auth.

## Final Answer Templates

The final composer repairs dense or incomplete answers into domain templates
when the task is recognized as high-risk.

### URL Shortener Deletion

```text
### Decision
State 404/410 and why a redirect is unsafe.

### Core Safety Reasoning
Tombstone/deleted_at/version, Redis negative cache, primary-read rule under
replica lag, stampede control, and analytics-vs-redirect separation.

### Tradeoffs
Correctness over stale availability for deleted aliases; analytics can remain
eventually consistent.

### Concrete Algorithm
Java-style pseudocode with tombstone-first order, singleflight, primary DB read,
negative caching, active-cache write, and async analytics.

### Common Mistakes
TTL-only deletion, lagging replica trust, active-cache-before-tombstone,
maybe-stale lease redirects, and dashboard-as-truth.
```

### Payment Transfer

```text
### Decision
Return stored idempotent result, reject same key with different body, or expose
PROCESSING/PENDING_REVIEW rather than double-debiting.

### Core Safety Reasoning
PostgreSQL is source of truth; Redis is not. Use idempotency records, request
hashes, deterministic row locks, atomic debit/credit, and transactional outbox.

### Tradeoffs
Higher latency is acceptable for money movement correctness; Kafka can be async
after commit; fraud path depends on product policy.

### Concrete Algorithm
Java-style pseudocode with idempotency conflict check, transaction boundary,
row locks, balance check, atomic ledger update, outbox insert, and commit.

### Common Mistakes
Kafka before commit, idempotency after debit, Redis as truth, split debit/credit,
and async-only fraud without policy conditions.
```

### Research Evidence

```text
### Decision
Answer from the evidence pack or say the source pack is insufficient.

### Core Safety Reasoning
Prefer primary/current sources, cite source IDs, separate facts from inference,
and explain conflicts.

### Tradeoffs
Freshness and citation accuracy beat speed for current-fact prompts.

### Concrete Evidence Check
Structured source-quality, recency, citation, conflict, and unsupported-claim
checks.

### Common Mistakes
Uncited current claims, weak sources, stale source use, and hidden conflicts.
```

## Scoring UX

The UI separates:

- `Answer quality`: absolute quality of the final synthesized answer.
- `Winner confidence`: selection certainty among available drafts.
- `Model agreement`: score clustering only when at least two valid drafts exist;
  otherwise shown as `N/A`.

Rubric dimensions render as bars. Unknown future dimensions are shown instead
of being silently dropped. Low dimensions use a red track; medium dimensions use
amber.

Provider failures are shown in trace detail with provider, model, latency, and
captured error reason. Missing research evidence packs show an explicit source
warning.

## Trace Redaction

`TraceRedactor` runs before persistence/export and redacts:

- Authorization headers.
- JSON/text fields named like API key, token, secret, password, authorization.
- Common long key prefixes such as `sk-`, `ghp-`, `hf-`, `nvapi-`, `tavily-`.
- Email addresses.

Configuration:

```yaml
council:
  trace:
    redaction-enabled: true
```

Disable redaction only for controlled local diagnostics. Production should keep
it enabled.

## Retention

Default retention:

| Artifact | Default |
| --- | ---: |
| Trace summaries/final answers | 30 days |
| Raw debug artifacts | 7 days |
| Export outbox rows | 14 days |

Raw debug retention strips `rawResponses`, `draftResults`, `criticResult`, and
`judgeResult` while preserving summary fields such as final answer, score
dimensions, providers, status, and latency.

Configuration:

```yaml
council:
  trace:
    retention-days: 30
    raw-debug-retention-days: 7
    export-retention-days: 14
    cleanup-cron: "0 17 3 * * *"
```

## Export Outbox

Trace export uses an internal sanitized outbox table. Enqueue failures are
caught and logged, so exporter or database outages do not break user-facing
reasoning responses.

Configuration:

```yaml
council:
  trace:
    export-outbox-enabled: true
    export-payload-max-chars: 12000
```

No external sink is required by default. The outbox is the stable async boundary
for future analytics exporters.

## Browser Smoke Tests

Playwright tests cover:

- Homepage rendering and critical accessibility checks.
- Prompt submission with structured answer, score cards, code block, and source
  warning.
- Admin unlock, provider status, trace list, trace detail, provider failure
  reasons, and `modelAgreement: N/A` semantics.

Run after the Spring Boot app is available:

```bash
npm install
npx playwright install chromium
npm run test:ui
```

Override the app URL with:

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:8080 npm run test:ui
```
