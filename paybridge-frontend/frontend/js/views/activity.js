import { API_BASE, api, getLog, onLog, onServer } from "../api.js";
import { h, toast, describeError, setBusy } from "../ui.js";

// A live log of every call this page has made to the backend, plus a checklist of the
// endpoints that have been exercised. Handy for showing an instructor what is happening.

const ENDPOINTS = [
  ["POST", "/api/v1/customers/register", /^\/api\/v1\/customers\/register$/],
  ["GET", "/api/v1/customers/me", /^\/api\/v1\/customers\/me$/],
  ["POST", "/api/v1/accounts", /^\/api\/v1\/accounts$/],
  ["GET", "/api/v1/accounts", /^\/api\/v1\/accounts$/],
  ["GET", "/api/v1/accounts/{accountNumber}", /^\/api\/v1\/accounts\/[^/]+$/],
  ["POST", "/api/v1/sandbox/fund", /^\/api\/v1\/sandbox\/fund$/],
  ["POST", "/api/v1/transfers", /^\/api\/v1\/transfers$/],
  ["POST", "/api/v1/transfers/external", /^\/api\/v1\/transfers\/external$/],
  ["GET", "/api/v1/transfers/{id}", /^\/api\/v1\/transfers\/[^/]+$/],
  ["POST", "/api/v1/transfers/{id}/refresh", /^\/api\/v1\/transfers\/[^/]+\/refresh$/],
  ["GET", "/api/v1/banks", /^\/api\/v1\/banks$/],
  ["GET", "/api/v1/banks/resolve", /^\/api\/v1\/banks\/resolve$/],
  ["GET", "/actuator/health", /^\/actuator\/health$/],
];

export function activityView() {
  const coverage = h("ul", { class: "coverage" });
  const entries = h("div", { class: "log" });
  const serverLine = h("p", { class: "hint", role: "status" });

  const healthBtn = h("button", { class: "btn btn-quiet", type: "button" }, "Check server health");
  healthBtn.addEventListener("click", async () => {
    setBusy(healthBtn, true, "Checking…");
    try {
      const { data } = await api.health();
      toast(`Server health: ${data && data.status ? data.status : "OK"}`, { kind: "ok" });
    } catch (e) { toast(describeError(e).text, { kind: "error" }); }
    finally { setBusy(healthBtn, false); }
  });

  const root = h("section", { class: "view" },
    h("header", { class: "view-head" },
      h("div", null,
        h("h1", null, "Activity"),
        h("p", { class: "lede" }, "Every request this page sends to the backend, newest first. API keys are never shown."))),
    h("div", { class: "panel" },
      h("dl", { class: "kvs" },
        h("div", { class: "kv" }, h("dt", null, "Backend"), h("dd", null, h("code", null, API_BASE || location.origin))),
        h("div", { class: "kv" }, h("dt", null, "Interactive docs"), h("dd", null, h("a", { href: `${API_BASE}/swagger-ui/index.html`, target: "_blank", rel: "noopener" }, "Open Swagger UI")))),
      serverLine,
      h("div", { class: "inline-actions" }, healthBtn)),
    h("h2", null, "Endpoints used this session"),
    coverage,
    h("p", { class: "hint" }, "POST /api/v1/webhooks/paystack is called by Paystack, not by this site."),
    h("h2", null, "Requests"),
    entries);

  const seen = (method, re) => getLog().some((e) => e.method === method && e.status >= 200 && e.status < 300 && re.test(e.path.split("?")[0]));

  function paintCoverage() {
    coverage.replaceChildren(...ENDPOINTS.map(([method, path, re]) => {
      const done = seen(method, re);
      return h("li", { class: done ? "is-done" : "" },
        h("span", { class: "tick", "aria-hidden": "true" }, done ? "✓" : ""),
        h("code", null, `${method} ${path}`),
        h("span", { class: "visually-hidden" }, done ? " used" : " not used yet"));
    }));
  }

  const pretty = (text) => { try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; } };
  const kind = (s) => (s >= 200 && s < 300 ? "ok" : s >= 400 && s < 500 ? "warn" : "bad");

  function entryEl(e) {
    return h("details", { class: "log-entry" },
      h("summary", null,
        h("span", { class: "log-time" }, e.at.toLocaleTimeString("en-NG")),
        h("span", { class: "log-method" }, e.method),
        h("span", { class: "log-path" }, e.path),
        h("span", { class: `log-status log-${kind(e.status)}` }, e.status === 0 ? (e.code || "failed") : String(e.status)),
        h("span", { class: "log-ms" }, `${e.ms} ms`)),
      h("pre", null, e.preview ? pretty(e.preview) : "(no response body)"));
  }

  function paintLog() {
    const items = getLog();
    entries.replaceChildren(...(items.length ? items.map(entryEl) : [h("p", { class: "hint" }, "Nothing yet. Use the app and requests will appear here.")]));
    paintCoverage();
  }

  const offLog = onLog(paintLog);
  const offServer = onServer((s) => {
    serverLine.textContent = s.phase === "online" ? "Server is online."
      : s.phase === "unreachable" ? "Server is not reachable."
      : "Server is waking up. The free tier can take up to three minutes.";
  });

  paintLog();
  root.cleanup = () => { offLog(); offServer(); };
  return root;
}
