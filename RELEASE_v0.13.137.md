# Hermes Agent Fork v0.13.137

Chat keyboard/scroll UX polish plus unreleased small-model catalog work after v0.13.136.

## Fixes

- Remove `imeNestedScroll` from chat so dragging the transcript near the composer no longer opens/resizes the keyboard.
- Dismiss keyboard on user scroll of the message list, history list, and empty-state surface.
- Clear focus when opening history, navigation, header actions, scroll-to-bottom, and send.
- Only auto-scroll the transcript when the user is already near the bottom or a send is in flight (no yank while reading older messages).
- Composer IME uses Send with sentence capitalization; keyboard Send respects attachments-only messages.
- Composer action tray closes when a send starts.
- Linux asset prep prefers packages-cf, sends a User-Agent, rejects empty bodies, and retries mirrors on SHA mismatch so release builds no longer fail on a stale Termux primary mirror.

## Also shipping (post-0.13.136 tip)

- Multi small-model smoke suite and catalog entries for MiniCPM / Qwen local models.
- Prior Gemma 4 E2B / MiniCPM / Qwen 1.5B LiteRT smoke paths remain available for debug validation.

## Validation

- `:app:compileDebugKotlin` successful with skip-linux-assets local gate.
- `:app:testDebugUnitTest` for `com.mobilefork.hermesagent.ui.chat.*` successful.

## Release

- Version: 0.13.137 / versionCode 143790
- Package: com.mobilefork.hermesagent
