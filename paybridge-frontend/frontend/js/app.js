import { api, session, wake, onServer, serverInfo, API_BASE } from "./api.js";
import { h, copyText, shortId } from "./ui.js";
import { saveTransfer } from "./history.js";
import { authView } from "./views/auth.js";
import { accountsView } from "./views/accounts.js";
import { sendView } from "./views/send.js";
import { transfersView } from "./views/transfers.js";
import { activityView } from "./views/activity.js";

const ROUTES = [
  { id: "accounts", label: "Accounts", view: accountsView },
  { id: "send", label: "Send money", view: sendView },
  { id: "transfers", label: "Transfers", view: transfersView },
  { id: "activity", label: "Activity", view: activityView },
];

/* ---------- shared state handed to every view ---------- */

const ctx = {
  customerId: "",
  accounts: [],
  _banks: null,

  async refreshAccounts() {
    ctx.accounts = await api.accounts();
    return ctx.accounts;
  },
  // The bank list is the same for everyone, so it is fetched once per page load.
  loadBanks() {
    if (!ctx._banks) ctx._banks = api.banks().catch((e) => { ctx._banks = null; throw e; });
    return ctx._banks;
  },
  saveTransfer(t) { saveTransfer(ctx.customerId, t); },
  navigate(route, params = {}) {
    pendingParams = params;
    if (currentRoute() === route) renderRoute();
    else location.hash = `#/${route}`;
  },
  signedIn(customerId) {
    ctx.customerId = customerId;
    showShell();
  },
  signOut() {
    session.clear();
    ctx.customerId = "";
    ctx.accounts = [];
    ctx._banks = null;
    showAuth();
  },
};

/* ---------- page frame: server banner above whatever screen is showing ---------- */

const root = document.getElementById("app");
const banner = h("div", { class: "banner", hidden: true });
const stage = h("div", { class: "stage" });
root.append(banner, stage);

onServer((info) => {
  paintBanner(info);
  document.querySelectorAll(".server-pill").forEach((el) => paintPill(el, info));
});

function paintBanner(info) {
  if (info.phase === "online") { banner.hidden = true; return; }
  banner.hidden = false;
  banner.className = `banner ${info.phase === "unreachable" ? "banner-bad" : "banner-wait"}`;
  banner.setAttribute("role", info.phase === "unreachable" ? "alert" : "status");

  if (info.phase === "unreachable") {
    const retry = h("button", { class: "btn btn-quiet", type: "button", onclick: () => wake({ force: true }) }, "Try again");
    banner.replaceChildren(
      h("p", null, info.blockedByCors
        ? h("span", null, "The server is up but is refusing requests from this site. On Render, add ", h("code", null, location.origin), " to the ", h("code", null, "PAYBRIDGE_CORS_ALLOWED_ORIGINS"), " environment variable and redeploy.")
        : h("span", null, "Can't reach ", h("code", null, API_BASE || "the backend"), ". Check ", h("code", null, "API_BASE"), " in config.js and that the backend is deployed.")),
      retry);
    return;
  }
  banner.replaceChildren(h("p", null,
    h("span", { class: "spinner", "aria-hidden": "true" }),
    info.attempts > 0
      ? "The server is waking up. The free tier sleeps when idle, so this can take up to three minutes. Hang tight."
      : "Connecting to the server…"));
}

function paintPill(el, info) {
  const text = info.phase === "online" ? "Server online" : info.phase === "unreachable" ? "Server offline" : "Server waking up";
  el.textContent = text;
  el.className = `server-pill pill-${info.phase === "online" ? "ok" : info.phase === "unreachable" ? "bad" : "wait"}`;
}

/* ---------- screens ---------- */

let shell = null;
let currentCleanup = null;
let pendingParams = {};

function showAuth(opts = {}) {
  teardownView();
  shell = null;
  stage.replaceChildren(authView(ctx, opts));
}

function showLoading(message) {
  teardownView();
  shell = null;
  stage.replaceChildren(h("main", { class: "auth" }, h("section", { class: "auth-card", role: "status" }, h("p", null, message))));
}

function showRetry(message) {
  teardownView();
  shell = null;
  stage.replaceChildren(h("main", { class: "auth" },
    h("section", { class: "auth-card" },
      h("h2", null, "Couldn't sign you in"),
      h("p", null, message),
      h("div", { class: "inline-actions" },
        h("button", { class: "btn btn-primary", type: "button", onclick: boot }, "Try again"),
        h("button", { class: "btn btn-quiet", type: "button", onclick: () => ctx.signOut() }, "Use a different key")))));
}

function showShell() {
  const pill = h("span", { class: "server-pill", role: "status" });
  const nav = h("nav", { class: "nav", "aria-label": "Main" },
    ...ROUTES.map((r) => h("a", { class: "nav-link", href: `#/${r.id}`, "data-route": r.id }, r.label)));
  const view = h("main", { class: "main", id: "main", tabindex: "-1" });

  const top = h("header", { class: "topbar" },
    h("div", { class: "brand" }, h("span", { class: "wordmark" }, "PayBridge"), h("span", { class: "tag" }, "sandbox")),
    pill,
    h("div", { class: "who" },
      ctx.customerId ? h("span", { class: "hint", title: ctx.customerId }, `ID ${shortId(ctx.customerId)}`) : null,
      h("button", { class: "btn btn-link", type: "button", onclick: () => copyText(session.key, "API key copied") }, "Copy API key"),
      h("button", { class: "btn btn-quiet", type: "button", onclick: () => ctx.signOut() }, "Sign out")));

  shell = { el: h("div", { class: "shell" }, top, nav, view), view, nav };
  stage.replaceChildren(shell.el);
  paintPill(pill, serverInfo);              // later changes are painted by the listener above
  renderRoute();
}

function currentRoute() {
  const id = location.hash.replace(/^#\/?/, "").split("?")[0];
  return ROUTES.some((r) => r.id === id) ? id : "accounts";
}

function teardownView() {
  if (currentCleanup) { try { currentCleanup(); } catch { /* ignore */ } }
  currentCleanup = null;
}

function renderRoute() {
  if (!shell) return;
  const id = currentRoute();
  const params = pendingParams;
  pendingParams = {};
  teardownView();

  const route = ROUTES.find((r) => r.id === id);
  const node = route.view(ctx, params);
  currentCleanup = node.cleanup || null;
  shell.view.replaceChildren(node);

  shell.nav.querySelectorAll(".nav-link").forEach((a) => {
    if (a.dataset.route === id) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
  window.scrollTo(0, 0);
}

window.addEventListener("hashchange", renderRoute);

// Any 401 from a protected call means the saved key is no longer good.
window.addEventListener("pb:unauthorized", () => {
  if (!session.key) return;
  session.clear();
  ctx.customerId = "";
  showAuth({ notice: "The server didn't accept your saved API key. Paste a valid key or create a new profile." });
});

/* ---------- start ---------- */

async function boot() {
  wake();                                   // begin waking the free-tier server straight away
  if (!session.key) { showAuth(); return; }

  ctx.customerId = session.customerId;
  showLoading("Signing you in…");
  try {
    const me = await api.me();
    ctx.customerId = me.customerId;
    session.save(session.key, me.customerId);
    showShell();
  } catch (e) {
    if (e.status === 401) return;           // the pb:unauthorized handler already showed the sign-in screen
    showRetry("The PayBridge server didn't answer. If it was asleep, it may need another minute.");
  }
}

boot();
