## Android Large-Font Chat Fixes

This Android release hardens the Hermes chat landing page and native local-model
prompt path for narrow phones, tall displays, and high system font settings.

- Reworks the Hermes chat composer so image, voice, message input, tips, and
  send controls no longer collapse into vertical text on large-font devices.
- Keeps bottom navigation and top-bar labels to one bounded line with ellipsis
  instead of splitting words such as Hermes, Accounts, or Settings.
- Makes the empty chat landing area scrollable so translated welcome copy and
  account/settings actions remain reachable on smaller screens.
- Compacts the native Android tool prompt while preserving Tasker-style
  automation, Shizuku/Sui device actions, shell, file, and UI control support.
- Replaces raw native context-window JSON failures with a readable local-model
  context warning and recovery guidance.

Validation:

- Focused Python Android runtime resilience checks for the compact prompt,
  friendly context error, and large-font layout guards.
- Android Kotlin compile, unit tests, debug APK build, and androidTest APK
  build with the Windows JDK 21 / Android SDK toolchain.
- Emulator visual instrumentation on normal display settings and a 720x1600
  large-font profile to verify chat, settings, model cards, and translated
  pages remain usable.
