# Hermes Agent v0.13.60

This Android release extends safe Tasker XML import for Shizuku-backed fixed actions.

## Android

- Imported Tasker XML Turn Off, Airplane Mode, Wi-Fi, Mobile Data, and End Call actions as disabled-by-default Hermes Shizuku automation records.
- Preserved the existing Shizuku/Sui permission boundary: imported records still require the user to start Shizuku/Sui and grant Hermes Agent access before execution.
- Updated the Android capability map so Tasker XML support distinguishes fixed safe imports from unsupported plugins, arbitrary code, and full Tasker scene graphs.

## Validation

- Focused JVM coverage for Tasker XML fixed Shizuku action import.
- Full `HermesAutomationStoreTest` JVM coverage for automation import and saved-action behavior.
