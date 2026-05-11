# Hermes Agent v0.13.91

## Android

- Keeps provider setup launches aligned with the app's direct-browser behavior, so Qwen Cloud and OpenAI setup checks no longer expect the old chooser-only path.
- Keeps native Android tool schemas available across sequential local-model turns, allowing a local Gemma 4 flow to write an HTML file and then request the browser-open tool on the next turn.
- Expands browser-tool inference for natural prompts such as "open it in the browser" after creating an HTML file.

## Validation

- `uv run pytest tests/hermes_android tests/hermes_android/test_android_packaging.py tests/gateway/test_api_server_toolset.py tests/gateway/test_api_server_android_toolset.py tests/tools/test_skills_sync.py tests/tools/test_web_tools_firecrawl_fallback.py tests/run_agent/test_run_agent_chatgpt_web.py -q`
- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- Emulator instrumentation:
  - `DeepAppUiVisualInstrumentedTest#signinQwenCommandOpensSetupPageAndReloadsSettingsProviderProfile`
  - `DeepAppUiVisualInstrumentedTest#signinOpenAiCommandOpensOpenAiSetupPageAndReloadsSettingsProviderProfile`
  - `NativeAgentToolAccessInstrumentedTest`
  - `NativeAppChatAndToolInstrumentedTest#nativeAppChatUsesGemma4AndEmbeddedToolsCanWriteWorkspaceFiles`
- GitHub Actions Android workflow: run `25687916461`
- `git diff --check`
