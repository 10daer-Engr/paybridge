// Every call to the PayBridge backend goes through request(). It adds the API key,
// understands the backend's problem+json errors, keeps a log for the Activity page,
// and waits for the free-tier server to wake up before the first real call.

const cfg = window.PAYBRIDGE_CONFIG || {};
export const API_BASE = String(cfg.API_BASE || "").replace(/\/+$/, "");

const KEY_STORE = "paybridge.apiKey";
const ID_STORE = "paybridge.customerId";

export const session = {
  get key() {
    try { return localStorage.getItem(KEY_STORE) || ""; } catch { return ""; }
  },
  get customerId() {
    try { return localStorage.getItem(ID_STORE) || ""; } catch { return ""; }
  },
  save(key, customerId) {
    try {
      localStorage.setItem(KEY_STORE, key);
      if (customerId) localStorage.setItem(ID_STORE, customerId);
    } catch { /* storage blocked: the session just won't survive a reload */ }
  },
  clear() {
    try {
      localStorage.removeItem(KEY_STORE);
      localStorage.removeItem(ID_STORE);
    } catch { /* ignore */ }
  },
};

export class ApiError extends Error {
  constructor({ status, code, detail, retryAfter = null, body = null }) {
    super(detail || code || "Request failed");
    this.status = status;       // 0 means no HTTP response was received at all
    this.code = code;
    this.detail = detail;
    this.retryAfter = retryAfter;
    this.body = body;
  }
}

/* ---------- request log (read by the Activity page) ---------- */

const log = [];
const logListeners = new Set();
let logSeq = 0;
export const getLog = () => log;
export function onLog(fn) { logListeners.add(fn); return () => logListeners.delete(fn); }
function pushLog(entry) {
  log.unshift(entry);
  if (log.length > 100) log.pop();
  logListeners.forEach((fn) => fn(entry));
}
const redact = (text) => text.replace(/("apiKey"\s*:\s*")[^"]+/g, "$1[hidden]");

/* ---------- server wake-up (Render's free tier sleeps when idle) ---------- */

export const serverInfo = { phase: "checking", attempts: 0, blockedByCors: false };
const serverListeners = new Set();
export function onServer(fn) { serverListeners.add(fn); fn(serverInfo); return () => serverListeners.delete(fn); }
function setPhase(phase, extra = {}) {
  Object.assign(serverInfo, extra, { phase });
  serverListeners.forEach((fn) => fn(serverInfo));
}

let wakePromise = null;
let lastOk = 0;
const STALE_AFTER_MS = 8 * 60 * 1000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function withTimeout(ms) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  return { signal: ctrl.signal, done: () => clearTimeout(timer) };
}

async function pingHealth() {
  const t = withTimeout(8000);
  try {
    const res = await fetch(new URL(API_BASE + "/actuator/health", location.href), {
      headers: { Accept: "application/json" }, cache: "no-store", signal: t.signal,
    });
    return res.ok;
  } finally { t.done(); }
}

// If a normal request fails but a no-cors one resolves, the server is up and is refusing this site.
async function reachableButBlocked() {
  const t = withTimeout(8000);
  try {
    await fetch(new URL(API_BASE + "/actuator/health", location.href), { mode: "no-cors", cache: "no-store", signal: t.signal });
    return true;
  } catch { return false; } finally { t.done(); }
}

export function wake({ force = false } = {}) {
  if (wakePromise && !force && serverInfo.phase !== "unreachable") return wakePromise;
  wakePromise = (async () => {
    setPhase("checking", { attempts: 0, blockedByCors: false });
    const started = Date.now();
    while (Date.now() - started < 240000) {
      try {
        if (await pingHealth()) { lastOk = Date.now(); setPhase("online"); return true; }
      } catch { /* not up yet */ }
      serverInfo.attempts += 1;
      setPhase("waking");
      await sleep(4000);
    }
    setPhase("unreachable", { blockedByCors: await reachableButBlocked() });
    return false;
  })();
  return wakePromise;
}

async function ensureAwake() {
  const fresh = Date.now() - lastOk < STALE_AFTER_MS;
  if (serverInfo.phase === "online" && fresh) return;
  if (serverInfo.phase === "unreachable") return;     // try anyway; the error will explain
  await wake({ force: serverInfo.phase === "online" });
}

/* ---------- the one request function ---------- */

export async function request(method, path, opts = {}) {
  const { body, query, headers = {}, auth = true, timeoutMs = 60000, skipWake = false } = opts;
  if (!skipWake) await ensureAwake();

  const url = new URL(API_BASE + path, location.href);
  if (query) for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== "") url.searchParams.set(k, v);

  const h = { Accept: "application/json, application/problem+json", ...headers };
  if (body !== undefined) h["Content-Type"] = "application/json";
  if (auth && session.key) h["X-API-Key"] = session.key;

  const t = withTimeout(timeoutMs);
  const started = performance.now();
  const entry = { id: ++logSeq, at: new Date(), method, path: url.pathname + url.search, status: 0, ms: 0, code: null, preview: "" };

  try {
    const res = await fetch(url, {
      method, headers: h, cache: "no-store", signal: t.signal,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch { data = text; } }

    entry.status = res.status;
    entry.ms = Math.round(performance.now() - started);
    entry.preview = redact(text).slice(0, 1500);

    if (!res.ok) {
      const err = new ApiError({
        status: res.status,
        code: data && typeof data === "object" ? (data.code || data.title) : null,
        detail: data && typeof data === "object" ? data.detail : null,
        retryAfter: Number(res.headers.get("Retry-After")) || null,
        body: data,
      });
      entry.code = err.code || null;
      pushLog(entry);
      if (res.status === 401 && auth) window.dispatchEvent(new CustomEvent("pb:unauthorized"));
      throw err;
    }
    lastOk = Date.now();
    pushLog(entry);
    return { data, status: res.status };
  } catch (e) {
    if (e instanceof ApiError) throw e;
    entry.ms = Math.round(performance.now() - started);
    const timedOut = e && e.name === "AbortError";
    entry.code = timedOut ? "TIMEOUT" : "NETWORK";
    pushLog(entry);
    throw new ApiError({
      status: 0,
      code: entry.code,
      detail: timedOut ? "The server took too long to answer." : "Could not reach the PayBridge server.",
    });
  } finally {
    t.done();
  }
}

/* ---------- one function per backend endpoint ---------- */

const enc = encodeURIComponent;

export const api = {
  health: () => request("GET", "/actuator/health", { auth: false, timeoutMs: 15000 }),

  register: (fullName, email) =>
    request("POST", "/api/v1/customers/register", { auth: false, body: { fullName, email } }).then((r) => r.data),

  // Pass a key to test one before saving it; otherwise the saved key is used.
  me: (candidateKey) =>
    (candidateKey
      ? request("GET", "/api/v1/customers/me", { auth: false, headers: { "X-API-Key": candidateKey } })
      : request("GET", "/api/v1/customers/me")
    ).then((r) => r.data),

  accounts: () => request("GET", "/api/v1/accounts").then((r) => r.data),
  account: (number) => request("GET", `/api/v1/accounts/${enc(number)}`).then((r) => r.data),
  openAccount: (currency) => request("POST", "/api/v1/accounts", { body: { currency } }).then((r) => r.data),

  fund: (idempotencyKey, accountNumber, amount) =>
    request("POST", "/api/v1/sandbox/fund", { headers: { "Idempotency-Key": idempotencyKey }, body: { accountNumber, amount } }).then((r) => r.data),

  banks: (name) => request("GET", "/api/v1/banks", { query: { name } }).then((r) => r.data),
  resolve: (accountNumber, bankCode) =>
    request("GET", "/api/v1/banks/resolve", { query: { accountNumber, bankCode } }).then((r) => r.data),

  // The two transfer calls return { data, status } because the HTTP status carries meaning
  // (201 final, 202 still processing, 200 replay of an earlier request).
  transfer: (idempotencyKey, payload) =>
    request("POST", "/api/v1/transfers", { headers: { "Idempotency-Key": idempotencyKey }, body: payload }),
  transferExternal: (idempotencyKey, payload) =>
    request("POST", "/api/v1/transfers/external", { headers: { "Idempotency-Key": idempotencyKey }, body: payload, timeoutMs: 90000 }),
  getTransfer: (id) => request("GET", `/api/v1/transfers/${enc(id)}`).then((r) => r.data),
  refreshTransfer: (id) => request("POST", `/api/v1/transfers/${enc(id)}/refresh`).then((r) => r.data),
};

/* ---------- idempotency keys ---------- */
// One key per intended money movement. If the answer is unclear (no response, or a 5xx),
// the same key is reused for the retry, so a repeat can never move money twice.
// After any clear answer the key is dropped and the next attempt gets a fresh one.

export function idempotency(prefix) {
  let key = null;
  let fingerprint = null;
  const fresh = () => {
    const raw = (globalThis.crypto && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}${Math.random().toString(16).slice(2)}`)
      .replace(/-/g, "").slice(0, 20);
    return `${prefix}-${raw}`;
  };
  return {
    keyFor(fp) {
      if (!key || fingerprint !== fp) { key = fresh(); fingerprint = fp; }
      return key;
    },
    settle(err) {
      const unclear = err && (err.status === 0 || err.status >= 500);
      if (!unclear) { key = null; fingerprint = null; }
    },
    get current() { return key; },
  };
}
