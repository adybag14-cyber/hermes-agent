---
title: Android Release Pipeline
---

# Android Release Pipeline

Hermes Android release assets are produced by the tag-push GitHub Actions workflow. The
general `scripts/release.py` command is the Python/CalVer release tool; it creates
`vYYYY.M.D` tags and must not be used for Android `v0.*` releases.

Release flow:

1. Update the Android/F-Droid version metadata, release notes, and Fastlane changelog for the intended `v0.x.y` tag.
2. Commit the complete headed-device release evidence for that exact clean source digest and candidate APK pair.
3. Push the candidate and default branches, and require the Android workflow to succeed at the exact evidence commit on both refs. The default branch must still point exactly at that evidence commit when the tag is pushed; the release workflow fetches the repository's configured default branch and fails before signing if the two commits differ.
4. Create an annotated `v0.x.y` tag that resolves exactly to the evidence commit, then push only that tag. Do not create or publish a GitHub release manually.
5. The tag push triggers `.github/workflows/android-release.yml`. Publishing or editing a GitHub release does not trigger a second Android build.
6. CI verifies the tag/source identity and committed headed-device release evidence, then builds signed Android artifacts:

   - release APK
   - release AAB

7. `scripts/android_release_manifest.py` renames artifacts and emits SHA256 files.
8. GitHub Actions creates or updates the matching release as a draft, uploads the APK, AAB, and checksum files, and verifies the complete asset manifest.
9. The workflow publishes the release only after the signed artifacts and checksums pass verification.

## Published-latest compatibility monitoring

Release and F-Droid builds continue to use the exact `liteRtLmStableVersion` pin in `android/app/build.gradle.kts`. The normal Android and Android Release workflows fail if that release pin no longer matches Google Maven metadata.

`.github/workflows/android-published-latest-compatibility.yml` runs daily and on manual dispatch. It resolves the exact `<release>` version from Google's published Maven metadata and supplies that version through the existing `hermesLiteRtLmVersion` Gradle override while compiling and testing Hermes. Only after that compatibility build completes does the workflow run the normal strict pin check, so a stale stable pin makes the lane visibly fail without preventing the published-latest compatibility attempt. The workflow reports the unchanged release pin separately; it never rewrites the pin.

This lane proves compatibility with Google's published-latest Maven artifact. It is not an upstream-main or nightly-AAR lane, because Google does not publish a nightly Android Maven AAR for LiteRT-LM. The same workflow reports Termux llama.cpp package drift as advisory information. That drift report does not replace or invalidate the content-addressed real-GGUF release compatibility evidence.

Required GitHub secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Local files which must stay untracked:

- `android/keystore.properties`
- `android/release.keystore`
- `android/local.properties`

The Android build reads the checked-in Android/F-Droid version metadata. A semantic
Android tag such as `v0.13.148` maps to the repository's monotonic semantic
`versionCode` formula; legacy CalVer tags retain their separate date-based mapping.
