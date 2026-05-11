# Hermes Agent v0.13.93

## Android

- Carries the v0.13.92 local-backend fallback fix for phones with stale or missing preferred local models.
- Keeps the tracked project and F-Droid version metadata aligned with the Android release tag, so F-Droid `checkupdates` can detect the current release correctly.

## Validation

- `python -m pytest tests/hermes_android tests/hermes_android/test_android_packaging.py tests/gateway/test_api_server_toolset.py tests/gateway/test_api_server_android_toolset.py tests/tools/test_skills_sync.py tests/tools/test_web_tools_firecrawl_fallback.py tests/run_agent/test_run_agent_chatgpt_web.py -q`
- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `git diff --check`
