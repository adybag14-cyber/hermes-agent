# Hermes Agent v0.13.90

## Android

- Keep release builds unsigned when no release keystore is present, so F-Droid can copy the published APK signature onto its source build for reproducible verification.
- Preserves v0.13.89 OpenGUI-style standby dispatch support for remote operator payloads, task-name matching, dispatch metadata, and localized Device status.

## Validation

- `python -m pytest tests\hermes_android\test_android_followup_polish.py tests\hermes_android\test_android_auth_ui.py -q`
- `.\gradlew.bat :app:testDebugUnitTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.nousresearch.hermesagent.HermesAutomationInstrumentedTest#openGuiStyleRemoteDispatchRunsMatchingEnabledAutomation" -PskipHermesAndroidLinuxAssets=true :app:connectedDebugAndroidTest --stacktrace`
- `git diff --check`
