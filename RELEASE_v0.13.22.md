# Hermes Agent v0.13.22

This Android-focused release extends Hermes Agent's Tasker-style saved
automation actions to visible UI controls through the existing accessibility
service boundary.

## Android

- Added saved accessibility UI-action automation tasks.
- Reused the live `android_ui_tool` bridge for saved click, long-click, focus,
  set-text, scroll, Back, Home, Recents, notifications, and quick-settings
  actions.
- Added `%NAME` and `{{NAME}}` expansion for saved UI selectors and text
  values.
- Extended `android_automation_tool` schema and model guidance for saved UI
  actions.
- Updated the Android capability map and settings tool profile for saved UI
  automation support.

## Validation

- `python -m pytest tests\hermes_android -q`
- `.\gradlew.bat :app:testDebugUnitTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.nousresearch.hermesagent.HermesAutomationInstrumentedTest" :app:connectedDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.nousresearch.hermesagent.BootSmokeTest,com.nousresearch.hermesagent.NativeAgentRuntimeSmokeTest" :app:connectedDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
