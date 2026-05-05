# Hermes Agent v0.13.11

Android/F-Droid reproducibility release.

## Android

- Builds the embedded Chaquopy runtime with Python 3.13 so GitHub release APKs match Debian 13/F-Droid builds.
- Emits a single universal release APK and disables Android dependency metadata for F-Droid scanner compatibility.
- Sorts generated Android Linux assets and native-library packaging inputs for deterministic release artifacts.

## Packaging

- Bumps the package version to 0.13.11.
- Updates the F-Droid metadata template for Android version code 131190.
