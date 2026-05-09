## Tasker Notification Progress

This Android release adds another safe Tasker 6.6.18 parity slice for Hermes
notification automations.

- Adds bounded notification progress fields to saved `create_notification_task`
  records: `progress_value`, `progress_max`, and `progress_indeterminate`.
- Adds `status_text` / `notification_status_text` for compact notification
  update labels while preserving the existing title, body, group, and action
  button support.
- Keeps notification posting behind Android's normal notification permission on
  Android 13+ and caps progress values so model-created updates cannot create
  unbounded notification payloads.

Validation:

- Focused JVM coverage for saved notification payload persistence and progress
  expansion.
- Android build and emulator smoke validation for the release APK.
