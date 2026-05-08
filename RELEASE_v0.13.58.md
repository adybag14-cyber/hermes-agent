## Android Tasker/Shizuku parity

- Added user-granted Shizuku/Sui Tasker Custom Setting actions for Android
  `system`, `secure`, and `global` settings get/set/delete operations.
- Added user-granted Shizuku/Sui Wi-Fi hotspot/tethering enable/disable
  actions.
- Exposed both slices through direct `android_system_tool` calls and saved
  `create_shizuku_action_task` automation records with run-time variable
  expansion.
- Kept the privilege boundary: settings and tethering actions only run after
  the user starts Shizuku/Sui and grants Hermes access.

## Validation

- `:app:testDebugUnitTest --tests "com.nousresearch.hermesagent.device.HermesAutomationStoreTest.bridgeCreatesShizukuCustomSettingAndTetheringRecords" --stacktrace`
- `:app:testDebugUnitTest --tests "com.nousresearch.hermesagent.device.HermesAutomationStoreTest" --stacktrace`
- `:app:compileDebugKotlin :app:compileReleaseKotlin :app:assembleRelease --stacktrace`
