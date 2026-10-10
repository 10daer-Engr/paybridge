import { api, idempotency } from "../api.js";
import {
  h, field, moneyText, setBusy, parseAmount, describeError, confirmDialog,
} from "../ui.js";
import { receipt } from "../receipt.js";

// POST /transfers, POST /transfers/external, GET /banks, GET /banks/resolve
const TEST_BANK = { name: "Sandbox test bank", code: "001" };
const TEST_BANK_LABEL = "Sandbox test bank (001)";

export function sendView(ctx, params = {}) {
  let mode = params.mode === "bank" ? "bank" : "internal";
  const idem = idempotency("trf");

  let bankIndex = new Map();             // lower-case label -> { name, code }
  let banksState = "idle";               // idle | loading | ready | failed
  let banksError = "";
  let resolved = null;                   // { accountNumber, bankCode, accountName }
  let resolveError = "";
  let resolving = false;
  let resolveToken = 0;
  let receiptNode = null;
  let wantedFrom = params.from || "";

  /* ---------- controls ---------- */

  const segInternal = h("button", { type: "button", class: "seg-btn", onclick: () => setMode("internal") }, "To a PayBridge account");
  const segBank = h("button", { type: "button", class: "seg-btn", onclick: () => setMode("bank") }, "To a bank account");
  const seg = h("div", { class: "seg", role: "group", "aria-label": "Where is the money going?" }, segInternal, segBank);

  const from = h("select", { class: "select", id: "send-from" });
  const fromHint = h("p", { class: "hint", id: "send-from-hint" });

  const dest = h("input", { class: "input", id: "send-dest", type: "text", inputmode: "numeric", autocomplete: "off", maxlength: "20", placeholder: "Account number" });
  const ownChips = h("div", { class: "chips" });

  const bankInput = h("input", { class: "input", id: "send-bank", type: "text", list: "bank-list", autocomplete: "off", placeholder: "Start typing a bank name" });
  const bankList = h("datalist", { id: "bank-list" });
  const bankHint = h("p", { class: "hint", id: "send-bank-hint" });
  const bankAcct = h("input", { class: "input", id: "send-bank-acct", type: "text", inputmode: "numeric", autocomplete: "off", maxlength: "10", placeholder: "10-digit account number" });
  const resolveBox = h("div", { class: "resolve", "aria-live": "polite" });

  const amount = h("input", { class: "input", id: "send-amount", type: "text", inputmode: "decimal", autocomplete: "off", placeholder: "1500.00" });
  const narration = h("input", { class: "input", id: "send-narration", type: "text", maxlength: "100", placeholder: "Optional note" });
  const narrationCount = h("span", { class: "hint" }, "0/100");

  const formError = h("p", { class: "form-error", role: "alert", hidden: true });
  const keyLine = h("p", { class: "hint", hidden: true });
  const submit = h("button", { class: "btn btn-primary btn-block", type: "submit" }, "Review and send");
  const result = h("div", { class: "result" });

  const internalGroup = h("div", { class: "stack" },
    field({ id: "send-dest", label: "Destination account number", input: dest, hint: "Any PayBridge account. Sending to your own second account shows both balances change." }),
    ownChips);

  const bankGroup = h("div", { class: "stack" },
    field({ id: "send-bank", label: "Bank", input: h("div", null, bankInput, bankList), hint: null }),
    h("div", { class: "chips" },
      h("button", { class: "chip", type: "button", onclick: useTestBank }, "Use the sandbox test bank (001)")),
    bankHint,
    field({ id: "send-bank-acct", label: "Recipient account number", input: bankAcct }),
    resolveBox);

  const form = h("form", { class: "stack", novalidate: true },
    field({ id: "send-from", label: "From", input: from }),
    fromHint,
    internalGroup,
    bankGroup,
    field({ id: "send-amount", label: "Amount in naira", input: amount, hint: "Up to two decimal places." }),
    h("div", { class: "field" },
      h("label", { for: "send-narration" }, "Narration"), narration, narrationCount),
    formError, keyLine, submit);

  const root = h("section", { class: "view" },
    h("header", { class: "view-head" },
      h("div", null,
        h("h1", null, "Send money"),
        h("p", { class: "lede" }, "Every transfer carries an idempotency key, so a retry can't pay twice."))),
    seg,
    h("div", { class: "send-grid" }, form, result));

  /* ---------- mode and accounts ---------- */

  function setMode(next) {
    mode = next;
    formError.hidden = true;
    paintMode();
    paintFrom();
    if (mode === "bank") ensureBanks();
  }

  function paintMode() {
    segInternal.setAttribute("aria-pressed", String(mode === "internal"));
    segBank.setAttribute("aria-pressed", String(mode === "bank"));
    internalGroup.hidden = mode !== "internal";
    bankGroup.hidden = mode !== "bank";
  }

  function eligible() {
    return ctx.accounts.filter((a) => a.status === "ACTIVE" && (mode === "bank" ? a.currency === "NGN" : true));
  }

  function selected() {
    return ctx.accounts.find((a) => a.accountNumber === from.value);
  }

  function paintFrom() {
    const accounts = eligible();
    const previous = from.value || wantedFrom;
    from.replaceChildren(...accounts.map((a) =>
      h("option", { value: a.accountNumber }, `${a.accountNumber} (${moneyText(a.balance, a.currency)})`)));
    if (accounts.some((a) => a.accountNumber === previous)) from.value = previous;
    fromHint.textContent = accounts.length
      ? ""
      : mode === "bank"
        ? "Bank transfers need an active NGN account. Open one on the Accounts page."
        : "You need an active account first. Open one on the Accounts page.";
    submit.disabled = !accounts.length;
    paintOwnChips();
  }

  function paintOwnChips() {
    const src = selected();
    const others = src ? ctx.accounts.filter((a) => a.accountNumber !== src.accountNumber && a.currency === src.currency && a.status === "ACTIVE") : [];
    ownChips.replaceChildren(...others.map((a) =>
      h("button", { class: "chip", type: "button", onclick: () => { dest.value = a.accountNumber; } }, `My account ${a.accountNumber}`)));
  }

  /* ---------- banks and name enquiry ---------- */

  async function ensureBanks() {
    if (banksState === "ready" || banksState === "loading") return;
    banksState = "loading"; banksError = ""; paintBankHint();
    try {
      const banks = await ctx.loadBanks();
      buildBankIndex(banks);
      banksState = "ready";
    } catch (e) {
      banksState = "failed";
      banksError = describeError(e).text;
    }
    paintBankHint();
  }

  function buildBankIndex(banks) {
    const counts = new Map();
    banks.forEach((b) => counts.set(b.name, (counts.get(b.name) || 0) + 1));
    bankIndex = new Map();
    const options = [];
    [...banks].sort((a, b) => a.name.localeCompare(b.name)).forEach((b) => {
      const label = counts.get(b.name) > 1 ? `${b.name} (${b.code})` : b.name;
      bankIndex.set(label.toLowerCase(), { name: b.name, code: b.code });
      options.push(h("option", { value: label }));
    });
    bankIndex.set(TEST_BANK_LABEL.toLowerCase(), TEST_BANK);
    bankList.replaceChildren(...options);
  }

  function currentBank() {
    const text = bankInput.value.trim();
    if (!text) return null;
    if (text.toLowerCase() === TEST_BANK_LABEL.toLowerCase()) return TEST_BANK;
    const hit = bankIndex.get(text.toLowerCase());
    if (hit) return hit;
    if (/^\d{3,6}$/.test(text)) return { name: `Bank code ${text}`, code: text };
    return null;
  }

  function paintBankHint() {
    bankHint.replaceChildren();
    const bank = currentBank();
    if (banksState === "loading") bankHint.textContent = "Loading banks…";
    else if (banksState === "failed") {
      bankHint.append(`${banksError} `, h("button", { class: "btn btn-link", type: "button", onclick: () => { banksState = "idle"; ensureBanks(); } }, "Try again"));
    } else if (bank) bankHint.textContent = `Bank code ${bank.code}`;
    else if (bankInput.value.trim()) bankHint.textContent = "Pick a bank from the suggestions, or type its bank code.";
    else bankHint.textContent = "Pick from the list. The sandbox test bank accepts any 10-digit account number.";
  }

  function useTestBank() {
    bankInput.value = TEST_BANK_LABEL;
    onBankFieldsChanged();
    bankAcct.focus();
  }

  function onBankFieldsChanged() {
    resolved = null; resolveError = ""; resolveToken += 1; resolving = false;
    paintBankHint();
    const bank = currentBank();
    if (bank && bank.code === TEST_BANK.code && /^\d{10}$/.test(bankAcct.value)) resolve();
    else paintResolve();
  }

  async function resolve() {
    const bank = currentBank();
    const number = bankAcct.value;
    if (!bank || !/^\d{10}$/.test(number)) return;
    const token = ++resolveToken;
    resolving = true; resolveError = ""; paintResolve();
    try {
      const r = await api.resolve(number, bank.code);
      if (token === resolveToken) resolved = r;
    } catch (e) {
      if (token === resolveToken) resolveError = describeError(e).text;
    } finally {
      if (token === resolveToken) { resolving = false; paintResolve(); }
    }
  }

  function paintResolve() {
    const bank = currentBank();
    const ready = bank && /^\d{10}$/.test(bankAcct.value);
    if (resolving) resolveBox.replaceChildren(h("p", { class: "hint" }, "Looking up the account name…"));
    else if (resolved) resolveBox.replaceChildren(h("p", { class: "verified" }, h("span", { "aria-hidden": "true" }, "✓ "), "Account name: ", h("strong", null, resolved.accountName)));
    else if (resolveError) resolveBox.replaceChildren(
      h("p", { class: "form-error" }, resolveError),
      ready ? h("button", { class: "btn btn-quiet", type: "button", onclick: resolve }, "Try again") : null);
    else if (ready) resolveBox.replaceChildren(
      h("button", { class: "btn btn-quiet", type: "button", onclick: resolve }, "Verify account name"),
      h("p", { class: "hint" }, "Optional. Paystack's sandbox allows only three lookups of real accounts a day for everyone."));
    else resolveBox.replaceChildren();
  }

  /* ---------- field behaviour ---------- */

  const digitsOnly = (input) => input.addEventListener("input", () => {
    const cleaned = input.value.replace(/\D/g, "");
    if (cleaned !== input.value) input.value = cleaned;
  });
  digitsOnly(dest);
  digitsOnly(bankAcct);

  from.addEventListener("change", () => { wantedFrom = from.value; paintOwnChips(); });
  bankInput.addEventListener("input", onBankFieldsChanged);
  bankInput.addEventListener("change", onBankFieldsChanged);
  bankAcct.addEventListener("input", onBankFieldsChanged);
  narration.addEventListener("input", () => { narrationCount.textContent = `${narration.value.length}/100`; });

  /* ---------- submit ---------- */

  function showError(message) { formError.textContent = message; formError.hidden = false; }

  function updateKeyLine() {
    if (idem.current) {
      keyLine.textContent = `The last attempt didn't get a clear answer. Sending again reuses idempotency key ${idem.current}, so the money can't move twice.`;
      keyLine.hidden = false;
    } else {
      keyLine.hidden = true;
    }
  }

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    formError.hidden = true;

    const source = selected();
    if (!source) return showError("Choose an account to send from.");
    const amt = parseAmount(amount.value);
    if (!amt.ok) return showError(amt.message);

    let payload;
    let toLines;
    if (mode === "internal") {
      if (!dest.value) return showError("Enter the destination account number.");
      if (dest.value === source.accountNumber) return showError("Choose a different destination account.");
      payload = { sourceAccountNumber: source.accountNumber, destinationAccountNumber: dest.value, amount: amt.value };
      toLines = dest.value;
    } else {
      const bank = currentBank();
      if (!bank) return showError("Pick a bank from the suggestions, or type its bank code.");
      if (!/^\d{10}$/.test(bankAcct.value)) return showError("The recipient account number must be exactly 10 digits.");
      payload = { sourceAccountNumber: source.accountNumber, destinationAccountNumber: bankAcct.value, destinationBankCode: bank.code, amount: amt.value };
      const name = resolved && resolved.accountNumber === bankAcct.value ? resolved.accountName : null;
      toLines = h("span", null, name ? h("strong", null, name) : null, name ? h("br") : null, `${bankAcct.value} at ${bank.name} (${bank.code})`);
    }
    const note = narration.value.trim();
    if (note) payload.narration = note;

    const ok = await confirmDialog({
      title: "Send this money?",
      confirmLabel: `Send ${moneyText(amt.text, source.currency)}`,
      body: h("dl", { class: "kvs" },
        kv("From", `${source.accountNumber} (${moneyText(source.balance, source.currency)})`),
        kv("To", toLines),
        kv("Amount", moneyText(amt.text, source.currency)),
        note ? kv("Narration", note) : null),
    });
    if (!ok) return;

    const key = idem.keyFor(JSON.stringify(payload));
    setBusy(submit, true, mode === "bank" ? "Sending to the bank…" : "Sending…");
    try {
      const { data, status } = mode === "bank" ? await api.transferExternal(key, payload) : await api.transfer(key, payload);
      idem.settle(null);
      updateKeyLine();
      ctx.saveTransfer(data);
      showReceipt(data, { replayed: data.replayed, httpStatus: status });
      amount.value = ""; narration.value = ""; narrationCount.textContent = "0/100";
      ctx.refreshAccounts().then(paintFrom).catch(() => {});
    } catch (err) {
      idem.settle(err);
      const d = describeError(err);
      showError(d.unclear ? `${d.text} The request may have gone through, so check the Transfers page before sending again.` : d.text);
      updateKeyLine();
    } finally {
      setBusy(submit, false);
    }
  });

  function showReceipt(transfer, opts) {
    if (receiptNode && receiptNode.cleanup) receiptNode.cleanup();
    receiptNode = receipt(ctx, transfer, opts);
    result.replaceChildren(h("h2", null, "Receipt"), receiptNode);
    receiptNode.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }

  const kv = (k, v) => (v === null ? null : h("div", { class: "kv" }, h("dt", null, k), h("dd", null, v)));

  /* ---------- start ---------- */

  paintMode();
  paintFrom();
  paintBankHint();
  paintResolve();
  ctx.refreshAccounts().then(() => { paintFrom(); }).catch((e) => showError(describeError(e).text));
  if (mode === "bank") ensureBanks();

  root.cleanup = () => { if (receiptNode && receiptNode.cleanup) receiptNode.cleanup(); };
  return root;
}
