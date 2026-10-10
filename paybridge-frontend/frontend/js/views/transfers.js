import { api } from "../api.js";
import { h, money, statusBadge, fmtDate, toast, setBusy, describeError, field } from "../ui.js";
import { listTransfers } from "../history.js";
import { receipt } from "../receipt.js";

// GET /transfers/{id} (and, through the receipt, POST /transfers/{id}/refresh)
export function transfersView(ctx, params = {}) {
  let receiptNode = null;
  let activeId = null;

  const listEl = h("div", { class: "ledger" });
  const detail = h("div", { class: "result" });

  const idInput = h("input", { class: "input", id: "lookup-id", type: "text", autocomplete: "off", spellcheck: "false", placeholder: "Transfer ID" });
  const lookupError = h("p", { class: "form-error", role: "alert", hidden: true });
  const lookupBtn = h("button", { class: "btn btn-quiet", type: "submit" }, "Look up");

  const lookup = h("form", { class: "stack", novalidate: true },
    field({ id: "lookup-id", label: "Look up a transfer by ID", input: idInput, hint: "Works for any transfer that belongs to you." }),
    lookupError, lookupBtn);

  lookup.addEventListener("submit", async (e) => {
    e.preventDefault();
    lookupError.hidden = true;
    const id = idInput.value.trim();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
      lookupError.textContent = "That doesn't look like a transfer ID. It is a 36-character code with dashes.";
      lookupError.hidden = false;
      return;
    }
    setBusy(lookupBtn, true, "Looking up…");
    try { await open(id); } finally { setBusy(lookupBtn, false); }
  });

  const root = h("section", { class: "view" },
    h("header", { class: "view-head" },
      h("div", null,
        h("h1", null, "Transfers"),
        h("p", { class: "lede" }, "Transfers this browser has sent or opened. Open one to get its current status from the server."))),
    h("div", { class: "send-grid" },
      h("div", { class: "stack" }, lookup, listEl),
      detail));

  function paintList() {
    const items = listTransfers(ctx.customerId);
    if (!items.length) {
      listEl.replaceChildren(h("div", { class: "empty" },
        h("h2", null, "No transfers yet"),
        h("p", null, "Send some money and it will show up here."),
        h("button", { class: "btn btn-primary", type: "button", onclick: () => ctx.navigate("send") }, "Send money")));
      return;
    }
    listEl.replaceChildren(...items.map((t) =>
      h("button", { class: `ledger-row row-button${t.id === activeId ? " is-active" : ""}`, type: "button", "aria-current": t.id === activeId ? "true" : null, onclick: () => open(t.id) },
        h("div", { class: "row-main" },
          h("div", null,
            h("div", { class: "acct-no small" }, t.type === "EXTERNAL" ? `To ${t.destinationAccountName || t.destinationAccountNumber}` : `To ${t.destinationAccountNumber}`),
            h("div", { class: "hint" }, fmtDate(t.createdAt))),
          h("div", { class: "bal" }, money(t.amount, t.currency), " ", statusBadge(t.status))))));
  }

  async function open(id) {
    activeId = id;
    detail.replaceChildren(h("p", { class: "hint", role: "status" }, "Loading transfer…"));
    try {
      const t = await api.getTransfer(id);
      ctx.saveTransfer(t);
      if (receiptNode && receiptNode.cleanup) receiptNode.cleanup();
      receiptNode = receipt(ctx, t);
      detail.replaceChildren(h("h2", null, "Transfer"), receiptNode);
    } catch (e) {
      detail.replaceChildren(h("p", { class: "form-error", role: "alert" }, describeError(e).text));
      toast(describeError(e).text, { kind: "error" });
    }
    paintList();
  }

  paintList();
  if (params.id) open(params.id);

  root.cleanup = () => { if (receiptNode && receiptNode.cleanup) receiptNode.cleanup(); };
  return root;
}
