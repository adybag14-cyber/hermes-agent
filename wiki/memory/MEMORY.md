# Project Memory

One durable fact per bullet — decisions, preferences, constraints, gotchas.
Newest at the bottom. Curate in place: update what changed, delete what is
wrong, merge duplicates, flag contradictions with ⚠️. Use ⛔ for hard
"never do this" rules. When this file passes ~200 lines, consolidate detail
into topic files in this folder and keep a one-line pointer here.
Detail pages: `<topic>.md` next to this file. Journal: `log.md` (append-only).
Never store secrets, API keys, or tokens here.

## Facts
- Task: Run this app. — Outcome: The Hermes Agent dashboard is running on http://localhost:3000. **What I found** - The repo is the [NousResearch/Hermes-Agent](https://github.com/NousResearch/hermes-agent) Python CLI (with a Vite `web/` dashboard and a Docusaurus `website/`). - The preview supervisor (`preview-runtime-v1.sh`) saw only the root `package.json` (no Node app of its own, no `dev` script) and fell through to `npx serve` — so port 3000 was serving a directory listing, not the app. **What I changed** 1. Built a venv at `/tmp/hermes-venv` (system Python is PEP 668-locked) and ran `pip install -e '.[web]'` — installed `fastapi==0.133.1`, `uvicorn==0.41.0`, `hermes-agent` (editable), `openai`, `anthropic`, `httpx`, `… #run-summary (fullstack-builder, 2026-10-02) <!-- productos-run:jxj88xhzhqjqkkbcxvpm7phx -->
