# Hermes Agent v0.13.72

## Android

- Adds a foreground-service-backed sensor watcher for saved Tasker-style
  Hermes sensor automations.
- `android_automation_tool` now exposes `sensor_watcher_status`,
  `start_sensor_watcher`, and `stop_sensor_watcher`.
- The watcher registers only supported Android sensor types declared by enabled
  saved records, persists its debounce interval, restarts after app recreation
  or boot, and feeds readings through the existing `run_sensor_trigger` path.
- Local tool-calling prompts now advertise the sensor watcher actions and the
  bounded `min_interval_ms` debounce argument so local Gemma-class models can
  choose the watcher path instead of only explicit sensor dispatch.

## Validation

- `:app:testDebugUnitTest --tests com.nousresearch.hermesagent.device.HermesAutomationStoreTest`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- Emulator install/launch smoke on `emulator-5582` with `versionName=0.13.72`
  and `versionCode=137290`
- Android visual harness wide screenshot and UIAutomator dump for the Hermes
  chat screen after launch
