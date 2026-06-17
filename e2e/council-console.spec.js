const { test, expect } = require("@playwright/test");
const axeModule = require("@axe-core/playwright");

const AxeBuilder = axeModule.default || axeModule.AxeBuilder;
const TRACE_ID = "11111111-2222-3333-4444-555555555555";

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.EventSource = undefined;
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === "/api/v1/health") {
      return json(route, {
        status: "UP",
        routingEnabled: true,
        availableProviders: ["groq", "nvidia"],
        research: { enabled: true, available: false, provider: "tavily" }
      });
    }

    if (path === "/api/v1/reason" && request.method() === "POST") {
      return json(route, finalResponse());
    }

    if (!request.headers().authorization) {
      return json(route, { error: "UNAUTHORIZED", message: "Authentication is required." }, 401);
    }

    if (path === "/api/v1/providers/status") {
      return json(route, [
        {
          provider: "groq",
          model: "llama-3.3-70b",
          enabled: true,
          coolingDown: false,
          recentFailureRate: 0.02,
          totalSuccesses: 120,
          totalFailures: 3,
          roles: ["DRAFT", "SYNTHESIS"],
          availableConcurrencyPermits: 4,
          availableForRouting: true
        },
        {
          provider: "nvidia",
          model: "nemotron",
          enabled: true,
          coolingDown: true,
          cooldownUntil: "2026-06-18T10:00:00Z",
          consecutive429Count: 2,
          recentFailureRate: 0.34,
          totalSuccesses: 19,
          totalFailures: 10,
          roles: ["DRAFT"],
          availableConcurrencyPermits: 0,
          availableForRouting: false
        }
      ]);
    }

    if (path === "/api/v1/providers/scorecards") {
      return json(route, [
        { provider: "groq", successRate: 0.98, p95LatencyMs: 1200, avgLatencyMs: 840, avgConfidence: 0.87 },
        { provider: "nvidia", successRate: 0.66, p95LatencyMs: 20000, avgLatencyMs: 9500, avgConfidence: 0.76 }
      ]);
    }

    if (path === "/api/v1/traces") {
      return json(route, {
        content: [
          {
            traceId: TRACE_ID,
            status: "COMPLETED",
            userQuery: "URL shortener deletion prompt",
            finalAnswer: "Return 404/410.",
            finalConfidence: 0.86,
            usedProviders: ["groq"],
            failedProviders: ["nvidia", "openrouter-qwen"],
            totalLatencyMs: 1234
          }
        ],
        totalElements: 1,
        number: 0,
        size: 8
      });
    }

    if (path === `/api/v1/traces/${TRACE_ID}/debug`) {
      return json(route, traceDebug());
    }

    return json(route, { error: "NOT_FOUND", message: `No fixture for ${path}` }, 404);
  });
});

test("homepage renders locked operator surfaces and passes critical accessibility checks", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "Council operator console" })).toBeVisible();
  await expect(page.getByText("Admin access required").first()).toBeVisible();
  await expect(page.locator("#research-mode")).toHaveText("No key");

  const results = await new AxeBuilder({ page }).analyze();
  const serious = results.violations.filter((violation) => ["critical", "serious"].includes(violation.impact));
  expect(serious).toEqual([]);
});

test("prompt submission renders structured answer, score cards, code, and source warning", async ({ page }) => {
  await page.goto("/");

  await page.locator("#query-input").fill("URL shortener deletion consistency under Redis and replica lag");
  await page.getByRole("button", { name: "Run council" }).click();

  await expect(page.locator(".score-card").filter({ hasText: "Answer quality" })).toBeVisible();
  await expect(page.locator(".score-card").filter({ hasText: "Model agreement" })).toBeVisible();
  await expect(page.getByText("N/A")).toBeVisible();
  await expect(page.getByText("Quality dimensions")).toBeVisible();
  await expect(page.getByText("No source pack was available")).toBeVisible();
  await expect(page.locator("pre.code-block")).toContainText("cached == DELETED");
});

test("admin unlock shows provider failures, trace list, and trace detail scoring", async ({ page }) => {
  await page.goto("/");

  await page.locator("#admin-username").fill("admin");
  await page.locator("#admin-password").fill("secret");
  await page.getByRole("button", { name: "Unlock ops" }).click();

  await expect(page.locator("#admin-auth-status")).toHaveText("Ops unlocked");
  await expect(page.getByText("groq")).toBeVisible();
  await expect(page.getByText("URL shortener deletion prompt")).toBeVisible();

  await page.getByText("URL shortener deletion prompt").click();

  await expect(page.getByRole("heading", { name: /11111111/ })).toBeVisible();
  await expect(page.getByText("Provider outcomes")).toBeVisible();
  await expect(page.getByText("Timed out at 20s deadline")).toBeVisible();
  await expect(page.getByText("Winner confidence")).toBeVisible();
  await expect(page.getByText("Only one valid draft was available; this is selection certainty")).toBeVisible();
});

function finalResponse() {
  return {
    traceId: TRACE_ID,
    finalAnswer: `### Decision
Return \`404 Not Found\` or \`410 Gone\`; never redirect a deleted alias.

### Core Safety Reasoning
Deletion tombstones beat active redirect cache entries, and replica reads are unsafe during the stated lag window.

### Tradeoffs
Deleted aliases prioritize correctness over low-latency stale availability. Analytics remains eventually consistent.

### Concrete Algorithm
\`\`\`java
if (cached == DELETED) return notFound();
return singleflight(alias, () -> primaryDb.findByAlias(alias));
\`\`\`

### Common Mistakes
- Trusting Redis TTL alone.
- Reading a lagging replica after deletion.
- Treating delayed analytics as redirect truth.`,
    judgeReason: "Only Groq produced a valid draft; answer quality is rubric-based.",
    usedProviders: ["groq"],
    failedProviders: ["nvidia", "openrouter-qwen"],
    confidence: 0.86,
    answerQuality: 0.86,
    winnerConfidence: 0.95,
    modelAgreement: null,
    dimensions: {
      correct_endpoint_decision: 0.92,
      deletion_safety: 0.84,
      replica_lag_awareness: 0.78,
      pseudocode: 0.75,
      custom_future_dimension: 0.58
    },
    research: {
      required: true,
      reason: "The prompt was treated as research-sensitive, but no source pack was returned.",
      queries: ["URL shortener deletion consistency"],
      sources: [],
      errorMessage: "TAVILY_API_KEY is not configured."
    },
    invariants: {
      evaluated: true,
      checked: ["url.tombstone_precedes_active_cache"],
      violations: [],
      overallCap: 0.85
    }
  };
}

function traceDebug() {
  return {
    ...finalResponse(),
    status: "COMPLETED",
    userQuery: "URL shortener deletion prompt",
    totalLatencyMs: 1234,
    createdAt: "2026-06-18T09:00:00Z",
    completedAt: "2026-06-18T09:00:01Z",
    totalDrafts: 3,
    successfulDrafts: 1,
    failedDrafts: 2,
    draftResults: {
      drafts: [
        {
          provider: "groq",
          model: "llama-3.3-70b",
          status: "SUCCESS",
          summary: "Tombstone-first answer succeeded.",
          latencyMs: 900
        },
        {
          provider: "nvidia",
          model: "nemotron",
          status: "FAILURE",
          errorMessage: "Timed out at 20s deadline",
          latencyMs: 20000
        }
      ]
    },
    researchContext: finalResponse().research,
    invariantFindings: finalResponse().invariants
  };
}

function json(route, payload, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(payload)
  });
}
