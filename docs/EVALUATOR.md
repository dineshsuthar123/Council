# Dynamic Evaluator

Council now runs a deterministic invariant critic after final answer synthesis and before trace persistence.
The critic is local Java logic: it does not call another model, search provider, or network service.

## Invariant Critic Contract

```json
{
  "evaluated": true,
  "checked": [
    {
      "domain": "URL_SHORTENER",
      "id": "url.tombstone_precedes_active_cache",
      "title": "Deletion tombstone must win over active cache",
      "description": "The redirect path must check or honor a deleted tombstone/negative cache before serving an active cached redirect.",
      "severity": "CRITICAL",
      "scoreCap": 0.75
    }
  ],
  "violations": [
    {
      "domain": "URL_SHORTENER",
      "invariantId": "url.tombstone_precedes_active_cache",
      "title": "Deletion tombstone must win over active cache",
      "severity": "CRITICAL",
      "scoreCap": 0.75,
      "evidence": "Algorithm appears to serve active cached redirect before checking tombstone/deleted state.",
      "remediation": "Check tombstone/deleted state first; only serve active cache when it is version-safe."
    }
  ],
  "domainCaps": {
    "URL_SHORTENER": 0.75
  },
  "overallCap": 0.75
}
```

## Invariant Library

Payment transfer:
- `payment.idempotency.body_mismatch`
- `payment.no_double_movement`
- `payment.atomic_ledger`
- `payment.transactional_outbox`
- `payment.crash_recovery`

URL shortener deletion and redirect consistency:
- `url.deleted_alias_must_not_redirect`
- `url.tombstone_precedes_active_cache`
- `url.replica_lag_unsafe_after_delete`
- `url.analytics_not_redirect_truth`
- `url.stampede_singleflight`
- `url.no_maybe_stale_redirect`

Research evidence quality:
- `research.cites_evidence`
- `research.valid_source_ids`
- `research.conflict_handling`
- `research.recency`
- `research.no_unsupported_current_claims`

## Mutation Harness

`ScenarioMutationHarness` generates controlled hard-prompt variants for:
- same idempotency key with different body
- app crash after debit before credit
- provider timeout retry
- Kafka producer failure after commit
- stale Redis cache after URL deletion
- Redis down plus replica lag
- replica lag duration changes
- Kafka analytics lag / producer failure
- recently deleted alias 404 vs 410 policy
- research with conflicting sources
- current research claim without citations

Each mutation carries a golden maximum score and expected invariant IDs.

## Latency

The invariant pass is bounded string analysis over the prompt, final answer, and optional research pack.
It reuses the final answer already produced by the pipeline and does not fan out to providers.
Existing calibrators consume the invariant result, so the extra work is a single local pass with no extra LLM latency.
