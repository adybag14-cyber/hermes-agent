# Project wiki

Synthesised memory for `mojealterego/ODYN-AI` (NousResearch/Hermes-Agent
import). Updated after meaningful work — see `log.md` for the change log.

## Entities

- [[entity-hermes-dashboard]] — the `hermes dashboard` FastAPI server
  that fronts the React SPA and `/api/*` JSON / websocket routes.

## Concepts

- [[concept-productos-port-3000-supervisor]] — how the Blaxel preview
  supervisor picks which dev server runs on port 3000 and how to swap it.

## Decisions

- [[decision-hermes-dashboard-on-port-3000]] — use the root
  `package.json` `scripts.dev` to launch `hermes dashboard` on
  `:3000 --insecure` so the ProductOS preview iframe actually serves the
  Hermes Agent UI instead of an `npx serve` directory listing.

## Sessions

- (none yet)