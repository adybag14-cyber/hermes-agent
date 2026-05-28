# Orboelectric Android Agent Runtime Spec

## Objective

Orboelectric is the Hermes Android control plane for on-device agents. It keeps
normal provider endpoints, custom OpenAI-compatible endpoints, local on-device
models, native Android tools, Linux terminal execution, overlays, and future MCP
servers behind one truth-preserving runtime contract.

## Runtime Lanes

- Chat lane: every model request receives the current user prompt plus bounded
  prior user/assistant turns, with custom instructions compacted into a system
  message before history.
- Native tool lane: local models use the same history contract, then call native
  Android tools for diagnostics, files, terminal, UI/accessibility, automation,
  memory, settings, and safe system intents.
- Linux lane: terminal commands run in the app-private Linux prefix when the
  packaged shell is executable, otherwise Android `/system/bin/sh` is used with
  an explicit fallback status.
- Overlay lane: persistent floating controls require Android draw-over-other-apps
  permission and must explain that permission in the current app language.
- MCP lane: native Hermes tools are available to all endpoint modes now; external
  Streamable HTTP MCP sessions, Context7, DeepWiki, Globalping, and similar
  servers require a future persisted MCP bridge with reconnect and credential
  redaction.

## Linux Package Contract

The Android app may expose packaged prefix commands from `PATH` when
`execution_mode=embedded_termux`. Installed package state is read-only inside
the signed APK/prefix until a signed package-feed bridge exists. User package
installation must be treated as future work unless Shizuku/root or a verified
download/install bridge is explicitly enabled by the user.

## Required Diagnostics

- `mcp_tool_server_registry_report` must disclose endpoint modes, Context7 test status,
  native-tool parity, external MCP gaps, and reconnect status.
- `terminal_tool` results must include shell path, execution mode, package count,
  and package-manager status.
- Raw tool JSON should be compacted in chat; detailed cards/graphs should remain
  available through diagnostic card rendering.
