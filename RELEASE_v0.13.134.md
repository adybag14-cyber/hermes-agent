# Hermes Agent Fork v0.13.134

This release closes the chat tool-display gap, adds Alpine Linux one-click deploy on
the Device screen, bumps LiteRT-LM to 0.13.1, and expands Robolectric coverage for
sandbox/MCP/memory tool routing before publishing the next GitHub release.

## Android

- Aligns chat UI XML tool-call formatting with the native parser:
  `linux_sandbox_tool`, `mcp_run_in_proot`, `mcp_send_terminal_input`, and
  `memory_search` / `memory_add` / `memory_delete` / `memory_list` now render as
  readable "Tool call" blocks instead of raw XML.
- Adds **Deploy Alpine** on the Device Linux sandbox card; sandbox actions resolve
  distro id and container name from `HermesLinuxSandboxCatalog` instead of hardcoding
  Debian Bookworm.
- Bumps `com.google.ai.edge.litertlm:litertlm-android` from `0.12.0` to `0.13.1`.
- Adds `Gemma3LocalInferenceInstrumentedTest` for the small `gemma3-1b-it-int4.litertlm`
  artifact (skips when the model is not provisioned).
- Expands Robolectric tests for Alpine deploy prompts, memory aliases, and sandbox
  target resolution.

## Validation

- Android debug unit tests (including new tool-routing and chat-formatting cases).
- Hermes Android Python packaging/memory tests.
- Emulator validation on `HermesX86Api35`: Alpine sandbox deploy, tool UI display,
  Device button smoke, Qwen GGUF + Gemma4 LiteRT-LM instrumented paths.
- Screenshots: `verification-screenshots/v0.13.134/`.

## Release

- Publishes signed universal APK and AAB artifacts for package
  `com.mobilefork.hermesagent`.