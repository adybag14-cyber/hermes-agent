# Hermes Agent v0.13.111

## Android and F-Droid

- Merges the latest Android release line back into `feat/termux-install-path`.
- Adds Hermes Agent F-Droid listing text, feature graphic, icon, and phone screenshots, including stripped image metadata and JPEG app markers.
- Keeps the Termux branch's Android asset cleanup and Unsloth Qwen3.5 tiny GGUF preset behavior after the release-line merge.
- Adds a signed Play verification APK workflow path for package ownership checks.

## Termux

- Keeps Termux update installs on the curated `termux-all` profile with Android psutil compatibility handling.
- Prefers the active project venv during update reinstalls and reduces noisy doctor output.
- Adds a one-shot Termux snapshot cleanup prompt after successful updates.
- Hardens the browser tool fallback path for Termux and Lightpanda.

## Validation

- `git diff --check`
- `python -m py_compile hermes_cli\main.py cli.py tools\browser_tool.py`
- `python -m pytest -q tests/hermes_cli/test_cmd_update.py tests/hermes_cli/test_update_autostash.py tests/hermes_cli/test_doctor.py tests/tools/test_browser_homebrew_paths.py tests/tools/test_browser_lightpanda.py tests/test_project_metadata.py tests/hermes_android/test_android_multimodal_model_selection.py tests/hermes_android/test_android_packaging.py tests/hermes_android/test_android_linux_asset_pipeline.py`
- `.\gradlew.bat :app:testDebugUnitTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat :app:assembleDebug -PskipHermesAndroidLinuxAssets=true --stacktrace`
