# Hermes Agent v0.13.14

This release completes the Android reproducible-build fixes needed by the
F-Droid inclusion review.

## Android

- Canonicalizes Chaquopy `requirements-common.imy` before APK packaging.
- Removes local pip `direct_url.json` install paths from embedded Python assets.
- Rewrites embedded pyc bodies with a stable marshal format while preserving
  hash-based pyc headers.

## F-Droid

- Bumps the package version to 0.13.14.
- Updates the F-Droid metadata template for Android version code 131490.
