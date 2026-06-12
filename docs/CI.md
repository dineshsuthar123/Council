# CI and Supply Chain Baseline

Council uses GitHub Actions as the required production hygiene gate for pull
requests and pushes to `main`.

## Required Checks

Require these status checks in branch protection for `main`:

- `verify`
- `dependency-review`

The `dependency-check` job is intentionally not recommended as a required check
for all pull requests because it is skipped for forked PRs and can depend on NVD
availability. Keep it enabled for trusted/internal PRs, pushes to `main`, and
manual `workflow_dispatch` runs.

Recommended branch protection settings:

- Require a pull request before merging.
- Require the required status checks above to pass.
- Require branches to be up to date before merging.
- Require conversation resolution before merging.
- Do not allow force pushes.
- Do not allow deletions of `main`.

## Default CI

`.github/workflows/ci.yml` runs on pull requests, pushes to `main`, and manual
dispatch.

The `verify` job performs:

- Java 21 setup with Maven dependency caching.
- Static syntax validation for `src/main/resources/static/app.js`.
- `mvn -B -ntp verify`.
- Docker image build validation with no provider credentials.
- Artifact upload for Surefire reports, JaCoCo reports, and CycloneDX SBOMs.

Default CI does not run live provider tests and does not require LLM API keys.
Forked pull requests receive no repository secrets.

## Coverage Gates

JaCoCo is bound to `verify` and generates XML/HTML reports under
`target/site/jacoco`.

Current bundle thresholds:

- Instruction coverage: `35%`
- Branch coverage: `20%`

These thresholds are intentionally conservative for the first enforceable
baseline because Council has many provider adapters, configuration classes, and
integration seams. The purpose is to prevent coverage regression immediately
while leaving room to ratchet upward as focused tests are added.

## Dependency Hygiene

The CI baseline includes:

- Maven Enforcer for Java 21, Maven 3.9+, release dependencies, and duplicate
  dependency declarations.
- GitHub dependency review for pull requests, failing on high-severity
  dependency changes and selected copyleft licenses.
- OWASP dependency-check for trusted/internal PRs, pushes to `main`, and manual
  runs. It fails on CVSS `9.0` or higher.
- CycloneDX SBOM generation during `mvn verify`, producing
  `target/classes/META-INF/sbom/bom.json` and
  `target/classes/META-INF/sbom/bom.xml`.
- Dependabot for Maven, GitHub Actions, and Docker updates.

For faster and more reliable OWASP scans, configure the optional repository
secret `NVD_API_KEY`. This secret is only consumed by trusted runs; forked PRs
do not receive it. The workflow passes only the environment variable name to
Maven, not the secret value.

## Live Provider Tests

Live provider tests are intentionally excluded from default CI because they
consume external quota and require provider credentials.

Run them only from a trusted environment:

```bash
mvn test "-Dtest=LiveComplexReasoningTest" "-Dlive.provider.tests=true"
mvn test "-Dtest=TestProviderDirectly" "-Dlive.provider.tests=true"
```

Never enable live provider tests for untrusted forked pull requests.
