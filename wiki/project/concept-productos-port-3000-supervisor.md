---
name: concept-productos-port-3000-supervisor
description: The Blaxel/ProductOS preview supervisor runs /home/user/.productos/preview-runtime-v1.sh in a restart loop. It only watches port 3000; the script exec's a single dev server based on which manifest file wins its menu of entrypoints.
metadata:
  type: concept
  scope: project
---

The supervisor (`sandbox-api`, PID 23 in this container) restarts the
launcher on every exit. The launcher is a bash script that walks a fixed
menu — `serve.py`, `manage.py`, `app.py`, `main.py`, `package.json`,
`composer.json`, `go.mod`, `frontend/index.html`, `index.html` — and `exec`s
the first match. Once `exec`d, it does not return. Port 3000 must stay
occupied by whatever process wins the menu; if it dies, the launcher
restarts, and a stale crash loops.

**How to redirect the preview** to a different command:
1. Edit the root manifest the menu picks (in this project: `package.json`'s
   `scripts.dev` / `scripts.start`).
2. Kill the launcher + its spawned dev server so the supervisor restarts it
   and re-reads the manifest. Target by PID, never `pkill node` (kills the
   engineering agent).
3. The launcher will hit `exec npm run dev`, which forks `sh -c` → the real
   command. Allow ~10s for the Python/FastAPI app to bind.

**Why the launcher matters:** because it `exec`s, a custom Python
`serve.py` would also work without touching `package.json` — only one of
the menu files needs to win the race.

**Related:** [[decision-hermes-dashboard-on-port-3000]]