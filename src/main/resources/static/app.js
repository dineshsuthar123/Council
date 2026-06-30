const API_BASE = "/api/v1";

const pipelineSteps = [
  ["classify", "Task classification", "Identifies the work type and verification posture."],
  ["route", "Task routing", "Selects provider roles and fallback paths."],
  ["draft", "Parallel drafts", "Collects independent candidate answers."],
  ["review", "Critic and verifier", "Checks contradictions, assumptions, constraints, and confidence."],
  ["verify", "Constraint filter", "Applies enforced or advisory verifier verdicts by workflow type."],
  ["judge", "Deterministic judge", "Ranks valid candidates with task-aware weights."],
  ["synthesis", "Synthesis", "Combines the strongest evidence into one answer."],
  ["trace", "Trace persistence", "Stores the full audit trail for inspection."]
];

const phaseIndex = {
  ACCEPTED: 0,
  START: 0,
  CLASSIFY: 0,
  ROUTE: 1,
  DRAFT: 2,
  REVIEW: 3,
  VERIFY: 4,
  JUDGE: 5,
  ESCALATE: 5,
  SYNTHESIS: 6,
  TRACE: 7,
  COMPLETE: 7,
  ERROR: 7
};

const streamedPhases = [
  "ACCEPTED",
  "START",
  "CLASSIFY",
  "ROUTE",
  "DRAFT",
  "REVIEW",
  "VERIFY",
  "JUDGE",
  "ESCALATE",
  "SYNTHESIS",
  "TRACE",
  "COMPLETE",
  "ERROR"
];

const state = {
  running: false,
  runStartedAt: null,
  currentTraceId: null,
  selectedMode: "balanced",
  phaseTimer: null,
  eventSource: null,
  pipelineStatuses: new Map(),
  adminAuth: loadAdminAuth()
};

const els = {
  form: document.querySelector("#reason-form"),
  input: document.querySelector("#query-input"),
  submit: document.querySelector("#submit-button"),
  answer: document.querySelector("#answer-panel"),
  steps: document.querySelector("#pipeline-steps"),
  latency: document.querySelector("#latency-badge"),
  providerTable: document.querySelector("#provider-table"),
  providerCount: document.querySelector("#provider-count"),
  traceList: document.querySelector("#trace-list"),
  reloadTraces: document.querySelector("#reload-traces"),
  refresh: document.querySelector("#refresh-button"),
  health: document.querySelector("#system-status"),
  routing: document.querySelector("#routing-mode"),
  available: document.querySelector("#available-count"),
  research: document.querySelector("#research-mode"),
  toast: document.querySelector("#toast-region"),
  adminForm: document.querySelector("#admin-auth-form"),
  adminUsername: document.querySelector("#admin-username"),
  adminPassword: document.querySelector("#admin-password"),
  adminLock: document.querySelector("#admin-lock"),
  adminStatus: document.querySelector("#admin-auth-status")
};

document.addEventListener("DOMContentLoaded", () => {
  renderPipeline("idle");
  bindEvents();
  refreshAll();
});

function bindEvents() {
  els.form.addEventListener("submit", runCouncil);
  els.reloadTraces.addEventListener("click", loadTraces);
  els.refresh.addEventListener("click", refreshAll);
  els.adminForm.addEventListener("submit", unlockAdmin);
  els.adminLock.addEventListener("click", lockAdmin);
  hydrateAdminForm();

  document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.addEventListener("click", () => {
      els.input.value = button.dataset.prompt;
      els.input.focus();
    });
  });

  document.querySelectorAll("[data-mode]").forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedMode = button.dataset.mode;
      document.querySelectorAll("[data-mode]").forEach((item) => item.classList.remove("active"));
      button.classList.add("active");
      toast(`Mode set to ${button.textContent.trim()}. Backend routing remains task-aware.`);
    });
  });
}

async function refreshAll() {
  await Promise.all([loadHealth(), loadProviders(), loadTraces()]);
}

async function runCouncil(event) {
  event.preventDefault();
  const query = els.input.value.trim();
  if (!query || state.running) return;

  setRunning(true);
  state.runStartedAt = performance.now();
  state.currentTraceId = null;
  state.pipelineStatuses = new Map();
  renderLoadingAnswer(query);
  renderPipeline("running", 0);

  try {
    if (typeof EventSource === "function") {
      await runCouncilStream(query);
    } else {
      await runCouncilSync(query);
    }
  } catch (error) {
    renderPipeline("failed");
    renderError(error.message || "Council request failed.");
    toast(error.message || "Council request failed.", true);
    setRunning(false);
  }
}

async function runCouncilStream(query) {
  const run = await fetchJson(`${API_BASE}/reason/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query })
  });

  state.currentTraceId = run.traceId;
  attachEventStream(run.eventsUrl);
}

async function runCouncilSync(query) {
  animatePipeline();
  const response = await fetchJson(`${API_BASE}/reason`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query })
  });

  state.currentTraceId = response.traceId;
  renderPipeline(response.error ? "failed" : "done");
  renderAnswer(response);
  await Promise.all([loadProviders(), loadTraces(), loadHealth()]);
  setRunning(false);
}

function setRunning(running) {
  state.running = running;
  els.submit.disabled = running;
  els.submit.classList.toggle("is-running", running);
  els.submit.querySelector(".button-label").textContent = running ? "Council running" : "Run council";
  if (!running) {
    clearInterval(state.phaseTimer);
    state.phaseTimer = null;
    closeEventStream();
  }
}

function attachEventStream(eventsUrl) {
  closeEventStream();
  const source = new EventSource(eventsUrl);
  state.eventSource = source;

  streamedPhases.forEach((phase) => {
    source.addEventListener(phase, (event) => handlePipelineEvent(JSON.parse(event.data)));
  });

  source.onerror = () => {
    if (state.running) {
      closeEventStream();
      renderPipeline("failed");
      renderError("Live event stream disconnected before the run completed.");
      toast("Live event stream disconnected.", true);
      setRunning(false);
    }
  };
}

function handlePipelineEvent(event) {
  const phase = event.phase || "START";
  const index = phaseIndex[phase] ?? 0;
  state.pipelineStatuses.set(index, event.status || "running");
  renderPipelineFromEvents(index);

  if (event.elapsedMs != null) {
    els.latency.textContent = `${event.elapsedMs} ms`;
  }

  if (event.message) {
    updateLoadingMessage(event.message);
  }

  if (phase === "COMPLETE") {
    const response = event.details && event.details.response;
    renderPipeline("done");
    if (response) {
      renderAnswer(response);
    } else {
      renderError("Run completed but did not include a final response.");
    }
    setRunning(false);
    Promise.all([loadProviders(), loadTraces(), loadHealth()]);
  }

  if (phase === "ERROR") {
    const response = event.details && event.details.response;
    renderPipeline("failed", index);
    renderError(event.message || "Council request failed.", response || {});
    setRunning(false);
    Promise.all([loadProviders(), loadTraces(), loadHealth()]);
  }
}

function renderPipelineFromEvents(activeIndex) {
  els.steps.innerHTML = pipelineSteps.map(([id, title, description], index) => {
    const rawStatus = state.pipelineStatuses.get(index);
    let className = "pipeline-step";
    if (index < activeIndex || rawStatus === "done" || rawStatus === "queued") className += " done";
    if (index === activeIndex && rawStatus !== "done" && rawStatus !== "queued") className += " running";
    if (rawStatus === "failed") className += " failed";
    if (rawStatus === "degraded") className += " running";

    return `
      <li class="${className}" data-step="${id}">
        <span class="step-index">${index + 1}</span>
        <div>
          <strong>${title}</strong>
          <p>${description}</p>
        </div>
      </li>
    `;
  }).join("");
}

function updateLoadingMessage(message) {
  const heading = els.answer.querySelector("[data-loading-title]");
  if (heading) {
    heading.textContent = message;
  }
}

function closeEventStream() {
  if (state.eventSource) {
    state.eventSource.close();
    state.eventSource = null;
  }
}

function animatePipeline() {
  let index = 0;
  renderPipeline("running", index);
  clearInterval(state.phaseTimer);
  state.phaseTimer = setInterval(() => {
    index = Math.min(index + 1, pipelineSteps.length - 1);
    renderPipeline("running", index);
  }, 2200);
}

function renderPipeline(status, runningIndex = -1) {
  els.steps.innerHTML = pipelineSteps.map(([id, title, description], index) => {
    let className = "pipeline-step";
    if (status === "done") className += " done";
    if (status === "failed" && index >= Math.max(runningIndex, 0)) className += " failed";
    if (status === "running") {
      if (index < runningIndex) className += " done";
      if (index === runningIndex) className += " running";
    }
    return `
      <li class="${className}" data-step="${id}">
        <span class="step-index">${index + 1}</span>
        <div>
          <strong>${title}</strong>
          <p>${description}</p>
        </div>
      </li>
    `;
  }).join("");

  if (status === "idle") els.latency.textContent = "Idle";
  if (status === "running") els.latency.textContent = "Running";
  if (status === "done" && state.runStartedAt) {
    els.latency.textContent = `${Math.round(performance.now() - state.runStartedAt)} ms`;
  }
  if (status === "failed") els.latency.textContent = "Needs attention";
}

function renderLoadingAnswer(query) {
  els.answer.innerHTML = `
    <div class="answer-header">
      <div>
        <p class="section-kicker">Council response</p>
        <h2 data-loading-title>Reasoning in progress</h2>
        <div class="answer-meta">
          <span class="meta-pill">Mode: ${escapeHtml(state.selectedMode)}</span>
          <span class="meta-pill">Prompt: ${query.length} chars</span>
        </div>
      </div>
    </div>
    <div class="loading-lines" aria-label="Loading response">
      <span class="skeleton medium"></span>
      <span class="skeleton"></span>
      <span class="skeleton"></span>
      <span class="skeleton short"></span>
    </div>
  `;
}

function renderAnswer(response) {
  if (response.error) {
    renderError(response.message || response.error, response);
    return;
  }

  const answerQuality = scoreValue(response.answerQuality ?? response.confidence);
  const winnerConfidence = scoreValue(response.winnerConfidence ?? response.confidence);
  const modelAgreement = response.modelAgreement == null ? null : scoreValue(response.modelAgreement);
  const confidencePct = Math.round(answerQuality * 100);
  const validDraftCount = Array.isArray(response.usedProviders) ? response.usedProviders.length : 0;
  const providers = listText(response.usedProviders);
  const failed = listText(response.failedProviders);

  els.answer.innerHTML = `
    <div class="answer-header">
      <div>
        <p class="section-kicker">Council response</p>
        <h2>${response.traceId ? `Trace ${escapeHtml(shortId(response.traceId))}` : "Final answer"}</h2>
        <div class="answer-meta">
          <span class="meta-pill">Used: ${escapeHtml(providers)}</span>
          <span class="meta-pill">Failed: ${escapeHtml(failed)}</span>
          ${response.judgeReason ? `<span class="meta-pill">Judge: ${escapeHtml(trimText(response.judgeReason, 80))}</span>` : ""}
        </div>
      </div>
      <div class="confidence-ring" style="--confidence-angle: ${answerQuality * 360}deg" aria-label="Answer quality ${confidencePct}%">
        <span>${confidencePct}%</span>
      </div>
    </div>
    <div class="score-grid" aria-label="Council scoring breakdown">
      ${scoreCard("Answer quality", answerQuality, "Absolute quality of the final answer.")}
      ${scoreCard("Winner confidence", winnerConfidence, winnerConfidenceHelper(validDraftCount))}
      ${agreementScoreCard(modelAgreement, validDraftCount)}
    </div>
    ${runHealthPanel(response.runDiagnostics)}
    ${providerOutcomePanel(response.providerOutcomes || response.providerFailures)}
    ${dimensionGrid(response.dimensions)}
    ${invariantPanel(response.invariants)}
    ${researchPanel(response.research)}
    <div class="answer-body">${formatAnswer(response.finalAnswer || "No final answer returned.")}</div>
  `;
}

function renderError(message, response = {}) {
  els.answer.innerHTML = `
    <div class="answer-error">
      <p class="section-kicker">Council response</p>
      <h2>${escapeHtml(response.error || "Request failed")}</h2>
      <p>${escapeHtml(message)}</p>
    </div>
  `;
}

async function loadHealth() {
  try {
    const health = await fetchJson(`${API_BASE}/health`);
    const status = String(health.status || "UNKNOWN").toUpperCase();
    const statusClass = status === "UP" ? "up" : status === "DEGRADED" ? "degraded" : "down";
    els.health.innerHTML = `<span class="status-dot ${statusClass}"></span><span>${escapeHtml(status)}</span>`;
    els.routing.textContent = health.routingEnabled ? "Enabled" : "Classic";
    els.available.textContent = Array.isArray(health.availableProviders) ? health.availableProviders.length : "--";
    els.research.textContent = health.research?.available
      ? "Ready"
      : health.research?.enabled
        ? "No key"
        : "Off";
  } catch (error) {
    els.health.innerHTML = `<span class="status-dot down"></span><span>Offline</span>`;
    els.routing.textContent = "--";
    els.available.textContent = "--";
    els.research.textContent = "--";
  }
}

async function loadProviders() {
  if (!hasAdminAuth()) {
    els.providerCount.textContent = "Locked";
    els.providerTable.innerHTML = lockedPanel("Provider status requires admin credentials.");
    return;
  }
  renderProviderSkeleton();
  try {
    const [providers, scorecards] = await Promise.all([
      fetchJson(`${API_BASE}/providers/status`, { admin: true }),
      fetchJson(`${API_BASE}/providers/scorecards?limit=300`, { admin: true })
    ]);
    const scorecardByProvider = new Map((scorecards || []).map((scorecard) => [scorecard.provider, scorecard]));
    els.providerCount.textContent = `${providers.length} providers - ${scorecards.length} scored`;
    els.providerTable.innerHTML = providers
      .map((provider) => renderProviderRow(provider, scorecardByProvider.get(provider.provider)))
      .join("") || emptyInline("No providers configured.");
    bindProviderActions();
  } catch (error) {
    els.providerCount.textContent = "Unavailable";
    els.providerTable.innerHTML = emptyInline("Provider status could not be loaded.");
  }
}

function renderProviderSkeleton() {
  els.providerTable.innerHTML = Array.from({ length: 5 }, () => `
    <div class="provider-row">
      <span class="skeleton medium"></span>
      <span class="skeleton"></span>
      <span class="skeleton short"></span>
      <span class="skeleton short"></span>
    </div>
  `).join("");
}

function renderProviderRow(provider, scorecard) {
  const warnings = Array.isArray(provider.configWarnings) ? provider.configWarnings : [];
  const preflightStatus = provider.preflightStatus || "NOT_RUN";
  const preflightFailed = preflightStatus === "FAILED";
  const statusClass = provider.enabled && provider.availableForRouting !== false && !provider.coolingDown && !preflightFailed
    ? (warnings.length ? "degraded" : "up")
    : provider.coolingDown || warnings.length ? "degraded" : "down";
  const roles = Array.isArray(provider.roles) && provider.roles.length
    ? provider.roles.map((role) => `<span class="role-tag">${escapeHtml(role)}</span>`).join("")
    : `<span class="role-tag">GENERAL</span>`;
  const permits = provider.availableConcurrencyPermits ?? "--";
  const total = (provider.totalSuccesses || 0) + (provider.totalFailures || 0);
  const quality = scorecard
    ? `${Math.round(scorecard.successRate * 100)}% success | p95 ${scorecard.p95LatencyMs} ms`
    : "No trace score yet";
  const confidence = scorecard
    ? `avg ${scorecard.avgLatencyMs} ms | conf ${Math.round(scorecard.avgConfidence * 100)}%`
    : `${Math.round((provider.recentFailureRate || 0) * 100)}% recent failure | ${total} calls`;
  const preflight = provider.preflightStatus
    ? `preflight ${humanize(provider.preflightStatus)}${provider.preflightFailureCategory ? ` (${humanize(provider.preflightFailureCategory)})` : ""}`
    : "preflight not available";
  const timeout = provider.timeoutMsConfigured ? `timeout ${provider.timeoutMsConfigured} ms` : "";
  const warningLine = warnings.length ? `<div class="provider-warning">${escapeHtml(warnings.join(" "))}</div>` : "";

  return `
    <div class="provider-row">
      <div>
        <div class="provider-name"><span class="status-dot ${statusClass}"></span>${escapeHtml(provider.provider)}</div>
        <div class="provider-model">${escapeHtml(provider.model || "model unavailable")}</div>
        <div class="provider-model">${escapeHtml([preflight, timeout, provider.timeoutSource].filter(Boolean).join(" - "))}</div>
        ${warningLine}
      </div>
      <div class="provider-roles">${roles}</div>
      <div class="provider-metric">
        <strong>${permits}</strong> permits<br>
        ${escapeHtml(quality)}<br>
        ${escapeHtml(confidence)}
      </div>
      <button class="reset-button" type="button" data-reset="${escapeHtml(provider.provider)}" ${provider.coolingDown ? "" : "disabled"}>Reset</button>
    </div>
  `;
}

function bindProviderActions() {
  document.querySelectorAll("[data-reset]").forEach((button) => {
    button.addEventListener("click", async () => {
      const provider = button.dataset.reset;
      button.disabled = true;
      try {
        await fetchJson(`${API_BASE}/providers/${encodeURIComponent(provider)}/reset-cooldown`, {
          method: "POST",
          admin: true
        });
        toast(`Cooldown reset for ${provider}.`);
        await Promise.all([loadProviders(), loadHealth()]);
      } catch (error) {
        toast(error.message || `Could not reset ${provider}.`, true);
        button.disabled = false;
      }
    });
  });
}

async function loadTraces() {
  if (!hasAdminAuth()) {
    els.traceList.innerHTML = lockedPanel("Trace history requires admin credentials.");
    return;
  }
  renderTraceSkeleton();
  try {
    const page = await fetchJson(`${API_BASE}/traces?page=0&size=8`, { admin: true });
    const traces = Array.isArray(page.content) ? page.content : [];
    els.traceList.innerHTML = traces.map(renderTraceItem).join("") || emptyInline("No traces yet.");
    bindTraceItems();
  } catch (error) {
    els.traceList.innerHTML = emptyInline("Trace history could not be loaded.");
  }
}

function renderTraceSkeleton() {
  els.traceList.innerHTML = Array.from({ length: 4 }, () => `
    <div class="trace-item">
      <span class="skeleton medium"></span>
      <span class="skeleton"></span>
      <span class="skeleton short"></span>
    </div>
  `).join("");
}

function renderTraceItem(trace) {
  const active = trace.traceId === state.currentTraceId ? " active" : "";
  return `
    <button class="trace-item${active}" type="button" data-trace="${escapeHtml(trace.traceId)}">
      <div class="trace-title">
        <span>${escapeHtml(shortId(trace.traceId))}</span>
        <span>${escapeHtml(trace.status || "UNKNOWN")}</span>
      </div>
      <div class="trace-query">${escapeHtml(trace.userQuery || trace.finalAnswer || "No query captured.")}</div>
      <div class="trace-meta">
        <span>${trace.totalLatencyMs ? `${trace.totalLatencyMs} ms` : "latency --"}</span>
        <span>${trace.finalConfidence != null ? `${Math.round(trace.finalConfidence * 100)}% confidence` : "confidence --"}</span>
      </div>
    </button>
  `;
}

function bindTraceItems() {
  document.querySelectorAll("[data-trace]").forEach((button) => {
    button.addEventListener("click", () => loadTraceDebug(button.dataset.trace));
  });
}

async function loadTraceDebug(traceId) {
  try {
    const debug = await fetchJson(`${API_BASE}/traces/${encodeURIComponent(traceId)}/debug`, { admin: true });
    state.currentTraceId = traceId;
    renderTraceDebug(debug);
    document.querySelectorAll("[data-trace]").forEach((item) => item.classList.toggle("active", item.dataset.trace === traceId));
  } catch (error) {
    toast(error.message || "Trace debug view could not be loaded.", true);
  }
}

function renderTraceDebug(debug) {
  const providers = listText(debug.usedProviders);
  const failed = listText(debug.failedProviders);
  const answerQuality = scoreValue(debug.answerQuality ?? debug.finalConfidence);
  const winnerConfidence = scoreValue(debug.winnerConfidence ?? debug.finalConfidence);
  const modelAgreement = debug.modelAgreement == null ? null : scoreValue(debug.modelAgreement);
  const validDraftCount = Number(debug.successfulDrafts ?? (Array.isArray(debug.usedProviders) ? debug.usedProviders.length : 0));

  els.answer.innerHTML = `
    <div class="answer-header">
      <div>
        <p class="section-kicker">Trace debug</p>
        <h2>${escapeHtml(shortId(debug.traceId))}</h2>
        <div class="answer-meta">
          <span class="meta-pill">${escapeHtml(debug.status || "UNKNOWN")}</span>
          <span class="meta-pill">${debug.totalLatencyMs || "--"} ms</span>
          <span class="meta-pill">${debug.successfulDrafts}/${debug.totalDrafts} drafts</span>
          <span class="meta-pill">Used: ${escapeHtml(providers)}</span>
          <span class="meta-pill">Failed: ${escapeHtml(failed)}</span>
        </div>
      </div>
      <div class="confidence-ring" style="--confidence-angle: ${answerQuality * 360}deg">
        <span>${Math.round(answerQuality * 100)}%</span>
      </div>
    </div>
    <div class="score-grid" aria-label="Trace score breakdown">
      ${scoreCard("Answer quality", answerQuality, "Absolute quality of the final answer.")}
      ${scoreCard("Winner confidence", winnerConfidence, winnerConfidenceHelper(validDraftCount))}
      ${agreementScoreCard(modelAgreement, validDraftCount)}
    </div>
    ${runHealthPanel(debug.runDiagnostics)}
    ${dimensionGrid(debug.dimensions)}
    ${scoreBreakdownPanel(debug.scoreBreakdown)}
    ${invariantPanel(debug.invariantFindings)}
    ${researchPanel(debug.researchContext)}
    ${providerOutcomePanel(debug.draftResults)}
    <div class="answer-body">
      <h3>Prompt</h3>
      <p>${escapeHtml(debug.userQuery || "No prompt captured.")}</p>
      <h3>Judge reason</h3>
      <p>${escapeHtml(debug.judgeReason || "No judge reason captured.")}</p>
      <h3>Final answer</h3>
      ${formatAnswer(debug.finalAnswer || "No final answer captured.")}
    </div>
  `;
}

async function fetchJson(url, options = {}) {
  const { admin = false, headers = {}, ...requestOptions } = options;
  const requestHeaders = { ...headers };
  if (admin) {
    if (!hasAdminAuth()) {
      throw new Error("Admin credentials are required for this operation.");
    }
    requestHeaders.Authorization = `Basic ${btoa(`${state.adminAuth.username}:${state.adminAuth.password}`)}`;
  }

  const response = await fetch(url, {
    ...requestOptions,
    headers: requestHeaders
  });
  const text = await response.text();
  let payload = {};
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (error) {
      throw new Error(`Non-JSON response from ${url}`);
    }
  }
  if (!response.ok) {
    throw new Error(payload.message || payload.error || `Request failed with ${response.status}`);
  }
  return payload;
}

function unlockAdmin(event) {
  event.preventDefault();
  const username = els.adminUsername.value.trim();
  const password = els.adminPassword.value;
  if (!username || !password) {
    toast("Enter admin username and password to unlock operator surfaces.", true);
    return;
  }
  state.adminAuth = { username, password };
  sessionStorage.setItem("council.adminAuth", JSON.stringify(state.adminAuth));
  hydrateAdminForm();
  toast("Operator surfaces unlocked for this browser session.");
  Promise.all([loadProviders(), loadTraces()]).catch(() => {});
}

function lockAdmin() {
  state.adminAuth = null;
  sessionStorage.removeItem("council.adminAuth");
  hydrateAdminForm();
  loadProviders();
  loadTraces();
  toast("Operator surfaces locked.");
}

function hydrateAdminForm() {
  const unlocked = hasAdminAuth();
  els.adminUsername.value = state.adminAuth?.username || "";
  els.adminPassword.value = state.adminAuth?.password || "";
  els.adminStatus.textContent = unlocked ? "Ops unlocked" : "Ops locked";
  els.adminStatus.classList.toggle("is-unlocked", unlocked);
  els.adminLock.disabled = !unlocked;
}

function hasAdminAuth() {
  return Boolean(state.adminAuth?.username && state.adminAuth?.password);
}

function loadAdminAuth() {
  try {
    const raw = sessionStorage.getItem("council.adminAuth");
    return raw ? JSON.parse(raw) : null;
  } catch (error) {
    return null;
  }
}

function lockedPanel(message) {
  return `
    <div class="locked-panel">
      <strong>Admin access required</strong>
      <p>${escapeHtml(message)}</p>
    </div>
  `;
}

function formatAnswer(value) {
  if (shouldStructureDenseAnswer(value)) {
    return formatDenseAnswer(value);
  }

  const safe = escapeHtml(value);
  const lines = safe.split(/\r?\n/);
  const html = [];
  let inList = false;
  let inCode = false;
  const codeLines = [];

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith("```")) {
      if (inCode) {
        html.push(`<pre class="code-block"><code>${codeLines.join("\n")}</code></pre>`);
        codeLines.length = 0;
        inCode = false;
      } else {
        if (inList) {
          html.push("</ul>");
          inList = false;
        }
        inCode = true;
      }
      continue;
    }

    if (inCode) {
      codeLines.push(line);
      continue;
    }

    if (!trimmed) {
      if (inList) {
        html.push("</ul>");
        inList = false;
      }
      continue;
    }

    if (trimmed.startsWith("### ")) {
      if (inList) {
        html.push("</ul>");
        inList = false;
      }
      html.push(`<h3>${inlineCode(trimmed.slice(4))}</h3>`);
      continue;
    }

    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
      if (!inList) {
        html.push("<ul>");
        inList = true;
      }
      html.push(`<li>${inlineCode(trimmed.slice(2))}</li>`);
      continue;
    }

    if (inList) {
      html.push("</ul>");
      inList = false;
    }
    html.push(`<p>${inlineCode(trimmed)}</p>`);
  }

  if (inList) html.push("</ul>");
  if (inCode) html.push(`<pre class="code-block"><code>${codeLines.join("\n")}</code></pre>`);
  return html.join("");
}

function shouldStructureDenseAnswer(value = "") {
  const text = String(value).trim();
  return text.length > 550 && !/\n\s*\n/.test(text) && sentenceSplit(text).length >= 5;
}

function formatDenseAnswer(value = "") {
  const sentences = sentenceSplit(value);
  const sections = [];
  let current = sectionForSentence(sentences[0] || "");

  for (const sentence of sentences) {
    const next = sectionForSentence(sentence);
    if (!current || next.title !== current.title) {
      current = next;
      sections.push(current);
    }
    current.items.push(sentence);
  }

  return sections.map((section) => `
    <section class="answer-section">
      <h3>${escapeHtml(section.title)}</h3>
      ${section.title === "Redirect algorithm"
        ? `<pre class="code-block"><code>${escapeHtml(pseudocodeLines(section.items.join(" ")))}</code></pre>`
        : section.items.length > 1
          ? `<ul>${section.items.map((item) => `<li>${inlineCode(escapeHtml(item))}</li>`).join("")}</ul>`
          : `<p>${inlineCode(escapeHtml(section.items[0] || ""))}</p>`}
    </section>
  `).join("");
}

function sectionForSentence(sentence = "") {
  const lower = sentence.toLowerCase();
  const title = lower.includes("pseudocode")
      || lower.includes("algorithm")
      || lower.includes("if redis")
      || lower.includes("else if")
      || lower.includes("primarydb.findbyalias")
      || lower.includes("resolve(")
    ? "Redirect algorithm"
    : lower.includes("metric") || lower.includes("monitor") || lower.includes("alert") || lower.includes("logs")
      ? "Observability"
      : lower.includes("tradeoff") || lower.includes("availability") && lower.includes("consistency")
        ? "Tradeoffs"
        : lower.includes("weaker system") || lower.includes("mistake")
          ? "Common mistakes"
          : lower.includes("stampede") || lower.includes("singleflight") || lower.includes("coalescing") || lower.includes("lock")
            ? "Stampede control"
            : lower.includes("tombstone") || lower.includes("negative-cache") || lower.includes("deleted_at") || lower.includes("stale redirects")
              ? "Deletion safety"
              : lower.includes("replica") || lower.includes("primary read") || lower.includes("version check")
                ? "Replica safety"
                : lower.includes("analytics") || lower.includes("dashboard") || lower.includes("kafka")
                  ? "Analytics separation"
                  : "Decision";
  return { title, items: [] };
}

function sentenceSplit(value = "") {
  return String(value)
    .replace(/\s+/g, " ")
    .split(/(?<=[.!?])\s+(?=[A-Z0-9])/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function pseudocodeLines(value = "") {
  return value
    .replace(/^.*?(pseudocode|algorithm)[^:]*:\s*/i, "")
    .replace(/\bELSE IF\b/g, "\nELSE IF")
    .replace(/\bELSE\b/g, "\nELSE")
    .replace(/\bIF\b/g, "IF")
    .replace(/;\s*/g, ";\n")
    .trim();
}

function scoreCard(label, value, helper) {
  const pct = Math.round(scoreValue(value) * 100);
  return `
    <div class="score-card">
      <span>${escapeHtml(label)}</span>
      <strong>${pct}%</strong>
      <small>${escapeHtml(helper)}</small>
    </div>
  `;
}

function scoreCardText(label, value, helper) {
  return `
    <div class="score-card is-muted">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
      <small>${escapeHtml(helper)}</small>
    </div>
  `;
}

function agreementScoreCard(modelAgreement, validDraftCount) {
  if (modelAgreement == null) {
    const helper = validDraftCount <= 1
      ? "Only one valid draft was available, so cross-model agreement was not measured."
      : "Agreement was not recorded for this trace.";
    return scoreCardText("Model agreement", "N/A", helper);
  }
  return scoreCard("Model agreement", modelAgreement, "How closely valid provider scores clustered.");
}

function winnerConfidenceHelper(validDraftCount) {
  if (validDraftCount <= 1) {
    return "Only one valid draft was available; this is selection certainty, not a head-to-head win.";
  }
  return "How strongly the judge preferred the selected provider.";
}

function dimensionGrid(dimensions) {
  if (!dimensions || typeof dimensions !== "object" || !Object.keys(dimensions).length) {
    return "";
  }

  const order = [
    ["transfer_decision", "Transfer decision"],
    ["idempotency_safety", "Idempotency safety"],
    ["atomicity", "Atomicity"],
    ["crash_recovery", "Crash recovery"],
    ["locking_concurrency", "Locking/concurrency"],
    ["redis_role", "Redis role"],
    ["kafka_outbox_safety", "Kafka/outbox safety"],
    ["fraud_path", "Fraud path"],
    ["correct_endpoint_decision", "Endpoint decision"],
    ["deletion_safety", "Deletion safety"],
    ["replica_lag_awareness", "Replica lag"],
    ["stampede_control", "Stampede control"],
    ["observability", "Observability"],
    ["tradeoffs", "Tradeoffs"],
    ["pseudocode", "Pseudocode"],
    ["common_mistakes", "Common mistakes"],
    ["source_quality", "Source quality"],
    ["citation_accuracy", "Citation accuracy"],
    ["recency", "Recency"],
    ["evidence_coverage", "Evidence coverage"],
    ["unsupported_claim_penalty", "Supported claims"],
    ["conflict_handling", "Conflict handling"],
    ["answer_completeness", "Completeness"],
    ["claim_evidence_consistency", "Claim evidence"],
    ["source_boundary_integrity", "Source boundary"],
    ["final_contract_compliance", "Final contract"],
    ["research_pipeline_concreteness", "Research pseudocode"],
    ["enumerated_section_coverage", "Section coverage"],
    ["final_recommendation_sentence_count", "Final rec sentences"],
    ["final_recommendation_required_min", "Final rec min"],
    ["final_recommendation_required_max", "Final rec max"],
    ["final_recommendation_contract_satisfied", "Final rec contract"],
    ["invariant_payment_transfer", "Payment invariants"],
    ["invariant_url_shortener", "URL invariants"],
    ["invariant_research_evidence", "Research invariants"],
    ["invariant_overall_cap", "Invariant cap"]
  ];

  const knownKeys = new Set(order.map(([key]) => key));
  const extraRows = Object.keys(dimensions)
    .filter((key) => !knownKeys.has(key))
    .sort()
    .map((key) => [key, labelizeDimension(key)]);

  const rows = [...order, ...extraRows]
    .filter(([key]) => dimensions[key] != null)
    .map(([key, label]) => {
      if (isDimensionMetadata(key)) {
        const raw = Number(dimensions[key]);
        const display = Number.isFinite(raw) ? Math.round(raw).toString() : String(dimensions[key]);
        return `
        <div class="dimension-row meta">
          <div class="dimension-label">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(display)}</strong>
          </div>
        </div>
      `;
      }
      const value = scoreValue(dimensions[key]);
      const pct = Math.round(value * 100);
      const tone = pct < 60 ? " low" : pct < 80 ? " medium" : "";
      return `
        <div class="dimension-row${tone}">
          <div class="dimension-label">
            <span>${escapeHtml(label)}</span>
            <strong>${pct}%</strong>
          </div>
          <div class="dimension-track" aria-hidden="true">
            <span style="width: ${pct}%"></span>
          </div>
        </div>
      `;
    })
    .join("");

  if (!rows) {
    return "";
  }

  return `
    <div class="dimension-panel" aria-label="Answer quality dimensions">
      <div class="dimension-heading">
        <span>Quality dimensions</span>
        <small>Weighted rubric behind answer quality</small>
      </div>
      <div class="dimension-grid">${rows}</div>
    </div>
  `;
}

function invariantPanel(result) {
  if (!result || !result.evaluated) {
    return "";
  }

  const violations = Array.isArray(result.violations) ? result.violations : [];
  const checked = Array.isArray(result.checked) ? result.checked : [];
  const cap = result.overallCap == null ? 1 : scoreValue(result.overallCap);
  const statusClass = violations.length ? "degraded" : "up";
  const statusText = violations.length
    ? `${violations.length} violation${violations.length === 1 ? "" : "s"}`
    : `${checked.length} invariants clean`;
  const rows = violations.map((violation) => `
    <article class="invariant-row">
      <div>
        <strong>${escapeHtml(violation.invariantId || violation.title || "Invariant violation")}</strong>
        <p>${escapeHtml(violation.evidence || violation.title || "Invariant failed.")}</p>
        ${violation.remediation ? `<small>${escapeHtml(violation.remediation)}</small>` : ""}
      </div>
      <span>${Math.round(scoreValue(violation.scoreCap) * 100)}%</span>
    </article>
  `).join("");

  return `
    <div class="invariant-panel" aria-label="Invariant critic findings">
      <div class="dimension-heading">
        <span>Invariant critic</span>
        <small><span class="status-dot ${statusClass}"></span>${escapeHtml(statusText)} - cap ${Math.round(cap * 100)}%</small>
      </div>
      ${rows || `<div class="empty-inline">No invariant violations detected.</div>`}
    </div>
  `;
}

function scoreBreakdownPanel(breakdown) {
  if (!breakdown || typeof breakdown !== "object") {
    return "";
  }

  const fields = [
    ["draftJudgeScore", "Draft judge score"],
    ["synthesisConfidence", "Synthesis confidence"],
    ["baseRubricScore", "Base rubric score"],
    ["researchCalibratedScore", "Research-calibrated score"],
    ["invariantCap", "Invariant cap"],
    ["finalCompletenessCap", "Final completeness cap"],
    ["productionConsistencyCap", "Production consistency cap"],
    ["finalAnswerQuality", "Final answer quality"]
  ];
  const unavailable = breakdown.unavailableReasons && typeof breakdown.unavailableReasons === "object"
    ? breakdown.unavailableReasons
    : {};
  const rows = fields.map(([key, label]) => {
    const value = breakdown[key];
    const detail = unavailable[key];
    const rendered = value == null ? "N/A" : `${Math.round(scoreValue(value) * 100)}%`;
    return `
      <div class="score-explanation-row">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(rendered)}</strong>
        ${detail ? `<small>${escapeHtml(detail)}</small>` : ""}
      </div>
    `;
  }).join("");
  const reasons = Array.isArray(breakdown.reasons) ? breakdown.reasons : [];

  return `
    <div class="score-explanation-panel" aria-label="Final answer quality formula">
      <div class="dimension-heading">
        <span>Score explanation</span>
        <small>Actual score inputs and caps</small>
      </div>
      <div class="score-explanation-grid">${rows}</div>
      <p class="score-formula">${escapeHtml(breakdown.formula || "Final score formula unavailable for this trace.")}</p>
      ${reasons.length ? `<ul class="score-reasons">${reasons.map((reason) => `<li>${escapeHtml(reason)}</li>`).join("")}</ul>` : ""}
    </div>
  `;
}

function labelizeDimension(key) {
  return String(key)
    .replace(/_/g, " ")
    .replace(/\b\w/g, (match) => match.toUpperCase());
}

function researchPanel(pack) {
  if (!pack || !pack.required) {
    return "";
  }

  const sources = Array.isArray(pack.sources) ? pack.sources : [];
  const queries = Array.isArray(pack.queries) ? pack.queries : [];
  const warnings = Array.isArray(pack.warnings) ? pack.warnings : [];
  const excludedSources = Array.isArray(pack.excludedSources) ? pack.excludedSources : [];
  const origin = pack.originSummary || (pack.hasPromptProvidedSources ? "Prompt-provided evidence" : "External research");
  const statusClass = sources.length ? "up" : "degraded";
  const statusText = sources.length ? `${sources.length} sources attached - ${origin}` : "Research unavailable";
  const sourceRows = sources.map((source) => {
    const href = safeExternalHref(source.url);
    const risk = source.injectionRisk || "LOW";
    const type = source.sourceType || "UNKNOWN";
    const sourceOrigin = source.origin || "EXTERNAL_RESEARCH";
    const boundary = source.metadata && source.metadata.boundaryEndReason;
    const boundaryPreview = source.metadata && source.metadata.boundaryLinePreview;
    return `
      <article class="source-row">
        <div>
          <strong>${escapeHtml(source.id || "S?")} - ${escapeHtml(source.title || source.url || "Untitled source")}</strong>
          <p>${escapeHtml(source.snippet || "No snippet captured.")}</p>
          <a href="${href}" target="_blank" rel="noreferrer">${escapeHtml(source.domain || source.url || "source")}</a>
          <span>${escapeHtml(sourceOrigin)} · ${escapeHtml(type)} · injection ${escapeHtml(risk)}</span>
          ${boundary ? `<span>boundary ${escapeHtml(boundary)}${boundaryPreview ? ` - ${escapeHtml(boundaryPreview)}` : ""}</span>` : ""}
        </div>
        <span>${escapeHtml(source.updatedAt || source.publishedAt || source.providedAt || "date --")}</span>
      </article>
    `;
  }).join("");
  const warningRows = [
    ...(warnings || []),
    ...(pack.researchUnavailableReason && sources.length
      ? [`${pack.researchUnavailableReason}. Prompt-provided sources were parsed and used.`]
      : [])
  ];
  const excludedRows = excludedSources.map((source) => `
    <li>
      <strong>${escapeHtml(source.id || "S?")} - ${escapeHtml(source.title || source.url || "Untitled source")}</strong>
      <span>${Math.round(scoreValue(source.relevanceScore) * 100)}% relevance - ${escapeHtml(source.excludedReason || source.relevanceReason || "Below relevance threshold")}</span>
    </li>
  `).join("");

  return `
    <div class="research-panel" aria-label="Research evidence pack">
      <div class="dimension-heading">
        <span>Research evidence</span>
        <small><span class="status-dot ${statusClass}"></span>${escapeHtml(statusText)}</small>
      </div>
      ${sources.length ? "" : `<div class="source-warning">No source pack was available. Treat current-fact claims as unverified.</div>`}
      ${warningRows.length ? `<div class="source-warning">${warningRows.map(escapeHtml).join(" ")}</div>` : ""}
      <p class="research-reason">${escapeHtml(pack.reason || "External research was requested.")}</p>
      ${queries.length ? `<div class="query-chips">${queries.map((query) => `<span>${escapeHtml(query)}</span>`).join("")}</div>` : ""}
      ${sources.length ? `<div class="source-list">${sourceRows}</div>` : `<div class="empty-inline">${escapeHtml(pack.errorMessage || "No source evidence was available for this run.")}</div>`}
      ${excludedSources.length ? `<details class="excluded-sources"><summary>${excludedSources.length} low-relevance external sources excluded from synthesis</summary><ul>${excludedRows}</ul></details>` : ""}
    </div>
  `;
}

function providerOutcomePanel(draftResults) {
  const drafts = Array.isArray(draftResults)
    ? draftResults
    : Array.isArray(draftResults?.drafts)
      ? draftResults.drafts
      : [];

  if (!drafts.length) {
    return "";
  }

  const groups = [
    { key: "used", label: "Used / valid drafts", statuses: ["SUCCEEDED"] },
    { key: "failed", label: "Failed attempts", statuses: ["FAILED"] },
    { key: "skipped", label: "Skipped", match: (status) => status.startsWith("SKIPPED_") },
    { key: "unavailable", label: "Unavailable / disabled", match: (status) => status.startsWith("UNAVAILABLE_") },
  ];

  const rows = drafts.map((draft) => {
    const diagnostics = draft.failureDetails || draft;
    const rawStatus = String(draft.outcomeStatus || draft.status
      || (diagnostics.failureCategory || draft.errorMessage ? "FAILED" : "SUCCEEDED")).toUpperCase();
    const status = rawStatus === "SUCCESS" ? "SUCCEEDED" : rawStatus === "FAILURE" ? "FAILED" : rawStatus;
    const ok = status === "SUCCEEDED";
    const skipped = status.startsWith("SKIPPED_");
    const unavailable = status.startsWith("UNAVAILABLE_");
    const attempted = typeof draft.attempted === "boolean" ? draft.attempted : !(skipped || unavailable);
    const provider = diagnostics.displayName || diagnostics.providerId || draft.provider || "unknown";
    const category = skipped ? "Skipped" : unavailable ? "Unavailable"
      : diagnostics.failureCategory ? humanize(diagnostics.failureCategory) : "Unknown failure";
    const reason = ok
      ? (draft.summary || "Draft succeeded.")
      : (draft.skipReason || diagnostics.safeMessage || draft.errorMessage || draft.summary
        || "Provider outcome did not include a safe reason.");
    const retry = !attempted
      ? "not attempted"
      : diagnostics.retryAttempted
      ? `retried (${diagnostics.attemptCount || 2} attempts)`
      : `single attempt (${diagnostics.attemptCount || 1})`;
    const circuit = diagnostics.circuitBreakerState && diagnostics.circuitBreakerState !== "UNKNOWN"
      ? `circuit ${diagnostics.circuitBreakerState.toLowerCase()}`
      : "";
    const timeout = diagnostics.timeoutMsConfigured
      ? `timeout ${diagnostics.timeoutMsConfigured} ms${diagnostics.timeoutSource ? ` ${humanize(diagnostics.timeoutSource)}` : ""}`
      : "";
    const requestShape = [
      diagnostics.promptTokenEstimate ? `~${diagnostics.promptTokenEstimate} prompt tokens` : "",
      diagnostics.requestSizeBytes ? `${diagnostics.requestSizeBytes} bytes` : ""
    ].filter(Boolean).join(" - ");
    const group = ok ? "used" : skipped ? "skipped" : unavailable ? "unavailable" : "failed";
    return { group, status, markup: `
      <div class="provider-outcome-row">
        <div>
          <strong><span class="status-dot ${ok ? "up" : skipped ? "neutral" : "down"}"></span>${escapeHtml(provider)}</strong>
          <small>${escapeHtml(diagnostics.providerId || draft.provider || "unknown")} - ${escapeHtml(draft.model || diagnostics.model || "model --")} - ${diagnostics.latencyMs ?? draft.latencyMs ?? "--"} ms</small>
        </div>
        <div>
          ${ok ? "" : `<span class="failure-category">${escapeHtml(category)}</span>`}
          <p class="${ok ? "" : "failure-reason"}">${escapeHtml(reason)}</p>
          ${ok ? "" : `<small class="failure-meta">${escapeHtml([humanize(status), retry, circuit, diagnostics.httpStatus ? `HTTP ${diagnostics.httpStatus}` : "", timeout, requestShape].filter(Boolean).join(" - "))}</small>`}
        </div>
      </div>
    ` };
  });

  const groupedRows = groups.map((group) => {
    const matching = rows.filter((row) => group.statuses
      ? group.statuses.includes(row.status)
      : group.match(row.status));
    if (!matching.length) return "";
    return `<section class="provider-outcome-group provider-outcome-${group.key}">
      <h4>${escapeHtml(group.label)} <span>${matching.length}</span></h4>
      <div class="provider-outcome-grid">${matching.map((row) => row.markup).join("")}</div>
    </section>`;
  }).join("");

  return `
    <div class="provider-outcome-panel" aria-label="Provider outcomes">
      <div class="dimension-heading">
        <span>Provider outcomes</span>
        <small>Used, failed, skipped, and unavailable providers</small>
      </div>
      ${groupedRows}
    </div>
  `;
}

function humanize(value) {
  return String(value || "Unknown")
    .toLowerCase()
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (match) => match.toUpperCase());
}

function runHealthPanel(diagnostics) {
  if (!diagnostics || typeof diagnostics !== "object") {
    return "";
  }
  const coverage = scoreValue(diagnostics.providerCoverage);
  const health = String(diagnostics.runHealth || "UNKNOWN").toUpperCase();
  const healthy = health === "HEALTHY";
  const selected = Number(diagnostics.selectedProviders || diagnostics.attemptedProviders || 0);
  const attempted = Number(diagnostics.attemptedProviders || 0);
  const valid = Number(diagnostics.validDraftProviders || 0);
  const failed = Number(diagnostics.failedAttempts || 0);
  const skipped = Number(diagnostics.skippedProviders || 0);
  const unavailable = Number(diagnostics.unavailableProviders || 0);
  const attemptCoverage = scoreValue(diagnostics.attemptCoverage ?? (selected ? attempted / selected : 0));
  const reason = diagnostics.degradedRunStatus || "All selected providers returned valid drafts.";
  return `
    <div class="run-health-panel ${healthy ? "healthy" : "degraded"}" aria-label="Provider run health">
      <div>
        <span class="status-dot ${healthy ? "up" : "down"}"></span>
        <strong>Run health: ${escapeHtml(humanize(health))}</strong>
        <p>${escapeHtml(reason)}</p>
      </div>
      <div class="run-health-metrics">
        <span>Provider coverage <strong>${Math.round(coverage * 1000) / 10}%</strong></span>
        <span>Attempt coverage <strong>${Math.round(attemptCoverage * 1000) / 10}%</strong></span>
        <span>Selected providers <strong>${selected}</strong></span>
        <span>Attempted / valid <strong>${attempted}/${valid}</strong></span>
        <span>Failed / skipped / unavailable <strong>${failed}/${skipped}/${unavailable}</strong></span>
        <span>Run confidence <strong>${Math.round(scoreValue(diagnostics.runConfidence) * 100)}%</strong></span>
      </div>
    </div>
  `;
}

function isDimensionMetadata(key) {
  return [
    "final_recommendation_sentence_count",
    "final_recommendation_required_min",
    "final_recommendation_required_max"
  ].includes(key);
}

function scoreValue(value) {
  return Math.max(0, Math.min(1, Number(value || 0)));
}

function inlineCode(value) {
  return value.replace(/`([^`]+)`/g, "<code>$1</code>");
}

function escapeHtml(value = "") {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function safeExternalHref(value = "") {
  try {
    const url = new URL(String(value));
    if (url.protocol === "http:" || url.protocol === "https:") {
      return escapeHtml(url.href);
    }
  } catch (error) {
    return "#";
  }
  return "#";
}

function shortId(value = "") {
  return value ? `${value.slice(0, 8)}...${value.slice(-4)}` : "--";
}

function listText(values) {
  return Array.isArray(values) && values.length ? values.join(", ") : "none";
}

function trimText(value, max) {
  return value.length > max ? `${value.slice(0, max - 1)}...` : value;
}

function emptyInline(message) {
  return `<div class="empty-inline">${escapeHtml(message)}</div>`;
}

function toast(message, error = false) {
  const item = document.createElement("div");
  item.className = `toast${error ? " error" : ""}`;
  item.textContent = message;
  els.toast.appendChild(item);
  setTimeout(() => item.remove(), 3600);
}
