import { api, idempotency } from "../api.js";
import { h, money, moneyText, statusBadge, copyText, toast, setBusy, parseAmount, describeError } from "../ui.js";

// GET /accounts, POST /accounts, GET /accounts/{number}, POST /sandbox/fund
export function accountsView(ctx) {
  const list = h("div", { class: "ledger" });
  const status = h("p", { class: "hint", role: "status" });
  const panels = new Map();            // accountNumber -> "fund" | "detail"
  const fundKeys = new Map();          // accountNumber -> idempotency helper (survives repaints)

  const currency = h("select", { class: "select", id: "new-currency", "aria-label": "Currency for the new account" },
    h("option", { value: "NGN" }, "NGN (naira)"),
    h("option", { value: "USD" }, "USD (dollars)"));
  const openBtn = h("button", { class: "btn btn-primary", type: "button", onclick: openAccount }, "Open account");

  const root = h("section", { class: "view" },
    h("header", { class: "view-head" },
      h("div", null,
        h("h1", null, "Accounts"),
        h("p", { class: "lede" }, "Your balances come straight from the ledger.")),
      h("div", { class: "inline-actions" },
        currency, openBtn,
        h("button", { class: "btn btn-quiet", type: "button", onclick: load }, "Refresh"))),
    status, list);

  async function load() {
    status.textContent = "Loading accounts…";
    try { await ctx.refreshAccounts(); status.textContent = ""; }
    catch (e) { status.textContent = describeError(e).text; }
    paint();
  }

  async function openAccount() {
    setBusy(openBtn, true, "Opening…");
    try {
      const acc = await api.openAccount(currency.value);
      toast(`Opened ${acc.currency} account ${acc.accountNumber}.`, { kind: "ok" });
      await ctx.refreshAccounts();
      paint();
    } catch (e) {
      toast(describeError(e).text, { kind: "error" });
    } finally {
      setBusy(openBtn, false);
    }
  }

  function paint() {
    if (!ctx.accounts.length) {
      list.replaceChildren(h("div", { class: "empty" },
        h("h2", null, "No accounts yet"),
        h("p", null, "Open an NGN account, add test money to it, then send it somewhere."),
        h("button", { class: "btn btn-primary", type: "button", onclick: openAccount }, "Open an NGN account")));
      currency.value = "NGN";
      return;
    }
    list.replaceChildren(...ctx.accounts.map(row));
  }

  function togglePanel(number, which) {
    panels.set(number, panels.get(number) === which ? null : which);
    paint();
  }

  function row(acc) {
    const open = panels.get(acc.accountNumber);
    return h("article", { class: "ledger-row" },
      h("div", { class: "row-main" },
        h("div", { class: "acct" },
          h("span", { class: "acct-no" }, acc.accountNumber),
          h("button", { class: "btn btn-link", type: "button", "aria-label": `Copy account number ${acc.accountNumber}`, onclick: () => copyText(acc.accountNumber, "Account number copied") }, "Copy"),
          h("span", { class: "tag" }, acc.currency),
          acc.status !== "ACTIVE" ? statusBadge(acc.status) : null),
        h("div", { class: "bal" }, money(acc.balance, acc.currency, { big: true }))),
      h("div", { class: "row-actions" },
        acc.currency === "NGN"
          ? h("button", { class: "btn btn-quiet", type: "button", "aria-expanded": String(open === "fund"), onclick: () => togglePanel(acc.accountNumber, "fund") }, "Add test money")
          : null,
        h("button", { class: "btn btn-quiet", type: "button", onclick: () => ctx.navigate("send", { from: acc.accountNumber }) }, "Send money"),
        h("button", { class: "btn btn-link", type: "button", "aria-expanded": String(open === "detail"), onclick: () => togglePanel(acc.accountNumber, "detail") }, "Details")),
      open === "fund" ? fundPanel(acc) : null,
      open === "detail" ? detailPanel(acc) : null);
  }

  function fundPanel(acc) {
    if (!fundKeys.has(acc.accountNumber)) fundKeys.set(acc.accountNumber, idempotency("fund"));
    const idem = fundKeys.get(acc.accountNumber);

    const amount = h("input", { class: "input", id: `fund-${acc.accountNumber}`, inputmode: "decimal", autocomplete: "off", placeholder: "5000.00", "aria-label": "Amount in naira" });
    const error = h("p", { class: "form-error", role: "alert", hidden: true });
    const submit = h("button", { class: "btn btn-primary", type: "submit" }, "Add test money");
    const chips = [1000, 5000, 50000].map((n) =>
      h("button", { class: "chip", type: "button", onclick: () => { amount.value = n.toFixed(2); amount.focus(); } }, moneyText(n.toFixed(2), "NGN")));

    const form = h("form", { class: "panel", novalidate: true },
      h("div", { class: "panel-line" }, amount, submit),
      h("div", { class: "chips" }, chips),
      error,
      h("p", { class: "hint" }, "Sandbox money only. Up to ₦10,000,000 per request."));

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      error.hidden = true;
      const amt = parseAmount(amount.value);
      if (!amt.ok) { error.textContent = amt.message; error.hidden = false; return; }
      const key = idem.keyFor(`${acc.accountNumber}|${amt.text}`);
      setBusy(submit, true, "Adding…");
      try {
        const res = await api.fund(key, acc.accountNumber, amt.value);
        idem.settle(null);
        toast(res.replayed
          ? "That request had already been processed, so nothing more was added."
          : `Added ${moneyText(amt.text, "NGN")}. New balance ${moneyText(res.balance, "NGN")}.`, { kind: res.replayed ? "info" : "ok" });
        panels.set(acc.accountNumber, null);
        await ctx.refreshAccounts();
        paint();
      } catch (err) {
        idem.settle(err);
        const d = describeError(err);
        error.textContent = d.unclear ? `${d.text} If you try again, the same idempotency key is reused so the money can't be added twice.` : d.text;
        error.hidden = false;
        setBusy(submit, false);
      }
    });
    return form;
  }

  function detailPanel(acc) {
    const kv = (k, v) => h("div", { class: "kv" }, h("dt", null, k), h("dd", null, v));
    const refetch = h("button", { class: "btn btn-quiet", type: "button" }, "Fetch this account again");
    refetch.addEventListener("click", async () => {
      setBusy(refetch, true, "Fetching…");
      try {
        const fresh = await api.account(acc.accountNumber);
        ctx.accounts = ctx.accounts.map((a) => (a.accountNumber === fresh.accountNumber ? fresh : a));
        toast("Account refreshed.", { kind: "ok", ms: 2000 });
        paint();
      } catch (e) {
        toast(describeError(e).text, { kind: "error" });
        setBusy(refetch, false);
      }
    });
    return h("div", { class: "panel" },
      h("dl", { class: "kvs" },
        kv("Account ID", h("code", null, acc.id)),
        kv("Customer ID", h("code", null, acc.customerId)),
        kv("Balance in kobo", String(acc.balanceMinor)),
        kv("Status", statusBadge(acc.status))),
      refetch);
  }

  load();
  return root;
}
