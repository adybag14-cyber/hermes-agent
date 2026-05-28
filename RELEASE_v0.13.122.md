# Hermes Agent Fork v0.13.122

This release supersedes v0.13.121 with Android chat memory fixes, keyboard
visibility polish, compact tool output, terminal crash recovery, and clearer
MCP/Context7 and Orboelectric runtime diagnostics.

## Android

- Sends bounded prior user/assistant turns to custom OpenAI-compatible endpoints
  and native local model requests so follow-up prompts can recall chat history.
- Keeps saved custom agent instructions compacted ahead of history for endpoint
  and on-device model requests.
- Keeps the composer visible above the Android soft keyboard, removes the extra
  non-error runtime banner below the header, and keeps the floating action
  button persistent in the chat surface.
- Adds draw-over-other-apps overlay permission guidance through localized
  floating action accessibility text.
- Compacts raw native tool JSON before it reaches chat replies while keeping
  graph/card diagnostics available.
- Fixes a Python `file_mutation_result_landed` import crash that could make
  terminal/tool execution fail.
- Routes Android terminal commands through the packaged Linux shell when
  available, reports package-prefix status, and documents the Orboelectric
  Android agent runtime contract.
- Adds MCP endpoint-mode and Context7 diagnostic rows so the app distinguishes
  native tool support from future external Streamable HTTP MCP sessions.

## Release

- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
- Updates the F-Droid version template and changelog for versionCode `142290`.
