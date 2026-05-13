# Hermes Agent v0.13.99

## Android

- Shows the Hermes chat surface immediately on launch instead of blocking the Hermes tab behind backend warmup.
- Defers first-launch runtime warmup so Chaquopy/Python startup no longer competes with the first focus and typing path.
- Starts the runtime from the chat send path when needed, preserving local and remote provider replies after the UI becomes interactive.
- Lazily creates Android TextToSpeech only when speech playback is requested, removing another avoidable launch-time service cost.

## Validation

- `.\gradlew.bat :app:testDebugUnitTest -PskipHermesAndroidLinuxAssets=true --stacktrace`
- `.\gradlew.bat :app:assembleDebug -PskipHermesAndroidLinuxAssets=true --stacktrace`
- Emulator `emulator-5554`: fresh debug install, launch, tap composer, type `hi`; no new Hermes ANR, typed text visible.
- Emulator `emulator-5554`: `BootSmokeTest` and `NativeAgentRuntimeSmokeTest` via direct `adb shell am instrument` passed 5 tests.
