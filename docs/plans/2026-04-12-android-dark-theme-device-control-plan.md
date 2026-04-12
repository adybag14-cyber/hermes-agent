# Android Dark Theme + Device Control Expansion Plan

> For Hermes: use subagent-driven-development to implement this plan task-by-task.

Goal: fix the Android layout regressions visible in the latest screenshots, ship an eye-friendly near-black Hermes theme, and broaden Hermes Android device-control capabilities with safer system integrations for connectivity, notifications, background persistence, overlays, NFC/USB visibility, and resizable-window support.

Architecture: keep the existing Compose + Chaquopy shell, but refactor the page chrome so contextual actions no longer collide with Hermes chat, make scrollable pages inset-safe, upgrade the app theme to a Discord-like dark palette, and expand the Android bridge around a new system-capability controller plus a foreground runtime service.

Tech stack: Jetpack Compose Material3, Android foreground services + notification channels, Android settings intents/panels, Bluetooth/NFC/Wi-Fi/USB platform managers, existing `android_device_status` bridge/tooling, SharedPreferences-backed Android state, and Hermes local API server/runtime manager.

---

## Task 1: Fix shell/page layout ownership and contextual action collisions

Objective: stop the floating cog/action sheet from colliding with Hermes chat and make the sheet itself bottom-safe.

Files:
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/shell/AppShell.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/shell/ContextActionSheet.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/chat/ChatScreen.kt`
- Test: `tests/hermes_android/test_android_shell_navigation.py`
- Test: `tests/hermes_android/test_android_chat_ui.py`

Step 1: Suppress the shell FAB on the Hermes chat page and surface the same contextual actions inside the chat header instead.
Step 2: Keep the shell FAB for non-chat pages.
Step 3: Convert the contextual action sheet content to a scrollable, navigation-bar-safe layout.
Step 4: Add explicit tests asserting the shell FAB is not used for Hermes chat and the sheet uses safe bottom padding/scrolling.
Step 5: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_shell_navigation.py tests/hermes_android/test_android_chat_ui.py -q`

---

## Task 2: Make Settings and other long pages truly scrollable and inset-safe

Objective: remove screenshot-visible clipping and non-scrollable settings behavior.

Files:
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/auth/AuthScreen.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceScreen.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/portal/NousPortalScreen.kt`
- Test: `tests/hermes_android/test_android_auth_ui.py`
- Test: `tests/hermes_android/test_android_device_screen.py`
- Test: `tests/hermes_android/test_android_onboarding_and_portal.py`

Step 1: Add vertical scrolling to Settings.
Step 2: Remove duplicate in-body page titles where the shell header already supplies the page title.
Step 3: Add extra bottom clearance for long page content and the portal container so content can clear floating actions and bottom navigation.
Step 4: Add/update tests asserting scroll containers and layout-safe content structure exist.
Step 5: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_auth_ui.py tests/hermes_android/test_android_device_screen.py tests/hermes_android/test_android_onboarding_and_portal.py -q`

---

## Task 3: Replace the current light palette with a full Hermes dark theme

Objective: deliver an eye-friendly near-black theme inspired by Discord dark surfaces while keeping Hermes purple identity.

Files:
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/theme/HermesTheme.kt`
- Modify: `android/app/src/main/res/values/colors.xml`
- Modify: `android/app/src/main/res/values/themes.xml`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/MainActivity.kt`
- Test: `tests/hermes_android/test_android_branding.py`

Step 1: Define a dark Material3 color scheme with near-black background/surface tokens, brighter text contrast, and restrained accent colors.
Step 2: Apply dark system-bar colors and explicit system-bar appearance handling in `MainActivity.kt`.
Step 3: Keep the Hermes shell/components on theme by relying on scheme tokens instead of light-only assumptions.
Step 4: Add/update tests asserting the dark scheme and dark XML theme resources are present.
Step 5: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_branding.py -q`

---

## Task 4: Add Android system capability snapshot + actions bridge

Objective: broaden Hermes Android backend/device awareness beyond shared folders/accessibility.

Files:
- Create: `android/app/src/main/java/com/nousresearch/hermesagent/device/HermesSystemControlBridge.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/device/DeviceStateWriter.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/data/DeviceCapabilityStore.kt`
- Modify: `hermes_android/device_bridge.py`
- Modify: `tools/android_device_tool.py`
- Modify: `toolsets.py`
- Test: `tests/hermes_android/test_device_bridge.py`
- Test: `tests/gateway/test_api_server_android_toolset.py`

Step 1: Add a system-capability snapshot that reports Wi-Fi/internet state, Bluetooth support + permission status + bonded device count, NFC support/state, connected USB devices, notification permission state, overlay permission state, background persistence state, and resizable-window support state.
Step 2: Add a bridge action API for high-level settings/panel launches and runtime persistence actions.
Step 3: Expose the new action tool in the Android toolset and expand `android_device_status` payloads with the new state.
Step 4: Add/update tests for the Python bridge wrappers and Android toolset membership.
Step 5: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_device_bridge.py tests/gateway/test_api_server_android_toolset.py -q`

---

## Task 5: Add foreground notification + background runtime persistence service

Objective: let Hermes stay alive in the background with an ongoing notification and user-controlled persistence.

Files:
- Create: `android/app/src/main/java/com/nousresearch/hermesagent/backend/HermesRuntimeService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/HermesApplication.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/backend/HermesRuntimeManager.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceViewModel.kt`
- Test: `tests/hermes_android/test_android_device_screen.py`
- Test: `tests/hermes_android/test_android_packaging.py`

Step 1: Add a foreground service with a Hermes runtime notification channel and ongoing notification.
Step 2: Add manifest permissions/service declarations for foreground service + notifications.
Step 3: Add state and controls for enabling/disabling persistent runtime mode.
Step 4: Ensure runtime/device state refreshes reflect service state.
Step 5: Add/update tests for manifest/service strings and UI/runtime persistence hooks.
Step 6: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_device_screen.py tests/hermes_android/test_android_packaging.py -q`

---

## Task 6: Expand the Device page into a control center for phone interfaces + permissions

Objective: expose the new system capabilities through the Android UI in a realistic, policy-compliant way.

Files:
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceViewModel.kt`
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceScreen.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `tests/hermes_android/test_android_device_screen.py`
- Test: `tests/hermes_android/test_android_linux_device_screen.py`

Step 1: Add cards/sections for:
- connectivity (Wi-Fi/internet + Bluetooth)
- phone interfaces (USB + NFC)
- permissions + system access (notifications, overlay, accessibility)
- runtime persistence + multi-window/resizable support
Step 2: Add buttons that launch the correct Android settings/panel actions instead of promising impossible direct toggles where Android disallows them.
Step 3: Add runtime permission requests where needed (for example notifications and Bluetooth connect on supported API levels).
Step 4: Add/update tests asserting the new capability language and control affordances exist.
Step 5: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_device_screen.py tests/hermes_android/test_android_linux_device_screen.py -q`

---

## Task 7: Polish Hermes empty-state/chat composition for the new dark shell

Objective: make the Hermes home/chat page feel intentional once the shell FAB is removed there.

Files:
- Modify: `android/app/src/main/java/com/nousresearch/hermesagent/ui/chat/ChatScreen.kt`
- Test: `tests/hermes_android/test_android_chat_ui.py`

Step 1: Improve the empty state so it centers cleanly instead of leaving an awkward blank gap.
Step 2: Keep the composer inset-safe and visually separated from the bottom navigation.
Step 3: Ensure the new in-header Hermes actions remain discoverable.
Step 4: Add/update tests for the revised chat structure.
Step 5: Run:
- `source .venv/bin/activate && python -m pytest tests/hermes_android/test_android_chat_ui.py -q`

---

## Task 8: Run validation, review, commit, push, and release

Objective: thoroughly validate the Android upgrade, then ship the next test asset.

Files:
- Review changed Android files and tests
- Update docs if needed

Step 1: Run targeted Android tests:
- `source .venv/bin/activate && python -m pytest tests/hermes_android -q`
- `source .venv/bin/activate && python -m pytest tests/gateway/test_api_server_android_toolset.py tests/tools/test_android_linux_environment.py -q`

Step 2: Run grouped non-Android regressions if Android-facing code touched shared tooling:
- `source .venv/bin/activate && python -m pytest tests/tools tests/gateway tests/agent tests/run_agent tests/hermes_cli -q`

Step 3: Build/validate Android packaging:
- `cd android && ./gradlew :app:installDebugPythonRequirements :app:assembleDebug :app:testDebugUnitTest`

Step 4: Request independent review before commit.

Step 5: Commit with a focused message.

Step 6: Push the branch, monitor CI, create the next Android release tag/asset, and verify the release workflow uploads the APK + checksum.
