# Hermes Agent v0.13.86

## Android provider setup

- Reloads persisted Settings state when chat commands navigate into Settings, so `/signin qwen` immediately shows the Qwen Cloud / DashScope API-key provider profile instead of stale OpenRouter state.
- Adds emulator UI regression coverage for the `/signin qwen` command path and JVM routing coverage for Qwen, Z.AI, app-account sign-in, and rejected auth starts.
- Keeps Android automation terminology aligned with Tasker and Shizuku/Sui.

## Validation

- `python -m pytest tests/hermes_android/test_android_chat_commands.py tests/hermes_android/test_android_auth_ui.py -q`
- `.\gradlew.bat :app:testDebugUnitTest --tests "com.nousresearch.hermesagent.ui.chat.ChatCommandRouterTest" -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat "-Pandroid.testInstrumentationRunnerArguments.class=com.nousresearch.hermesagent.DeepAppUiVisualInstrumentedTest#signinQwenCommandReloadsSettingsProviderProfile" :app:connectedDebugAndroidTest --stacktrace`
