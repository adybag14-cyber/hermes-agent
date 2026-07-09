# Hermes Agent Fork v0.13.135

This release polishes Settings/Device/Chat UX, adds local agent endpoint visibility
for ACP-style clients, improves MCP onboarding with runtime reload, expands i18n
coverage, and hardens Settings so opening the screen no longer cold-starts the
Python runtime.

## Android UX / UI

- **Settings → Agent endpoint card**: shows loopback + optional LAN base URL, model
  name, and masked API key with copy/refresh actions (`AgentEndpointCard`).
- **Settings → MCP quick add**: native-tools preset, stdio, and SSE one-tap flows
  with immediate `McpRuntimeBridge` reload into the Python runtime.
- **Device → Linux sandbox / diagnostics**: full ES/DE/FR/PT/ZH labels for sandbox
  actions; diagnostics crash export card uses localized strings.
- **Chat**: message action menu (Copy / Edit / Resend) localized; quick-prompt
  send guarded when a draft or attachments are present (signal quick actions).
- **Composer / signal intelligence**: expanded quick-action test coverage and
  chat-ui emulator validation harness for expanded mode.

## Runtime / performance

- Local API server bind defaults to `0.0.0.0` for optional LAN clients; status
  payload keeps **loopback** `http://127.0.0.1:<port>` for on-device tools.
- Status also exposes optional `lan_base_url` when a non-loopback IPv4 is known.
- Android MCP JSON config syncs into Hermes `config.yaml` via
  `hermes_android.mcp_bridge` on server ensure and Settings reload.
- **Settings open no longer calls `ensureStarted`**: passive endpoint snapshot uses
  `HermesRuntimeManager.currentState()`; explicit Refresh still force-starts.

## Validation

- Android debug unit tests (quick-prompt guard, diagnostics cards, signal quick
  actions, i18n, device sandbox i18n).
- Python: `tests/hermes_android` including `test_mcp_bridge.py` and runtime_env
  host/LAN URL expectations.
- Emulator chat-ui validation for v0.13.135 quick actions on `HermesX86Api35`.

## Release

- Version: `0.13.135` / versionCode `143590`
- Package: `com.mobilefork.hermesagent`
- Publishes signed universal APK and AAB via the Android Release tag workflow.
