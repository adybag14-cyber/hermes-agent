# Hermes Agent v0.13.69

## Android

- Adds a safe Locale-compatible Tasker action plugin entry point for running
  existing saved Hermes automations from Tasker.
- Includes a small Tasker configuration screen that returns a Locale plugin
  bundle and blurb for the selected Hermes automation.
- Protects the exported Tasker fire receiver with a per-configuration token so
  other apps cannot trigger Hermes automations by guessing an automation ID.
- Exposes `%TASKER_PLUGIN_AUTOMATION_ID` and `%TASKER_PLUGIN_CALLER_PACKAGE`
  while a Tasker plugin-triggered automation runs.
