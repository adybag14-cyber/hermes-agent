---
name: entity-hermes-dashboard
description: The Hermes Agent FastAPI dashboard server (`hermes_cli.main dashboard`) — serves the compiled React SPA from `hermes_cli/web_dist/` plus a /api JSON + websocket surface for sessions, memory, tools, and gateway status.
metadata:
  type: entity
  scope: project
---

`hermes_cli.main dashboard` (alias for the legacy `hermes web`) starts a
FastAPI app on the requested `--port` / `--host`. Defaults to port 9119
on `127.0.0.1`. In this sandbox it runs on `:3000` (ProductOS preview).

**Routes exposed:**
- `GET /` — the built SPA `hermes_cli/web_dist/index.html` with a
  one-shot `window.__HERMES_SESSION_TOKEN__` and `__HERMES_DASHBOARD_EMBEDDED_CHAT__`
  injected per response.
- `/assets/*` — hashed JS / CSS bundles.
- `/api/status` — public, returns runtime status.
- `/api/health` — requires the session token (returns 401 without it).
- `/api/*` — most routes require the same token.

**Security:** the dashboard refuses `0.0.0.0` unless `--insecure` is set,
because `/api/*` can read API keys and config. Token is regenerated per page
hit; the dev `web/` Vite plugin scrapes it from the running dashboard's
`index.html` and re-injects it for HMR clients.

**Related:** [[decision-hermes-dashboard-on-port-3000]]