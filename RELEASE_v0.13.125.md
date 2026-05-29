# Hermes Agent Fork v0.13.125

This release fixes the latest phone screenshot evidence where a Chinese
all-features prompt was still routed through the selected model instead of the
native Android diagnostic bridge.

## Android

- Routes Chinese all-features/self-test prompts such as `全部功能全测试` directly to
  `HermesDeviceDiagnosticsBridge`, matching the existing English all-features
  route.
- Prevents provider-generated hallucinated self-test tables from claiming
  `env_var_enabled` or Android bridge class-loading failures when the user asked
  for a real native tool test.
- Cleans collapsed markdown table syntax, emphasis markers, and inline-code
  ticks in chat bubbles so diagnostic output remains readable in the app and in
  translated screenshot shares.

## Release

- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
- Updates the F-Droid version template and changelog for versionCode `142590`.
