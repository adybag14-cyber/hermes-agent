# Hermes Agent Fork v0.13.142

F-Droid reproducibility and LiteRT-LM currency release.

## F-Droid build reliability

- Serves every lock-pinned Termux package from one immutable, checksummed release archive instead of depending on rotating package mirrors.
- Verifies the archive SHA-256 and then verifies each embedded `.deb` against its existing package-level SHA-256 lock before extraction.
- Retains byte-verifying mirror fallback for temporary archive-host outages.
- Adds deterministic package-archive generation and regression tests for archive preference and repeatable output.
- Resolves the package-rotation failure that stalled the F-Droid auto-update merge request after version 0.13.137.
- Bounds Gradle to a 3 GiB heap and two workers so release compilation fits constrained local and CI build containers.

## LiteRT-LM

- Updates `com.google.ai.edge.litertlm:litertlm-android` from 0.13.1 to Google's current 0.14.0 release.
- Adds a CI and release gate that compares the exact Gradle pin with official Google Maven metadata so stale LiteRT-LM releases are detected automatically.

## Release validation

- Reproduces the F-Droid recipe locally with the official Debian Trixie build-server image.
- Exercises the Android compile, unit-test, instrumentation-compile, debug, release APK, and release bundle gates.

## Version

- 0.13.142 / versionCode 144290
- Package: com.mobilefork.hermesagent
