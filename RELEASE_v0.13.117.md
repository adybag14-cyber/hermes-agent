# Hermes Agent Fork v0.13.117

This release adds a Gemma-visible signal briefing deck for Android agent
diagnostics, keeping the ranked card planner and F-Droid metadata aligned for
the next public build.

## Android

- Adds `agent_signal_briefing_report`, a passive evidence deck that summarizes
  Wi-Fi, Bluetooth, sensors, radio, GPU/backend, MediaTek, and ranked top-card
  readiness for the chat agent.
- Adds signal briefing aliases and a Signal Briefing quick action so users can
  open the first-read diagnostic deck directly from the signal intelligence
  shortcuts.
- Adds top-card slot rows, metadata-key rows, and briefing directives that
  survive chat tool compaction and expandable diagnostic-card parsing.
- Extends tests for the diagnostics bridge, diagnostic card parsing, quick
  actions, and source-level Android chat integration checks.
- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
