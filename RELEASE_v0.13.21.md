# Hermes Agent v0.13.21

This Android-focused release extends Hermes Agent's Tasker-style saved
automation actions beyond shell commands.

## Android

- Added saved file-write automation tasks.
- Added saved file-delete automation tasks.
- Added saved safe Android system-action automation tasks.
- Shared the app-workspace file safety checks between chat file writes and
  saved file automations.
- Extended `android_automation_tool` schema and model guidance for saved file
  and system-action tasks.
- Updated the Android capability map and settings tool profile for saved
  file/system automation support.

## Validation

- `python -m pytest tests\hermes_android -q`
- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.nousresearch.hermesagent.HermesAutomationInstrumentedTest" :app:connectedDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
