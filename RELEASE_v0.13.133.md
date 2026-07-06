# Hermes Agent Fork v0.13.133

This release addresses the Android feedback captured in the 2026-06-12 screenshot
bundle and validates the proot Linux sandbox, on-device models, and agent memory
flow before publishing the next GitHub release.

## Android

- Adds one-click Linux sandbox deployment (`linux_sandbox_tool action=deploy`) that
  installs Debian Bookworm, enables agent shell access, and refreshes packages.
- Adds domestic mirror switching for apt/apk sandboxes via
  `linux_sandbox_tool action=set_mirror` with `mirror_profile=china|aliyun|tsinghua`.
- Extends sandbox lifecycle controls: download, update, start, stop/close, and
  uninstall/remove with agent-shell gating.
- Adds MCP-style aliases `mcp_send_terminal_input` and `mcp_run_in_proot`.
- Improves chat UX from screenshot feedback:
  - keyboard hides while Hermes is thinking/running tools
  - message copy via action menu and selectable assistant text
  - edit/resend for sent user messages
- Adds a Device screen Linux sandbox card with deploy/update/start/stop/mirror/uninstall
  controls under app-private storage.
- Teaches the native tool agent not to claim `/system/bin/sh` cannot update when a
  proot sandbox is available; terminal update requests should use sandbox update/deploy.
- Expands HY Memory tool aliases (`memory_search`, `memory_add`, `memory_delete`,
  `memory_list`) and bumps bundled `hy-memory` to 1.2.18.

## Validation

- Android debug unit tests: 522 passed.
- Hermes Android Python packaging/memory tests: 23 passed.
- Emulator validation on `HermesX86Api35` (API 35, x86_64):
  - Installed debug APK `0.13.133` / versionCode `143390`.
  - Provisioned `Qwen3.5-0.8B-Q4_K_M.gguf` and `gemma-4-E2B-it.litertlm`.
  - `HermesHyMemoryBridgeInstrumentedTest`: retain/recall passed.
  - `NativeAppChatAndToolInstrumentedTest` (Qwen GGUF): local chat + tool execution passed.
  - `Gemma4LocalInferenceInstrumentedTest`: LiteRT-LM load + local answer passed.
  - Device UI: Linux sandbox card with one-click deploy, mirror controls, and app-private storage root verified.
  - Screenshots: `verification-screenshots/v0.13.133/` (+ `validation-summary.json`).

## Release

- Publishes signed universal APK and AAB artifacts for package
  `com.mobilefork.hermesagent`.