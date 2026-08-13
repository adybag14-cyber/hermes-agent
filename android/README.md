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
`sys.boot_completed=1`, verify the live emulator/QEMU command, and capture the
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
│   ├── phone-compact.host.raw.json
│   ├── phone-compact.macrobenchmark.raw.json
│   ├── phone-compact.traces/iteration-{001..005}.perfetto-trace
│   ├── tablet.json
│   ├── tablet.host.raw.json
│   ├── tablet.macrobenchmark.raw.json
│   └── tablet.traces/iteration-{001..005}.perfetto-trace
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
than 10 percent pooled App Deadline Missed surface tokens, bounded
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
release coordinate `com.google.ai.edge.litertlm:litertlm-android:0.16.0`, and
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
`hermesFrameNonDeadlineSelfJankTaggedCount`, `hermesFrameOtherJankTaggedCount`,
`hermesFrameDroppedCount`, `hermesFrameUnknownTagCount`,
`hermesFrameOverlappingJankTagCount`, `hermesFrameSelfJankTaggedPercent`, and
`hermesEvidenceToken` for every iteration. `hermesFrameSelfJankTaggedCount` is
the Perfetto visualization tag `Self Jank`, not proof of causal ownership. It
is a non-headline visualization-tag diagnostic and must equal
app-deadline-missed plus non-deadline Self Jank-tagged tokens.
`hermesFrameOtherJankTaggedCount` only records the Perfetto visualization tag
`Other Jank`; it is a non-gating diagnostic and makes no causal claim about
SurfaceFlinger, the emulator, or the system. Dropped, unknown-tag, or
overlapping Self/Other-tag tokens invalidate the evidence instead of
disappearing from the surface-token denominator.
The metric always returns structurally valid counts, even when the performance
budget fails, so the complete JSON and traces remain available for diagnosis.
The host evidence validator sums all five iterations, requires at least 100
FrameTiming samples and at least 100 distinct Hermes surface-frame tokens. Its
controlled AVD gate is the share of exact `App Deadline Missed` surface tokens,
using distinct Hermes surface-frame tokens as its denominator; it rejects an
aggregate share above 10 percent. Perfetto Self Jank-tagged percentage is
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
$coordinate = 'com.google.ai.edge.litertlm:litertlm-android:0.16.0'
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
`adb install -r -t`, then prove their installed `pm path` bytes with
`sha256sum`. Keep that AVD boot alive while the collector rechecks both APKs,
device/QEMU/source identity, launch, PID, and memory before and after the run:

```powershell
Pop-Location
adb -s $serial install -r -t $benchmarkApk
if ($LASTEXITCODE -ne 0) { throw 'Failed to reinstall the prehashed benchmark target APK' }
adb -s $serial install -r -t $benchmarkTestApk.FullName
if ($LASTEXITCODE -ne 0) { throw 'Failed to reinstall the prehashed benchmark test APK' }
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
tokens, and the 10 percent `App Deadline Missed` controlled-AVD budget.
Positive `frameOverrunMs` count and percentage remain bound diagnostics without
a threshold in this AVD-only lane. Pooled `frameDurationCpuMs` P95 and P99 must
remain at or below 50 ms and 100 ms respectively. FrameTiming samples and Perfetto
surface-frame tokens keep distinct, explicitly named denominators.
App-deadline-missed plus non-deadline Self Jank-tagged tokens must reproduce the Perfetto Self
Jank-tagged total. The `Other Jank`-tagged count is diagnostic only and carries no
causal attribution; dropped, unknown-tag, and overlapping-tag counts must all
be zero. It atomically commits the normalized JSON, host raw
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
