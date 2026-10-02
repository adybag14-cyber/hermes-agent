---
name: decision-hermes-dashboard-on-port-3000
description: We run `hermes dashboard --port 3000 --host 0.0.0.0 --insecure` as the npm `dev` script in the root package.json so the ProductOS preview (port 3000 only) serves the Hermes Agent web UI.
metadata:
  type: decision
  scope: project
---

The repository root has a `package.json` (no Node app of its own), a `web/`
Vite dashboard, and a Python `hermes` CLI. The ProductOS preview supervisor
expects a single dev server on port 3000 — whichever command the
`preview-runtime-v1.sh` launcher `exec`s into. It walks a fixed menu
(`serve.py`, `app.py`, `main.py`, `package.json`, …) and the package.json
branch resolves to `npm run dev` / `npm run start`.

**Decision:** set `scripts.dev` and `scripts.start` in the root `package.json`
to `/tmp/hermes-venv/bin/python -m hermes_cli.main dashboard --port 3000
--host 0.0.0.0 --insecure`. The launcher detects it and the dashboard takes
port 3000 in place of the previous `npx serve` directory listing.

**Why --insecure:** the dashboard refuses `0.0.0.0` without `--insecure`
because the embedded server has thin authentication on API key / config
endpoints. In a sandboxed preview the choice is between insecure-or-nothing,
and the iframe only renders content from `localhost:3000`.

**Why a venv path:** the system Python is PEP 668-locked and the dashboard
needs `fastapi` + `uvicorn` + `hermes-agent` itself. `pip install -e '.[web]'`
into `/tmp/hermes-venv` is the only path that works without `--break-system-packages`.

**Related:**
- [[entity-hermes-dashboard]]
- [[concept-productos-port-3000-supervisor]]