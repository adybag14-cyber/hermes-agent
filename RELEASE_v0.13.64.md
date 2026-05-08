# Hermes Agent v0.13.64

This Android release adds safe Tasker Custom Setting XML import for Shizuku-backed settings writes.

## Android

- Imported Tasker XML Custom Setting actions with explicit `system`, `secure`, or `global` namespace, setting name, and literal value as disabled-by-default Hermes Shizuku automation records.
- Reused the existing `set_custom_setting` Shizuku action path, so execution still requires user-started Shizuku/Sui and Hermes permission.
- Skipped Custom Setting reads, toggles, missing values, invalid namespaces, and invalid setting names rather than guessing at Tasker variable-return behavior.
- Updated the Android capability map to include Custom Setting writes in the safe Tasker XML import subset.

## Validation

- Focused JVM coverage for Tasker XML fixed Shizuku action import, including Custom Setting writes.
- Full `HermesAutomationStoreTest` JVM coverage for automation import and saved-action behavior.
