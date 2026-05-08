# Hermes Agent v0.13.56

## Android Tasker/Shizuku parity

- Added `clear_app_data` as an explicit Shizuku/Sui app-management action for `android_system_tool` and saved `create_shizuku_action_task` records.
- Added safe aliases such as `pm_clear`, `clear_data`, and `clear_package_data`.
- Preserved the privilege boundary: the action only runs after the user starts Shizuku/Sui and grants Hermes access, and Hermes refuses to clear its own app data.

## Validation

- `:app:testDebugUnitTest --tests "com.nousresearch.hermesagent.device.HermesAutomationStoreTest.bridgeCreatesShizukuClearAppDataRecordsAndProtectsHermes" --stacktrace`
- `:app:testDebugUnitTest --tests "com.nousresearch.hermesagent.device.HermesAutomationStoreTest" --stacktrace`
- `:app:compileDebugKotlin :app:compileReleaseKotlin :app:assembleRelease --stacktrace`
