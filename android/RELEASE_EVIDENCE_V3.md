# Android release evidence v3 (v0.13.148+)

This is the run, extraction, merge, review, and validation contract for Android
tags at or after `v0.13.148`. Earlier committed evidence, including
`android/release-evidence/v0.13.147`, remains on manifest v2 and must not be
regenerated or rearranged.

The v3 additions do not replace the existing six-language Device overview,
performance, Perfetto, or real-model evidence. They add:

- the complete `HermesUiCoverageInstrumentedTest` output for both release
  profiles;
- the phone-only six-language recommended-model and framework-activity output;
- launcher-tap and deep-link Android 12+ starting-window recordings for each
  profile;
- exact comparison of the installed app's persisted palette with the rendered
  `appearance-custom-light` UI proof; and
- a separate, explicit human frame-by-frame review decision. Capture code never
  marks splash pixels as passing;
- one instrumentation-originated issue-#8 record for the two exact direct-tool
  prompts, the mobile quick-catalog exclusion, and the metadata-only nominal
  16-GiB production memory preflight for the exact 12B artifact; and
- one issue-#16 record for a fresh full-assets Debian deploy, trusted packaged
  PRoot/QEMU/coreutils routes, guest-only PATH, CA provisioning, four individual
  guest command exits, real HTTPS, and successful disposable-sandbox removal;
  and, from `v0.13.151` onward,
- one privacy-safe physical ARM64 record which binds the signed source candidate,
  the exact Nanbeige Q4_K_M artifact, the extracted Stable-runtime failure, the
  automatic TurboQuant lane repair, readiness, ordinary-chat completion, visible
  elapsed progress, and terminal Stop behavior.

## 1. Bind and build one candidate pair

Start from the clean source commit which will be certified. Use one run ID for
performance, legacy UI, model, comprehensive UI, and launch-theme evidence.
Build the debug app and androidTest APK once, then keep their bytes unchanged.
The issue-#16 lane is invalid if the Linux assets are skipped, so pass the
explicit false property even though false is the default.

```powershell
$tag = 'v0.13.151'
$sourceLine = python scripts/android_release_evidence.py source-identity --require-clean |
    Select-String '^sourceDigest='
$sourceDigest = $sourceLine.Line.Substring('sourceDigest='.Length)
$runId = "$tag-$($sourceDigest.Substring(0,16))-$(Get-Date -Format yyyyMMdd-HHmmss)bst"
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
```

### 1A. Certify the signed Nanbeige repair candidate on physical ARM64 (v0.13.151+)

The physical record is separate from the headed-AVD candidate pair. First push
the clean source commit to the repository's default branch, then request the
short-lived signed candidate from that exact live default-branch SHA. The
`android-device-candidate` workflow accepts only `repository_dispatch`, checks
the live default head before the build and before any signing secret is
restored, checks it again after signing before upload, and never publishes a
release:

```powershell
$candidateCommit = (git rev-parse HEAD).Trim()
$defaultBranch = (gh repo view adybag14-cyber/hermes-agent `
    --json defaultBranchRef --jq '.defaultBranchRef.name').Trim()
git fetch --no-tags origin "+refs/heads/$($defaultBranch):refs/remotes/origin/$($defaultBranch)"
if ($candidateCommit -ne (git rev-parse "origin/$defaultBranch").Trim()) {
    throw 'Candidate source is not the fetched default-branch head'
}
gh api --method POST repos/adybag14-cyber/hermes-agent/dispatches `
    -f event_type=android-device-candidate `
    -f "client_payload[candidate_sha]=$candidateCommit" `
    -f "client_payload[release_tag]=$tag"
```

Download the artifact only from the resulting run for `$candidateCommit`. Check
its recorded checksum, APK package/version, approved signer, and embedded source
digest before touching the phone. Retain the exact APK bytes: the installed
`base.apk`, the committed physical record, and the final tag workflow must all
match this candidate's byte count and SHA-256.

Before `adb install -r`, preserve the existing production app and its data. The
required precondition is a persisted Stable selection with the exact app-scoped
Nanbeige file already present. A production APK is not debuggable, so `run-as`
cannot execute its private native library. The Stable entry point is also a
small dynamically linked wrapper, not a portable standalone server. Extract the
exact ARM64 runtime closure below from the signed candidate:

```text
lib/arm64-v8a/libandroid-spawn.so
lib/arm64-v8a/libc++_shared.so
lib/arm64-v8a/libcrypto.so
lib/arm64-v8a/libggml-base.so
lib/arm64-v8a/libggml-cpu.so
lib/arm64-v8a/libggml.so
lib/arm64-v8a/libhermes_android_llama_server.so
lib/arm64-v8a/libllama-common.so
lib/arm64-v8a/libllama-server-impl.so
lib/arm64-v8a/libllama.so
lib/arm64-v8a/libmtmd.so
lib/arm64-v8a/libssl.so
```

Inspect and record each file's direct `DT_NEEDED` entries. All non-system
dependencies must resolve inside that exact closure; the only accepted system
libraries are `libc.so`, `libm.so`, and `libdl.so`. `libggml-cpu.so` is required
even though it is loaded dynamically rather than through `DT_NEEDED`. For every
file, record its APK entry, role, byte count, SHA-256, direct dependencies, and
candidate-bound device path. Hash the canonical compact, key-sorted UTF-8 JSON
closure array as `runtime_closure_manifest_sha256`.

Push the files basename-preserving to the unique directory

```text
/data/local/tmp/hermes-<tag>-<first-16-candidate-apk-sha256>-llama-stable
```

and verify every device byte count and SHA-256 against the extraction. Create
private `home` and `tmp` children, make the wrapper executable, and launch from
that directory through `/system/bin/env -i` with exactly `PATH=/system/bin`,
`LANG=C`, `LC_ALL=C`, and
candidate-bound `HOME`, `TMPDIR`, and `LD_LIBRARY_PATH`, plus
`GGML_BACKEND_PATH` bound to the extracted `libggml-cpu.so` file. Passing the
runtime directory itself is invalid for this llama.cpp revision because it is
interpreted as a backend file and produces a loader warning before model load.
The latter is mandatory because GGML discovers the CPU backend dynamically.
Record the canonical environment JSON hash. Invoke the wrapper from that exact
working directory with the closed argv below, commit the argv array in the
physical record, and record the SHA-256 of its compact canonical UTF-8 JSON:

```text
<candidate-bound-wrapper> --model <exact-app-scoped-model-path> --host 127.0.0.1 --port 18081
```

Passing Stable proof is exit 1 at model load with the exact `unknown model
architecture: 'nanbeige'` failure, an empty unresolved-non-system dependency
set, and no linker, missing-library, missing-symbol, namespace, or `dlopen`
failure. Delete only that validated candidate-bound directory and prove it is
absent afterward. Never claim a `run-as` capture route for a release APK.

Install the unchanged signed candidate with `adb install -r`; do not uninstall,
clear data, remove the model, or manually reselect the runtime lane. Verify that
the installed `base.apk` bytes and signer equal the candidate, and that the
pre-upgrade Stable selection and model/history survived. A normal app launch
must then prove, in order:

1. exact artifact verification occurred before reconciliation;
2. Hermes automatically and durably changed `stable` to `turboquant` before
   spawning the runtime, without user reselection;
3. the local controller, health endpoint, and nonblank canary became ready;
4. reopening the same Settings state after readiness visibly showed the
   reconciled TurboQuant lane, matching the persisted launch authority rather
   than the stale Stable draft;
5. General-mode prompt `Reply exactly NANBEIGE_OK` completed in the visible app
   with the exact marker-free answer `NANBEIGE_OK`, no tool call/result cards,
   and at least one visible elapsed-progress observation; and
6. prompt `Write a long numbered list, continuing until I press Stop.` showed
   progress and Stop, then reached the exact English terminal message
   `This reply was stopped by the user.` with no busy state, Stop button, or
   blank placeholder left behind.

Hash the ADB serial rather than committing it. Record the live physical-device
properties, boot ID, candidate and installed APK identity, exact model identity,
Stable scratch-runtime proof, automatic reconciliation, readiness, ordinary
chat, and Stop observations in:

```text
android/release-evidence/<tag>/physical-device/nanbeige4.2-3b-q4-k-m-repair.json
```

The closed schema is `hermes-android-physical-nanbeige-repair-v1`; use
`tests/hermes_android/test_android_release_evidence_v151.py` as the field-shape
reference and validate the actual record with
`python scripts/android_release_evidence.py verify --tag <tag>` before creating
the release manifest. Screenshots, UI XML, logs, raw serials, and command argv
remain private scratch evidence and are not added to the closed release layout.

Install those exact files on the one explicitly owned headed AVD. Keep the same
serial, AVD, boot, source digest, run ID, and APK hashes throughout a profile's
performance, UI, model, and launch-theme capture. Do not reboot between the
rendered custom-light proof and its launch-theme recording.

For each serial, define the common bound arguments from the live device rather
than reusing another lane's values:

```powershell
$package = 'com.mobilefork.hermesagent'
$testPackage = 'com.mobilefork.hermesagent.test'
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$serial = 'emulator-5580'                 # replace with the owned endpoint
$avdName = 'Qitong_API_35_D'              # must equal ro.boot.qemu.avd_name
$bind = @(
    '-e', 'release_source_digest', $sourceDigest,
    '-e', 'candidate_apk_sha256', $candidateSha,
    '-e', 'instrumentation_apk_sha256', $testSha,
    '-e', 'evidence_run_id', $runId,
    '-e', 'device_serial', $serial,
    '-e', 'avd_name', $avdName
)
```

## 2. Run the comprehensive UI methods

Run methods separately so every required invocation ends in `OK (1 test)`;
do not use the tablet-only assumption skip as evidence. On the compact phone,
run both methods:

```powershell
$class = 'com.mobilefork.hermesagent.HermesUiCoverageInstrumentedTest'
adb -s $serial shell am instrument -w -r @bind `
    -e expected_ui_profile phone `
    -e class "$class#capturesEveryDestinationDevicePageThemeAndFrameworkActivityAtCurrentWidth" `
    $runner
adb -s $serial shell am instrument -w -r @bind `
    -e expected_ui_profile phone `
    -e class "$class#capturesSixLanguageRecommendedModelsAndPhoneOnlyLocalizedFrameworkActivities" `
    $runner
```

On the tablet, run only the complete-profile method:

```powershell
adb -s $serial shell am instrument -w -r @bind `
    -e expected_ui_profile tablet `
    -e class "$class#capturesEveryDestinationDevicePageThemeAndFrameworkActivityAtCurrentWidth" `
    $runner
```

The methods assert the current app sections, every nested Settings page, every
non-Overview Device page, appearance presets, card shapes, UI font-scale states,
the custom-light palette, provider/Tasker activities, recommended-model cards,
and all six languages before writing an inventory. The offline validator derives
the exact `AppSection`, `SettingsPage`, non-Overview `DevicePage`, and
`recommendedModelPresets` ID sets from the source tree. It also verifies the
instrumentation producer still relates each source entry to its declared
identity and proof page ID, rejects omitted or invented entries, and requires
every declared sentinel to occur in the proof body. Every inventory reference,
proof, screenshot hash, palette value, package/version, source digest, APK pair,
run ID, serial, AVD, boot ID, fingerprint, dimensions, and font scale remains
identity-bound.

## 3. Extract private app evidence without text-mode corruption

`files/hermes-ui-visuals` is app-private. Stream a tar archive through
`adb exec-out` and write stdout in binary mode. Repeat with a fresh staging
directory for each profile.

```powershell
$stage = Resolve-Path .
$archive = Join-Path $stage "$runId-phone-ui.tar"
@'
import pathlib, subprocess, sys
adb, serial, package, output = sys.argv[1:]
with pathlib.Path(output).open("wb") as handle:
    completed = subprocess.run(
        [adb, "-s", serial, "exec-out", "run-as", package,
         "tar", "-C", "files/hermes-ui-visuals", "-cf", "-", "."],
        stdout=handle,
    )
raise SystemExit(completed.returncode)
'@ | python - (Get-Command adb).Source $serial $package $archive

$phoneRaw = Join-Path $stage "$runId-phone-ui"
New-Item -ItemType Directory -Force $phoneRaw | Out-Null
tar -xf $archive -C $phoneRaw
```

Before merging, require exactly one current-run complete inventory, plus one
current-run localized inventory on the phone and none on the tablet. Reject
unrelated run IDs rather than copying a whole stale output directory.

```powershell
$phoneComplete = @(Get-ChildItem $phoneRaw -File -Filter "headed-$runId-profile-*-inventory.txt")
$phoneLocalized = @(Get-ChildItem $phoneRaw -File -Filter "headed-$runId-localized-*-inventory.txt")
if ($phoneComplete.Count -ne 1 -or $phoneLocalized.Count -ne 1) {
    throw 'Phone UI extraction does not contain the two unique bound inventories'
}
```

## 4. Capture the v3-only historical E4B issue-#8 record

Manifest v3 additionally requires
`models/gemma-4-e4b-litert-lm.json`. This is a historical, experimental,
text-only issue-#8 reproduction record; it is deliberately not added to
`VerifiedLocalModelArtifacts.releaseMatrix`, quick recommendations, or automatic
selection.

Provision the exact
`litert-community/gemma-4-E4B-it-litert-lm` revision
`9695417f248178c63a9f318c6e0c56cb917cb837` file
`gemma-4-E4B-it.litertlm`: 3,654,467,584 bytes with SHA-256
`f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc`.
Run it on the suitable bound AVD with the same `$bind` release identity; the
unbound shorthand invocation is not evidence.

```powershell
adb -s $serial shell am instrument -w -r @bind `
    -e model_id gemma-4-e4b-litert-lm `
    -e model_file_name gemma-4-E4B-it.litertlm `
    -e model_path '<device-readable-exact-E4B-path>' `
    -e model_bytes 3654467584 `
    -e model_sha256 f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc `
    -e model_repo litert-community/gemma-4-E4B-it-litert-lm `
    -e model_revision 9695417f248178c63a9f318c6e0c56cb917cb837 `
    -e preferred_accelerator cpu `
    -e speculative_decoding disabled `
    -e exercise_backend_manager true `
    -e require_model true `
    -e class 'com.mobilefork.hermesagent.LiteRtLmModelMatrixInstrumentedTest#provisionedLiteRtLmModelLoadsAndAnswersLocally' `
    $runner
```

Require `OK (1 test)`, then extract the single current-run file emitted under
`files/hermes-model-evidence/`. `ModelMatrixEvidence` names the raw device file
`litert-lm-gemma-4-E4B-it.litertlm-<recorded-at-ms>.json`; copy its bytes to the
fixed release path `models/gemma-4-e4b-litert-lm.json`.

The offline validator requires the exact publisher identity and bytes, the
honest `content-addressed-preprovisioned-preferred-download-record` provisioning
method (the harness seeds a preferred record after external provisioning; it
does not claim to drive the UI importer), the `on-device-backend-manager` entry point, requested and observed CPU, no GPU
attempt, requested and observed speculative decoding off, an MTP policy which
starts with `disabled:`, image/audio support false, healthy startup, nonblank
completion, positive elapsed time, and `clean_shutdown=true`. The record must
match one hardware-accelerated AVD performance identity from the same run. This
emulator record does not certify Snapdragon/Adreno or a physical-device path.

## 5. Capture issue #8 direct-tool, catalog, and 12B preflight evidence

Run the exact headed instrumentation method once on the bound compact-phone
profile. It enters both prompts through `MainActivity`, asserts the rendered
tool-call and result event nodes, counts model requests and connections to the
configured provider probe, evaluates the production mobile-catalog policy, and
calls the production memory preflight with a controlled nominal-16-GiB
snapshot. It does not download, create, sparsely allocate, or claim to inspect
a 6.5-GB file.

Create one scratch directory outside `android/release-evidence` and retain the
raw instrumentation transcript there. The extraction helper copies the exact
JSON value emitted by instrumentation; it does not construct evidence fields.

```powershell
$issueStage = Join-Path $stage "$runId-issues"
New-Item -ItemType Directory -Force $issueStage | Out-Null
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Export-InstrumentationJson {
    param(
        [string[]]$Lines,
        [string]$Key,
        [string]$Destination
    )
    $prefix = "INSTRUMENTATION_STATUS: $Key="
    $matches = @($Lines | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one instrumentation result named $Key"
    }
    $json = $matches[0].Substring($prefix.Length)
    try { $null = $json | ConvertFrom-Json } catch { throw "Invalid $Key JSON: $_" }
    [IO.File]::WriteAllText($Destination, $json + "`n", $utf8NoBom)
}

$issue8Method = 'com.mobilefork.hermesagent.NativeAppUiChatInstrumentedTest#mainActivityRunsIssueEightReadOnlyToolsBeforeAnyRemoteProviderRequest'
$issue8Lines = @(& adb -s $serial shell am instrument -w -r @bind `
    -e profile phone-compact `
    -e class $issue8Method `
    $runner 2>&1 | ForEach-Object { "$_" })
$issue8Exit = $LASTEXITCODE
[IO.File]::WriteAllLines((Join-Path $issueStage 'issue-8-instrumentation.txt'), $issue8Lines, $utf8NoBom)
if ($issue8Exit -ne 0 -or -not ($issue8Lines -contains 'OK (1 test)')) {
    throw "Issue #8 instrumentation failed with exit $issue8Exit"
}
$issue8Json = Join-Path $issueStage 'issue-8-tool-and-preflight.json'
Export-InstrumentationJson -Lines $issue8Lines `
    -Key 'issue8_tool_and_preflight' -Destination $issue8Json
```

The fixed record schema is
`hermes-android-issue-8-tool-and-preflight-v1`, with
`evidence_source=instrumentation`. It must be identity-bound to the exact source
digest, app APK, androidTest APK, run ID, package/version/build, compact-phone
profile, serial, AVD, boot ID, model, fingerprint, API, and ABI list.

The two route entries are closed and exact:

- `Run a command to tell me what time it is.` must visibly call
  `terminal_tool` action `date`, visibly return a nonblank result containing a
  four-digit year, execute one tool, and record both `model_request_count=0`
  and `provider_network_request_count=0`.
- `Check my device status` must visibly call
  `android_device_diagnostics_tool` action `status`, visibly return the native
  JSON status, execute one tool, and record the same two zero counts.

The catalog and preflight sections pin the official 12B metadata separately:

```text
repository = litert-community/gemma-4-12B-it-litert-lm
revision = d7de8ec6dcf035c90999ff38560bf4c6eb45a947
file = gemma-4-12B-it.litertlm
catalog bytes = 6547589312
SHA-256 = 74fc29a10c20eb5b3ced6c389471a7994a0ffd657255b2a1c764262fb9054aef
```

Catalog policy must report `release_certified=false`,
`quick_start_eligible=false`, `present_in_mobile_quick_catalog=false`,
`automatically_selected=false`, and `artifact_file_present=false`. The
preflight must evaluate the same declared bytes through
`production-local-model-runtime-preflight`, with no artifact path and no file.
Its controlled snapshot is exactly 16 GiB total (17,179,869,184 bytes),
10,000,000,000 available, 500,000,000 threshold, and 9,500,000,000 usable. The
production result must be blocked, use the 2,048-token effective context, report
the 10,440,486,640-byte additional-memory estimate and an actionable smaller
model reason, and prove `native_engine_start_attempted=false`,
`native_engine_started=false`, and `requires_app_restart=false`.

## 6. Capture issue #16 fresh Debian and HTTPS evidence

Use the same bound compact-phone APK pair. The full-assets build command in
section 1 must have completed with `-PskipHermesAndroidLinuxAssets=false`.
Choose a run-derived sandbox name which has never existed on this AVD; the
instrumentation fails if the rootfs already exists.

```powershell
$runIdHasher = [Security.Cryptography.SHA256]::Create()
try {
    $runIdHashBytes = $runIdHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($runId))
} finally {
    $runIdHasher.Dispose()
}
$runIdHash = -join ($runIdHashBytes | ForEach-Object { $_.ToString('x2') })
$sandboxName = "hermes-debian-h16-$($runIdHash.Substring(0,16))"
$issue16Method = 'com.mobilefork.hermesagent.LiveDebianSandboxInstrumentedTest#oneClickDebianRunsGuestBinariesWithoutWritableHostFallback'
$issue16Lines = @(& adb -s $serial shell am instrument -w -r @bind `
    -e profile phone-compact `
    -e run_live_debian_sandbox true `
    -e live_debian_sandbox_name $sandboxName `
    -e class $issue16Method `
    $runner 2>&1 | ForEach-Object { "$_" })
$issue16Exit = $LASTEXITCODE
[IO.File]::WriteAllLines((Join-Path $issueStage 'issue-16-instrumentation.txt'), $issue16Lines, $utf8NoBom)
if ($issue16Exit -ne 0 -or -not ($issue16Lines -contains 'OK (1 test)')) {
    throw "Issue #16 instrumentation failed with exit $issue16Exit"
}
$issue16Json = Join-Path $issueStage 'issue-16-debian-sandbox.json'
Export-InstrumentationJson -Lines $issue16Lines `
    -Key 'issue16_runtime_proof' -Destination $issue16Json
```

The fixed schema `hermes-android-issue-16-debian-sandbox-v1` must carry the
same full release/device/profile identity as issue #8. The validator requires:

- the real `hermes-linux/<abi>/manifest.json` asset path and matching SHA-256,
  `packaged_asset_skipped=false`, no asset-refresh error, embedded Termux mode,
  and the direct-exec patch;
- existing, executable, trusted PRoot, QEMU-user, and coreutils routes whose
  canonical targets are package-manager-extracted APK native libraries rather
  than writable app data;
- a fresh Debian deployment (`sandbox_existed_before=false`), deploy and update
  exit zero, `deployment_completed=true`, `sandbox_state=ready`, the exact
  900-second request, and the curl-installing apt command;
- a positive Android-trust-root certificate count, source, guest bundle path,
  and bundle SHA-256;
- exact observed guest-only PATH
  `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, with `id`,
  `uname`, and `curl` resolving inside the guest;
- separate `proot_distro_qemu` command records, each with exit zero and blank
  stderr, for `id`, `uname -a`, `curl --version`, and
  `curl -fsS https://example.com/ >/dev/null && printf 'HTTPS_OK\n'`; and
- after a successful proof, uninstall exit zero, status exit zero, agent shell
  disabled, sandbox absent, and disposition `sandbox_removed_stopped`.

The producer emits its final record after cleanup and includes
`validation_errors=[]`. A failed proof may stop and preserve an incomplete
sandbox for inspection, but that honest failure/retry disposition is not
accepted as passing v3 evidence.

## 7. Verify the live persisted palette, then capture launch frames

Find the `appearance-custom-light` semantics proof in the extracted complete
inventory. Its palette fields are the values which must be saved through the
visible Appearance UI after instrumentation restores the pre-test settings.

```powershell
$paletteProofMatches = @(Get-ChildItem $phoneRaw -File -Filter '*-semantics.txt' |
    Where-Object { Select-String -Path $_.FullName -SimpleMatch 'evidence_identity=appearance-custom-light' -Quiet })
if ($paletteProofMatches.Count -ne 1) { throw 'Expected one bound custom-light palette proof' }
$paletteProof = $paletteProofMatches[0]
Select-String -Path $paletteProof.FullName `
    -Pattern '^(theme_(primary|secondary|background|surface|surface_variant)|card_shape|ui_font_scale)='
```

Save those exact values in the installed app, return to the visible launcher,
and run capture. `--palette-proof` replaces the old caller-supplied theme label:
the script reads `shared_prefs/hermes_android_settings.xml` with `run-as`,
filters only the palette fields, and rejects any difference from the rendered
proof before recording either launch.

```powershell
$launchRaw = Join-Path $stage "$runId-phone-launch"
python scripts/android_launch_theme_evidence.py capture `
    --serial $serial --avd-name $avdName --expected-profile phone `
    --palette-proof $paletteProof.FullName `
    --evidence-run-id $runId --source-digest $sourceDigest `
    --candidate-apk-sha256 $candidateSha `
    --instrumentation-apk-sha256 $testSha `
    --launcher-x 540 --launcher-y 1450 `
    --output-dir $launchRaw
```

Use the actual visible Hermes icon coordinates for that launcher. Repeat on the
tablet with `--expected-profile tablet` and the tablet custom-light proof.

Inspect both MP4 files for each profile frame by frame. Confirm the Android 12+
static Hermes splash is present, no black/white/legacy/third-party frame appears
before Hermes, and handoff to the persisted custom-light palette has no
contrasting flash. Only then record the human decision:

```powershell
$launchManifestMatches = @(Get-ChildItem $launchRaw -File -Filter '*-manifest.json')
if ($launchManifestMatches.Count -ne 1) { throw 'Expected one launch-theme manifest' }
$launchManifest = $launchManifestMatches[0]
python scripts/android_launch_theme_evidence.py review `
    --manifest $launchManifest.FullName `
    --reviewer 'Reviewer name' --decision pass `
    --reviewed-at-utc '2026-08-14T20:15:00Z' `
    --notes 'Launcher and deep-link MP4s reviewed frame by frame.'
```

The review command verifies referenced artifact hashes and records the explicit
decision. It does not analyze or self-certify pixels. A pending or failed review
is rejected by the release-evidence validator.

## 8. Merge into the closed release layout

Create only the v3 additions below. Preserve all existing `ui/`,
`performance/`, and `models/` paths required by the v2 contract.

```text
android/release-evidence/<tag>/
├── physical-device/                         # required from v0.13.151
│   └── nanbeige4.2-3b-q4-k-m-repair.json
├── issues/
│   ├── issue-8-tool-and-preflight.json
│   └── issue-16-debian-sandbox.json
├── models/
│   └── gemma-4-e4b-litert-lm.json
├── ui-coverage/
│   ├── phone-compact/
│   │   ├── complete-inventory.txt
│   │   ├── localized-inventory.txt
│   │   └── <only screenshots/proofs referenced by those inventories>
│   └── tablet/
│       ├── complete-inventory.txt
│       └── <only screenshots/proofs referenced by that inventory>
└── launch-theme/
    ├── phone-compact/
    │   ├── manifest.json
    │   └── <only files referenced by the manifest>
    └── tablet/
        ├── manifest.json
        └── <only files referenced by the manifest>
```

Copy only files whose names begin with the current `headed-$runId-` prefix.
Rename the extracted inventory files to the fixed names above without editing
their contents. Copy every launch output from its clean staging directory and
rename only the dynamic `*-manifest.json` to `manifest.json`; referenced video,
screenshot, activity-dump, and persisted-palette names stay unchanged.

For `v0.13.151+`, copy the already validated physical JSON to its one fixed path
without reformatting it. It is source-digest and signed-candidate bound; an AVD
record or a record generated from the later release artifact is not a substitute.

Copy the two instrumentation-emitted JSON values to their fixed paths without
adding fields, reformatting values, or substituting a handwritten summary:

```powershell
$evidenceRoot = Join-Path 'android/release-evidence' $tag
$issueDir = Join-Path $evidenceRoot 'issues'
New-Item -ItemType Directory -Force $issueDir | Out-Null
Copy-Item -LiteralPath $issue8Json `
    -Destination (Join-Path $issueDir 'issue-8-tool-and-preflight.json')
Copy-Item -LiteralPath $issue16Json `
    -Destination (Join-Path $issueDir 'issue-16-debian-sandbox.json')
```

Do not add extraction archives, instrumentation stdout, reviewer scratch notes,
or an unreferenced screenshot. The validator computes the expected dynamic path
set from the three inventories and two launch manifests, then rejects both
missing and extra files.

## 9. Validate, manifest, commit, and re-verify

Run the repository tests through the canonical wrapper, then create the
deterministic v3 manifest while the source tree is clean outside the evidence
directory:

```powershell
$env:HERMES_TEST_WORKERS = '12'
wsl.exe bash -lc "cd /mnt/c/Users/adyba/hermes-agent-android-overhaul && HERMES_TEST_WORKERS=12 scripts/run_tests.sh tests/hermes_android --dist=worksteal"
git diff --check
actionlint
python scripts/android_release_evidence.py create --tag $tag
git add "android/release-evidence/$tag"
git commit -m "release(android): certify $tag headed-device evidence"
git tag -a $tag -m "Hermes Agent Fork $tag"
python scripts/android_release_evidence.py verify --tag $tag --require-tag-ref
```

Manifest v3 hashes every added file and records the comprehensive UI capture
count, four launch lanes, two completed human reviews, and one passing record
for each issue-specific lane. Any source, registry, APK identity, run ID,
device/profile identity, direct-tool counter, 12B metadata/policy/preflight
decision, packaged-runtime route, Debian/CA/HTTPS result, cleanup disposition,
persisted palette, review, referenced artifact, or byte change invalidates
verification.
