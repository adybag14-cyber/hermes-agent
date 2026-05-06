# Hermes Agent v0.13.23

## Android

- Adds Tasker-style saved app-launch automations through `android_automation_tool`
  with `create_app_launch_task`.
- Supports Tasker-style variable expansion in saved package names so reusable
  tasks can launch `%APP_PACKAGE` or `{{APP_PACKAGE}}`.
- Exposes the app-launch automation capability in the Android settings tool
  profile and updates the Tasker/Shizuku capability map.

## Validation

- Android unit and instrumented coverage now includes saved app-launch task
  creation, safe missing-package handling, and launching the Hermes package on
  the emulator.
