import { api, session } from "../api.js";
import { h, field, toast, copyText, setBusy, describeError } from "../ui.js";

// First screen: create a profile (POST /customers/register) or paste a key you already have
// (checked with GET /customers/me).
export function authView(ctx, { notice = "" } = {}) {
  let tab = "register";

  const tabs = h("div", { class: "seg", role: "group", "aria-label": "How do you want to start?" });
  const panel = h("div", { class: "auth-panel" });

  function paintTabs() {
    tabs.replaceChildren(
      tabBtn("register", "Create a profile"),
      tabBtn("key", "Use an existing key"),
    );
  }
  function tabBtn(id, label) {
    return h("button", {
      type: "button", class: "seg-btn", "aria-pressed": String(tab === id),
      onclick: () => { tab = id; paintTabs(); paintPanel(); },
    }, label);
  }

  function paintPanel() {
    panel.replaceChildren(tab === "register" ? registerForm() : keyForm());
  }

  /* ---------- create a profile ---------- */

  function registerForm() {
    const name = h("input", { class: "input", id: "reg-name", type: "text", autocomplete: "name", maxlength: "150", required: true, placeholder: "Ada Lovelace" });
    const email = h("input", { class: "input", id: "reg-email", type: "email", autocomplete: "email", maxlength: "254", required: true, placeholder: "ada@example.com" });
    const error = h("p", { class: "form-error", role: "alert", hidden: true });
    const submit = h("button", { class: "btn btn-primary btn-block", type: "submit" }, "Create profile");

    const form = h("form", { class: "stack", novalidate: true },
      field({ id: "reg-name", label: "Full name", input: name }),
      field({ id: "reg-email", label: "Email", input: email, hint: "Used only to keep profiles unique. There is no verification email." }),
      error, submit);

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      error.hidden = true;
      const fullName = name.value.trim();
      const mail = email.value.trim();
      if (!fullName) return fail("Enter your full name.");
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(mail)) return fail("Enter a valid email address.");
      setBusy(submit, true, "Creating profile…");
      try {
        const res = await api.register(fullName, mail);
        session.save(res.apiKey, res.customerId);   // saved immediately so a reload can't lose it
        panel.replaceChildren(reveal(res));
      } catch (err) {
        fail(describeError(err).text);
      } finally {
        setBusy(submit, false);
      }
    });

    function fail(message) { error.textContent = message; error.hidden = false; }
    return form;
  }

  function reveal(res) {
    const ack = h("input", { type: "checkbox", id: "ack" });
    const go = h("button", { class: "btn btn-primary btn-block", type: "button", disabled: true }, "Continue to my accounts");
    go.addEventListener("click", async () => {
      setBusy(go, true, "Checking your key…");
      try {
        const me = await api.me();            // proves the saved key really authenticates before we open the app
        ctx.signedIn(me.customerId);
      } catch (err) {
        toast(describeError(err).text, { kind: "error" });
        setBusy(go, false);
      }
    });
    ack.addEventListener("change", () => { go.disabled = !ack.checked; });
    return h("div", { class: "stack" },
      h("h2", null, `Welcome, ${res.fullName}`),
      h("p", null, "This is your API key. PayBridge shows it once and keeps only a hash of it, so it can't be recovered."),
      h("div", { class: "key-box" },
        h("code", { class: "key" }, res.apiKey),
        h("button", { class: "btn btn-quiet", type: "button", onclick: () => copyText(res.apiKey, "API key copied") }, "Copy key")),
      h("p", { class: "hint" }, "This browser also keeps it so you stay signed in. You'll need it to sign in anywhere else, including Swagger's Authorize button."),
      h("label", { class: "check", for: "ack" }, ack, "I've saved my key somewhere safe"),
      go);
  }

  /* ---------- paste an existing key ---------- */

  function keyForm() {
    const key = h("input", { class: "input", id: "key-input", type: "text", autocomplete: "off", spellcheck: "false", placeholder: "pbk_…" });
    const error = h("p", { class: "form-error", role: "alert", hidden: true });
    const submit = h("button", { class: "btn btn-primary btn-block", type: "submit" }, "Sign in with key");
    const form = h("form", { class: "stack", novalidate: true },
      field({ id: "key-input", label: "API key", input: key, hint: "The key starts with pbk_. Lost it? Create a new profile with a different email." }),
      error, submit);

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      error.hidden = true;
      const candidate = key.value.trim();
      if (!candidate) { error.textContent = "Paste your API key."; error.hidden = false; return; }
      setBusy(submit, true, "Checking key…");
      try {
        const me = await api.me(candidate);
        session.save(candidate, me.customerId);
        toast("Signed in.", { kind: "ok", ms: 2000 });
        ctx.signedIn(me.customerId);
      } catch (err) {
        error.textContent = err.status === 401 ? "That key wasn't recognised. Check for extra characters." : describeError(err).text;
        error.hidden = false;
      } finally {
        setBusy(submit, false);
      }
    });
    return form;
  }

  /* ---------- the page ---------- */

  paintTabs();
  paintPanel();

  return h("main", { class: "auth" },
    h("section", { class: "auth-hero" },
      h("p", { class: "wordmark" }, "PayBridge"),
      h("h1", null, "Send test naira through a double-entry ledger"),
      h("p", { class: "lede" }, "Open accounts, add test money, and send it to another PayBridge account or a Nigerian bank. This is a sandbox, so no real money moves."),
      postingFigure()),
    h("section", { class: "auth-card", "aria-label": "Get started" },
      notice ? h("p", { class: "notice", role: "status" }, notice) : null,
      tabs, panel));
}

function postingFigure() {
  return h("figure", { class: "posting" },
    h("figcaption", null, "What the ledger records when you add ₦5,000 of test money"),
    h("table", null,
      h("thead", null, h("tr", null, h("th", { scope: "col" }, "Account"), h("th", { scope: "col", class: "num" }, "Debit"), h("th", { scope: "col", class: "num" }, "Credit"))),
      h("tbody", null,
        h("tr", null, h("td", null, "Sandbox funding"), h("td", { class: "num" }, "5,000.00"), h("td", { class: "num" }, "")),
        h("tr", null, h("td", null, "Your account"), h("td", { class: "num" }, ""), h("td", { class: "num" }, "5,000.00"))),
      h("tfoot", null, h("tr", null, h("th", { scope: "row" }, "Total"), h("td", { class: "num" }, "5,000.00"), h("td", { class: "num" }, "5,000.00")))),
    h("p", { class: "posting-note" }, "Every movement is written twice and the two sides always match."));
}
