# Hermes Agent v0.13.98

## Android

- Fixes Qwen Cloud setup so `/signin qwen` opens the API key documentation path before any account-login page.
- Stabilizes provider setup WebView teardown and fallback handling so stalled provider pages do not leave WebView resources attached.
- Keeps the provider setup instrumentation test isolated by intercepting the expected external browser intent instead of launching a real browser during CI.

## Validation

- `.\gradlew.bat :app:testDebugUnitTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- Emulator `emulator-5554`: `ProviderSetupWebActivityInstrumentedTest`
- Emulator `emulator-5554`: `DeepAppUiVisualInstrumentedTest#signinQwenCommandOpensSetupPageAndReloadsSettingsProviderProfile`
- `python -m pytest tests/hermes_android tests/hermes_android/test_android_packaging.py tests/gateway/test_api_server_toolset.py tests/gateway/test_api_server_android_toolset.py tests/tools/test_skills_sync.py tests/tools/test_web_tools_firecrawl_fallback.py tests/run_agent/test_run_agent_chatgpt_web.py -q`
- GitHub Actions Android run `25804547701`
