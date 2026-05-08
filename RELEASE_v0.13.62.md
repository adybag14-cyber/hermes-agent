# Hermes Agent v0.13.62

This Android release expands safe Tasker XML import for Shizuku-backed device controls.

## Android

- Imported Tasker XML WiFi Tether actions with explicit on/off states as disabled-by-default Hermes Shizuku automation records.
- Imported Tasker XML Do Not Disturb actions for total silence, priority, alarms, and off modes through the existing Shizuku DND path.
- Kept ambiguous Tasker toggle/custom DND cases unsupported rather than guessing at destructive device state.
- Updated the Android capability map to include Wi-Fi tethering and Do Not Disturb in the safe Tasker XML import subset.

## Validation

- Focused JVM coverage for Tasker XML fixed Shizuku action import, including Wi-Fi tethering and Do Not Disturb.
- Full `HermesAutomationStoreTest` JVM coverage for automation import and saved-action behavior.
