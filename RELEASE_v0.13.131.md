# Hermes Agent Fork v0.13.131

This release repairs the F-Droid Linux asset failure from v0.13.130 and adds
native Android agent usability fixes from the emulator screenshot audit.

## Android

- Refreshes the pinned Termux Linux asset lock so F-Droid no longer downloads
  the removed `ca-certificates_1:2026.03.19_all.deb` package.
- Adds downloadable Linux sandbox catalog support for Alpine, Debian, Ubuntu,
  Fedora, Arch, Void, and openSUSE roots exposed to the Android agent.
- Improves XML tool-call and markdown display in the assistant page so raw tool
  envelopes render as readable tool-call blocks.
- Localizes the remaining MCP onboarding, model-download, navigation, and signal
  quick-action strings found in Chinese emulator screenshots.
- Adds simpler MCP setup controls for detect, auto fill, add draft server, auto
  setup, and test/refresh.

## Validation

- Re-ran full Android debug unit tests with Linux assets skipped.
- Re-ran focused chat formatting, native tool-calling, MCP settings, Linux
  sandbox, quick-action, and i18n Android unit tests.
- Re-ran provider endpoint and MCP transport Python regression tests.
- Re-ran Android Linux asset preparation from the updated Termux lockfile.
- Re-ran local release assembly and inspected APK metadata for versionCode
  `143190` and versionName `0.13.131`.
- Verified on emulator that `hermes-alpine` returns `x86_64` through
  `linux_sandbox_tool`, and that Chinese MCP/model-download settings are
  localized.

## Release

- Publishes signed universal APK and AAB artifacts for package
  `com.mobilefork.hermesagent`.
- Updates the F-Droid template and changelog for versionCode `143190`.
