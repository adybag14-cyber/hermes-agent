# Hermes Agent v0.13.83

## Android provider setup

- Adds one-tap official key-page buttons for supported remote providers in Android Settings.
- Makes Qwen a direct Qwen Cloud / DashScope API-key provider, with `qwen3.6-plus` as the suggested remote model and `DASHSCOPE_API_KEY` synced into the Android auth bridge.
- Keeps Qwen OAuth available as an advanced provider preset instead of using it as the default setup path.
- Clarifies Corr3xt app sign-in failures separately from runtime-provider API-key setup failures.

## Validation

- `python -m pytest tests/hermes_android -q`
- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- Emulator Settings validation for OpenRouter and Qwen Cloud / DashScope provider setup
