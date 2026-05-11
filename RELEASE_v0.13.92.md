# Hermes Agent v0.13.92

## Android

- Falls back to the saved remote provider when a selected local backend has no compatible preferred model, instead of blocking the app on the boot screen.
- Preserves remote provider settings when starting a local runtime from Settings, so one-tap local setup does not erase the user's remote fallback.
- Adds emulator regression coverage for the stale local-backend/no-model startup case found on device.

## Validation

- `.\gradlew.bat :app:testDebugUnitTest --tests com.nousresearch.hermesagent.backend.HermesRuntimeManagerTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `uv run pytest tests/hermes_android tests/hermes_android/test_android_packaging.py tests/gateway/test_api_server_toolset.py tests/gateway/test_api_server_android_toolset.py tests/tools/test_skills_sync.py tests/tools/test_web_tools_firecrawl_fallback.py tests/run_agent/test_run_agent_chatgpt_web.py -q`
- Emulator instrumentation:
  - `NativeAgentRuntimeSmokeTest#embeddedRuntimeFallsBackToRemoteProviderWhenSelectedLocalModelIsMissing`
  - `BootSmokeTest`
  - `NativeAgentRuntimeSmokeTest`
- Emulator visual check: launched `com.nousresearch.hermesagent/.MainActivity` after reinstall and confirmed Hermes chat opens without the backend-failed screen.
- `git diff --check`
