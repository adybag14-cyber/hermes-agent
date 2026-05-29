# Hermes Agent Fork v0.13.123

This release supersedes v0.13.122 with a custom-instructions repair, stronger
local context recovery, and Android UI/icon fixes validated against the reported
phone screenshots.

## Android

- Moves saved custom agent instructions to the front of the native on-device
  system prompt so long tool policy text cannot bury the configured persona.
- Keeps the latest user request when local context-window recovery falls back to
  a compressed tool-heavy retry.
- Recognizes additional token/context-length overflow messages before retrying
  native local chat.
- Collapses Hermes bottom navigation while the Android keyboard is open and
  clears composer focus on send so the chat bar returns to the bottom cleanly.
- Moves the former floating Hermes action shortcut into the chat header actions,
  avoiding overlap with the composer and lower assistant cards.
- Cleans lightweight markdown emphasis and table separators in chat bubbles so
  diagnostic replies render as readable text instead of raw pipe tables.
- Replaces the square launcher icon with adaptive foreground, background,
  round, and monochrome resources for modern Android devices.

## Release

- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
- Updates the F-Droid version template and changelog for versionCode `142390`.
