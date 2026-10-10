// A transfer receipt. If the transfer is PROCESSING it asks the backend to settle it
// (POST /transfers/{id}/refresh) every few seconds, then stops and offers a manual button.

import { api } from "./api.js";
import { h, statusBadge, money, fmtDate, copyText, toast, describeError } from "./ui.js";

const MEANING = {
  COMPLETED: "The money has moved.",
  PROCESSING: "The money is held while Paystack confirms the payout.",
  PENDING: "Created, but nothing has moved yet.",
  FAILED: "The transfer was rejected before any money moved.",
  REVERSED: "The payout failed and the money went back to your account.",
};

const HTTP_MEANING = (status, replayed) => {
  if (replayed || status === 200) return "Replay of an earlier request (200). No new money moved.";
  if (status === 202) return "Accepted and still in progress (202).";
  if (status === 201) return "Created and final (201).";
  return null;
};

const POLL_MS = 6000;
const MAX_POLLS = 10;

export function receipt(ctx, initial, { replayed = false, httpStatus = null } = {}) {
  let t = initial;
  let wasReplay = replayed;
  let status = httpStatus;
  let polls = 0;
  let busy = false;
  let stopped = false;
  let timer = null;

  const root = h("article", { class: "receipt", "aria-live": "polite" });

  function row(label, value) {
    if (value === undefined || value === null || value === "") return null;
    return h("div", { class: "kv" }, h("dt", null, label), h("dd", null, value));
  }

  function destination() {
    if (t.type === "EXTERNAL") {
      return h("span", null,
        t.destinationAccountName ? h("strong", null, t.destinationAccountName) : null,
        t.destinationAccountName ? h("br") : null,
        `${t.destinationAccountNumber} at bank ${t.destinationBankCode}`);
    }
    return t.destinationAccountNumber;
  }

  function paint() {
    const processing = t.status === "PROCESSING";
    root.replaceChildren(
      h("header", { class: "receipt-head" },
        statusBadge(t.status, { large: true }),
        h("span", { class: "receipt-type" }, t.type === "EXTERNAL" ? "Bank transfer" : "PayBridge transfer")),
      h("div", { class: "receipt-amount" }, money(t.amount, t.currency, { big: true })),
      h("p", { class: "receipt-meaning" }, MEANING[t.status] || ""),
      HTTP_MEANING(status, wasReplay) ? h("p", { class: "receipt-note" }, HTTP_MEANING(status, wasReplay)) : null,
      h("dl", { class: "kvs" },
        row("From", t.sourceAccountNumber),
        row("To", destination()),
        row("Narration", t.narration),
        row("Reason", t.failureReason),
        row("Created", fmtDate(t.createdAt)),
        row("Transfer ID", h("span", { class: "id-line" },
          h("code", null, t.id),
          h("button", { class: "btn btn-link", type: "button", onclick: () => copyText(t.id, "Transfer ID copied") }, "Copy")))),
      processing
        ? h("div", { class: "receipt-actions" },
            h("button", { class: "btn btn-quiet", type: "button", disabled: busy, onclick: () => refresh() },
              busy ? "Checking…" : "Check status now"),
            h("span", { class: "hint" },
              polls < MAX_POLLS && !stopped
                ? "Checking with Paystack automatically."
                : "Still processing. The server also re-checks every minute, so look again shortly."))
        : null,
    );
  }

  function schedule() {
    clearTimeout(timer);
    if (stopped || t.status !== "PROCESSING" || polls >= MAX_POLLS) return;
    timer = setTimeout(() => { polls += 1; refresh(true); }, POLL_MS);
  }

  async function refresh(auto = false) {
    if (busy || stopped) return;
    busy = true; paint();
    try {
      const next = await api.refreshTransfer(t.id);
      const changed = next.status !== t.status;
      t = next; wasReplay = false; status = null;
      ctx.saveTransfer(t);
      if (changed) {
        ctx.refreshAccounts().catch(() => {});
        toast(`Transfer is now ${t.status.toLowerCase()}.`, { kind: t.status === "COMPLETED" ? "ok" : "info" });
      }
    } catch (e) {
      if (!auto) toast(describeError(e).text, { kind: "error" });
      polls = MAX_POLLS;
    } finally {
      busy = false; paint(); schedule();
    }
  }

  paint();
  schedule();
  root.cleanup = () => { stopped = true; clearTimeout(timer); };
  return root;
}
