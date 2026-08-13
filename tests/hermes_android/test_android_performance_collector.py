from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIGEST = "d" * 64
TARGET_SHA = "c" * 64
BENCHMARK_SHA = "e" * 64
RUN_ID = "release-v0.13.147-live-5566"
BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
SERIAL = "emulator-5566"
AVD = "Medium_Phone_API_35"
FINGERPRINT = "google/sdk_gphone64_x86_64/emu64:15/test-keys"
MODEL = "sdk_gphone64_x86_64"
QEMU_COMMAND = (
    '"C:\\Users\\private-builder\\AppData\\Local\\Android\\Sdk\\emulator\\qemu\\'
    'windows-x86_64\\qemu-system-x86_64.exe" '
    f"-avd {AVD} -gpu host -accel on -port 5566 "
    '-data "C:\\Users\\private-builder\\.android\\avd\\Medium_Phone_API_35.avd\\userdata-qemu.img"'
)
PUBLIC_QEMU_COMMAND = (
    f"qemu-system-x86_64.exe -avd {AVD} -port 5566 -gpu host -accel on"
)


def _dexopt_dump(*statuses: str, include_base_path: bool = True) -> str:
    lines = ["Packages:", "Dexopt state:", "  [com.mobilefork.hermesagent]"]
    if include_base_path:
        lines.append("    path: /data/app/hermes/base.apk")
        lines.extend(
            f"      x86_64: [status={status}] [reason=cmdline]" for status in statuses
        )
    lines.extend(["Compiler stats:", "  [com.mobilefork.hermesagent]"])
    return "\n".join(lines) + "\n"


def _load_module(name: str, relative_path: str):
    spec = importlib.util.spec_from_file_location(name, REPO_ROOT / relative_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def release_module():
    return _load_module("android_release_evidence", "scripts/android_release_evidence.py")


@pytest.fixture(scope="module")
def collector_module(release_module):
    del release_module
    return _load_module(
        "android_collect_performance_evidence", "scripts/android_collect_performance_evidence.py"
    )


def _token(profile: str = "phone-compact") -> int:
    canonical = (
        "hermes-macrobenchmark-evidence-v2\n"
        f"{SOURCE_DIGEST}\n{TARGET_SHA}\n{BENCHMARK_SHA}\n{RUN_ID}\n{profile}\n"
        f"{AVD}\n{BOOT_ID}\n"
    )
    return int(hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:13], 16)


def _single_metric(runs: list[float | int]) -> dict:
    ordered = sorted(float(value) for value in runs)
    middle = len(ordered) // 2
    median = (
        ordered[middle]
        if len(ordered) % 2
        else (ordered[middle - 1] + ordered[middle]) / 2
    )
    return {
        "minimum": min(runs),
        "maximum": max(runs),
        "median": median,
        "coefficientOfVariation": 0.0,
        "runs": runs,
    }


def _sampled_metric(runs: list[list[float]]) -> dict:
    pooled = sorted(value for iteration in runs for value in iteration)

    def percentile(percent: int) -> float:
        ideal = percent / 100.0 * (len(pooled) - 1)
        lower = int(ideal)
        upper = min(lower + 1, len(pooled) - 1)
        return pooled[lower] + (pooled[upper] - pooled[lower]) * (ideal - lower)

    return {
        "P50": percentile(50),
        "P90": percentile(90),
        "P95": percentile(95),
        "P99": percentile(99),
        "runs": runs,
    }


def _macro_report(
    *,
    profile: str = "phone-compact",
    iterations: int = 5,
    frames_per_iteration: int = 24,
    self_jank_tagged_per_iteration: int = 1,
    app_deadline_missed_per_iteration: int | None = None,
    other_jank_tagged_per_iteration: int = 2,
    dropped_per_iteration: int = 0,
    deadline_dropped_overlap_per_iteration: int = 0,
    app_deadline_missed_or_dropped_per_iteration: int | None = None,
    unknown_tag_per_iteration: int = 0,
    overlapping_jank_tag_per_iteration: int = 0,
    positive_overrun_per_iteration: int = 2,
    token_delta: int = 0,
) -> dict:
    frame_counts = [frames_per_iteration] * iterations
    totals = [frames_per_iteration] * iterations
    self_jank_tagged = [self_jank_tagged_per_iteration] * iterations
    deadline_per_iteration = (
        self_jank_tagged_per_iteration
        if app_deadline_missed_per_iteration is None
        else app_deadline_missed_per_iteration
    )
    deadline = [deadline_per_iteration] * iterations
    deadline_or_dropped_per_iteration = (
        deadline_per_iteration
        + dropped_per_iteration
        - deadline_dropped_overlap_per_iteration
        if app_deadline_missed_or_dropped_per_iteration is None
        else app_deadline_missed_or_dropped_per_iteration
    )
    deadline_or_dropped = [deadline_or_dropped_per_iteration] * iterations
    non_deadline_self = [
        self_jank_tagged_per_iteration - deadline_per_iteration
    ] * iterations
    other_jank_tagged = [other_jank_tagged_per_iteration] * iterations
    dropped = [dropped_per_iteration] * iterations
    unknown_tag = [unknown_tag_per_iteration] * iterations
    overlapping_jank_tag = [overlapping_jank_tag_per_iteration] * iterations
    percentages = [
        self_jank_tagged_per_iteration * 100.0 / frames_per_iteration
    ] * iterations
    evidence_tokens = [_token(profile) + token_delta] * iterations
    duration_runs = [
        [8.0 + (sample % 4) for sample in range(frames_per_iteration)]
        for _ in range(iterations)
    ]
    overrun_runs = [
        [
            1.0 if sample < positive_overrun_per_iteration else (0.0 if sample == positive_overrun_per_iteration else -1.0)
            for sample in range(frames_per_iteration)
        ]
        for _ in range(iterations)
    ]
    return {
        "context": {
            "build": {
                "brand": "google",
                "device": "emu64",
                "fingerprint": FINGERPRINT,
                "id": "test",
                "model": MODEL,
                "type": "userdebug",
                "version": {"codename": "REL", "sdk": 35},
            },
            "cpuCoreCount": 8,
            "cpuLocked": False,
            "cpuMaxFreqHz": 4_000_000_000,
            "memTotalBytes": 8_000_000_000,
            "sustainedPerformanceModeEnabled": False,
            "artMainlineVersion": 1,
            "osCodenameAbbreviated": "REL",
            "compilationMode": "run-from-apk",
            "payload": {
                "sourceDigest": SOURCE_DIGEST,
                "targetApkSha256": TARGET_SHA,
                "benchmarkApkSha256": BENCHMARK_SHA,
                "evidenceRunId": RUN_ID,
                "evidenceProfile": profile,
                "avdName": AVD,
                "bootId": BOOT_ID,
            },
        },
        "benchmarks": [
            {
                "name": "settingsListFling",
                "params": {},
                "className": (
                    "com.mobilefork.hermesagent.macrobenchmark."
                    "HermesSettingsScrollBenchmark"
                ),
                "totalRunTimeNs": 20_000_000_000,
                "metrics": {
                    "frameCount": _single_metric(frame_counts),
                    "hermesFrameTotalCount": _single_metric(totals),
                    "hermesFrameSelfJankTaggedCount": _single_metric(
                        self_jank_tagged
                    ),
                    "hermesFrameAppDeadlineMissedCount": _single_metric(deadline),
                    "hermesFrameAppDeadlineMissedOrDroppedCount": _single_metric(
                        deadline_or_dropped
                    ),
                    "hermesFrameNonDeadlineSelfJankTaggedCount": _single_metric(
                        non_deadline_self
                    ),
                    "hermesFrameOtherJankTaggedCount": _single_metric(
                        other_jank_tagged
                    ),
                    "hermesFrameDroppedCount": _single_metric(dropped),
                    "hermesFrameUnknownTagCount": _single_metric(unknown_tag),
                    "hermesFrameOverlappingJankTagCount": _single_metric(
                        overlapping_jank_tag
                    ),
                    "hermesFrameSelfJankTaggedPercent": _single_metric(percentages),
                    "hermesEvidenceToken": _single_metric(evidence_tokens),
                },
                "sampledMetrics": {
                    "frameDurationCpuMs": _sampled_metric(duration_runs),
                    "frameOverrunMs": _sampled_metric(overrun_runs),
                },
                "warmupIterations": 0,
                "repeatIterations": iterations,
                "thermalThrottleSleepSeconds": 0,
                "profilerOutputs": [
                    {
                        "type": "PerfettoTrace",
                        "label": f"Trace Iteration {index - 1}",
                        "filename": f"settings_iter{index:03d}.perfetto-trace",
                    }
                    for index in range(1, iterations + 1)
                ],
            }
        ],
    }


def _invocation(profile: str = "phone-compact") -> dict:
    benchmark_class = (
        "com.mobilefork.hermesagent.macrobenchmark.HermesSettingsScrollBenchmark"
    )
    return {
        "schema": "hermes-android-macrobenchmark-invocation-v1",
        "argv": [
            "gradlew.bat",
            ":macrobenchmark:connectedBenchmarkAndroidTest",
            f"-PhermesBenchmarkExpectedSourceDigest={SOURCE_DIGEST}",
            "-PhermesBenchmarkExpectedVersionName=0.13.147",
            "-PhermesBenchmarkExpectedVersionCode=144790",
            "-PhermesBenchmarkExpectedLiteRtLmCoordinate="
            "com.google.ai.edge.litertlm:litertlm-android:0.16.0",
            f"-PhermesBenchmarkTargetApkSha256={TARGET_SHA}",
            f"-PhermesBenchmarkApkSha256={BENCHMARK_SHA}",
            f"-PhermesBenchmarkEvidenceRunId={RUN_ID}",
            f"-PhermesBenchmarkEvidenceProfile={profile}",
            f"-PhermesBenchmarkExpectedAvdName={AVD}",
            f"-PhermesBenchmarkExpectedBootId={BOOT_ID}",
            f"-Pandroid.testInstrumentationRunnerArguments.class={benchmark_class}#settingsListFling",
            "-Pandroid.testInstrumentationRunnerArguments."
            "androidx.benchmark.suppressErrors=EMULATOR",
            "-Pandroid.testInstrumentationRunnerArguments."
            "androidx.benchmark.profiling.mode=None",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.sourceDigest={SOURCE_DIGEST}",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.targetApkSha256={TARGET_SHA}",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.benchmarkApkSha256={BENCHMARK_SHA}",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.evidenceRunId={RUN_ID}",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.evidenceProfile={profile}",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.avdName={AVD}",
            "-Pandroid.testInstrumentationRunnerArguments."
            f"androidx.benchmark.output.payload.bootId={BOOT_ID}",
            "--no-daemon",
            "--console=plain",
        ],
        "exit_code": 0,
        "stdout": "5 tests completed\nBUILD SUCCESSFUL in 1m\n",
        "stderr": "",
    }


def _inputs(
    tmp_path: Path,
    *,
    profile: str = "phone-compact",
    iterations: int = 5,
    frames_per_iteration: int = 24,
    self_jank_tagged_per_iteration: int = 1,
    app_deadline_missed_per_iteration: int | None = None,
    other_jank_tagged_per_iteration: int = 2,
    dropped_per_iteration: int = 0,
    deadline_dropped_overlap_per_iteration: int = 0,
    app_deadline_missed_or_dropped_per_iteration: int | None = None,
    unknown_tag_per_iteration: int = 0,
    overlapping_jank_tag_per_iteration: int = 0,
    positive_overrun_per_iteration: int = 2,
    token_delta: int = 0,
):
    inputs = tmp_path / "macro-inputs" / profile
    inputs.mkdir(parents=True)
    report_path = inputs / "benchmarkData.json"
    report_path.write_text(
        json.dumps(
            _macro_report(
                profile=profile,
                iterations=iterations,
                frames_per_iteration=frames_per_iteration,
                self_jank_tagged_per_iteration=self_jank_tagged_per_iteration,
                app_deadline_missed_per_iteration=app_deadline_missed_per_iteration,
                other_jank_tagged_per_iteration=other_jank_tagged_per_iteration,
                dropped_per_iteration=dropped_per_iteration,
                deadline_dropped_overlap_per_iteration=(
                    deadline_dropped_overlap_per_iteration
                ),
                app_deadline_missed_or_dropped_per_iteration=(
                    app_deadline_missed_or_dropped_per_iteration
                ),
                unknown_tag_per_iteration=unknown_tag_per_iteration,
                overlapping_jank_tag_per_iteration=overlapping_jank_tag_per_iteration,
                positive_overrun_per_iteration=positive_overrun_per_iteration,
                token_delta=token_delta,
            )
        ),
        encoding="utf-8",
    )
    trace_paths = []
    for index in range(1, iterations + 1):
        trace = inputs / f"settings_iter{index:03d}.perfetto-trace"
        trace.write_bytes(b"PERFETTO\x00" + bytes([index]) * 64)
        trace_paths.append(trace)
    invocation_path = inputs / "invocation.json"
    invocation_path.write_text(json.dumps(_invocation(profile)), encoding="utf-8")
    return report_path, tuple(trace_paths), invocation_path


def _config(module, tmp_path: Path, *, profile: str = "phone-compact", **input_options):
    report, traces, invocation = _inputs(
        tmp_path, profile=profile, **input_options
    )
    return module.CollectorConfig(
        serial=SERIAL,
        profile=profile,
        expected_avd_name=AVD,
        expected_boot_id=BOOT_ID,
        release_source_digest=SOURCE_DIGEST,
        benchmark_target_apk_sha256=TARGET_SHA,
        benchmark_test_apk_sha256=BENCHMARK_SHA,
        evidence_run_id=RUN_ID,
        version_name="0.13.147",
        version_code=144790,
        litertlm_coordinate=module.LITERTLM_COORDINATE,
        macrobenchmark_report=report,
        macrobenchmark_traces=traces,
        macrobenchmark_invocation=invocation,
    )


class FixtureExecutor:
    def __init__(self, module, *, profile: str = "phone-compact"):
        self.module = module
        self.profile = profile
        self.calls: list[tuple[str, ...]] = []
        self.pid_reads = 0
        self.test_sha = BENCHMARK_SHA
        self.compiler_dump_reads = 0
        self.compiler_dumps = [_dexopt_dump("speed"), _dexopt_dump("speed")]

    def result(self, argv, stdout="", *, returncode=0, stderr=""):
        return self.module.CommandResult(tuple(argv), returncode, stdout, stderr)

    def run(self, args, *, timeout_seconds):
        del timeout_seconds
        argv = tuple(str(part) for part in args)
        self.calls.append(argv)
        if argv == ("adb", "devices", "-l"):
            return self.result(argv, f"List of devices attached\n{SERIAL} device product:sdk\n")
        if argv == ("emulator", "-accel-check"):
            return self.result(argv, "accel:\n0\nWHPX is installed and usable.\n")
        prefix = ("adb", "-s", SERIAL)
        assert argv[:3] == prefix, f"unexpected command: {argv!r}"
        command = argv[3:]
        if command == ("get-serialno",):
            return self.result(argv, f"{SERIAL}\n")
        if command == ("get-state",):
            return self.result(argv, "device\n")
        if command[:2] == ("shell", "getprop"):
            values = {
                "ro.boot.qemu.avd_name": AVD,
                "ro.build.fingerprint": FINGERPRINT,
                "ro.product.model": MODEL,
                "ro.build.version.sdk": "35",
                "ro.product.cpu.abilist": "x86_64,x86",
            }
            return self.result(argv, values[command[2]] + "\n")
        if command == ("shell", "cat", "/proc/sys/kernel/random/boot_id"):
            return self.result(argv, BOOT_ID + "\n")
        if command == ("shell", "settings", "get", "system", "font_scale"):
            return self.result(argv, "1.0\n")
        if command == ("shell", "pm", "path", "com.mobilefork.hermesagent"):
            return self.result(argv, "package:/data/app/hermes/base.apk\n")
        if command == ("shell", "sha256sum", "/data/app/hermes/base.apk"):
            return self.result(argv, f"{TARGET_SHA}  /data/app/hermes/base.apk\n")
        if command == (
            "shell",
            "pm",
            "path",
            "com.mobilefork.hermesagent.macrobenchmark",
        ):
            return self.result(argv, "package:/data/app/hermes-benchmark/base.apk\n")
        if command == ("shell", "sha256sum", "/data/app/hermes-benchmark/base.apk"):
            return self.result(
                argv, f"{self.test_sha}  /data/app/hermes-benchmark/base.apk\n"
            )
        if command == ("shell", "dumpsys", "package", "com.mobilefork.hermesagent"):
            return self.result(
                argv,
                "Packages:\n  versionCode=144790 minSdk=31 targetSdk=35\n"
                "  versionName=0.13.147\n",
            )
        if command == (
            "shell",
            "cmd",
            "package",
            "dump",
            "com.mobilefork.hermesagent",
        ):
            index = min(self.compiler_dump_reads, len(self.compiler_dumps) - 1)
            self.compiler_dump_reads += 1
            return self.result(argv, self.compiler_dumps[index])
        if command == ("shell", "wm", "size"):
            size = "1080x2400" if self.profile == "phone-compact" else "1600x2560"
            return self.result(argv, f"Physical size: {size}\n")
        if command == ("shell", "wm", "density"):
            density = 420 if self.profile == "phone-compact" else 320
            return self.result(argv, f"Physical density: {density}\n")
        if command == ("shell", "am", "get-config"):
            config = "w411dp-h891dp" if self.profile == "phone-compact" else "w800dp-h1280dp"
            return self.result(argv, f"config: en-rUS-{config}-normal\n")
        if command == ("shell", "dumpsys", "SurfaceFlinger"):
            return self.result(
                argv,
                "GLES: Google, Android Emulator OpenGL ES Translator (NVIDIA RTX), OpenGL ES 3.2\n",
            )
        if command == ("shell", "am", "force-stop", "com.mobilefork.hermesagent"):
            return self.result(argv)
        if command == (
            "shell",
            "am",
            "start",
            "-W",
            "-S",
            "-n",
            "com.mobilefork.hermesagent/.MainActivity",
        ):
            return self.result(
                argv,
                "Status: ok\nLaunchState: COLD\n"
                "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                "TotalTime: 900\nWaitTime: 950\nComplete\n",
            )
        if command == ("shell", "pidof", "com.mobilefork.hermesagent"):
            self.pid_reads += 1
            return self.result(argv, "8123\n")
        if command == ("shell", "input", "keyevent", "KEYCODE_BACK"):
            return self.result(argv)
        if command == (
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "com.mobilefork.hermesagent/.MainActivity",
        ):
            return self.result(
                argv,
                "Status: ok\nLaunchState: WARM\n"
                "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                "TotalTime: 200\nWaitTime: 210\nComplete\n",
            )
        if command == ("shell", "dumpsys", "activity", "activities"):
            return self.result(
                argv,
                "topResumedActivity=ActivityRecord{abc u0 "
                "com.mobilefork.hermesagent/.MainActivity t1}\n",
            )
        if command == ("shell", "dumpsys", "meminfo", "com.mobilefork.hermesagent"):
            return self.result(
                argv,
                "** MEMINFO in pid 8123 [com.mobilefork.hermesagent] **\n"
                " TOTAL PSS: 250000 TOTAL RSS: 320000 TOTAL SWAP PSS: 0\n",
            )
        raise AssertionError(f"unexpected command: {argv!r}")


class StaticProcessSource:
    def __init__(self, module):
        self.module = module

    def qemu_snapshot(self):
        process = self.module.ProcessInfo(4242, "qemu-system-x86_64.exe", QEMU_COMMAND)
        stdout = json.dumps(
            [{"pid": process.pid, "name": process.name, "command_line": process.command_line}],
            separators=(",", ":"),
        )
        argv = (
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            self.module.QEMU_CIM_SCRIPT,
        )
        return self.module.ProcessSnapshot(
            self.module.CommandResult(argv, 0, stdout, ""), (process,)
        )


class StaticSourceVerifier:
    def __init__(self, module):
        self.module = module
        self.seen: list[str] = []

    def verify(self, expected_digest):
        self.seen.append(expected_digest)
        return self.module.SourceIdentity(
            SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
        )


def _collect(module, tmp_path: Path, *, profile="phone-compact", **input_options):
    config = _config(module, tmp_path, profile=profile, **input_options)
    executor = FixtureExecutor(module, profile=profile)
    verifier = StaticSourceVerifier(module)
    collector = module.PerformanceCollector(
        config, executor, StaticProcessSource(module), verifier
    )
    payload = collector.collect()
    return config, payload, collector.raw_transcript, executor, verifier


def test_collector_produces_validator_accepted_macrobenchmark_v2(
    collector_module, release_module, tmp_path
):
    config, payload, host_raw, executor, verifier = _collect(collector_module, tmp_path)
    output = tmp_path / "evidence" / "performance" / "phone-compact.json"
    collector_module.write_atomic_validated_evidence(
        output,
        payload,
        host_raw,
        config,
        collector_module.ReleaseEvidencePayloadValidator(),
        verifier,
        overwrite=False,
    )

    assert payload["schema"] == "hermes-android-performance-evidence-v2"
    assert payload["benchmark"]["compilation_mode"] == "Full"
    assert payload["benchmark"]["reporting_package_compilation_mode"] == "run-from-apk"
    assert payload["benchmark"]["target_compiler_filter"] == "speed"
    assert payload["benchmark"]["suppressed_errors"] == ["EMULATOR"]
    assert payload["benchmark"]["target_debuggable"] is False
    assert payload["benchmark"]["target_profileable_by_shell"] is True
    assert payload["device"]["active_qemu_process_count"] == 1
    assert payload["device"]["emulator_public_command"] == PUBLIC_QEMU_COMMAND
    assert payload["device"]["emulator_public_command_sha256"] == hashlib.sha256(
        PUBLIC_QEMU_COMMAND.encode("utf-8")
    ).hexdigest()
    assert payload["device"]["emulator_raw_command_sha256"] == hashlib.sha256(
        QEMU_COMMAND.encode("utf-8")
    ).hexdigest()
    assert payload["evidence_classification"] == {
        "environment": "headed-hardware-accelerated-avd",
        "result_kind": "validation-signal",
        "representative_end_user_benchmark": False,
    }
    assert len(payload["traces"]) == 5
    assert payload["frames"]["frame_timing_total_rendered"] == 120
    assert payload["frames"]["frame_timing_overrun_positive"] == 10
    assert payload["frames"]["frame_timing_overrun_positive_percent"] == 100 / 12
    assert payload["frames"]["perfetto_surface_frame_timeline_tokens"] == 120
    assert payload["frames"]["perfetto_self_jank_tagged"] == 5
    assert payload["frames"]["perfetto_app_deadline_missed"] == 5
    assert payload["frames"]["perfetto_app_deadline_missed_percent"] == 100 / 24
    assert payload["frames"]["perfetto_app_deadline_missed_or_dropped"] == 5
    assert (
        payload["frames"]["perfetto_app_deadline_missed_or_dropped_percent"]
        == 100 / 24
    )
    assert payload["frames"]["perfetto_app_deadline_missed_and_dropped"] == 0
    assert payload["frames"]["perfetto_non_deadline_self_jank_tagged"] == 0
    assert payload["frames"]["perfetto_other_jank_tagged"] == 10
    assert payload["frames"]["perfetto_dropped"] == 0
    assert payload["frames"]["perfetto_unknown_tag"] == 0
    assert payload["frames"]["perfetto_overlapping_jank_tag"] == 0
    assert payload["frames"]["perfetto_self_jank_tagged_percent"] == 100 / 24
    assert host_raw["schema"] == "hermes-android-performance-host-raw-v2"
    compiler_records = [
        record
        for record in host_raw["records"]
        if record["id"].startswith("measure.package.target_compiler_filter.")
    ]
    assert [record["id"] for record in compiler_records] == [
        "measure.package.target_compiler_filter.initial",
        "measure.package.target_compiler_filter.final",
    ]
    assert all(
        record["argv"]
        == [
            "adb",
            "-s",
            SERIAL,
            "shell",
            "cmd",
            "package",
            "dump",
            "com.mobilefork.hermesagent",
        ]
        for record in compiler_records
    )
    qemu_records = [
        record
        for record in host_raw["records"]
        if record["id"].endswith(".host.qemu_processes")
    ]
    assert len(qemu_records) == 2
    for record in qemu_records:
        assert json.loads(record["stdout"]) == [
            {
                "pid": 4242,
                "name": "qemu-system-x86_64.exe",
                "public_command": PUBLIC_QEMU_COMMAND,
                "public_command_sha256": hashlib.sha256(
                    PUBLIC_QEMU_COMMAND.encode("utf-8")
                ).hexdigest(),
                "raw_command_sha256": hashlib.sha256(QEMU_COMMAND.encode("utf-8")).hexdigest(),
            }
        ]
    public_evidence = json.dumps(
        {"normalized": payload, "host_transcript": host_raw}, sort_keys=True
    )
    assert "private-builder" not in public_evidence
    assert "C:\\\\Users\\\\" not in public_evidence
    assert "userdata-qemu.img" not in public_evidence
    assert not any(
        "gfxinfo" in record["argv"] or record["argv"][-2:] == ["input", "swipe"]
        for record in host_raw["records"]
    )
    assert not any("gfxinfo" in call or "swipe" in call for call in executor.calls)
    assert release_module._validate_performance(
        output, "phone-compact", SOURCE_DIGEST, "0.13.147", 144790
    ) == payload
    assert (output.parent / "phone-compact.host.raw.json").is_file()
    assert (output.parent / "phone-compact.macrobenchmark.raw.json").is_file()
    assert len(list((output.parent / "phone-compact.traces").iterdir())) == 5


def test_report_token_must_bind_source_both_apks_run_and_profile(collector_module, tmp_path):
    config = _config(collector_module, tmp_path, token_delta=1)
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="hermesEvidenceToken"):
        collector.collect()


def test_self_instrumenting_report_requires_run_from_apk_context(
    collector_module, tmp_path
):
    config = _config(collector_module, tmp_path)
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    report["context"]["compilationMode"] = "speed"
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="run-from-apk"):
        collector.collect()


@pytest.mark.parametrize(
    "package_dump",
    (
        _dexopt_dump(),
        _dexopt_dump("speed", "speed"),
        _dexopt_dump("verify"),
    ),
)
def test_target_base_apk_requires_one_speed_dexopt_status(
    collector_module, package_dump
):
    with pytest.raises(collector_module.CollectorError, match="status=speed"):
        collector_module._parse_target_compiler_filter(
            package_dump, "/data/app/hermes/base.apk"
        )


def test_target_compiler_filter_is_rechecked_after_measurement(
    collector_module, tmp_path
):
    executor = FixtureExecutor(collector_module)
    executor.compiler_dumps = [_dexopt_dump("speed"), _dexopt_dump("verify")]
    with pytest.raises(collector_module.CollectorError, match="status=speed"):
        _collector_with_executor(collector_module, tmp_path, executor).collect()


def test_invocation_rejects_any_suppression_beyond_emulator(collector_module, tmp_path):
    config = _config(collector_module, tmp_path)
    invocation = json.loads(config.macrobenchmark_invocation.read_text(encoding="utf-8"))
    index = next(
        i for i, value in enumerate(invocation["argv"]) if "suppressErrors=" in value
    )
    invocation["argv"][index] += ",DEBUGGABLE"
    config.macrobenchmark_invocation.write_text(json.dumps(invocation), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="exact release command"):
        collector.collect()


@pytest.mark.parametrize(
    ("options", "message"),
    [
        ({"iterations": 4}, "5 to 20"),
        ({"frames_per_iteration": 10}, "at least 100"),
    ],
)
def test_iteration_frame_and_jank_gates_fail_closed(
    collector_module, tmp_path, options, message
):
    config = _config(collector_module, tmp_path, **options)
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match=message):
        collector.collect()


@pytest.mark.parametrize(
    ("options", "message"),
    (
        ({"other_jank_tagged_per_iteration": 24}, "jank counts"),
        ({"unknown_tag_per_iteration": 1}, "unknown-tag or overlapping Self/Other-tag"),
        (
            {"overlapping_jank_tag_per_iteration": 1},
            "unknown-tag or overlapping Self/Other-tag",
        ),
    ),
)
def test_other_unknown_and_self_other_overlap_perfetto_counts_fail_closed(
    collector_module, tmp_path, options, message
):
    config = _config(collector_module, tmp_path, **options)
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match=message):
        collector.collect()


def test_nonzero_dropped_frames_are_budgeted_with_deadline_misses(
    collector_module, tmp_path
):
    _, payload, _, _, _ = _collect(
        collector_module,
        tmp_path,
        dropped_per_iteration=1,
    )

    assert payload["frames"]["perfetto_dropped"] == 5
    assert payload["frames"]["perfetto_app_deadline_missed_or_dropped"] == 10
    assert (
        payload["frames"]["perfetto_app_deadline_missed_or_dropped_percent"]
        == 100 / 12
    )
    assert payload["frames"]["perfetto_app_deadline_missed_and_dropped"] == 0
    assert all(
        iteration["perfetto_dropped_frames"] == 1
        and iteration["perfetto_app_deadline_missed_or_dropped_frames"] == 2
        and iteration["perfetto_app_deadline_missed_and_dropped_frames"] == 0
        for iteration in payload["frames"]["iterations"]
    )


def test_deadline_and_dropped_overlap_is_derived_from_union(
    collector_module, tmp_path
):
    _, payload, _, _, _ = _collect(
        collector_module,
        tmp_path,
        dropped_per_iteration=1,
        deadline_dropped_overlap_per_iteration=1,
    )

    assert payload["frames"]["perfetto_app_deadline_missed_or_dropped"] == 5
    assert payload["frames"]["perfetto_app_deadline_missed_and_dropped"] == 5
    assert all(
        iteration["perfetto_app_deadline_missed_or_dropped_frames"] == 1
        and iteration["perfetto_app_deadline_missed_and_dropped_frames"] == 1
        for iteration in payload["frames"]["iterations"]
    )


def test_combined_deadline_and_dropped_budget_accepts_exact_ten_percent(
    collector_module, tmp_path
):
    _, payload, _, _, _ = _collect(
        collector_module,
        tmp_path,
        frames_per_iteration=20,
        dropped_per_iteration=1,
    )

    assert (
        payload["frames"]["perfetto_app_deadline_missed_or_dropped_percent"]
        == 10.0
    )


@pytest.mark.parametrize(
    "options",
    (
        {
            "frames_per_iteration": 20,
            "self_jank_tagged_per_iteration": 3,
        },
        {
            "frames_per_iteration": 20,
            "self_jank_tagged_per_iteration": 0,
            "dropped_per_iteration": 3,
        },
        {
            "frames_per_iteration": 20,
            "self_jank_tagged_per_iteration": 1,
            "dropped_per_iteration": 2,
        },
    ),
    ids=("deadline", "dropped", "combined"),
)
def test_combined_deadline_and_dropped_budget_rejects_each_over_ten_source(
    collector_module, tmp_path, options
):
    config = _config(collector_module, tmp_path, **options)
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )

    with pytest.raises(
        collector_module.CollectorError,
        match="App Deadline Missed or Dropped Frame",
    ):
        collector.collect()


@pytest.mark.parametrize(
    "union_per_iteration",
    (0, 3),
    ids=("below-max", "above-sum"),
)
def test_deadline_dropped_union_divergence_fails_closed(
    collector_module, tmp_path, union_per_iteration
):
    config = _config(
        collector_module,
        tmp_path,
        dropped_per_iteration=1,
        app_deadline_missed_or_dropped_per_iteration=union_per_iteration,
    )
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )

    with pytest.raises(collector_module.CollectorError, match="union does not reconcile"):
        collector.collect()


def test_non_deadline_self_jank_must_reconcile_with_self_jank(
    collector_module, tmp_path
):
    config = _config(collector_module, tmp_path)
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    metric = report["benchmarks"][0]["metrics"][
        "hermesFrameNonDeadlineSelfJankTaggedCount"
    ]
    metric["runs"][0] = 1
    metric["maximum"] = 1
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="jank counts"):
        collector.collect()


def test_positive_frame_overrun_is_a_nongating_avd_diagnostic(
    collector_module, tmp_path
):
    _, zero_payload, _, _, _ = _collect(
        collector_module,
        tmp_path / "zero",
        frames_per_iteration=20,
        positive_overrun_per_iteration=0,
    )
    assert zero_payload["frames"]["frame_timing_overrun_positive"] == 0
    assert zero_payload["frames"]["frame_timing_overrun_positive_percent"] == 0.0

    _, high_overrun_payload, _, _, _ = _collect(
        collector_module,
        tmp_path / "high-overrun",
        frames_per_iteration=20,
        positive_overrun_per_iteration=3,
    )
    assert high_overrun_payload["frames"]["frame_timing_overrun_positive"] == 15
    assert high_overrun_payload["frames"]["frame_timing_overrun_positive_percent"] == 15.0
    assert high_overrun_payload["frames"]["perfetto_app_deadline_missed_percent"] == 5.0
    assert high_overrun_payload["evidence_classification"]["result_kind"] == (
        "validation-signal"
    )
    assert all(
        iteration["frame_timing_overrun_positive_frames"] == 3
        and iteration["frame_timing_overrun_positive_percent"] == 15.0
        for iteration in high_overrun_payload["frames"]["iterations"]
    )


@pytest.mark.parametrize(
    ("case", "expected_p95", "expected_p99", "rejected"),
    (
        ("p95-boundary", 50.0, 50.0, False),
        ("p95-over", 51.0, 51.0, True),
        ("p99-boundary", 50.0, 100.0, False),
        ("p99-over", 50.0, 101.0, True),
    ),
)
def test_frame_duration_cpu_controlled_avd_boundaries(
    collector_module, tmp_path, case, expected_p95, expected_p99, rejected
):
    config = _config(collector_module, tmp_path)
    if case == "p95-boundary":
        flattened = [50.0] * 120
    elif case == "p95-over":
        flattened = [51.0] * 120
    elif case == "p99-boundary":
        flattened = [50.0] * 117 + [100.0] * 3
    else:
        flattened = [50.0] * 117 + [101.0] * 3
    runs = [flattened[index : index + 24] for index in range(0, 120, 24)]
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    report["benchmarks"][0]["sampledMetrics"]["frameDurationCpuMs"] = (
        _sampled_metric(runs)
    )
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    if rejected:
        with pytest.raises(collector_module.CollectorError, match="CPU-work ceilings"):
            collector.collect()
    else:
        frames = collector.collect()["frames"]
        assert frames["p95_ms"] == expected_p95
        assert frames["p99_ms"] == expected_p99


def test_frame_duration_cpu_rejects_negative_samples(collector_module, tmp_path):
    config = _config(collector_module, tmp_path)
    runs = [[-1.0] + [5.0] * 23 for _ in range(5)]
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    report["benchmarks"][0]["sampledMetrics"]["frameDurationCpuMs"] = (
        _sampled_metric(runs)
    )
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )

    with pytest.raises(collector_module.CollectorError, match="cannot contain negative"):
        collector.collect()


def test_exact_benchmark_test_apk_hash_is_reobserved_before_and_after(
    collector_module, tmp_path
):
    config = _config(collector_module, tmp_path)
    executor = FixtureExecutor(collector_module)
    executor.test_sha = "f" * 64
    collector = collector_module.PerformanceCollector(
        config,
        executor,
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="differs from exact input"):
        collector.collect()


def test_macrobenchmark_report_device_identity_must_match_live_avd(collector_module, tmp_path):
    config = _config(collector_module, tmp_path)
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    report["context"]["build"]["fingerprint"] = "stale/fingerprint"
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="live AVD"):
        collector.collect()


@pytest.mark.parametrize(
    ("field", "replacement"),
    (
        ("avdName", "Stale_Phone_API_35"),
        ("bootId", "87654321-4321-4abc-8def-1234567890ab"),
    ),
)
def test_macrobenchmark_payload_must_bind_exact_avd_boot_identity(
    collector_module, tmp_path, field, replacement
):
    config = _config(collector_module, tmp_path)
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    report["context"]["payload"][field] = replacement
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="context payload"):
        collector.collect()


@pytest.mark.parametrize(
    ("metric", "percentile", "replacement"),
    (("frameDurationCpuMs", "P50", 9.6), ("frameOverrunMs", "P99", 1.5)),
)
def test_sampled_percentiles_must_reproduce_pooled_androidx_runs(
    collector_module, tmp_path, metric, percentile, replacement
):
    config = _config(collector_module, tmp_path)
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    report["benchmarks"][0]["sampledMetrics"][metric][percentile] = replacement
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="pooled AndroidX runs"):
        collector.collect()


def test_profiler_output_labels_bind_exact_iteration_order(collector_module, tmp_path):
    config = _config(collector_module, tmp_path)
    report = json.loads(config.macrobenchmark_report.read_text(encoding="utf-8"))
    outputs = report["benchmarks"][0]["profilerOutputs"]
    outputs[0]["label"], outputs[1]["label"] = outputs[1]["label"], outputs[0]["label"]
    config.macrobenchmark_report.write_text(json.dumps(report), encoding="utf-8")
    collector = collector_module.PerformanceCollector(
        config,
        FixtureExecutor(collector_module),
        StaticProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="iteration order"):
        collector.collect()


def test_atomic_writer_preserves_existing_diagnostics_when_validation_fails(
    collector_module, tmp_path
):
    config, payload, raw, _, verifier = _collect(collector_module, tmp_path)
    output = tmp_path / "evidence" / "performance" / "phone-compact.json"
    collector_module.write_atomic_validated_evidence(
        output,
        payload,
        raw,
        config,
        collector_module.ReleaseEvidencePayloadValidator(),
        verifier,
        overwrite=False,
    )
    before = {
        path.relative_to(output.parent).as_posix(): path.read_bytes()
        for path in output.parent.rglob("*")
        if path.is_file()
    }

    class RejectingValidator:
        def validate(self, path, host_raw_path, macrobenchmark_raw_path, trace_paths, config):
            del path, host_raw_path, macrobenchmark_raw_path, trace_paths, config
            raise collector_module.CollectorError("synthetic rejection")

    with pytest.raises(collector_module.CollectorError, match="synthetic rejection"):
        collector_module.write_atomic_validated_evidence(
            output,
            payload,
            raw,
            config,
            RejectingValidator(),
            verifier,
            overwrite=True,
        )
    after = {
        path.relative_to(output.parent).as_posix(): path.read_bytes()
        for path in output.parent.rglob("*")
        if path.is_file()
    }
    assert after == before


def test_start_parser_keeps_bounded_warm_retry_contract(collector_module):
    valid = (
        "Status: ok\nLaunchState: WARM\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\n"
        "TotalTime: 200\nWaitTime: 210\nComplete\n"
    )
    assert collector_module._parse_start_metrics(valid, expected_states={"WARM"}) == {
        "TotalTime": 200,
        "WaitTime": 210,
    }
    duplicate = valid + "TotalTime: 201\n"
    with pytest.raises(collector_module.CollectorError, match="exactly one TotalTime"):
        collector_module._parse_start_metrics(duplicate, expected_states={"WARM"})


def test_git_source_verifier_brackets_clean_identity_checks(
    collector_module, monkeypatch, tmp_path
):
    identity = collector_module.SourceIdentity(
        SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
    )

    class Fixture:
        def __init__(self):
            self.events = []

        def require_source_clean_for_create(self, repo_root, evidence_root):
            del repo_root, evidence_root
            self.events.append("clean")

        def git_source_tree_identity(self, repo_root):
            del repo_root
            self.events.append("identity")
            return identity

    fixture = Fixture()
    monkeypatch.setattr(
        collector_module, "_load_release_evidence_module", lambda: fixture
    )
    assert collector_module.GitSourceVerifier(tmp_path).verify(SOURCE_DIGEST) == identity
    assert fixture.events == ["clean", "identity", "clean", "identity"]


def _collector_with_executor(module, tmp_path, executor, *, verifier=None):
    verifier = verifier or StaticSourceVerifier(module)
    return module.PerformanceCollector(
        _config(module, tmp_path), executor, StaticProcessSource(module), verifier
    )


def test_git_source_verifier_rejects_identity_drift_between_clean_checks(
    collector_module, monkeypatch, tmp_path
):
    identities = iter(
        (
            collector_module.SourceIdentity(
                SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
            ),
            collector_module.SourceIdentity(
                "f" * 64, "sha256-git-tree-contents-v1", 123, "sha1"
            ),
        )
    )

    class Fixture:
        @staticmethod
        def require_source_clean_for_create(repo_root, evidence_root):
            del repo_root, evidence_root

        @staticmethod
        def git_source_tree_identity(repo_root):
            del repo_root
            return next(identities)

    monkeypatch.setattr(
        collector_module, "_load_release_evidence_module", lambda: Fixture()
    )
    with pytest.raises(collector_module.CollectorError, match="identity changed"):
        collector_module.GitSourceVerifier(tmp_path).verify(SOURCE_DIGEST)


def test_ambiguous_matching_qemu_processes_fail_closed(collector_module, tmp_path):
    class AmbiguousProcessSource(StaticProcessSource):
        def qemu_snapshot(self):
            snapshot = super().qemu_snapshot()
            duplicate = self.module.ProcessInfo(
                4243, "qemu-system-x86_64.exe", QEMU_COMMAND
            )
            return self.module.ProcessSnapshot(
                snapshot.query, (*snapshot.processes, duplicate)
            )

    collector = collector_module.PerformanceCollector(
        _config(collector_module, tmp_path),
        FixtureExecutor(collector_module),
        AmbiguousProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="exactly one total live qemu-system"):
        collector.collect()


def test_absolute_two_emulator_ceiling_fails_before_target_matching(collector_module, tmp_path):
    class ThreeProcessSource(StaticProcessSource):
        def qemu_snapshot(self):
            snapshot = super().qemu_snapshot()
            extras = (
                self.module.ProcessInfo(
                    4243,
                    "qemu-system-x86_64.exe",
                    'qemu-system-x86_64.exe -avd Spare_1 -gpu host -accel on -port 5570',
                ),
                self.module.ProcessInfo(
                    4244,
                    "qemu-system-x86_64.exe",
                    'qemu-system-x86_64.exe -avd Spare_2 -gpu host -accel on -port 5572',
                ),
            )
            return self.module.ProcessSnapshot(snapshot.query, (*snapshot.processes, *extras))

    collector = collector_module.PerformanceCollector(
        _config(collector_module, tmp_path),
        FixtureExecutor(collector_module),
        ThreeProcessSource(collector_module),
        StaticSourceVerifier(collector_module),
    )
    with pytest.raises(collector_module.CollectorError, match="absolute two-emulator limit"):
        collector.collect()


def test_collection_requires_one_exclusive_adb_device_endpoint(collector_module, tmp_path):
    executor = FixtureExecutor(collector_module)
    original = executor.run

    def run(args, *, timeout_seconds):
        argv = tuple(args)
        if argv == ("adb", "devices", "-l"):
            return executor.result(
                argv,
                "List of devices attached\n"
                f"{SERIAL} device product:sdk\n"
                "emulator-5570 device product:sdk\n",
            )
        return original(args, timeout_seconds=timeout_seconds)

    executor.run = run
    with pytest.raises(collector_module.CollectorError, match="exactly one attached adb endpoint"):
        _collector_with_executor(collector_module, tmp_path, executor).collect()


@pytest.mark.parametrize(
    ("stdout", "returncode"),
    (("WHPX is NOT usable\n", 0), ("WHPX is installed and usable.\n", 1)),
)
def test_acceleration_check_failure_or_negative_output_fails_closed(
    collector_module, tmp_path, stdout, returncode
):
    executor = FixtureExecutor(collector_module)
    original = executor.run

    def run(args, *, timeout_seconds):
        if tuple(args) == ("emulator", "-accel-check"):
            return executor.result(args, stdout, returncode=returncode)
        return original(args, timeout_seconds=timeout_seconds)

    executor.run = run
    with pytest.raises(collector_module.CollectorError, match="accel"):
        _collector_with_executor(collector_module, tmp_path, executor).collect()


@pytest.mark.parametrize("drift_kind", ("boot", "font"))
def test_device_boot_or_font_scale_drift_fails_closed(
    collector_module, tmp_path, drift_kind
):
    executor = FixtureExecutor(collector_module)
    original = executor.run
    seen = 0

    def run(args, *, timeout_seconds):
        nonlocal seen
        argv = tuple(args)
        target = (
            ("adb", "-s", SERIAL, "shell", "cat", "/proc/sys/kernel/random/boot_id")
            if drift_kind == "boot"
            else (
                "adb",
                "-s",
                SERIAL,
                "shell",
                "settings",
                "get",
                "system",
                "font_scale",
            )
        )
        if argv == target:
            seen += 1
            if seen == 2:
                value = "87654321-4321-4abc-8def-1234567890ab\n" if drift_kind == "boot" else "1.1\n"
                return executor.result(argv, value)
        return original(args, timeout_seconds=timeout_seconds)

    executor.run = run
    with pytest.raises(
        collector_module.CollectorError,
        match="boot_id|pre-run identity|changed|font_scale",
    ):
        _collector_with_executor(collector_module, tmp_path, executor).collect()


@pytest.mark.parametrize(
    ("target", "output", "message"),
    (
        (
            ("shell", "sha256sum", "/data/app/hermes/base.apk"),
            f"{'f' * 64}  /data/app/hermes/base.apk\n",
            "APK SHA-256",
        ),
        (
            ("shell", "dumpsys", "package", "com.mobilefork.hermesagent"),
            "Packages:\n  versionCode=144790 minSdk=31\n  versionCode=144790 minSdk=31\n  versionName=0.13.147\n",
            "installed version",
        ),
    ),
)
def test_installed_target_and_version_identity_are_fail_closed(
    collector_module, tmp_path, target, output, message
):
    executor = FixtureExecutor(collector_module)
    original = executor.run

    def run(args, *, timeout_seconds):
        argv = tuple(args)
        if argv[3:] == target:
            return executor.result(argv, output)
        return original(args, timeout_seconds=timeout_seconds)

    executor.run = run
    with pytest.raises(collector_module.CollectorError, match=message):
        _collector_with_executor(collector_module, tmp_path, executor).collect()


@pytest.mark.parametrize("pid_read", (2, 3))
def test_warm_process_kill_or_replacement_fails_closed(
    collector_module, tmp_path, pid_read
):
    executor = FixtureExecutor(collector_module)
    original = executor.run

    def run(args, *, timeout_seconds):
        argv = tuple(args)
        if argv[3:] == ("shell", "pidof", "com.mobilefork.hermesagent"):
            executor.pid_reads += 1
            value = "" if executor.pid_reads == pid_read else "8123\n"
            return executor.result(argv, value)
        return original(args, timeout_seconds=timeout_seconds)

    executor.run = run
    with pytest.raises(collector_module.CollectorError, match="process|pidof"):
        _collector_with_executor(collector_module, tmp_path, executor).collect()


def test_cim_process_source_parses_exact_pid_name_and_command_line(
    collector_module,
):
    class Executor:
        def run(self, args, *, timeout_seconds):
            del timeout_seconds
            payload = json.dumps(
                {"pid": 4242, "name": "qemu-system-x86_64.exe", "command_line": QEMU_COMMAND}
            )
            return collector_module.CommandResult(tuple(args), 0, payload, "")

    snapshot = collector_module.WindowsCimProcessSource(Executor()).qemu_snapshot()
    assert snapshot.processes == (
        collector_module.ProcessInfo(4242, "qemu-system-x86_64.exe", QEMU_COMMAND),
    )


@pytest.mark.parametrize("fail_at", (2, 5))
def test_atomic_writer_rolls_back_all_v2_artifacts_on_source_drift(
    collector_module, tmp_path, fail_at
):
    config, payload, raw, _, verifier = _collect(collector_module, tmp_path)
    output = tmp_path / "evidence" / "performance" / "phone-compact.json"
    validator = collector_module.ReleaseEvidencePayloadValidator()
    collector_module.write_atomic_validated_evidence(
        output, payload, raw, config, validator, verifier, overwrite=False
    )
    before = {
        path.relative_to(output.parent).as_posix(): path.read_bytes()
        for path in output.parent.rglob("*")
        if path.is_file()
    }

    class DriftingVerifier(StaticSourceVerifier):
        def __init__(self, module):
            super().__init__(module)
            self.calls = 0

        def verify(self, expected_digest):
            self.calls += 1
            if self.calls == fail_at:
                raise collector_module.CollectorError("source identity changed during commit")
            return super().verify(expected_digest)

    changed = dict(payload)
    changed["recorded_at_epoch_ms"] += 1
    with pytest.raises(collector_module.CollectorError, match="source identity changed"):
        collector_module.write_atomic_validated_evidence(
            output,
            changed,
            raw,
            config,
            validator,
            DriftingVerifier(collector_module),
            overwrite=True,
        )
    after = {
        path.relative_to(output.parent).as_posix(): path.read_bytes()
        for path in output.parent.rglob("*")
        if path.is_file()
    }
    assert after == before
