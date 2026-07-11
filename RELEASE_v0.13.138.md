# Hermes Agent Fork v0.13.138

Feature and integration release after systematic UX, hy-memory, and upstream-gap work on v0.13.137.

## Features

- **Kanban** board section (shared SQLite board: create / filter / comment / complete / unblock; `/kanban` slash).
- **Skills** browser in Settings (list + best-effort enable toggles via `skills_bridge`).
- **Phone automations** card on Device (list / enable / disable / run / delete).
- **Local memory (hy-memory companion)** Settings section: status, list, delete, clear-all.
- **Chat readiness strip**: backend / Python / memory counts without cold-starting runtimes.
- **Streamable HTTP MCP** quick-add (Gallery-style) with optional Authorization header.

## Fixes / polish

- Always inject promoted memory context; always expose `hy_memory_tool` to the native agent.
- Honest hy-memory metadata (android local companion, not fake package runtime claim).
- Empty-chat Signal tools collapsed by default (nav safety).
- Larger chat drawer hit target; Kanban waits for Python boot with retries.
- Termux `proot` lock updated to **5.1.107.84** (mirrors retired 5.1.107.83; fixes Android Release asset download SHA/404).

## Validation

- `:app:compileDebugKotlin` + unit tests (chat/shell/settings/memory) green.
- Emulator: Kanban navigation verified; IME open-on-scroll still fixed on 0.13.137 baseline.
- Skills bridge host smoke: list_skills returns ok with catalog skills.

## Release

- Version: 0.13.138 / versionCode 143890
- Package: com.mobilefork.hermesagent
