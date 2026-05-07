## Android Tasker Logcat Automation

This Android-focused release adds the next Tasker/Shizuku automation slice for
Hermes Agent.

- Adds saved Tasker-style `logcat_entry` trigger records through
  `android_automation_tool`.
- Supports bounded logcat filters for tag/component, message substring, level,
  pid, package, and `trigger_package_name`.
- Adds `run_logcat_entry_trigger` explicit dispatch for tests and future
  Shizuku-backed watchers.
- Exposes logcat run variables: `%LOGCAT_TAG`, `%LOGCAT_MESSAGE`,
  `%LOGCAT_LEVEL`, `%LOGCAT_PID`, `%LOGCAT_PACKAGE`, `%LOGCAT_TIME`, plus
  `LOG_*` aliases.
- Keeps the background logcat watcher permission-honest: Shizuku/Sui must be
  user-started and granted before a future watcher can feed these records.
