# Hermes Agent Fork v0.13.116

This release adds a ranked top-card planner for signal-aware Android agent
workflows and keeps the F-Droid metadata aligned for the next public build.

## Android

- Adds `agent_card_priority_report`, a Gemma-visible planner that ranks the
  next expandable diagnostic cards by priority, graph type, source action,
  refresh policy, and permission gate.
- Adds a Top Cards quick action so users can open the planner directly from the
  signal intelligence shortcuts.
- Adds Kai interactive parity rows for persistent memory, heartbeat visibility,
  provider fallback, tool/MCP bridge coverage, image context, generated-screen
  parity, and sandbox boundaries.
- Preserves the ranked planner rows through chat tool compaction and expandable
  diagnostic card parsing.
- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
