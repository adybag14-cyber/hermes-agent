# Native Android fork

Use this reference for the Android APK in `adybag14-cyber/hermes-agent`.
The APK, the Termux CLI, and upstream's Electron desktop app are separate
surfaces. Keep the user's chosen surface; do not substitute a Termux setup
for an APK problem.

## Build authority

Work from the fork checkout, not the installed skill directory. Read the
current `android/README.md`, Android build files, and Android workflows
before choosing versions or commands. JDK 21 is the established build
baseline; Python, SDK, NDK, and native-backend versions must match the
current build contract rather than an older release's instructions.

Set `JAVA_HOME`, `PYTHON_FOR_BUILD`, and the SDK locator for that toolchain.
Keep local SDK paths and signing credentials out of commits. Windows-local
compilation is development feedback; reproducibility evidence requires the
pinned Linux buildserver and a native Linux source filesystem.

Run Python checks through the repository wrapper, with a worker limit
appropriate to the machine:

```bash
bash scripts/run_tests.sh tests/hermes_android tests/tools/test_skills_sync.py
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

On native Windows, use Git Bash for the Python wrapper and `gradlew.bat`
for Gradle. Check available tasks when the build contract changes.
Do not omit Python requirements, native libraries, or runtime assets from
the final candidate to make a compilation-only check pass. Keep generated
pip-stub metadata and intermediate build directories out of source changes.

## Device and model evidence

Use the requested device or emulator and its explicit ADB serial. Determine
the installed package, instrumentation runner, and current test classes
from the candidate/source; do not reuse package names from historical notes.
Installation or replacement of a personal-device app requires task scope
that authorizes it, and must preserve app data unless removal was requested.

For UI changes, exercise chat typing, settings/model selection, navigation,
translations, and the affected native tool, recording screenshots from the
actual candidate. For local inference, verify each claimed model/backend
combination with the actual artifact. File extensions and catalog presence
are not runtime proof: a web-oriented `.task` file need not load in Android.
Test image understanding with a genuinely multimodal model; text-only
models must reject unsupported image requests clearly.

For `Unable to open zip archive`, inspect the artifact's actual header and
the selected loader. `TFL3` identifies a TFLite FlatBuffer, not the ZIP-style
bundle expected by that LiteRT-LM load path. Use the artifact declared for
the selected backend; renaming a web `.task` file does not convert it.

For `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, verify package and signer identity.
Use a correctly signed candidate/update; publish a patch release only when
that is requested. Do not uninstall the existing app to bypass the mismatch.

Use app-scoped storage or the current scoped-storage-safe provisioning path.
Watch both emulator data-space and RAM limits when testing large models.
Remove only fixtures owned by the current run or files the user explicitly
authorized removing; do not broadly clear model caches or app data.

## Release boundary

Use the current Android release workflow and repository F-Droid contract.
Keep source/build identities, versions, signer checks, and device evidence
bound to the candidate being published. Prior release notes are historical
evidence, not certification of a new build.

After a requested GitHub release is public, completion also requires two
independent gates: a fresh update-server check must discover that public
release, and a fresh pinned F-Droid buildserver must reproduce the public
APK according to the current comparison contract. A candidate container,
local APK hash, or green compile does not substitute for either gate.
Do not submit a fdroiddata merge request unless the user requests it.
