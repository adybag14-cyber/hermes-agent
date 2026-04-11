# Android Linux Command Subsystem Implementation Plan

> For Hermes: use subagent-driven-development skill to implement this plan task-by-task.

Goal: give the Hermes Android app a real local Linux command subsystem with `terminal` and `process` support backed by an app-private Termux-style command suite, so Hermes can execute local CLI commands in the APK runtime instead of relying on a narrowed mobile tool profile.

Architecture:
- Generate Android build assets from a curated Termux package set for `arm64-v8a` and `x86_64`.
- Extract that suite into app-private storage on first runtime boot.
- Add a dedicated `android_linux` backend which runs the extracted `bash` binary with the suite’s `PATH` and `LD_LIBRARY_PATH`.
- Surface suite state in Device UI and enable `terminal` / `process` in the Android toolset.

Tech stack:
- Existing Hermes terminal backend abstraction
- Android build-time asset generation
- Chaquopy embedded Python runtime
- Android Compose Device UI
- Curated Termux package subset + dependency closure

---

## Task 1: Add the research docs and Linux asset manifest helpers

Files:
- Create: `docs/plans/2026-04-11-android-linux-command-subsystem-research.md`
- Create: `docs/plans/2026-04-11-android-linux-command-subsystem-implementation-plan.md`
- Create: `hermes_android/linux_assets.py`
- Create: `tests/hermes_android/test_linux_assets.py`

Objective:
- Pin the Android/Termux arch mapping, curated root package list, ignored app-companion dependencies, and package-index parsing helpers.

Validation:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_linux_assets.py -q`

## Task 2: Build Android Linux suite assets from Termux packages

Files:
- Create: `scripts/prepare_android_linux_assets.py`
- Modify: `android/app/build.gradle.kts`
- Create: `tests/hermes_android/test_android_linux_asset_pipeline.py`

Objective:
- Download the curated Termux package set plus dependencies for each ABI.
- Extract package payloads into a generated Android asset tree.
- Preserve link intent in a manifest rather than exploding APK size by materializing every hardlink/symlink.
- Wire the asset-prep task into Android builds.

Validation:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_linux_asset_pipeline.py -q`
- `cd android && ./gradlew :app:prepareHermesAndroidLinuxAssets`

## Task 3: Add Python-side subsystem state loading and env injection

Files:
- Create: `hermes_android/linux_subsystem.py`
- Modify: `hermes_android/runtime_env.py`
- Modify: `hermes_android/bootstrap.py`
- Create: `tests/hermes_android/test_linux_subsystem.py`

Objective:
- Load the extracted Linux-suite state file from app-private storage.
- Export Android runtime env vars that point Hermes terminal execution at the extracted suite.
- Include Linux-subsystem metadata in bootstrap payloads.

Validation:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_linux_subsystem.py tests/hermes_android/test_runtime_env.py -q`

## Task 4: Add an `android_linux` terminal backend

Files:
- Create: `tools/environments/android_linux.py`
- Modify: `tools/terminal_tool.py`
- Create: `tests/tools/test_android_linux_environment.py`
- Modify: `tests/tools/test_terminal_tool_requirements.py`

Objective:
- Add a dedicated Android backend which runs commands through the extracted bash binary with prefix-first PATH + library env.
- Keep background process handling and cwd tracking compatible with existing terminal/process behavior.

Validation:
- `source .venv/bin/activate && python -m pytest tests/tools/test_android_linux_environment.py tests/tools/test_terminal_tool_requirements.py -q`

## Task 5: Add Android runtime extraction/state bridge

Files:
- Create: `android/app/src/main/java/com/nousresearch/hermesagent/device/HermesLinuxSubsystemBridge.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/backend/HermesRuntimeManager.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/device/DeviceStateWriter.kt`

Objective:
- Copy the generated Linux-suite assets into app-private storage.
- Recreate link entries from the manifest.
- Write subsystem state JSON for Python bootstrap and Device UI.

Validation:
- `cd android && ./gradlew :app:testDebugUnitTest`
- plus Android CI debug build

## Task 6: Enable terminal/process in the Android toolset

Files:
- Modify: `toolsets.py`
- Modify: `tests/gateway/test_api_server_android_toolset.py`
- Modify: `tests/hermes_android/test_android_packaging.py`

Objective:
- Enable real `terminal` + `process` access in `hermes-android-app`.
- Keep the existing direct shared-folder and UI-targeting tools.

Validation:
- `source .venv/bin/activate && python -m pytest tests/gateway/test_api_server_android_toolset.py tests/hermes_android/test_android_packaging.py -q`

## Task 7: Surface Linux command-suite state in Device UI

Files:
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceViewModel.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceScreen.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/settings/ToolProfileCard.kt`
- Create: `tests/hermes_android/test_android_device_screen.py`
- Create: `tests/hermes_android/test_android_linux_device_screen.py`

Objective:
- Show Linux-suite status (ABI, prefix path, bash path, package count).
- Tell users Hermes can now use `terminal`/`process` locally.
- Keep shared-folder and accessibility guidance intact.

Validation:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_device_screen.py tests/hermes_android/test_android_linux_device_screen.py -q`

## Task 8: Validate the whole Android command subsystem

Commands:
- `source .venv/bin/activate && python -m pytest tests/hermes_android -q`
- `source .venv/bin/activate && python -m pytest tests/tools/test_android_linux_environment.py tests/tools/test_terminal_tool_requirements.py -q`
- `source .venv/bin/activate && python -m pytest tests/ -q`
- `cd android && ./gradlew :app:prepareHermesAndroidLinuxAssets :app:testDebugUnitTest :app:assembleDebug`

Expected:
- targeted tests pass
- full suite passes
- Android debug build passes in CI

## Task 9: Push, watch CI, and ship the next alpha release

Commands:
- `git push fork feat/termux-install-path`
- `gh run list --repo adybag14-cyber/hermes-agent --branch feat/termux-install-path --limit 5`
- `gh run watch <push-run-id> --repo adybag14-cyber/hermes-agent --exit-status`
- `gh release create v0.0.1-alpha.6 --repo adybag14-cyber/hermes-agent --target feat/termux-install-path --title "Hermes Android v0.0.1-alpha.6" --notes "Android alpha.6: local Linux command subsystem"`
- `gh run watch <release-run-id> --repo adybag14-cyber/hermes-agent --exit-status`

## Risks / open questions

1. Some Termux packages may need extra path/shebang normalization when relocated.
2. Asset size must stay reasonable; link preservation is mandatory.
3. The first alpha may still be a curated command suite rather than literally every package users can install in Termux.
4. If future package-manager parity is required, this prefix architecture is still the right base to extend.
