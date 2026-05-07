## Android Safe Tasker Import

This Android-focused release adds the first native Tasker import path for
Hermes Agent automation.

- Adds `import_tasker_xml` through `android_automation_tool`, with raw XML,
  `data:text/xml` URI, and base64 XML inputs.
- Converts a conservative safe subset of exported Tasker actions into Hermes
  automation records: Run Shell, Write File, Delete File, Launch App, Browse
  URL, and Notify.
- Imports Tasker variables into the Hermes automation variable table so saved
  file and shell actions can keep using `%VARIABLE` expansion.
- Leaves imported Tasker records disabled by default unless the caller
  explicitly sets `enable_imported`.
- Rejects unsupported/unsafe Tasker actions, NUL-bearing payloads, and XML
  `DOCTYPE`/`ENTITY` declarations, while reporting skipped Tasker actions in
  structured output.
- Adds JVM and connected emulator coverage for safe import, disabled-by-default
  records, variable expansion, and app-workspace file execution.
