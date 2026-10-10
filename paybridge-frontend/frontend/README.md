# PayBridge frontend

A static site (plain HTML, CSS and JavaScript modules, no build step) that talks to the PayBridge API.

| Page | Backend endpoints it uses |
|---|---|
| Sign in | `POST /customers/register`, `GET /customers/me` |
| Accounts | `GET /accounts`, `POST /accounts`, `GET /accounts/{number}`, `POST /sandbox/fund` |
| Send money | `POST /transfers`, `POST /transfers/external`, `GET /banks`, `GET /banks/resolve` |
| Transfers | `GET /transfers/{id}`, `POST /transfers/{id}/refresh` |
| Activity | a live log of every call, plus `GET /actuator/health` |

`POST /webhooks/paystack` is called by Paystack, so the site never calls it.

## Point it at a backend

Edit `config.js`:

```js
window.PAYBRIDGE_CONFIG = { API_BASE: "https://paybridge-xssx.onrender.com" };
```

For local testing against a backend on your machine, use `http://localhost:8080`.

## Run it locally

```
cd frontend
npx serve -l 3000
```

Open http://localhost:3000. Ports 3000 and 5500 (VS Code Live Server) are already allowed by the backend's default CORS setting.

## Deploy

Vercel, with **Root Directory** set to `frontend`, Framework Preset **Other**, and no build command. Full steps are in the README one folder up.
