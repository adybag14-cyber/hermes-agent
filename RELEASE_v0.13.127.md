# Hermes Agent Fork v0.13.127

This release completes the Android shell, settings, MCP, and diagnostics polish
pass from the latest emulator verification.

## Android

- Replaces the bottom navigation banner with the top-left drawer navigation for
  Chat, Accounts, Portal, Device, and Settings.
- Adds a model configuration panel in Settings with Model configs and System
  prompt tabs, local/backend model knobs, accelerator controls, and an explicit
  API-provider generation knob toggle.
- Adds MCP configuration onboarding with simple auto-detect, auto-fill,
  auto-setup, advanced raw JSON edit, save/reload, server reload, and provider
  cache resend control.
- Adds persisted crash diagnostics, hard-crash preview/export controls, and
  PII-redacted diagnostics log export.
- Improves startup reliability by removing eager backend health probes, eager
  settings ViewModel creation, routine native state writes, and foreground
  accessibility automation loops.

## Validation

- Verified cold emulator launch, drawer navigation, Settings model tabs, MCP
  simple and advanced modes, provider cache resend, and Device diagnostics log
  export UI on `emulator-5554`.
- Ran `git diff --check`, `:app:assembleDebug`, and targeted
  `:app:testDebugUnitTest` coverage for API provider routing, chat/settings
  state, MCP persistence, crash logs, and drawer behavior.

## Release

- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
- Updates the F-Droid version template and changelog for versionCode `142790`.
