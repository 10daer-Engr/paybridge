// Small DOM and formatting helpers.
// There is no innerHTML anywhere: names, narrations and bank names come from users or from
// Paystack, so every value is inserted as text.

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  let deferredValue;
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "value") deferredValue = v;
      else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v === true) el.setAttribute(k, "");
      else el.setAttribute(k, String(v));
    }
  }
  appendAll(el, children);
  if (deferredValue !== undefined) el.value = deferredValue;
  return el;
}

export function appendAll(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === undefined || c === null || c === false) continue;
    el.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
}

/* ---------- feedback ---------- */

export function toast(message, { kind = "info", ms = 5000 } = {}) {
  const box = document.getElementById("toasts");
  if (!box) return;
  const t = h("div", { class: `toast toast-${kind}`, role: kind === "error" ? "alert" : "status" }, message);
  box.append(t);
  setTimeout(() => t.remove(), ms);
}

export async function copyText(text, label = "Copied") {
  try {
    await navigator.clipboard.writeText(text);
    toast(label, { kind: "ok", ms: 2200 });
  } catch {
    const ta = h("textarea", { style: "position:fixed;opacity:0" });
    ta.value = text;
    document.body.append(ta);
    ta.select();
    try { document.execCommand("copy"); toast(label, { kind: "ok", ms: 2200 }); }
    catch { toast("Copy failed. Select the text and copy it by hand.", { kind: "error" }); }
    ta.remove();
  }
}

export function confirmDialog({ title, body, confirmLabel = "Confirm", cancelLabel = "Cancel" }) {
  return new Promise((resolve) => {
    const dlg = h("dialog", { class: "dialog", "aria-labelledby": "dlg-title" });
    const done = (value) => { try { dlg.close(); } catch { /* already closed */ } dlg.remove(); resolve(value); };
    dlg.append(
      h("h2", { id: "dlg-title" }, title),
      body,
      h("div", { class: "dialog-actions" },
        h("button", { class: "btn btn-quiet", type: "button", onclick: () => done(false) }, cancelLabel),
        h("button", { class: "btn btn-primary", type: "button", onclick: () => done(true) }, confirmLabel)),
    );
    dlg.addEventListener("cancel", (e) => { e.preventDefault(); done(false); });
    document.body.append(dlg);
    dlg.showModal();
  });
}

export function setBusy(button, busy, busyLabel) {
  if (!button) return;
  if (busy) {
    button.dataset.label = button.textContent;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    if (busyLabel) button.textContent = busyLabel;
  } else {
    button.disabled = false;
    button.removeAttribute("aria-busy");
    if (button.dataset.label) button.textContent = button.dataset.label;
  }
}

/* ---------- money and dates ---------- */

export const SYMBOL = { NGN: "₦", USD: "$" };

// Balances arrive from the API as strings like "5000.00". They are formatted as text so no
// floating-point number ever touches the money.
export function splitMajor(text) {
  const s = String(text);
  const negative = s.startsWith("-");
  const [whole, frac = "00"] = s.replace("-", "").split(".");
  return {
    negative,
    whole: whole.replace(/\B(?=(\d{3})+(?!\d))/g, ","),
    frac: frac.padEnd(2, "0").slice(0, 2),
  };
}

export function money(text, currency = "NGN", { big = false } = {}) {
  const { negative, whole, frac } = splitMajor(text);
  return h("span", { class: big ? "money money-big" : "money" },
    negative ? "−" : "",
    h("span", { class: "cur" }, SYMBOL[currency] || `${currency} `),
    whole,
    h("span", { class: "kobo" }, `.${frac}`));
}

export function moneyText(text, currency = "NGN") {
  const { negative, whole, frac } = splitMajor(text);
  return `${negative ? "−" : ""}${SYMBOL[currency] || currency + " "}${whole}.${frac}`;
}

export function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return new Intl.DateTimeFormat("en-NG", { dateStyle: "medium", timeStyle: "short" }).format(d);
}

export function parseAmount(raw) {
  const s = String(raw).trim().replace(/,/g, "");
  if (!s) return { ok: false, message: "Enter an amount." };
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return { ok: false, message: "Use numbers with at most two decimal places, like 1500.50." };
  const n = Number(s);
  if (n < 0.01) return { ok: false, message: "The smallest amount is 0.01." };
  if (n > 10000000) return { ok: false, message: "The largest amount is 10,000,000.00." };
  return { ok: true, value: n, text: s };
}

/* ---------- status badges ---------- */

const BADGE_KIND = {
  COMPLETED: "ok", ACTIVE: "ok",
  PROCESSING: "warn", PENDING: "neutral", FROZEN: "warn", CLOSED: "neutral",
  FAILED: "bad", REVERSED: "bad",
};

export function statusBadge(status, { large = false } = {}) {
  const label = status ? status.charAt(0) + status.slice(1).toLowerCase() : "Unknown";
  return h("span", { class: `badge badge-${BADGE_KIND[status] || "neutral"}${large ? " badge-large" : ""}` },
    h("span", { class: "dot", "aria-hidden": "true" }), label);
}

/* ---------- errors ---------- */

const FRIENDLY = {
  UNAUTHENTICATED: "That API key is missing or wasn't accepted.",
  ACCOUNT_NOT_FOUND: "That account doesn't exist, or it isn't yours.",
  TRANSFER_NOT_FOUND: "There is no transfer with that ID.",
  IDEMPOTENCY_CONFLICT: "That idempotency key was already used for a different request. Start the transfer again.",
  EMAIL_ALREADY_REGISTERED: "That email is already registered. Use another email, or paste the key you saved.",
  DATA_CONFLICT: "An identical request was being processed at the same moment. Try again.",
  INSUFFICIENT_FUNDS: "The source account doesn't have enough money for this.",
  INVALID_AMOUNT: "Amounts must be positive with at most two decimal places.",
  CURRENCY_MISMATCH: "Both accounts must use the same currency.",
  SAME_ACCOUNT_TRANSFER: "Choose a different destination account.",
  ACCOUNT_NOT_ACTIVE: "One of the accounts is frozen or closed.",
  ACCOUNT_LIMIT_REACHED: "You can hold at most 10 accounts.",
  UNSUPPORTED_CURRENCY: "This only works for NGN accounts.",
  INVALID_IDEMPOTENCY_KEY: "The idempotency key must be 1 to 80 characters.",
  PROVIDER_REJECTED: "Paystack refused the request. No money moved.",
  PROVIDER_UNAVAILABLE: "Paystack didn't give a reliable answer.",
  PROVIDER_NOT_CONFIGURED: "The server has no Paystack key configured yet.",
  NETWORK: "Can't reach the PayBridge server. If it was asleep, give it a minute and try again.",
  TIMEOUT: "The server took too long to answer.",
};

const SHOW_SERVER_DETAIL = new Set(["PROVIDER_REJECTED", "PROVIDER_UNAVAILABLE", "INSUFFICIENT_FUNDS", "ACCOUNT_NOT_FOUND"]);

// Returns { text, unclear }. "unclear" means the request may or may not have been processed.
export function describeError(err) {
  const code = err && err.code;
  let text = FRIENDLY[code];
  if (err && err.status === 429) {
    const wait = err.retryAfter ? ` Try again in ${err.retryAfter} seconds.` : " Wait a moment and try again.";
    text = `You're sending requests too quickly.${wait}`;
  } else if (err && err.status === 400 && !text) {
    text = "The server rejected one of the values. Check the fields and try again.";
  }
  if (!text) text = (err && err.detail) || "Something went wrong. Try again.";
  else if (SHOW_SERVER_DETAIL.has(code) && err.detail && !text.includes(err.detail)) text += ` ${err.detail}`;
  return { text, unclear: !!err && (err.status === 0 || err.status >= 500) };
}

export function field({ id, label, hint, input }) {
  return h("div", { class: "field" },
    h("label", { for: id }, label),
    input,
    hint ? h("p", { class: "hint", id: `${id}-hint` }, hint) : null);
}

export const shortId = (id) => (id ? `${String(id).slice(0, 8)}…` : "");
