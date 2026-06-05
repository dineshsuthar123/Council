const API_BASE = "/api/v1";

const pipelineSteps = [
  ["route", "Task routing", "Classifies the prompt and selects provider roles."],
  ["draft", "Parallel drafts", "Collects independent candidate answers."],
  ["critic", "Critic pass", "Finds contradictions, weak assumptions, and gaps."],
  ["verifier", "Verifier pass", "Checks design and capacity claims with workflow-aware enforcement."],
  ["synthesis", "Synthesis", "Combines the strongest evidence into one answer."],
  ["trace", "Trace persistence", "Stores the full audit trail for inspection."]
];

const state = {
  running: false,
  runStartedAt: null,
  currentTraceId: null,
  selectedMode: "balanced",
  phaseTimer: null
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
  toast: document.querySelector("#toast-region")
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
  renderLoadingAnswer(query);
  animatePipeline();

  try {
    const response = await fetchJson(`${API_BASE}/reason`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query })
    });

    state.currentTraceId = response.traceId;
    renderPipeline(response.error ? "failed" : "done");
    renderAnswer(response);

    await Promise.all([loadProviders(), loadTraces(), loadHealth()]);
  } catch (error) {
    renderPipeline("failed");
    renderError(error.message || "Council request failed.");
    toast(error.message || "Council request failed.", true);
  } finally {
    setRunning(false);
  }
}

function setRunning(running) {
  state.running = running;
  els.submit.disabled = running;
  els.submit.classList.toggle("is-running", running);
  els.submit.querySelector(".button-label").textContent = running ? "Council running" : "Run council";
  if (!running) {
    clearInterval(state.phaseTimer);
    state.phaseTimer = null;
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
        <h2>Reasoning in progress</h2>
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

  const confidence = Math.max(0, Math.min(1, Number(response.confidence || 0)));
  const confidencePct = Math.round(confidence * 100);
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
      <div class="confidence-ring" style="--confidence-angle: ${confidence * 360}deg" aria-label="Confidence ${confidencePct}%">
        <span>${confidencePct}%</span>
      </div>
    </div>
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
  } catch (error) {
    els.health.innerHTML = `<span class="status-dot down"></span><span>Offline</span>`;
    els.routing.textContent = "--";
    els.available.textContent = "--";
  }
}

async function loadProviders() {
  renderProviderSkeleton();
  try {
    const providers = await fetchJson(`${API_BASE}/providers/status`);
    els.providerCount.textContent = `${providers.length} providers`;
    els.providerTable.innerHTML = providers.map(renderProviderRow).join("") || emptyInline("No providers configured.");
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

function renderProviderRow(provider) {
  const statusClass = provider.enabled && provider.availableForRouting !== false && !provider.coolingDown ? "up" : provider.coolingDown ? "degraded" : "down";
  const roles = Array.isArray(provider.roles) && provider.roles.length
    ? provider.roles.map((role) => `<span class="role-tag">${escapeHtml(role)}</span>`).join("")
    : `<span class="role-tag">GENERAL</span>`;
  const permits = provider.availableConcurrencyPermits ?? "--";
  const total = (provider.totalSuccesses || 0) + (provider.totalFailures || 0);

  return `
    <div class="provider-row">
      <div>
        <div class="provider-name"><span class="status-dot ${statusClass}"></span>${escapeHtml(provider.provider)}</div>
        <div class="provider-model">${escapeHtml(provider.model || "model unavailable")}</div>
      </div>
      <div class="provider-roles">${roles}</div>
      <div class="provider-metric">
        <strong>${permits}</strong> permits<br>
        ${Math.round((provider.recentFailureRate || 0) * 100)}% recent failure | ${total} calls
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
        await fetchJson(`${API_BASE}/providers/${encodeURIComponent(provider)}/reset-cooldown`, { method: "POST" });
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
  renderTraceSkeleton();
  try {
    const page = await fetchJson(`${API_BASE}/traces?page=0&size=8`);
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
    const debug = await fetchJson(`${API_BASE}/traces/${encodeURIComponent(traceId)}/debug`);
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
  const confidence = Math.max(0, Math.min(1, Number(debug.finalConfidence || 0)));

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
      <div class="confidence-ring" style="--confidence-angle: ${confidence * 360}deg">
        <span>${Math.round(confidence * 100)}%</span>
      </div>
    </div>
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
  const response = await fetch(url, options);
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

function formatAnswer(value) {
  const safe = escapeHtml(value);
  const lines = safe.split(/\r?\n/);
  const html = [];
  let inList = false;

  for (const line of lines) {
    const trimmed = line.trim();
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
  return html.join("");
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
