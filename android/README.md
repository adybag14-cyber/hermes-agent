# Hermes Agent for Android

Hermes Agent ships as a native Android app as well as the separate Termux CLI.
The app embeds Hermes, can connect to remote OpenAI-compatible providers, and
can run supported local models through either LiteRT-LM or llama.cpp.

The published F-Droid package ID is `com.mobilefork.hermesagent`:

- [F-Droid](https://f-droid.org/packages/com.mobilefork.hermesagent/)
- [GitHub releases](https://github.com/adybag14-cyber/hermes-agent/releases)

Do not install packages named `org.woheller69.hermesagent`; that is not this
repository's application ID.

## Platform and artifact matrix

| Area | Supported value |
| --- | --- |
| Minimum Android version | Android 7.0 / API 24 |
| Compile and target SDK | API 35 |
| Release ABIs | `arm64-v8a` for devices and `x86_64` for emulators |
| Embedded Python | Chaquopy 17 with Python 3.13 |
| Java bytecode | Java 17 |
| Release artifacts | One universal APK and one Android App Bundle |
| F-Droid update source | Signed Git tags, via `fdroid/com.mobilefork.hermesagent.version` |

The release APK is intentionally universal. There are no 32-bit ABI splits and
there is no Play-only build.

## First run

1. Install the APK from F-Droid or the matching GitHub release.
2. Open **Settings > Provider and model** to connect a remote provider, or open
   **Settings > Local models** to import/download an on-device model.
3. Choose **LiteRT-LM** for a `.litertlm` Android bundle or **llama.cpp** for a
   `.gguf` file. A browser-only FlatBuffer renamed to `.task` is not a valid
   Android LiteRT-LM bundle.
4. Start the selected local backend and wait for the health and completion
   checks. If initialization fails, use the status text and diagnostics rather
   than repeatedly retrying a model which exceeds available memory.
5. Enable only the tool profiles you intend the agent to use. A model must have
   compatible function/tool-calling training; describing a command in prose is
   not the same as emitting a tool call.

Large local models need substantially more free memory than their file size.
Hermes checks current memory headroom before starting, but Android can still
reclaim a process when another app, the GPU driver, KV cache, or model-native
buffers consume the remaining RAM. Start with the smallest certified model for
your backend and close other memory-heavy apps before moving up.

## Local-model release certification

A model is called compatible only after a headed, hardware-accelerated device
run records all of the following: exact repository/revision/file/byte size,
device-visible byte size, selected backend, runtime health, a non-empty real
completion, and elapsed time. Merely downloading a file or launching a server
does not prove inference works.

The Android test lane contains fixtures for these small-model families:

- Qwen3.5 0.8B Q4_K_M GGUF (llama.cpp)
- MiniCPM5 1B Fable5 Q4_K_M GGUF (llama.cpp)
- MiniCPM5 1B LiteRT-LM
- VibeThinker 3B LiteRT-LM on the larger model-test AVD

Check the release notes for the exact files that passed the current release;
the catalog can expose experimental or user-selected models which have not
passed that release matrix.

The managed llama.cpp chat backend accepts single-file GGUF v2/v3 artifacts
whose metadata includes an architecture, tensors, and an embedded
`tokenizer.chat_template`. Split shards and base GGUFs without an embedded chat
template are rejected with an actionable message instead of being reported as
ready. They can be supported later through an explicit user-selected/family
template path, but they are outside this release's chat-ready compatibility
contract.

## LiteRT-LM stable and upstream-preview builds

Normal and F-Droid builds use the exact `liteRtLmStableVersion` declared in
`app/build.gradle.kts`. The live CI guard compares that pin with Google Maven
and rejects stale or dynamic release dependencies.

Google does not currently publish an Android nightly Maven coordinate. To test
a newly published exact preview version without changing the release default:

```powershell
./gradlew.bat :app:compileDebugKotlin -PhermesLiteRtLmVersion=0.16.0
```

To test an Android AAR built locally from LiteRT-LM `main`:

```powershell
./gradlew.bat :app:compileDebugKotlin `
  -PhermesLiteRtLmLocalAar=D:\path\to\litertlm-android-main.aar
```

The version override must be one exact version. `latest.release`, `+`, and
other moving dependency selectors are rejected for the release contract.

## Building on Windows

Use Android Studio's JBR and a real CPython 3.13 executable. Do not let Gradle
resolve an unrelated `python.exe` or an old Java installation from `PATH`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PYTHON_FOR_BUILD = `
  'C:\Users\you\AppData\Local\Programs\Python\Python313\python.exe'

Set-Location android
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written beneath `android/app/build/outputs/apk/debug/`.
Release builds additionally need `android/keystore.properties` and the
corresponding signing keystore; neither belongs in Git.

For a disposable Windows build which avoids Chaquopy ACL/path problems, use the
repository's pinned F-Droid Debian buildserver container described under
`../fdroid/`. F-Droid reproducibility certification must use the pinned image,
fdroidserver commit, source tag, and dependency lock—not a host-only Gradle
build.

## Emulator validation

UI, translation, accessibility, and local-model certification use a visible
API 35 `x86_64` AVD with host GPU acceleration. Keep separate snapshots for:

- a 2 GB phone profile for UI and small-model tests;
- a 6 GB-or-larger profile with a 24 GB data partition for the large LiteRT
  fixture;
- compact-phone and tablet window sizes for responsive-layout checks.

Before trusting a run, verify `emulator -accel-check`, wait for
`sys.boot_completed=1`, record the emulator/QEMU command lines, and capture the
app's UI tree, screenshots, frame timing, and memory use. Tests which skip
because a model file is missing are not release evidence.

### Committed release-evidence gate

The release workflow does not run an emulator. It verifies evidence captured
locally from the exact headed, hardware-accelerated AVD candidate and stops
before signing if that committed evidence is absent, incomplete, stale, or for
a different source tree/tag. A successful instrumentation compile is not
device certification.

Release evidence is deliberately a two-commit operation. First commit every
source, test, workflow, metadata, and documentation change. With that source
commit checked out, obtain the identity embedded into the headed debug
candidate and build both APKs from the same process environment:

```powershell
$tag = 'v0.13.147'
$sourceLine = python scripts/android_release_evidence.py source-identity --require-clean |
    Select-String '^sourceDigest='
$sourceDigest = $sourceLine.Line.Substring('sourceDigest='.Length)
$env:HERMES_RELEASE_TAG = $tag
$env:HERMES_SOURCE_DIGEST = $sourceDigest
$env:PYTHON_FOR_BUILD = Join-Path $env:LOCALAPPDATA 'Programs\Python\Python313\python.exe'
Push-Location android
.\gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
Pop-Location
$candidateApk = Resolve-Path android/app/build/outputs/apk/debug/app-debug.apk
$testApk = Resolve-Path android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$candidateSha = (Get-FileHash $candidateApk -Algorithm SHA256).Hash.ToLowerInvariant()
$testSha = (Get-FileHash $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
$runId = "v0.13.147-$($sourceDigest.Substring(0,16))-20260812"
```

The Gradle configuration recomputes the committed source identity, requires a
clean tree, rejects `hermesLiteRtLmLocalAar` and non-release LiteRT-LM versions,
and fails if the supplied digest differs. Install those exact two APKs with `adb install -r
--no-streaming`. Every evidence-producing instrumentation invocation must pass
the same binding arguments (substitute the active serial and AVD name):

```powershell
$bind = @(
    '-e', 'release_source_digest', $sourceDigest,
    '-e', 'candidate_apk_sha256', $candidateSha,
    '-e', 'instrumentation_apk_sha256', $testSha,
    '-e', 'evidence_run_id', $runId,
    '-e', 'device_serial', 'emulator-5570',
    '-e', 'avd_name', 'Medium_Phone_API_35'
)
adb -s emulator-5570 shell am instrument -w -r @bind `
    -e class 'com.mobilefork.hermesagent.DeepAppUiVisualInstrumentedTest#allSixLanguagesSwitchAcrossModelToolsKanbanAndDeviceCards' `
    'com.mobilefork.hermesagent.test/androidx.test.runner.AndroidJUnitRunner'
```

The test refuses an unbound build, independently hashes the installed app and
instrumentation APKs, and writes those identities plus the source digest,
shared run ID, package/version/build, serial, AVD, and build fingerprint into
every semantics and model record. It verifies the AVD against
`ro.boot.qemu.avd_name` and records the executing kernel boot UUID, so a
look-alike emulator cannot be substituted through instrumentation arguments.
Model invocations must additionally set
`require_model=true` and the exact content-addressed model arguments documented
above; a skip is a failed release gate.

After both profile runs, arrange the retrieved device files in this exact
layout (repeat `screen.png` and `semantics.txt` for `en`, `zh`, `es`, `de`,
`pt`, and `fr` under both UI profiles):

```text
android/release-evidence/<tag>/
├── ui/
│   ├── phone-compact/<language>/{screen.png,semantics.txt}
│   └── tablet/<language>/{screen.png,semantics.txt}
├── performance/
│   ├── phone-compact.json
│   ├── phone-compact.raw.json
│   ├── tablet.json
│   └── tablet.raw.json
├── models/<registered-model-id>.json
└── manifest.json                 # emitted by the create command
```

The performance records use schema
`hermes-android-performance-evidence-v1` and include emulator/AVD/build/GPU
identity, the usable acceleration check and headed `-gpu host -accel on` launch
command plus its SHA-256, screen px/dp/density, cold and warm launch times, at
least 100 frames with p50/p90/p95/p99 and jank counts, and total PSS/RSS. The
model files are the unaltered `hermes-model-evidence-v1` JSON records retrieved
from `files/hermes-model-evidence/`. There must be exactly one passing record
for every current `VerifiedLocalModelArtifacts.releaseMatrix` entry.

Use the compact-phone AVD for `performance/phone-compact.json` and the
large-memory tablet AVD for `performance/tablet.json`; all model evidence must
come from one of those exact measured serial/AVD/fingerprint records. Capture
the live QEMU command line, not a remembered launch command. The validator
requires a positive accelerator result, one effective `-gpu host`, one
effective `-accel on`, a headed window, at least 100 rendered frames, bounded
launch/frame/jank results, `PSS <= RSS`, and compact/tablet PSS/RSS ceilings of
512/768 MiB and 768/1024 MiB respectively. It fully decodes screenshot pixels,
rejects blank/reused captures, and requires localized Device/Overview plus the
correct drawer-versus-persistent-rail semantics for each profile.

#### Live performance collector

Keep the same exact APKs, `$sourceDigest`, and `$runId` used by instrumentation
installed on the active headed AVD. Start that AVD with one explicit console
port, `-gpu host`, and `-accel on`, then collect each profile while the same
emulator boot remains alive:

```powershell
# Run this headed launch in its own terminal for the compact-phone lane.
emulator -avd Medium_Phone_API_35 -port 5570 -gpu host -accel on

$serial = 'emulator-5570'
$profile = 'phone-compact' # repeat with the tablet serial and 'tablet'
python scripts/android_collect_performance_evidence.py `
    --serial $serial `
    --profile $profile `
    --release-source-digest $sourceDigest `
    --candidate-apk-sha256 $candidateSha `
    --instrumentation-apk-sha256 $testSha `
    --evidence-run-id $runId `
    --version-name '0.13.147' `
    --version-code 144790 `
    --litertlm-coordinate 'com.google.ai.edge.litertlm:litertlm-android:0.16.0' `
    --output "android/release-evidence/$tag/performance/$profile.json"
```

The collector requires a clean committed source tree outside
`android/release-evidence/` and recomputes its digest before touching the
device. It verifies the exact adb serial, observed AVD name, fingerprint, API,
ABIs, boot UUID, installed app/test APK hashes, and installed version. On the
host it resolves exactly one matching live `qemu-system-*` process through
Windows CIM and records its actual PID and command line. It also requires a
successful `emulator -accel-check`, a hardware SurfaceFlinger renderer, and a
headed command containing exactly one effective `-gpu host` and `-accel on`.

For the measurements it records effective `wm` size/density and cold/warm
`am start -W` timings. The warm lane captures the Hermes PID after the cold
launch, sends `KEYCODE_BACK`, proves that the same nonblank process PID remains,
and only then relaunches the activity; a killed or replaced process is rejected.
If Android returns the transient `UNKNOWN` launch state with `TotalTime: 0`
and one bounded nonnegative `WaitTime` of at most 1000 ms, the collector
records that result, rechecks the same PID across one more
`KEYCODE_BACK`, and permits exactly one recorded warm-start retry. The retry
must report `WARM` or `HOT` with positive timings; `UNKNOWN` is never accepted
as performance evidence.

Both the initial and final device identity require Android's system
`font_scale` to be exactly `1.0`; the normalized performance record and every
language/profile semantics header must agree. The collector also proves that
`com.mobilefork.hermesagent/.MainActivity` is the single resumed activity
immediately before the frame reset and again after the final UI exercise, so
overlays, keyguard, and redirected activities cannot be reported as headed
Hermes performance.

The AppShell publishes its stable Compose test tags as accessibility resource
IDs. After the warm launch, the collector records fixed `uiautomator dump` and
`cat` commands. Each phase first removes its phase-specific temporary XML path
and accepts only UiAutomator's exact successful-dump marker before reading it,
so a timeout or null-root failure cannot reuse stale hierarchy bytes. It then
follows exactly one profile route: compact phones tap
`HermesChatDrawerButton` and `HermesNavSettings`; tablets tap
`HermesRailSettings`. Both routes must expose exactly one enabled, app-owned,
scrollable `HermesSettingsContentList`. Its on-screen bounds determine interior
tap/swipe coordinates, and the raw-evidence validator reparses the XML and
reconciles every navigation command, bound, route, and gesture coordinate.
Missing, duplicate, wrong-package, wrong-profile, off-screen, or retargeted
records fail closed.

Only after reaching that real Settings list does the collector reset `gfxinfo`
and swipe its scrollable content until at least 100 frames exist. It derives
jank from the reported counts, preserves the
p50/p90/p95/p99 timings, and reads TOTAL PSS/RSS from `dumpsys meminfo`. It
requires both dumps to identify the same Hermes PID measured by the warm lane,
then rechecks that live PID, the device, boot, APKs, and QEMU command after
measurement. The
collector atomically writes both `$profile.json` and
`$profile.raw.json` under `performance/`. The deterministic raw transcript
retains every command argv, exit code, stdout, and stderr, including initial
and final `adb devices`, exact `get-serialno`, guest/package/boot identity,
Win32_Process QEMU inventory, acceleration/GPU/screen probes, both launches,
every UI exercise, each `gfxinfo` dump, and `meminfo`. The normalized record
contains the raw relative path and SHA-256.

Both files are staged and parsed by the current release validator before they
replace anything. The clean committed source identity is rechecked before,
between, and after the paired replacements; a late source change restores the
prior evidence pair (or removes the newly created pair). At manifest creation
the validator requires the two raw
profile files, hashes them into the manifest, independently reparses their
security identities and metrics, and reconciles those values with the
normalized JSON. Missing, reordered, retargeted, tampered, or internally
inconsistent transcripts fail the release gate. Existing records are
preserved unless `--overwrite` is explicitly used. Use `--adb`, `--emulator`,
or `--powershell` when those executables are not on `PATH`.

Create the deterministic manifest only while the source tree is clean outside
the evidence directory, then commit the evidence before creating the tag:

```powershell
$tag = 'v0.13.147'
python scripts/android_release_evidence.py create --tag $tag
git add "android/release-evidence/$tag"
git commit -m "release(android): certify $tag headed-device evidence"
git tag $tag
python scripts/android_release_evidence.py verify --tag $tag --require-tag-ref
```

The manifest records the shared run ID and hashes every evidence file, both
installed APKs, and a deterministic Git source-tree identity.
`android/release-evidence/**` is excluded from the source identity,
so the evidence-only commit does not create a circular commit-hash dependency;
any later source, registry, or evidence-byte change still invalidates the gate.

## Languages and accessibility

The in-app language switch covers English, Simplified Chinese, Spanish, German,
Portuguese, and French. Every release checks core navigation, chat, settings,
terminal, local-model, and provider surfaces in all six languages, including
compact layouts and screen-reader labels. Android's system language can still
control platform-owned dialogs such as the document picker.

## Troubleshooting

### A model downloads but will not start

- Confirm the selected backend matches the file format.
- Check the exact file byte size and revision; partial downloads are rejected.
- Reduce context length and choose CPU if the vendor GPU/OpenCL path fails.
- Try a smaller model if current available memory is below the preflight
  estimate. Total installed RAM is not the same as memory available now.
- After a native or low-memory termination, reopen Hermes and inspect the
  recovered prior-exit diagnostic.

### A GGUF server is "ready" but chat does not answer

Hermes requires both the llama.cpp model endpoint and a real completion canary.
Check the reported GGUF architecture/chat-template error, process exit code,
and server log tail. Do not treat a successful `/v1/models` request alone as
proof that the model can generate text.

### Terminal commands return exit code 126

Exit code 126 means Android found the target but could not execute it. Capture
the Device/Terminal diagnostics, including Android version, device model,
selected Linux mode, command, executable path, mount/path classification, and
the final log lines. Ordinary storage permission prompts cannot make a binary
on a `noexec` mount executable; Hermes must route commands through its internal
executable runtime.

### Tools are described instead of executed

Verify the selected model supports structured tool calling, enable the desired
tool profile, and ask for a concrete operation. The local runtime status must
show that tool schemas were registered. Some general chat models will always
answer with prose even when tools are available.

## Release and F-Droid updater contract

GitHub releases are tag-driven. The Android Release workflow must finish green
and publish signed APK/AAB assets with SHA-256 digests. After that, the local
F-Droid check is intentionally read-only with respect to GitLab:

```bash
fdroid lint com.mobilefork.hermesagent
fdroid checkupdates --auto --allow-dirty com.mobilefork.hermesagent
```

Run this from a fresh checkout of the live F-Droid metadata after the GitHub tag
exists. The local diff must add the new version/code and resolve the exact tag
commit. Do not add `--commit` or `--merge-request`, and do not push the preview.
Reproducibility still requires a local pinned build and byte-for-byte APK
comparison before the release is certified.
