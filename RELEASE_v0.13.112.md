# Hermes Agent v0.13.112

## F-Droid

- Adds the Hermes Agent icon, feature graphic, and phone screenshots under `fastlane/metadata/android/en-US/images`, the source-repo path F-Droid scans from release tags.
- Keeps the existing F-Droid summary, description, and changelog files in Fastlane metadata.
- Bumps the Android release metadata to `versionName=0.13.112` and `versionCode=141290` so F-Droid's tag-based update check can supersede the already-published `v0.13.111` tag.

## Android

- Carries forward the 0.13.111 Android runtime and smoke-test fixes from `feat/termux-install-path`.

## Validation

- `git diff --check`
- `python -m pytest -q tests/test_project_metadata.py tests/hermes_android/test_android_packaging.py`
