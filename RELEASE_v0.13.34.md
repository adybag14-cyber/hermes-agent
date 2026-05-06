# Hermes Agent v0.13.34

This Android-focused release adds Hermes automation bundle backup and restore
for the native app's Tasker-style automation lane.

## Android Tasker Automation Bundles

- Adds `android_automation_tool` actions `export_automations` and
  `import_automations`.
- Export bundles include saved Hermes automation records and durable
  Tasker-style variables.
- Import validates action types, trigger types, automation ids, schedules,
  trigger payload JSON, and NUL-bearing payloads before writing records.
- Import can merge/upsert into existing records or replace the current saved
  automations and variables, then schedules enabled imported records.
- Keeps native Tasker XML/Data URI import and Tasker App Factory export listed
  as separate compatibility gaps.

## Validation

- Added unit coverage for export/import round trips, variable restoration, and
  invalid bundle rejection.
- Added connected Android coverage proving an imported file-write automation can
  run in the real app workspace with restored variables.
