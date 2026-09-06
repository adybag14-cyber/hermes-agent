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
5. Enable only the tool profiles you intend the agent to use. The narrow,
   read-only prompts `What time is it?`, `Show the current directory`, `Who is
   the current user?`, `List files`, and `Show system information` run their
   built-in native command before any local or remote model request. Other
   commands require an enabled profile and a model with compatible structured
   function/tool-calling training; describing a command in prose is not the
   same as emitting a tool call.

The embedded Android runtime deliberately exposes only its audited
in-process tool profile. External MCP stdio/SSE/HTTP sessions, user plugin and
context-engine code, process-backed ACP/Codex provider modes, and the async
web/vision tools are unavailable because the app cannot yet prove that their
threads and child processes have stopped before switching runtimes. Existing
MCP JSON is retained for export but is not loaded or executed. These limits are
specific to the embedded Android app; they do not remove the corresponding
desktop or CLI features.

Large local models need substantially more free memory than their file size.
Hermes checks current memory headroom before starting, but Android can still
reclaim a process when another app, the GPU driver, KV cache, or model-native
buffers consume the remaining RAM. Start with the smallest certified model for
your backend and close other memory-heavy apps before moving up.

Gemma 4 12B LiteRT-LM is not supported by Hermes on nominal 16 GB phones. For
LiteRT-LM files of 6 GB or more, Hermes requires at least 2.5 times the file
size as total device RAM before native initialization; a 6.5 GB bundle needs
about 16.3 GB before Android, the GPU driver, KV cache, and other process
memory. Hermes blocks this configuration before native allocation. A GPU run
in Google AI Edge Gallery does not certify the same artifact/runtime path in
Hermes. Experimental Gemma 4 E2B/E4B files require the explicit custom-import
path; they are excluded from the release-certified quick-start catalog and are
never selected automatically. The historical E4B pin
`9695417f248178c63a9f318c6e0c56cb917cb837` is an April artifact of
3,654,467,584 bytes (SHA-256
`f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc`).
Hermes classifies it as experimental and text-only. The narrow validation
recipe below explicitly selects CPU and disables speculative decoding; Hermes
does not silently force those settings for every custom import. This is not the
newer upstream artifact for which speculative decoding is advertised, and it
remains unverified on Snapdragon/Adreno until that exact path passes a headed
physical-device matrix.

To reproduce only that narrowly scoped historical E4B path, use the custom
model importer rather than a quick-start card: enter repository
`litert-community/gemma-4-E4B-it-litert-lm`, immutable revision
`9695417f248178c63a9f318c6e0c56cb917cb837`, and file
`gemma-4-E4B-it.litertlm`; verify the exact byte count and SHA-256 above before
loading it. Select LiteRT-LM with CPU, keep speculative decoding off,
send a text-only prompt, and require both a healthy runtime and a non-empty
completion. This recipe does not certify the current moving upstream artifact,
multimodal input, MTP, NPU, Snapdragon/Adreno, or another device/ABI.

After provisioning those exact bytes and creating the release-identity `$bind`
array described under **Committed release-evidence gate** below, the scoped
device-evidence invocation is:

```powershell
$modelBind = @($bind) + @(
  '-e', 'model_id', 'gemma-4-e4b-litert-lm',
  '-e', 'model_file_name', 'gemma-4-E4B-it.litertlm',
  '-e', 'model_bytes', '3654467584',
  '-e', 'model_sha256', 'f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc',
  '-e', 'model_repo', 'litert-community/gemma-4-E4B-it-litert-lm',
  '-e', 'model_revision', '9695417f248178c63a9f318c6e0c56cb917cb837',
  '-e', 'preferred_accelerator', 'cpu',
  '-e', 'speculative_decoding', 'disabled',
  '-e', 'exercise_backend_manager', 'true',
  '-e', 'require_model', 'true',
  '-e', 'class', 'com.mobilefork.hermesagent.LiteRtLmModelMatrixInstrumentedTest#provisionedLiteRtLmModelLoadsAndAnswersLocally'
)
adb -s $serial shell am instrument -w -r @modelBind `
  com.mobilefork.hermesagent.test/androidx.test.runner.AndroidJUnitRunner
```

Use the same active emulator serial in `$bind` and `adb -s`. The test discovers
the model in the same private/external app directories as production, seeds an
exact completed preferred-download record and persisted settings, then enters
through `OnDeviceBackendManager`. It fails unless health reports CPU, no GPU
attempt, speculative decoding disabled, no image/audio support, a successful
startup canary, and a non-empty real completion. Its durable evidence records
the app entry point, requested and observed accelerator, MTP policy, modality
support, exact artifact identity, and elapsed time. This release-evidence
identity is deliberately emulator-bound; a future physical Snapdragon test
needs a separate physical-device identity contract and cannot reuse this record
to claim S24/Adreno certification.

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

Check the release notes for the exact files that passed the current release.
The one-tap recommended cards and signed-catalog quick-start choices are
restricted to exact content-addressed release-matrix artifacts with a known
byte count no larger than 5 GiB. Unknown-size, moving, oversized, or merely
experimental catalog rows are excluded from quick start; an operator can still
use custom import after separately verifying the repo, immutable revision,
file, bytes, and runtime compatibility.

Hermes currently implements LiteRT-LM GPU and CPU delegates only. It does not
expose a separate AICore/NPU backend, does not infer one from Android API level,
and normalizes a legacy `npu` preference back to `auto`. Do not interpret a GPU
or CPU fallback as NPU execution.

The managed llama.cpp chat backend accepts single-file GGUF v2/v3 artifacts
whose metadata includes an architecture, tensors, and an embedded
`tokenizer.chat_template`. Split shards and base GGUFs without an embedded chat
template are rejected with an actionable message instead of being reported as
ready. They can be supported later through an explicit user-selected/family
template path, but they are outside this release's chat-ready compatibility
contract.

## Stable and experimental llama.cpp lanes

The default **Stable compatibility** lane preserves the v0.13.149 Termux
`llama-cpp` b9784 runtime and its existing launch defaults. This avoids silently
changing a release-tested native dependency graph for users who do not opt in.
That build accepts the standard `f32`, `f16`, `bf16`, `q8_0`, `q4_0`, `q4_1`,
`iq4_nl`, `q5_0`, and `q5_1` KV-cache types, but it predates official Nanbeige
architecture support.

The opt-in **Experimental TurboQuant / Nanbeige** lane is a separate,
system-library-only Android executable built from
`TheTom/llama-cpp-turboquant` commit
`e30664a710b62aaf13c6b12e39e74500e6ce21ef` (build 10539). Its source archive,
NDK 29.0.14206865, Android SDK CMake 3.31.6/Ninja 1.12.1 toolchain, ABI list,
cache-type capabilities, and hashes are
content-pinned in `hermes_android/experimental_llama_server.lock.json`. The
stable b9784 executable and shared libraries remain untouched. The experimental
lane adds Nanbeige plus the `turbo2`, `turbo3`, and `turbo4` KV-cache types, but
it is CPU-only and may trade speed for KV-cache memory savings on phones.
Selecting the exact Nanbeige recommended card or Model Manager catalog row
persists TurboQuant before the download begins and reapplies that requirement
when the completed file becomes preferred or auto-starts. Starting a known
catalog artifact also rechecks its exact byte count and SHA-256 at the final
runtime boundary and persists its required lane before process launch. A stale
Stable selection therefore cannot launch a verified Nanbeige artifact, and a
failed lane commit stops startup instead of advertising a configuration which
was not applied. Unknown imports and lane-neutral model presets do not change
the user's current lane.

The Settings screen exposes K and V cache types independently. There are no
llama.cpp cache formats called `q5_k` or `q5_v`: choose `q5_0` or `q5_1` in the
K selector and independently in the V selector. Flash attention can be left at
the server default or set to Auto, On, or Off. Quantized V caches require
effective flash attention, and TurboQuant cache types cannot be combined with
Flash Off; Hermes rejects those combinations before it starts a process.

Expert additional arguments are stored as an argument list, one token per
line. Hermes shell-quotes every token and rejects positional values,
app-owned model/host/port options, API/TLS/download options, duplicate managed
options, control characters, and oversized argument sets. It also checks the
exact value count for a reviewed set of pinned-parser performance flags,
while model, paging/RAM, device-placement, endpoint, and chat/tool-protocol
overrides remain Hermes-owned. Other non-owned flags remain available for
expert and forward-compatible use; the selected native parser performs their final
per-flag semantic validation during the controlled restart. Because expert argv
may contain device paths or secrets, it is intentionally omitted from portable
settings exports; importing such a redacted bundle clears destination-local
expert argv. Saving a changed lane or argument fingerprint stops only the owned
server, restarts it, and reruns both readiness and the real completion canary.
The displayed effective arguments are the authority for what was applied.

Every owned llama.cpp process receives a fresh 256-bit loopback bearer token.
The pinned server intentionally leaves `GET /health` and `GET /v1/models`
public, so Hermes uses them only for readiness and model metadata. It separately
proves that the data-bearing chat endpoint rejects an unkeyed request, then uses
the token for the completion canary, streamed chat, and native tool chat. The
controller checks that the port is free both before runtime discovery and
immediately before spawning, retains the exact process handle, and confirms the
owned process is still alive after readiness and completion. The token and raw
expert argv are not written to diagnostics, portable exports, or user-visible
failure status.

The experimental Nanbeige request path adds `reasoning_format=none` for both
streamed chat and the non-stream fallback and keeps native chat's
`enable_thinking=false` template argument. This prevents a short generation
from landing only in `reasoning_content` while assistant-visible `content`
remains empty. The TurboQuant-only reasoning-format override is not sent
through the Stable lane, LiteRT-LM, or remote providers.

**Try once despite the RAM warning** is deliberately a one-shot action. It
bypasses only Hermes' RAM admission estimate for that single llama.cpp start;
it is never persisted or exported and does not bypass file/GGUF validation,
content-addressed checks, executable validation, localhost ownership,
readiness, the completion canary, or fail-closed process cleanup. Android may
still kill the process or the native allocator may fail. Normal launches retain
the bounded 1,024/2,048-token context defaults; importing a model which advertises
a much larger training context does not make that full context a safe phone
default.

The Nanbeige artifact used for this lane's exact compatibility gate is
`Tdamre/Nanbeige4.2-3B-GGUF` revision
`128d8e87d69f9c1a30c37e40530c69deda96475d`, file
`Nanbeige4.2-3B-Q4_K_M.gguf`, 2,574,807,840 bytes, SHA-256
`99c7bfb88907f7eee0a04c4314f1c46bca391819478d8cb90b3e164f09576489`.
Do not call a renamed local file byte-identical until its on-device digest has
been checked. As with every model, lane availability is not certification: a
headed device run must still prove model load, health, and non-empty completion.

## LiteRT-LM stable and upstream-preview builds

For the opt-in source-built Chaquopy consumer, real SDK/model experiments and
public ModelScope routes, see [the model laboratory](CHAQUOPY_MODEL_LAB.md).

Normal and F-Droid builds use the exact `liteRtLmStableVersion` declared in
`app/build.gradle.kts`. The live CI guard compares that pin with Google Maven
and rejects stale or dynamic release dependencies.

Google does not currently publish an Android nightly Maven coordinate. To test
a newly published exact preview version without changing the release default:

```powershell
./gradlew.bat :app:compileDebugKotlin -PhermesLiteRtLmVersion=0.17.0
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
First prepare the source-built Python dependency bundle in the pinned Linux
buildserver as documented in [PYTHON_RUNTIME.md](PYTHON_RUNTIME.md), then export
the verified bundle to Windows. The example below supplies its absolute path;
the default location is `GRADLE_USER_HOME/hermes-python-runtime`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PYTHON_FOR_BUILD = `
  'C:\Users\you\AppData\Local\Programs\Python\Python313\python.exe'

Set-Location android
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug `
  -PhermesPythonBundle=C:\path\to\verified-python-bundle
```

The debug APK is written beneath `android/app/build/outputs/apk/debug/`.
Release builds additionally need `android/keystore.properties` and the
corresponding signing keystore; neither belongs in Git.

When an upgrade must be exercised against an already-installed production-
signed app before the release tag exists, first require the candidate commit to
be the exact live default-branch head, then trigger the default-branch-only
repository dispatch with both the upcoming tag and immutable commit:

```powershell
$tag = 'v0.13.154'
$candidateSha = (git rev-parse 'HEAD^{commit}').Trim()
$defaultBranch = (gh repo view adybag14-cyber/hermes-agent `
    --json defaultBranchRef --jq '.defaultBranchRef.name').Trim()
git fetch --no-tags origin "+refs/heads/$($defaultBranch):refs/remotes/origin/$($defaultBranch)"
$remoteDefaultSha = (git rev-parse "origin/$defaultBranch").Trim()
if ($candidateSha -ne $remoteDefaultSha) {
    throw "Candidate is not the live default-branch head"
}
gh api --method POST repos/adybag14-cyber/hermes-agent/dispatches `
    -f event_type=android-device-candidate `
    -f "client_payload[release_tag]=$tag" `
    -f "client_payload[candidate_sha]=$candidateSha"
```

`Android Signed Device Candidate` checks that SHA against the live default head
before running repository build code, rechecks it immediately before restoring
any signing secret, and checks it again after signing before upload. It then
builds a clean source-bound release APK, signs it with the approved release
certificate, verifies package/version/source identity, and uploads a short-lived
Actions artifact. It never creates or edits a GitHub release. Use the artifact
only for `adb install -r` device certification; the tag-triggered release
workflow remains the publication authority.

The experimental llama.cpp task pins the source archive, compatibility patch,
NDK 29.0.14206865, the official Android SDK `cmake;3.31.6` package (CMake
3.31.6 with Ninja 1.12.1), ABIs, build definitions, and `SOURCE_DATE_EPOCH`;
every executed task validates those exact tool versions before downloading or
building, then rechecks the Android ELF dependencies and 16 KB load alignment.
The lock also hashes and packages the ggml/llama.cpp, nlohmann
JSON, and cpp-httplib MIT notices under
`assets/hermes-experimental-llama/`; a candidate missing any notice is not
releasable. Normal builds resolve CMake and Ninja only from that exact Android
SDK package; paired explicit overrides remain available for diagnostics but
must report the same locked versions. The verified source archive cache lives
under `GRADLE_USER_HOME/caches/hermes-experimental-llama/source`, so the named
F-Droid Gradle volume can reuse the immutable download. A Windows build is
still a verified candidate rather than a byte-reproducibility certification.

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
`sys.boot_completed=1`, verify the live emulator/QEMU command, and capture the
app's UI tree, screenshots, frame timing, and memory use. Tests which skip
because a model file is missing are not release evidence.

### Committed release-evidence gate

The release workflow does not run an emulator. It verifies evidence captured
locally from the exact headed, hardware-accelerated AVD candidate and stops
before signing if that committed evidence is absent, incomplete, stale, or for
a different source tree/tag. A successful instrumentation compile is not
device certification.

For `v0.13.148` and later, [RELEASE_EVIDENCE_V3.md](RELEASE_EVIDENCE_V3.md) is
the authoritative operator contract and closed-layout specification. It adds
comprehensive UI inventories, reviewed launch-theme captures, the scoped E4B
lane, and the fixed issue-8 and issue-16 records. The manifest-v2 tree shown
later in this section is retained only to explain and reproduce `v0.13.147`;
it is incomplete for a manifest-v3 release.

Release evidence is deliberately a two-commit operation. First commit every
source, test, workflow, metadata, and documentation change. With that source
commit checked out, obtain the identity embedded into the headed debug
candidate and build both APKs from the same process environment:

```powershell
$tag = 'v0.13.154'
$sourceLine = python scripts/android_release_evidence.py source-identity --require-clean |
    Select-String '^sourceDigest='
$sourceDigest = $sourceLine.Line.Substring('sourceDigest='.Length)
$env:HERMES_RELEASE_TAG = $tag
$env:HERMES_SOURCE_DIGEST = $sourceDigest
$env:PYTHON_FOR_BUILD = Join-Path $env:LOCALAPPDATA 'Programs\Python\Python313\python.exe'
Push-Location android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest `
    -PskipHermesAndroidLinuxAssets=false `
    --max-workers=12 --parallel --no-daemon --console=plain
Pop-Location
$candidateApk = Resolve-Path android/app/build/outputs/apk/debug/app-debug.apk
$testApk = Resolve-Path android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$candidateSha = (Get-FileHash $candidateApk -Algorithm SHA256).Hash.ToLowerInvariant()
$testSha = (Get-FileHash $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
$runId = "$tag-$($sourceDigest.Substring(0,16))-$(Get-Date -Format yyyyMMdd-HHmmss)bst"
```

The Gradle configuration recomputes the committed source identity, requires a
clean tree, rejects `hermesLiteRtLmLocalAar` and non-release LiteRT-LM versions,
and fails if the supplied digest differs. Install those exact two APKs with `adb install -r
--no-streaming`. Every evidence-producing instrumentation invocation must pass
the same binding arguments (substitute the active serial and AVD name):

```powershell
$serial = 'emulator-5570'
$avdName = 'Medium_Phone_API_35'
$bind = @(
    '-e', 'release_source_digest', $sourceDigest,
    '-e', 'candidate_apk_sha256', $candidateSha,
    '-e', 'instrumentation_apk_sha256', $testSha,
    '-e', 'evidence_run_id', $runId,
    '-e', 'device_serial', $serial,
    '-e', 'avd_name', $avdName
)
adb -s $serial shell am instrument -w -r @bind `
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

For the legacy `v0.13.147` manifest-v2 contract, the retrieved files use the
following exact layout (repeat `screen.png` and `semantics.txt` for `en`, `zh`,
`es`, `de`, `pt`, and `fr` under both UI profiles). Do not use this abbreviated
tree for `v0.13.148` or later; follow [RELEASE_EVIDENCE_V3.md](RELEASE_EVIDENCE_V3.md)
for the additional required `ui-coverage/`, `launch-theme/`, issue, and E4B
artifacts:

```text
android/release-evidence/<tag>/
├── ui/
│   ├── phone-compact/<language>/{screen.png,semantics.txt}
│   └── tablet/<language>/{screen.png,semantics.txt}
├── performance/
│   ├── phone-compact.json
│   ├── phone-compact.host.raw.json
│   ├── phone-compact.macrobenchmark.raw.json
│   ├── phone-compact.traces/iteration-{001..005}.perfetto-trace  # logical/artifact path
│   ├── tablet.json
│   ├── tablet.host.raw.json
│   ├── tablet.macrobenchmark.raw.json
│   └── tablet.traces/iteration-{001..005}.perfetto-trace        # logical/artifact path
├── models/<registered-model-id>.json
└── manifest.json                 # emitted by the create command
```

The performance records use schema
`hermes-android-performance-evidence-v2`. The host transcript preserves the
emulator/AVD/build/GPU identity, usable acceleration check, public-safe
canonical headed `-gpu host -accel on` command, its recomputable SHA-256, and
a one-way SHA-256 of the raw live command, plus screen px/dp/density, cold and
warm launch proof, live PID, and total PSS/RSS. Separately produced AndroidX
Macrobenchmark JSON and every referenced Perfetto trace supply the frame/jank
claim. Both raw streams and all traces are content-addressed from the normalized
record. The
model files are the unaltered `hermes-model-evidence-v1` JSON records retrieved
from `files/hermes-model-evidence/`. There must be exactly one passing record
for every current `VerifiedLocalModelArtifacts.releaseMatrix` entry.

The 70 historical raw traces for `v0.13.147` through `v0.13.153` are stored as
seven GitHub Actions artifacts instead of occupying the current checkout. Their
logical paths, byte counts, and SHA-256 values remain unchanged in each
performance record and release manifest. The closed inventory is
`release-evidence/perfetto-artifacts/source-manifest.json`; the successful
workflow run, immutable artifact IDs, archive digests, and expiration times are
bound by `release-evidence/perfetto-artifacts/registry.json`. Run
`.github/workflows/android-perfetto-artifacts.yml` only against the exact
40-character source commit recorded by that manifest. It verifies all source
bytes before upload and then uses a separate runner to download by artifact ID
and rehash all 70 files.

Actions artifacts are retained for 90 days, not forever. The recorded source
commit and historical release tags therefore remain the durable recovery path
for renewing an expired archive. Removing the files from the current tree does
not rewrite those immutable releases or erase the blobs from Git history.

To verify a downloaded set independently, download all seven artifacts from the
registered run into one directory (the GitHub CLI creates one child directory
per artifact), then run:

```powershell
python scripts/android_perfetto_artifacts.py verify-downloads `
    --download-root C:\path\to\downloaded-artifacts
python scripts/android_perfetto_artifacts.py verify-registry
```

For a release-evidence verification whose trace files are external, pass the
single version artifact directory as `--perfetto-root`. That directory must
contain only `phone-compact.traces/` and `tablet.traces/`; every byte is opened
and hashed, and the reconstructed release manifest must still match exactly.
An artifact receipt or registry record alone is never accepted as trace-byte
evidence.

Use the compact-phone AVD for `performance/phone-compact.json` and the
large-memory tablet AVD for `performance/tablet.json`; all model evidence must
come from one of those exact measured serial/AVD/fingerprint records. Verify
the live QEMU command line, not a remembered launch command. The collector
compares that raw command again after measurement but never writes its
user-specific paths: persisted evidence contains only the QEMU executable
basename, AVD/port/GPU/acceleration identity, and command hashes. The validator
requires a positive accelerator result, one effective `-gpu host`, one
effective `-accel on`, a headed window, at least five Macrobenchmark iterations
and 100 pooled frames in both FrameTiming and Hermes Perfetto counts, no more
than 10 percent pooled distinct surface tokens marked either App Deadline
Missed or Dropped Frame, bounded
launch/frame results, `PSS <= RSS`, and
compact/tablet PSS/RSS ceilings of
512/768 MiB and 768/1024 MiB respectively. It fully decodes screenshot pixels,
rejects blank/reused captures, and requires localized Device/Overview plus the
correct drawer-versus-persistent-rail semantics for each profile.

#### Non-debuggable Macrobenchmark jank gate

Frame/jank release acceptance comes exclusively from the separate
`:macrobenchmark` process and its Perfetto traces. The app's
`benchmark` build type is initialized from `release`, remains non-debuggable,
uses the local debug signing key, and adds `profileable shell=true` only through
the benchmark manifest overlay. `androidx.profileinstaller:profileinstaller`
1.4.1 intentionally applies to normal release and F-Droid artifacts as well as
the benchmark variant; this is the AndroidX-supported runtime hook used for
profile capture/reset and is inert when an APK contains no installed profile.

Build the source-bound target first, record that exact universal APK hash on
the host, and pass it back to the external benchmark process:

```powershell
$serial = 'emulator-5570'
$profile = 'phone-compact' # repeat later with the tablet serial and 'tablet'
$env:HERMES_RELEASE_TAG = $tag
$env:HERMES_SOURCE_DIGEST = $sourceDigest
Push-Location android
.\gradlew.bat :app:assembleBenchmark --no-daemon --console=plain
$benchmarkApk = Resolve-Path app/build/outputs/apk/benchmark/app-benchmark.apk
$benchmarkSha = (Get-FileHash $benchmarkApk -Algorithm SHA256).Hash.ToLowerInvariant()
$benchmarkOutput = Get-Content app/build/outputs/apk/benchmark/output-metadata.json -Raw |
    ConvertFrom-Json
if (@($benchmarkOutput.elements).Count -ne 1) { throw 'Expected one target APK' }
$benchmarkVersionCode = [string]$benchmarkOutput.elements[0].versionCode
.\gradlew.bat :macrobenchmark:assembleBenchmark --no-daemon --console=plain
$benchmarkTestApk = Get-ChildItem macrobenchmark/build/outputs/apk/benchmark/*.apk -File
if (@($benchmarkTestApk).Count -ne 1) { throw 'Expected one benchmark APK' }
$benchmarkTestSha = (Get-FileHash $benchmarkTestApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
```

Do not invoke the connected task until the exclusive-device preflight below has
recorded the exact AVD name and boot UUID. Those values are part of the runner
arguments, AndroidX payload, and per-iteration evidence token; a reboot after
the preflight invalidates the run.

The task-graph guard rejects benchmark artifact or connected tasks unless the
release tag and lowercase source SHA-256 are exact, LiteRT-LM remains the
release coordinate `com.google.ai.edge.litertlm:litertlm-android:0.17.0`, and
no local LiteRT-LM AAR is selected. Before measurement the benchmark reads the
installed target's benchmark-only manifest identity, package version,
debuggable/profileable flags, and APK bytes; it refuses any mismatch with the
expected digest, version code, dependency coordinate, or host-recorded APK
SHA-256. It also self-hashes the separately installed benchmark APK and binds
both APKs to the shared evidence run ID and phone/tablet profile. The same five
identity values plus the exact AVD name and kernel boot UUID are written into
AndroidX's official `context.payload`, and the
custom metric emits `hermesEvidenceToken` on every iteration. That safe 52-bit
integer is derived from the exact source digest, both APK SHA-256 values, run ID,
profile, AVD name, and boot UUID; the offline validator recomputes it and
requires every run to match.

`HermesSettingsScrollBenchmark` navigates through UiAutomator to the real
Compose resource ID `HermesSettingsContentList` and performs alternating list
flings for five measured iterations. `FrameTimingMetric` writes the standard
Macrobenchmark JSON distributions and one Perfetto trace per iteration. A
custom `TraceMetric` queries Hermes-only `actual_frame_timeline_slice` rows and
emits the single-value metrics `hermesFrameTotalCount`,
`hermesFrameSelfJankTaggedCount`, `hermesFrameAppDeadlineMissedCount`,
`hermesFrameAppDeadlineMissedOrDroppedCount`,
`hermesFrameNonDeadlineSelfJankTaggedCount`, `hermesFrameOtherJankTaggedCount`,
`hermesFrameDroppedCount`, `hermesFrameUnknownTagCount`,
`hermesFrameOverlappingJankTagCount`, `hermesFrameSelfJankTaggedPercent`, and
`hermesEvidenceToken` for every iteration. `hermesFrameSelfJankTaggedCount` is
the Perfetto visualization tag `Self Jank`, not proof of causal ownership. It
is a non-headline visualization-tag diagnostic and must equal
app-deadline-missed plus non-deadline Self Jank-tagged tokens.
`hermesFrameOtherJankTaggedCount` only records the Perfetto visualization tag
`Other Jank`; it is a non-gating diagnostic and makes no causal claim about
SurfaceFlinger, the emulator, or the system. `hermesFrameDroppedCount` is
preserved as a diagnostic and every dropped token is included in the gated
App Deadline Missed-or-Dropped union instead of disappearing from the
surface-token denominator. The union is computed after de-duplicating surface
tokens, so a token carrying both conditions is counted once; the host derives
and records that intersection as `deadline + dropped - union`. Unknown-tag or
overlapping Self/Other-tag tokens invalidate the evidence.
The metric always returns structurally valid counts, even when the performance
budget fails, so the complete JSON and traces remain available for diagnosis.
The host evidence validator sums all five iterations, requires at least 100
FrameTiming samples and at least 100 distinct Hermes surface-frame tokens. Its
controlled AVD gate is the share of the exact union of `App Deadline Missed`
and `Dropped Frame` surface tokens, using distinct Hermes surface-frame tokens
as its denominator; it rejects an aggregate share above 10 percent. Each
iteration must satisfy exact inclusion-exclusion bounds for the reported
deadline, dropped, and union counts. Perfetto Self Jank-tagged percentage is
separately recomputed over the same surface-token population as a non-gating
visualization-tag diagnostic. Positive raw AndroidX `frameOverrunMs` sample
count and percentage are preserved with the separate FrameTiming sample
denominator, but are non-gating AVD buffer-queue diagnostics. Trace inspection
showed emulator Buffer Stuffing and sleeping EGL swap/dequeue waits dominate
those positive samples, so this lane does not present them as a physical-device
or user-visible late-frame claim. Zero is not counted as a positive overrun.
The controlled AVD still has an app-work gate over AndroidX
`frameDurationCpuMs`: pooled P95 must be at most 50 ms and pooled P99 at most
100 ms. The preserved run is comfortably inside those ceilings; these bounds
measure CPU frame work rather than promoting emulator buffer-queue delay to a
physical-device performance claim.
The normalized record calls the raw FrameTiming denominator
`frame_timing_total_rendered`, its diagnostic numerator and percentage
`frame_timing_overrun_positive` and
`frame_timing_overrun_positive_percent`, and the distinct Perfetto denominator
`perfetto_surface_frame_timeline_tokens`. All pooled tag/timeline values use a
`perfetto_` prefix; the schema never
implies that those populations reconcile or have the same size. Preserve
the JSON report and every `.perfetto-trace` from
`macrobenchmark/build/outputs/connected_android_test_additional_output/benchmark/`.
Keep the closed trace set in run-bound scratch until the external artifact has
passed its independent download-and-hash gate.

AndroidX warns that emulator measurements are not representative of physical
devices. This hardware-accelerated AVD lane therefore suppresses only the
`EMULATOR` configuration error and is suitable for this release's controlled
AVD comparison, not a claim about physical-device latency. Never suppress
`DEBUGGABLE` or `NOT-PROFILEABLE`; either condition invalidates the run.
AndroidX BenchmarkData 1.4.1 derives `context.compilationMode` from the
instrumentation `targetContext`. Hermes' benchmark APK is self-instrumenting,
so its exact reporting-package value is `run-from-apk`; that field does not
describe the measured Hermes application. The normalized v2 record therefore
keeps the requested `compilation_mode = "Full"`, records
`reporting_package_compilation_mode = "run-from-apk"`, and independently
records `target_compiler_filter = "speed"`. The latter comes from exact raw
`adb -s <serial> shell cmd package dump com.mobilefork.hermesagent` captures
before host launch measurement and after final identity verification. Both
captures must contain one unambiguous API 35 `Dexopt state` status for the
installed target base APK, and that status must be `speed`.

#### Live performance collector

Treat each profile as one serialized AVD phase. Before starting a phase, count
live `qemu-system-*` processes and fail if there are more than two; the normal
release lane requires exactly one active emulator. Finish the compact-phone
capture, shut that emulator down, prove the QEMU count returned to zero, and
only then start the tablet/model AVD. Never keep a spare background emulator
alive during normal collection.

Before invoking Macrobenchmark, quarantine the prior contents of
`macrobenchmark/build/outputs/connected_android_test_additional_output/benchmark`
so stale output cannot be selected. Record the run start time and the exact
Gradle argv, exit code, stdout, and stderr as strict JSON with schema
`hermes-android-macrobenchmark-invocation-v1`. A successful run must create
exactly one fresh
`com.mobilefork.hermesagent.macrobenchmark-benchmarkData.json`, exactly one
benchmark result for the fully qualified `Class#settingsListFling` selector,
and exactly five fresh nonempty `.perfetto-trace` files named by its
`profilerOutputs`. Reject missing, extra, duplicate, symlinked, zero-byte, or
pre-run output. Copy that closed set to run-bound scratch before collection.
The following continuation of the build snippet performs the exclusive ADB/QEMU
preflight, captures the exact Gradle process result, and closes the AndroidX
output set. Keep the argument array unchanged: the collector and offline
validator require this exact order.

```powershell
$deviceRows = @(
    adb devices -l |
        Where-Object { $_ -match '^\S+\s+\S+' -and $_ -notmatch '^List of devices attached' }
)
if ($deviceRows.Count -ne 1) {
    throw "Expected exactly one attached ADB endpoint; observed $($deviceRows.Count)"
}
$deviceFields = @($deviceRows[0] -split '\s+')
if ($deviceFields[0] -ne $serial -or $deviceFields[1] -ne 'device') {
    throw "The only ADB endpoint must be $serial in device state"
}

$qemu = @(
    Get-CimInstance Win32_Process |
        Where-Object { $_.Name -like 'qemu-system-*' -and $_.CommandLine }
)
if ($qemu.Count -gt 2) { throw 'The absolute two-emulator RAM limit was exceeded' }
if ($qemu.Count -ne 1) { throw 'Normal release capture requires exactly one live emulator' }

$avdName = (adb -s $serial shell getprop ro.boot.qemu.avd_name).Trim()
$bootId = (adb -s $serial shell cat /proc/sys/kernel/random/boot_id).Trim().ToLowerInvariant()
if ($avdName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$') {
    throw "Invalid AVD identity: $avdName"
}
if ($bootId -notmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {
    throw "Invalid boot identity: $bootId"
}

$versionName = $tag.TrimStart('v')
$coordinate = 'com.google.ai.edge.litertlm:litertlm-android:0.17.0'
$gradle = (Resolve-Path .\gradlew.bat).Path
$gradleArgs = @(
    ':macrobenchmark:connectedBenchmarkAndroidTest'
    "-PhermesBenchmarkExpectedSourceDigest=$sourceDigest"
    "-PhermesBenchmarkExpectedVersionName=$versionName"
    "-PhermesBenchmarkExpectedVersionCode=$benchmarkVersionCode"
    "-PhermesBenchmarkExpectedLiteRtLmCoordinate=$coordinate"
    "-PhermesBenchmarkTargetApkSha256=$benchmarkSha"
    "-PhermesBenchmarkApkSha256=$benchmarkTestSha"
    "-PhermesBenchmarkEvidenceRunId=$runId"
    "-PhermesBenchmarkEvidenceProfile=$profile"
    "-PhermesBenchmarkExpectedAvdName=$avdName"
    "-PhermesBenchmarkExpectedBootId=$bootId"
    '-Pandroid.testInstrumentationRunnerArguments.class=com.mobilefork.hermesagent.macrobenchmark.HermesSettingsScrollBenchmark#settingsListFling'
    '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR'
    '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None'
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.sourceDigest=$sourceDigest"
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.targetApkSha256=$benchmarkSha"
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.benchmarkApkSha256=$benchmarkTestSha"
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.evidenceRunId=$runId"
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.evidenceProfile=$profile"
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.avdName=$avdName"
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.bootId=$bootId"
    '--no-daemon'
    '--console=plain'
)

$runStamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$scratch = Join-Path $env:TEMP "hermes-macro-$runId-$profile-$runStamp"
New-Item -ItemType Directory -Path $scratch -ErrorAction Stop | Out-Null
$outputRoot = Join-Path (Resolve-Path macrobenchmark).Path `
    'build/outputs/connected_android_test_additional_output/benchmark'
if (Test-Path -LiteralPath $outputRoot) {
    $quarantine = "$outputRoot.stale-$runStamp"
    Move-Item -LiteralPath $outputRoot -Destination $quarantine -ErrorAction Stop
}

$stdoutPath = Join-Path $scratch 'gradle.stdout.txt'
$stderrPath = Join-Path $scratch 'gradle.stderr.txt'
$startedUtc = [DateTime]::UtcNow
& $gradle @gradleArgs 1> $stdoutPath 2> $stderrPath
$gradleExit = $LASTEXITCODE
$stdout = [IO.File]::ReadAllText($stdoutPath)
$stderr = [IO.File]::ReadAllText($stderrPath)
$invocation = [ordered]@{
    schema = 'hermes-android-macrobenchmark-invocation-v1'
    argv = @($gradle) + $gradleArgs
    exit_code = $gradleExit
    stdout = $stdout
    stderr = $stderr
}
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationPath = Join-Path $scratch 'invocation.json'
[IO.File]::WriteAllText(
    $invocationPath,
    ($invocation | ConvertTo-Json -Depth 5),
    $utf8NoBom
)
if ($gradleExit -ne 0 -or "$stdout`n$stderr" -notmatch 'BUILD SUCCESSFUL') {
    throw "Macrobenchmark failed; diagnostics are preserved in $scratch"
}
if ("$stdout`n$stderr" -match 'BUILD FAILED|FAILURE:|INSTRUMENTATION_FAILED') {
    throw "Macrobenchmark output contains a failure marker; see $scratch"
}

$reports = @(
    Get-ChildItem -LiteralPath $outputRoot -Recurse -File `
        -Filter 'com.mobilefork.hermesagent.macrobenchmark-benchmarkData.json'
)
if ($reports.Count -ne 1) { throw "Expected one fresh AndroidX report; got $($reports.Count)" }
$sourceReport = $reports[0]
if (($sourceReport.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
    $sourceReport.Length -le 0 -or $sourceReport.LastWriteTimeUtc -lt $startedUtc) {
    throw 'AndroidX report is unsafe, empty, or predates this run'
}
$reportJson = Get-Content -LiteralPath $sourceReport.FullName -Raw | ConvertFrom-Json
if (@($reportJson.benchmarks).Count -ne 1) { throw 'Expected one benchmark result' }
$outputs = @($reportJson.benchmarks[0].profilerOutputs)
if ($outputs.Count -ne 5) { throw 'Expected five AndroidX Perfetto outputs' }
$rootPrefix = [IO.Path]::GetFullPath($outputRoot).TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
) + `
    [IO.Path]::DirectorySeparatorChar
$referencedTraces = for ($index = 0; $index -lt $outputs.Count; $index++) {
    $entry = $outputs[$index]
    if ($entry.type -ne 'PerfettoTrace' -or $entry.label -ne "Trace Iteration $index") {
        throw "Invalid profiler output at iteration $index"
    }
    $candidate = Join-Path $sourceReport.Directory.FullName ([string]$entry.filename)
    $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    if (-not $resolved.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Trace escapes the fresh output root: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $item.Length -le 0 -or $item.LastWriteTimeUtc -lt $startedUtc) {
        throw "Trace is unsafe, empty, or predates this run: $resolved"
    }
    $item
}
if (@($referencedTraces.FullName | Sort-Object -Unique).Count -ne 5) {
    throw 'AndroidX trace references are duplicated'
}
$allTraces = @(Get-ChildItem -LiteralPath $outputRoot -Recurse -File -Filter '*.perfetto-trace')
$traceDiff = @(Compare-Object `
    @($allTraces.FullName | Sort-Object) `
    @($referencedTraces.FullName | Sort-Object))
if ($traceDiff.Count -ne 0) { throw 'Fresh output contains missing or unreferenced traces' }

$report = Join-Path $scratch $sourceReport.Name
Copy-Item -LiteralPath $sourceReport.FullName -Destination $report -ErrorAction Stop
foreach ($trace in $referencedTraces) {
    Copy-Item -LiteralPath $trace.FullName -Destination `
        (Join-Path $scratch $trace.Name) -ErrorAction Stop
}
$traces = @(Get-ChildItem -LiteralPath $scratch -File -Filter '*.perfetto-trace' | Sort-Object Name)
if ($traces.Count -ne 5) { throw 'Run-bound scratch does not contain five traces' }
```

Do not assume the connected test task leaves either APK installed. Reinstall
the same prehashed benchmark target and benchmark test APK pair explicitly with
`adb install -r -t`. Because reinstall can reset ART's compiler filter to
`verify`, explicitly compile the measured target back to `speed`; the collector
then proves both that compiler state and the installed `pm path` bytes. Keep
that AVD boot alive while the collector rechecks both APKs, device/QEMU/source
identity, launch, PID, and memory before and after the run:

```powershell
Pop-Location
adb -s $serial install -r -t $benchmarkApk
if ($LASTEXITCODE -ne 0) { throw 'Failed to reinstall the prehashed benchmark target APK' }
adb -s $serial install -r -t $benchmarkTestApk.FullName
if ($LASTEXITCODE -ne 0) { throw 'Failed to reinstall the prehashed benchmark test APK' }
adb -s $serial shell cmd package compile -m speed -f com.mobilefork.hermesagent
if ($LASTEXITCODE -ne 0) { throw 'Failed to restore the measured target speed compiler filter after reinstall' }
$report = Resolve-Path $report
$traceArgs = @($traces | ForEach-Object { @('--macrobenchmark-trace', $_.FullName) } |
    ForEach-Object { $_ })
python scripts/android_collect_performance_evidence.py `
    --serial $serial `
    --profile $profile `
    --expected-avd-name $avdName `
    --expected-boot-id $bootId `
    --release-source-digest $sourceDigest `
    --benchmark-target-apk-sha256 $benchmarkSha `
    --benchmark-test-apk-sha256 $benchmarkTestSha `
    --evidence-run-id $runId `
    --version-name $versionName `
    --version-code $benchmarkVersionCode `
    --litertlm-coordinate $coordinate `
    --macrobenchmark-report $report `
    --macrobenchmark-invocation "$scratch/invocation.json" `
    @traceArgs `
    --output "android/release-evidence/$tag/performance/$profile.json"
```

The collector requires a clean committed source tree outside
`android/release-evidence/` and recomputes its digest before touching the
device. It verifies the exact adb serial, observed AVD name, fingerprint, API,
ABIs, boot UUID, installed app/test APK hashes, and installed version. On the
host it resolves exactly one matching live `qemu-system-*` process through
Windows CIM and verifies its actual PID and raw command in memory. It persists
only a deterministic public-safe canonical command plus public/raw SHA-256
digests. Before launching Hermes it captures the target base APK's exact
package-manager Dexopt status and requires `speed`; after all measurement and
identity checks it repeats the same raw command and rejects any drift. It also
requires a successful `emulator -accel-check`, a hardware SurfaceFlinger renderer, and a
headed command containing exactly one effective `-gpu host` and `-accel on`.

For the host measurements it records effective `wm` size/density and cold/warm
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
after the warm launch, so an overlay, keyguard, or redirected activity cannot
be reported as headed Hermes host evidence. It reads TOTAL PSS/RSS from
`dumpsys meminfo`, requires the dump to identify that same warm PID, then
rechecks the live PID, device, boot, both benchmark APKs, QEMU command, and
source after measurement.

The collector never creates a frame claim from host gestures or shell renderer
counters. It strictly parses the already completed AndroidX report, requires
five to twenty iterations and one nonempty trace per iteration, recomputes the
pooled AndroidX percentiles from the raw sample arrays, and enforces at least
100 pooled FrameTiming samples, at least 100 pooled Hermes Perfetto surface
tokens, and the 10 percent pooled `App Deadline Missed`-or-`Dropped Frame`
controlled-AVD budget.
Positive `frameOverrunMs` count and percentage remain bound diagnostics without
a threshold in this AVD-only lane. Pooled `frameDurationCpuMs` P95 and P99 must
remain at or below 50 ms and 100 ms respectively. FrameTiming samples and Perfetto
surface-frame tokens keep distinct, explicitly named denominators.
App-deadline-missed plus non-deadline Self Jank-tagged tokens must reproduce the Perfetto Self
Jank-tagged total. The `Other Jank`-tagged count is diagnostic only and carries no
causal attribution. Dropped tokens may be nonzero, but they are budgeted in the
de-duplicated App Deadline Missed-or-Dropped union and must satisfy exact
inclusion-exclusion reconciliation; unknown-tag and overlapping Self/Other-tag
counts must be zero. It atomically commits the normalized JSON, host raw
JSON, untouched Macrobenchmark raw JSON, and canonical iteration traces; raw
diagnostics go first and the normalized claim last. The source identity is
rechecked between replacements, and any failure restores every prior artifact.

At manifest creation the offline validator reparses both raw streams, verifies
the exact Gradle argv and AndroidX `context.payload`, recomputes the evidence
token, frame counts, percentiles, and file hashes, and binds all traces to the
same device/APK/source/run/profile identity. Missing, reordered, retargeted,
tampered, symlinked, extra, or internally inconsistent artifacts fail closed.
Existing records are preserved unless `--overwrite` is explicitly used. Use
`--adb`, `--emulator`, or `--powershell` when those executables are not on
`PATH`.

After satisfying the complete version-selected schema (including the v3
contract for `v0.13.148+`), create the deterministic manifest only while the
source tree is clean outside the evidence directory, then commit the evidence
before creating the tag:

```powershell
$tag = 'v0.13.154'
python scripts/android_release_evidence.py create --tag $tag
git add "android/release-evidence/$tag"
git commit -m "release(android): certify $tag headed-device evidence"
git tag -a $tag -m "Hermes Agent Fork $tag"
python scripts/android_release_evidence.py verify --tag $tag --require-tag-ref
```

When the raw traces have already been externalized, supply the downloaded
single-version artifact root to both manifest creation and verification:

```powershell
python scripts/android_release_evidence.py create `
    --tag $tag `
    --perfetto-root C:\path\to\artifact
python scripts/android_release_evidence.py verify `
    --tag $tag `
    --perfetto-root C:\path\to\artifact `
    --require-tag-ref
```

Keep that external root outside the repository so clean-tree checks remain
meaningful. Local collection still writes and transactionally validates raw
traces first. Those new files intentionally remain visible to `git status`:
the existing tagged-release workflow requires repository-resident trace bytes
unless an exact artifact download is explicitly wired into that release.

The manifest records the shared run ID and hashes every evidence file, the
debug UI candidate/instrumentation pair, the separate non-debuggable benchmark
target/debuggable benchmark-test pair, and a deterministic Git source-tree
identity.
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
- After a native or low-memory termination, force stop and reopen Hermes, then inspect the
  recovered prior-exit diagnostic.
- For Gemma 4, `Auto` speculative decoding is enabled only when the LiteRT-LM
  capabilities probe explicitly advertises support. A filename containing
  `gemma-4` is not evidence of MTP compatibility. Forced CPU mode skips the
  OpenCL loader probe, and disabled speculative decoding skips the capabilities
  probe. Any remaining native probes, Engine initialization, and completion
  canary run inside one shared 300-second monotonic startup budget. An ordinary
  failure which safely returns may continue only after bounded candidate
  cleanup. A deadline or caller interruption aborts the whole fallback chain;
  Hermes will not create another native engine until the abandoned worker exits
  and cleanup succeeds. Replacing an existing engine likewise uses a bounded
  shutdown wait and never overlaps the replacement with an old native close.
  Each real chat completion has the same ownership boundary: the native worker
  creates, uses, cancels, and closes its `Conversation`. If vendor JNI ignores
  interruption, the HTTP/UI wait returns a bounded error, `/health` reports
  `generation_state=running_or_unwinding`, and Hermes rejects another prompt or
  backend switch instead of overlapping native work. A cleanup failure changes
  that state to `restart_required`; the runtime is not reported ready again.
  Force stop and reopen the app if a vendor JNI call or cleanup never returns,
  or if Hermes reports that cleanup failed and a restart is required.

### A GGUF server is "ready" but chat does not answer

Hermes requires both the llama.cpp model endpoint and a real completion canary.
Check the reported GGUF architecture/chat-template error, process exit code,
and server log tail. Do not treat a successful `/v1/models` request alone as
proof that the model can generate text.

If the log says `unknown model architecture: 'nanbeige'`, the Stable b9784 lane
was selected; this is a real native-backend compatibility error, not a graphical
error. v0.13.151 and later automatically reconcile the exact catalog-bound
Nanbeige artifact to Experimental TurboQuant / Nanbeige immediately before
launch. If the error remains, verify the file's expected byte count and SHA-256;
an unknown or modified import has no catalog authority for an automatic lane
change. After resolving that boundary, require the same health and non-empty-
completion checks. If startup rejects a K/V-cache configuration, first restore
Server default or enable flash attention for a quantized V cache. The dangerous
RAM action cannot repair an unsupported architecture or invalid server
arguments.

### Terminal commands return exit code 126

Exit code 126 means Android found the target but could not execute it. Capture
the Device/Terminal diagnostics, including Android version, device model,
selected Linux mode, command, executable path, mount/path classification, and
the final log lines. Ordinary storage permission prompts cannot make a binary
on a `noexec` mount executable; Hermes must route commands through its internal
executable runtime.

The embedded Android terminal accepts bounded foreground commands only.
`background=true` and shell-detached daemons are rejected: Android cannot
reliably prove ownership of every reparented same-UID descendant, so accepting
them would let old tool work overlap a backend stop or app-runtime restart. Use
a native Hermes automation for persistent Android work.

### Tools are described instead of executed

Ask in ordinary language: `What time is it?` or `Run a command to tell me what
time it is.` Hermes uses a narrow, read-only built-in route to execute `date`
before the configured local or remote endpoint, so no tool name or
model-generated function call is required. `Check my device status` similarly
selects native device diagnostics. Confirm the visible tool result or event;
prose saying a tool ran is not execution evidence.

For commands outside the built-in safe routes, ask for one concrete affirmative
operation. Ordinary non-action chat receives no native tool schema. A natural
action request receives only the request-scoped tool/action schema inferred for
that operation, with at most one dispatch across initial and context-recovery
rounds. A validated typed invocation such as `terminal_tool command="pwd"`
bypasses model startup and dispatches its carried arguments exactly once. An
unknown tool, action, extra argument, changed bound value, multi-call response,
or later replay is rejected before native side effects. Some general chat
models can still answer with prose instead of using the single offered action.

Raw Shizuku/privileged shell is deliberately unavailable from chat. The
current Shizuku service protocol cannot cancel a remote shell once Binder has
dispatched it, so chat does not advertise that action and rejects both direct
privileged-shell calls and saved `useShizuku` shell automations before Binder.
Use the explicit manual or background automation surface when that tradeoff is
intentional.

## Release and F-Droid updater contract

GitHub releases are tag-driven. The Android Release workflow must finish green
and publish signed APK/AAB assets with SHA-256 digests. After that, the local
F-Droid check is intentionally read-only with respect to GitLab:

```bash
fdroid lint com.mobilefork.hermesagent
fdroid checkupdates --auto --allow-dirty com.mobilefork.hermesagent
```

Run this from a fresh checkout of the live F-Droid metadata after the GitHub tag
exists. The local diff must add exactly one 0.13.154/145490 build and resolve the
tag to its full Git commit. Before the pinned build, merge only the committed
template's source-binding fields into that autoupdater-generated build and
verify the result:

```bash
bash fdroid/run-local-buildserver.sh \
  --render-autoupdate-preview \
  /path/to/fdroiddata/metadata/com.mobilefork.hermesagent.yml \
  fdroid/com.mobilefork.hermesagent.yml.template
bash fdroid/run-local-buildserver.sh \
  --verify-autoupdate-preview \
  /path/to/fdroiddata/metadata/com.mobilefork.hermesagent.yml \
  fdroid/com.mobilefork.hermesagent.yml.template
```

This transaction preserves the resolved commit, historical builds, and every
unrelated live-metadata field. It overlays the exact `sudo`, `ndk`, `gradle`,
`gradleprops`, and `prebuild` contract, then requires
`hermesFdroidSourceBinding=true` and the leading external-digest
`prepare` handoff. An old two-`sed` recipe or any path which can produce
`unbound` is rejected before the container downloads or builds anything. Do not
copy the whole candidate template over live metadata. Do not add `--commit` or
`--merge-request`, and do not commit or push the preview. The exact commands,
mounts, immutable toolchain pins, and no-MR boundary are documented in
[`fdroid/LOCAL_TOOLCHAIN.md`](../fdroid/LOCAL_TOOLCHAIN.md). Reproducibility
still requires that pinned build and its APK comparison before certification.
