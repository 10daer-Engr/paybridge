# PayBridge frontend: what to add and how to deploy

This folder has two parts.

```
frontend/         the website. Copy this folder into the root of your repo, next to src/
backend-changes/  three small files that let a browser on another domain call your API (CORS)
```

## Why a backend change is needed

The site will live on `something.vercel.app` and the API on `paybridge-xssx.onrender.com`. Browsers block that
unless the API says the site is allowed (CORS). Your API has no CORS setup, and Spring Security would also answer
the browser's preflight check (an `OPTIONS` request with no API key) with 401. The three files fix both.

## Steps

Do them in this order. Check each one before moving on.

### 1. Add the backend files

From `backend-changes/src/`, copy into your project's `src/`:

| File | Action |
|---|---|
| `main/java/com/academy/paybridge/shared/config/CorsConfig.java` | new file |
| `main/java/com/academy/paybridge/customer/web/SecurityConfig.java` | replaces yours (two lines added: one import and `.cors(Customizer.withDefaults())`) |
| `test/java/com/academy/paybridge/customer/CorsPreflightTest.java` | new file |

Run `mvn test`. You should see the three new tests pass along with your existing 43.

### 2. Add the frontend and push

Copy the `frontend/` folder to your project root, then:

```
git add .
git commit -m "Add frontend and CORS support"
git push
```

Render redeploys the backend on every push to `main`. Wait for it to show **Live**.
(Optional: in Render, under Settings, Build Filters, add `frontend/**` as an ignored path so frontend-only
changes don't restart the backend.)

### 3. Deploy the frontend on Vercel

1. Go to vercel.com, click **Add New**, then **Project**, and import your GitHub repo.
2. Set **Root Directory** to `frontend`.
3. Set **Framework Preset** to `Other`. Leave Build Command and Output Directory empty.
4. Click **Deploy**.

Vercel gives you a URL such as `https://paybridge-abc123.vercel.app`. Copy it.

### 4. Tell the backend which site is allowed

In Render, open your service, then **Environment**, and add:

| Key | Value |
|---|---|
| `PAYBRIDGE_CORS_ALLOWED_ORIGINS` | `https://paybridge-abc123.vercel.app,https://paybridge-*.vercel.app` |

Use your own Vercel URL in both places. The second entry covers Vercel's preview deployments. Save, and Render
restarts the service.

### 5. Try it

Open the Vercel URL. A banner says the server is waking up if Render was asleep; that takes up to three minutes
on the free tier. Then register, open two accounts, add test money, and send.

Open the **Activity** page to see every request and tick off which endpoints you've used.

## If something goes wrong

| What you see | Cause |
|---|---|
| Red banner: "The server is up but is refusing requests from this site" | Step 4 is missing, has a typo, or the origin doesn't match exactly (no trailing slash, `https`). |
| Red banner: "Can't reach ..." | `API_BASE` in `frontend/config.js` is wrong, or the backend isn't running. |
| Blank page on Vercel, 404 | Root Directory isn't `frontend`. Change it under Project Settings, then redeploy. |
| Registration says too many requests | Registration is limited to 5 per hour per address. |
| Name lookup fails for a real bank | Paystack's sandbox allows only three lookups of real accounts a day. Use the sandbox test bank (001). |

## Things to know

- There is no endpoint that lists transfers, so the Transfers page remembers the ones this browser has seen.
  Opening one asks the server for its current status.
- The API key is kept in the browser's local storage so you stay signed in. That is fine for a sandbox. A real
  banking site would not do this.
- Every money-moving request carries an `Idempotency-Key`. If the server doesn't answer clearly, the retry
  reuses the same key, so money can't move twice.
