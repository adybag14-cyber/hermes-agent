# Hermes Agent v0.13.74

## Android

- Adds a foreground-service-backed location watcher for saved Tasker-style
  location automations.
- `android_automation_tool` now exposes `location_watcher_status`,
  `start_location_watcher`, `stop_location_watcher`, and `scan_location`.
- The watcher uses Android `LocationManager` providers only after the user
  grants location access, persists provider/min-interval/min-distance settings,
  restarts after app recreation or boot, and feeds matches through the existing
  `run_location_trigger` path.
- Local tool-calling prompts now advertise the provider-backed location path so
  local Gemma-class models can choose scanned/watched location profiles instead
  of only explicit location dispatch.

## Validation

- `:app:testDebugUnitTest --tests com.nousresearch.hermesagent.device.HermesAutomationStoreTest.bridgeExposesProviderBackedLocationWatcherActions`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
