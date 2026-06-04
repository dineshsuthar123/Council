# Elite Tier Completion Roadmap

## Immediate Gates
- Keep `mvn clean test` green before every merge.
- Run `LiveComplexReasoningTest` with `-Dlive.provider.tests=true` after provider credential changes.
- Treat verifier output as authoritative only for design and capacity prompts; keep it advisory for debugging and general reasoning.

## Provider Reliability
- Fix account state before adding more orchestration logic:
  - DeepSeek verifier: restore balance or remove it from the active verifier chain until funded.
  - OpenRouter: resolve credential/account 401s before relying on Qwen/Nemotron paths.
  - Gemini: keep normalization regressions captured until complex draft JSON becomes consistently parseable.
- Keep Groq in the verifier fallback chain while it continues to complete draft, critic, verifier, and synthesis paths.
- Re-run NVIDIA direct smoke tests after any model or base URL change.

## Model Quality
- Expand live prompts across debugging, system design, payment capacity, coding, and general reasoning.
- Persist every complex live trace and review failures by phase: draft, critic, verifier, synthesis, trace persistence.
- Add a scorecard that records latency, final error, winner provider, synthesis provider, and verifier verdict coverage.

## Release Discipline
- Ship small commits by behavior: contracts, orchestration, provider fallback, tests, docs.
- Prefer clean branch merges through `gh pr create`, `gh pr merge --squash` or `--merge`, then update `main`.
- Do not chase contribution count with empty commits; count only commits that improve behavior, tests, docs, or operability.
