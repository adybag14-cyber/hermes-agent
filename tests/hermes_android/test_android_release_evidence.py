from __future__ import annotations

import hashlib
import importlib.util
import json
import struct
import subprocess
import sys
import zlib
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIGEST = "d" * 64
UI_TARGET_SHA = "a" * 64
UI_TEST_SHA = "b" * 64
BENCHMARK_TARGET_SHA = "c" * 64
BENCHMARK_TEST_SHA = "e" * 64
RUN_ID = "release-v0.1.2-synthetic-run"
BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
AVD_NAME = "Hermes_API_35"
TAG = "v0.1.2"
VERSION_NAME = "0.1.2"
VERSION_CODE = 10_290
FINGERPRINT = "google/sdk_gphone64_x86_64/emu64xa:15/test/release-keys"
MODEL = "sdk_gphone64_x86_64"
LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
QEMU_RAW_COMMAND = (
    '"C:\\Users\\private-builder\\AppData\\Local\\Android\\Sdk\\emulator\\qemu\\'
    'windows-x86_64\\qemu-system-x86_64.exe" '
    f"-avd {AVD_NAME} -gpu host -accel on -port 5566 "
    '-data "C:\\Users\\private-builder\\.android\\avd\\Hermes_API_35.avd\\userdata.img"'
)
QEMU_PUBLIC_COMMAND = (
    f"qemu-system-x86_64.exe -avd {AVD_NAME} -port 5566 -gpu host -accel on"
)
QEMU_PUBLIC_SHA = hashlib.sha256(QEMU_PUBLIC_COMMAND.encode("utf-8")).hexdigest()
QEMU_RAW_SHA = hashlib.sha256(QEMU_RAW_COMMAND.encode("utf-8")).hexdigest()
QEMU_CIM_SCRIPT = (
    "$utf8 = [System.Text.UTF8Encoding]::new($false); "
    "[Console]::OutputEncoding = $utf8; $OutputEncoding = $utf8; "
    "@(Get-CimInstance Win32_Process | "
    "Where-Object { $_.Name -like 'qemu-system-*' -and $_.CommandLine } | "
    "Select-Object @{Name='pid';Expression={[int]$_.ProcessId}},"
    "@{Name='name';Expression={[string]$_.Name}},"
    "@{Name='command_line';Expression={[string]$_.CommandLine}}) | "
    "ConvertTo-Json -Compress"
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


def _load_module():
    script = REPO_ROOT / "scripts/android_release_evidence.py"
    spec = importlib.util.spec_from_file_location("android_release_evidence", script)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def evidence_module():
    return _load_module()


@pytest.fixture
def artifacts(evidence_module):
    return (
        evidence_module.ArtifactSpec(
            model_id="small-litert",
            repository="publisher/mobile-model",
            revision="1" * 40,
            file_name="small.litertlm",
            runtime="litert-lm",
            expected_bytes=123_456,
            sha256="6" * 64,
        ),
        evidence_module.ArtifactSpec(
            model_id="small-gguf",
            repository="publisher/gguf-model",
            revision="2" * 40,
            file_name="small.gguf",
            runtime="llama.cpp",
            expected_bytes=654_321,
            sha256="7" * 64,
        ),
    )


def _chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def _png(width: int, height: int, marker: str) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    seed = hashlib.sha256(marker.encode()).digest()
    # Every channel in the synthetic pattern repeats modulo 256 on both axes.
    # Build that period once instead of regenerating every pixel in Python for
    # each full-resolution release screenshot.  The tiled rows are byte-for-byte
    # identical to the original formula, so the fixture keeps the same color,
    # dimension, content-hash, and per-language-distinctness coverage.
    period = 256
    row_repetitions = (width + period - 1) // period
    x_period = min(width, period)
    y_period = min(height, period)
    periodic_rows = tuple(
        (
            bytes(
                channel
                for x in range(x_period)
                for channel in (
                    (seed[0] + x + y) & 0xFF,
                    (seed[1] + 2 * x + y) & 0xFF,
                    (seed[2] + x + 2 * y) & 0xFF,
                )
            )
            * row_repetitions
        )[: width * 3]
        for y in range(y_period)
    )
    compressor = zlib.compressobj(level=9)
    compressed = bytearray()
    for y in range(height):
        row = periodic_rows[y % period]
        compressed.extend(compressor.compress(b"\x00" + row))
    compressed.extend(compressor.flush())
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", bytes(compressed))
        + _chunk(b"IEND", b"")
    )


@pytest.mark.parametrize(("width", "height"), ((20, 20), (257, 257)))
def test_synthetic_png_tiling_matches_direct_pixel_formula(width, height):
    marker = f"period-boundary-{width}x{height}"
    seed = hashlib.sha256(marker.encode()).digest()
    png = _png(width, height, marker)
    idat_length = struct.unpack(">I", png[33:37])[0]
    assert png[37:41] == b"IDAT"
    decoded = zlib.decompress(png[41 : 41 + idat_length])
    direct = b"".join(
        b"\x00"
        + bytes(
            channel
            for x in range(width)
            for channel in (
                (seed[0] + x + y) & 0xFF,
                (seed[1] + 2 * x + y) & 0xFF,
                (seed[2] + x + 2 * y) & 0xFF,
            )
        )
        for y in range(height)
    )

    assert decoded == direct


def _token(profile: str) -> int:
    canonical = (
        "hermes-macrobenchmark-evidence-v2\n"
        f"{SOURCE_DIGEST}\n{BENCHMARK_TARGET_SHA}\n{BENCHMARK_TEST_SHA}\n"
        f"{RUN_ID}\n{profile}\n{AVD_NAME}\n{BOOT_ID}\n"
    )
    return int(hashlib.sha256(canonical.encode()).hexdigest()[:13], 16)


def _single_metric(runs: list[int | float]) -> dict:
    ordered = sorted(float(value) for value in runs)
    middle = len(ordered) // 2
    median = ordered[middle]
    return {
        "minimum": min(runs),
        "maximum": max(runs),
        "median": median,
        "coefficientOfVariation": 0.0,
        "runs": runs,
    }


def _set_perfetto_jank_runs(
    report: dict,
    *,
    self_jank: int | list[int] = 1,
    deadline: int | list[int] = 1,
    dropped: int | list[int] = 0,
    deadline_or_dropped: int | list[int] | None = None,
    other: int | list[int] = 2,
    unknown: int | list[int] = 0,
    self_other_overlap: int | list[int] = 0,
) -> None:
    benchmark = report["benchmarks"][0]
    iterations = benchmark["repeatIterations"]

    def expanded(value: int | list[int]) -> list[int]:
        runs = [value] * iterations if isinstance(value, int) else list(value)
        assert len(runs) == iterations
        return runs

    self_runs = expanded(self_jank)
    deadline_runs = expanded(deadline)
    dropped_runs = expanded(dropped)
    union_runs = (
        [left + right for left, right in zip(deadline_runs, dropped_runs)]
        if deadline_or_dropped is None
        else expanded(deadline_or_dropped)
    )
    other_runs = expanded(other)
    unknown_runs = expanded(unknown)
    self_other_overlap_runs = expanded(self_other_overlap)
    total_runs = benchmark["metrics"]["hermesFrameTotalCount"]["runs"]
    metrics = benchmark["metrics"]
    metrics["hermesFrameSelfJankTaggedCount"] = _single_metric(self_runs)
    metrics["hermesFrameAppDeadlineMissedCount"] = _single_metric(deadline_runs)
    metrics["hermesFrameAppDeadlineMissedOrDroppedCount"] = _single_metric(
        union_runs
    )
    metrics["hermesFrameNonDeadlineSelfJankTaggedCount"] = _single_metric(
        [self_count - deadline_count for self_count, deadline_count in zip(self_runs, deadline_runs)]
    )
    metrics["hermesFrameOtherJankTaggedCount"] = _single_metric(other_runs)
    metrics["hermesFrameDroppedCount"] = _single_metric(dropped_runs)
    metrics["hermesFrameUnknownTagCount"] = _single_metric(unknown_runs)
    metrics["hermesFrameOverlappingJankTagCount"] = _single_metric(
        self_other_overlap_runs
    )
    metrics["hermesFrameSelfJankTaggedPercent"] = _single_metric(
        [self_count * 100.0 / total for self_count, total in zip(self_runs, total_runs)]
    )


def _sampled_distribution(runs: list[list[float]]) -> dict:
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


def _macro_report(profile: str) -> dict:
    iterations = 5
    frame_count = 24
    values = [frame_count] * iterations
    one = [1] * iterations
    zero = [0] * iterations
    percent = [100 / frame_count] * iterations
    duration_runs = [[8.0 + sample % 4 for sample in range(frame_count)] for _ in values]
    overrun_runs = [
        [1.0 if sample < 2 else (0.0 if sample == 2 else -1.0) for sample in range(frame_count)]
        for _ in values
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
                "targetApkSha256": BENCHMARK_TARGET_SHA,
                "benchmarkApkSha256": BENCHMARK_TEST_SHA,
                "evidenceRunId": RUN_ID,
                "evidenceProfile": profile,
                "avdName": AVD_NAME,
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
                    "frameCount": _single_metric(values),
                    "hermesFrameTotalCount": _single_metric(values),
                    "hermesFrameSelfJankTaggedCount": _single_metric(one),
                    "hermesFrameAppDeadlineMissedCount": _single_metric(one),
                    "hermesFrameAppDeadlineMissedOrDroppedCount": _single_metric(
                        one
                    ),
                    "hermesFrameNonDeadlineSelfJankTaggedCount": _single_metric(zero),
                    "hermesFrameOtherJankTaggedCount": _single_metric([2] * iterations),
                    "hermesFrameDroppedCount": _single_metric(zero),
                    "hermesFrameUnknownTagCount": _single_metric(zero),
                    "hermesFrameOverlappingJankTagCount": _single_metric(zero),
                    "hermesFrameSelfJankTaggedPercent": _single_metric(percent),
                    "hermesEvidenceToken": _single_metric([_token(profile)] * iterations),
                },
                "sampledMetrics": {
                    "frameDurationCpuMs": _sampled_distribution(duration_runs),
                    "frameOverrunMs": _sampled_distribution(overrun_runs),
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


def _invocation_argv(profile: str) -> list[str]:
    prefix = "-Pandroid.testInstrumentationRunnerArguments."
    return [
        "gradlew.bat",
        ":macrobenchmark:connectedBenchmarkAndroidTest",
        f"-PhermesBenchmarkExpectedSourceDigest={SOURCE_DIGEST}",
        f"-PhermesBenchmarkExpectedVersionName={VERSION_NAME}",
        f"-PhermesBenchmarkExpectedVersionCode={VERSION_CODE}",
        f"-PhermesBenchmarkExpectedLiteRtLmCoordinate={LITERTLM_COORDINATE}",
        f"-PhermesBenchmarkTargetApkSha256={BENCHMARK_TARGET_SHA}",
        f"-PhermesBenchmarkApkSha256={BENCHMARK_TEST_SHA}",
        f"-PhermesBenchmarkEvidenceRunId={RUN_ID}",
        f"-PhermesBenchmarkEvidenceProfile={profile}",
        f"-PhermesBenchmarkExpectedAvdName={AVD_NAME}",
        f"-PhermesBenchmarkExpectedBootId={BOOT_ID}",
        f"{prefix}class=com.mobilefork.hermesagent.macrobenchmark."
        "HermesSettingsScrollBenchmark#settingsListFling",
        f"{prefix}androidx.benchmark.suppressErrors=EMULATOR",
        f"{prefix}androidx.benchmark.profiling.mode=None",
        f"{prefix}androidx.benchmark.output.payload.sourceDigest={SOURCE_DIGEST}",
        f"{prefix}androidx.benchmark.output.payload.targetApkSha256={BENCHMARK_TARGET_SHA}",
        f"{prefix}androidx.benchmark.output.payload.benchmarkApkSha256={BENCHMARK_TEST_SHA}",
        f"{prefix}androidx.benchmark.output.payload.evidenceRunId={RUN_ID}",
        f"{prefix}androidx.benchmark.output.payload.evidenceProfile={profile}",
        f"{prefix}androidx.benchmark.output.payload.avdName={AVD_NAME}",
        f"{prefix}androidx.benchmark.output.payload.bootId={BOOT_ID}",
        "--no-daemon",
        "--console=plain",
    ]


def _command(record_id: str, argv: list[str], stdout="", stderr="") -> dict:
    return {"id": record_id, "argv": argv, "exit_code": 0, "stdout": stdout, "stderr": stderr}


def _host_raw(profile: str, performance: dict) -> dict:
    serial = "emulator-5566"
    adb = "adb"
    targeted = [adb, "-s", serial]
    records = [
        _command(
            "macrobenchmark.invocation",
            _invocation_argv(profile),
            "5 tests completed\nBUILD SUCCESSFUL in 1m\n",
        )
    ]

    def identity(phase: str) -> None:
        records.extend(
            [
                _command(
                    f"{phase}.adb.devices",
                    [adb, "devices", "-l"],
                    f"List of devices attached\n{serial} device product:sdk\n",
                ),
                _command(f"{phase}.adb.get-serialno", [*targeted, "get-serialno"], f"{serial}\n"),
                _command(f"{phase}.adb.get-state", [*targeted, "get-state"], "device\n"),
                _command(
                    f"{phase}.device.getprop.avd_name",
                    [*targeted, "shell", "getprop", "ro.boot.qemu.avd_name"],
                    AVD_NAME + "\n",
                ),
                _command(
                    f"{phase}.device.getprop.build_fingerprint",
                    [*targeted, "shell", "getprop", "ro.build.fingerprint"],
                    FINGERPRINT + "\n",
                ),
                _command(
                    f"{phase}.device.getprop.model",
                    [*targeted, "shell", "getprop", "ro.product.model"],
                    MODEL + "\n",
                ),
                _command(
                    f"{phase}.device.getprop.android_sdk",
                    [*targeted, "shell", "getprop", "ro.build.version.sdk"],
                    "35\n",
                ),
                _command(
                    f"{phase}.device.getprop.supported_abis",
                    [*targeted, "shell", "getprop", "ro.product.cpu.abilist"],
                    "x86_64\n",
                ),
                _command(
                    f"{phase}.device.boot_id",
                    [*targeted, "shell", "cat", "/proc/sys/kernel/random/boot_id"],
                    BOOT_ID + "\n",
                ),
                _command(
                    f"{phase}.device.settings.font_scale",
                    [*targeted, "shell", "settings", "get", "system", "font_scale"],
                    "1.0\n",
                ),
                _command(
                    f"{phase}.package.benchmark_target.path",
                    [*targeted, "shell", "pm", "path", "com.mobilefork.hermesagent"],
                    "package:/data/app/hermes/base.apk\n",
                ),
                _command(
                    f"{phase}.package.benchmark_target.sha256",
                    [*targeted, "shell", "sha256sum", "/data/app/hermes/base.apk"],
                    f"{BENCHMARK_TARGET_SHA}  /data/app/hermes/base.apk\n",
                ),
                _command(
                    f"{phase}.package.benchmark_test.path",
                    [
                        *targeted,
                        "shell",
                        "pm",
                        "path",
                        "com.mobilefork.hermesagent.macrobenchmark",
                    ],
                    "package:/data/app/hermes-benchmark/base.apk\n",
                ),
                _command(
                    f"{phase}.package.benchmark_test.sha256",
                    [*targeted, "shell", "sha256sum", "/data/app/hermes-benchmark/base.apk"],
                    f"{BENCHMARK_TEST_SHA}  /data/app/hermes-benchmark/base.apk\n",
                ),
                _command(
                    f"{phase}.package.version",
                    [*targeted, "shell", "dumpsys", "package", "com.mobilefork.hermesagent"],
                    f"Packages:\n  versionCode={VERSION_CODE} minSdk=31\n"
                    f"  versionName={VERSION_NAME}\n",
                ),
                _command(
                    f"{phase}.host.qemu_processes",
                    [
                        "powershell.exe",
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        QEMU_CIM_SCRIPT,
                    ],
                    json.dumps(
                        [
                            {
                                "pid": 4242,
                                "name": "qemu-system-x86_64.exe",
                                "public_command": performance["device"][
                                    "emulator_public_command"
                                ],
                                "public_command_sha256": performance["device"][
                                    "emulator_public_command_sha256"
                                ],
                                "raw_command_sha256": performance["device"][
                                    "emulator_raw_command_sha256"
                                ],
                            }
                        ],
                        separators=(",", ":"),
                    ),
                ),
            ]
        )

    identity("initial")
    records.append(
        _command(
            "measure.package.target_compiler_filter.initial",
            [
                *targeted,
                "shell",
                "cmd",
                "package",
                "dump",
                "com.mobilefork.hermesagent",
            ],
            _dexopt_dump("speed"),
        )
    )
    screen = performance["screen"]
    records.extend(
        [
            _command("measure.emulator.accel-check", ["emulator", "-accel-check"], "WHPX is installed and usable.\n"),
            _command("measure.screen.wm_size", [*targeted, "shell", "wm", "size"], f"Physical size: {screen['width_px']}x{screen['height_px']}\n"),
            _command("measure.screen.wm_density", [*targeted, "shell", "wm", "density"], f"Physical density: {screen['density_dpi']}\n"),
            _command("measure.screen.am_config", [*targeted, "shell", "am", "get-config"], f"config: en-rUS-w{screen['width_dp']}dp-h{screen['height_dp']}dp-normal\n"),
            _command("measure.gpu.surfaceflinger", [*targeted, "shell", "dumpsys", "SurfaceFlinger"], "GLES: Google, Android Emulator OpenGL ES Translator (NVIDIA RTX), OpenGL ES 3.2\n"),
            _command("measure.launch.force_stop", [*targeted, "shell", "am", "force-stop", "com.mobilefork.hermesagent"]),
            _command("measure.launch.cold", [*targeted, "shell", "am", "start", "-W", "-S", "-n", "com.mobilefork.hermesagent/.MainActivity"], "Status: ok\nLaunchState: COLD\nActivity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 1200\nWaitTime: 1100\n"),
            _command("measure.launch.pid_before_back", [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"], "8123\n"),
            _command("measure.launch.back", [*targeted, "shell", "input", "keyevent", "KEYCODE_BACK"]),
            _command("measure.launch.pid_after_back", [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"], "8123\n"),
            _command("measure.launch.warm", [*targeted, "shell", "am", "start", "-W", "-n", "com.mobilefork.hermesagent/.MainActivity"], "Status: ok\nLaunchState: WARM\nActivity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 400\nWaitTime: 410\n"),
            _command("measure.activity.after_launch", [*targeted, "shell", "dumpsys", "activity", "activities"], "topResumedActivity=ActivityRecord{abc u0 com.mobilefork.hermesagent/.MainActivity t1}\n"),
            _command("measure.memory.meminfo", [*targeted, "shell", "dumpsys", "meminfo", "com.mobilefork.hermesagent"], "** MEMINFO in pid 8123 [com.mobilefork.hermesagent] **\n TOTAL PSS: 250000 TOTAL RSS: 320000 TOTAL SWAP PSS: 0\n"),
            _command("measure.process.pid_after_measurement", [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"], "8123\n"),
        ]
    )
    identity("final")
    records.append(
        _command(
            "measure.package.target_compiler_filter.final",
            [
                *targeted,
                "shell",
                "cmd",
                "package",
                "dump",
                "com.mobilefork.hermesagent",
            ],
            _dexopt_dump("speed"),
        )
    )
    return {
        "schema": "hermes-android-performance-host-raw-v2",
        "profile": profile,
        "release_source_digest": SOURCE_DIGEST,
        "benchmark_target_apk_sha256": BENCHMARK_TARGET_SHA,
        "benchmark_test_apk_sha256": BENCHMARK_TEST_SHA,
        "evidence_run_id": RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "benchmark_test_package_id": "com.mobilefork.hermesagent.macrobenchmark",
        "version_name": VERSION_NAME,
        "version_code": VERSION_CODE,
        "build_variant": "benchmark",
        "litertlm_coordinate": LITERTLM_COORDINATE,
        "records": records,
    }


def _frames(profile: str) -> dict:
    sampled = _macro_report(profile)["benchmarks"][0]["sampledMetrics"]
    duration = sampled["frameDurationCpuMs"]
    overrun = sampled["frameOverrunMs"]
    positive_by_iteration = [
        sum(value > 0.0 for value in iteration) for iteration in overrun["runs"]
    ]
    return {
        "metric_source": "androidx.macrobenchmark.FrameTimingMetric+HermesFrameJankMetric",
        "iterations": [
            {
                "iteration": index,
                "frame_timing_frame_count": 24,
                "frame_timing_overrun_positive_frames": positive_by_iteration[index - 1],
                "frame_timing_overrun_positive_percent": (
                    positive_by_iteration[index - 1] * 100 / 24
                ),
                "perfetto_surface_frame_timeline_tokens": 24,
                "perfetto_self_jank_tagged_frames": 1,
                "perfetto_app_deadline_missed_frames": 1,
                "perfetto_app_deadline_missed_percent": 100 / 24,
                "perfetto_app_deadline_missed_or_dropped_frames": 1,
                "perfetto_app_deadline_missed_or_dropped_percent": 100 / 24,
                "perfetto_app_deadline_missed_and_dropped_frames": 0,
                "perfetto_non_deadline_self_jank_tagged_frames": 0,
                "perfetto_other_jank_tagged_frames": 2,
                "perfetto_dropped_frames": 0,
                "perfetto_unknown_tag_frames": 0,
                "perfetto_overlapping_jank_tag_frames": 0,
                "perfetto_self_jank_tagged_percent": 100 / 24,
            }
            for index in range(1, 6)
        ],
        "frame_timing_total_rendered": 120,
        "frame_timing_overrun_positive": sum(positive_by_iteration),
        "frame_timing_overrun_positive_percent": sum(positive_by_iteration) * 100 / 120,
        "perfetto_surface_frame_timeline_tokens": 120,
        "perfetto_self_jank_tagged": 5,
        "perfetto_app_deadline_missed": 5,
        "perfetto_app_deadline_missed_percent": 100 / 24,
        "perfetto_app_deadline_missed_or_dropped": 5,
        "perfetto_app_deadline_missed_or_dropped_percent": 100 / 24,
        "perfetto_app_deadline_missed_and_dropped": 0,
        "perfetto_non_deadline_self_jank_tagged": 0,
        "perfetto_other_jank_tagged": 10,
        "perfetto_dropped": 0,
        "perfetto_unknown_tag": 0,
        "perfetto_overlapping_jank_tag": 0,
        "perfetto_self_jank_tagged_percent": 100 / 24,
        "p50_ms": duration["P50"],
        "p90_ms": duration["P90"],
        "p95_ms": duration["P95"],
        "p99_ms": duration["P99"],
        "frame_overrun_ms": {
            "p50": overrun["P50"],
            "p90": overrun["P90"],
            "p95": overrun["P95"],
            "p99": overrun["P99"],
        },
    }


def _write_performance(root: Path, profile: str) -> dict:
    compact = profile == "phone-compact"
    width_px, height_px, density = ((1080, 2400, 420) if compact else (1600, 2560, 320))
    width_dp, height_dp = ((411, 891) if compact else (800, 1280))
    performance_dir = root / "performance"
    trace_dir = performance_dir / f"{profile}.traces"
    trace_dir.mkdir(parents=True, exist_ok=True)
    trace_records = []
    for index in range(1, 6):
        trace = trace_dir / f"iteration-{index:03d}.perfetto-trace"
        trace.write_bytes(b"PERFETTO\x00" + bytes([index]) * 64)
        trace_records.append(
            {
                "iteration": index,
                "path": f"performance/{profile}.traces/iteration-{index:03d}.perfetto-trace",
                "source_name": f"settings_iter{index:03d}.perfetto-trace",
                "bytes": trace.stat().st_size,
                "sha256": hashlib.sha256(trace.read_bytes()).hexdigest(),
            }
        )
    macro_path = performance_dir / f"{profile}.macrobenchmark.raw.json"
    macro_path.write_text(json.dumps(_macro_report(profile)), encoding="utf-8")
    payload = {
        "schema": "hermes-android-performance-evidence-v2",
        "profile": profile,
        "release_source_digest": SOURCE_DIGEST,
        "benchmark_target_apk_sha256": BENCHMARK_TARGET_SHA,
        "benchmark_test_apk_sha256": BENCHMARK_TEST_SHA,
        "evidence_run_id": RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "version_name": VERSION_NAME,
        "version_code": VERSION_CODE,
        "build_variant": "benchmark",
        "litertlm_coordinate": LITERTLM_COORDINATE,
        "recorded_at_epoch_ms": 1_780_000_000_000,
        "evidence_classification": {
            "environment": "headed-hardware-accelerated-avd",
            "result_kind": "validation-signal",
            "representative_end_user_benchmark": False,
        },
        "benchmark": {
            "target_package_id": "com.mobilefork.hermesagent",
            "test_package_id": "com.mobilefork.hermesagent.macrobenchmark",
            "runner": "androidx.test.runner.AndroidJUnitRunner",
            "test_id": "com.mobilefork.hermesagent.macrobenchmark.HermesSettingsScrollBenchmark#settingsListFling",
            "androidx_benchmark_coordinate": "androidx.benchmark:benchmark-macro-junit4:1.4.1",
            "compilation_mode": "Full",
            "reporting_package_compilation_mode": "run-from-apk",
            "target_compiler_filter": "speed",
            "iteration_count": 5,
            "suppressed_errors": ["EMULATOR"],
            "profiling_mode": "None",
            "target_debuggable": False,
            "target_profileable_by_shell": True,
        },
        "traces": trace_records,
        "device": {
            "serial": "emulator-5566",
            "avd_name": AVD_NAME,
            "boot_id": BOOT_ID,
            "model": MODEL,
            "build_fingerprint": FINGERPRINT,
            "android_sdk": 35,
            "supported_abis": ["x86_64"],
            "hardware_acceleration": True,
            "acceleration_check": "WHPX is installed and usable.",
            "acceleration_check_exit_code": 0,
            "gpu_renderer": "Android Emulator OpenGL ES Translator (NVIDIA RTX)",
            "active_qemu_process_count": 1,
            "emulator_pid": 4242,
            "emulator_process_name": "qemu-system-x86_64.exe",
            "emulator_public_command": QEMU_PUBLIC_COMMAND,
            "emulator_public_command_sha256": QEMU_PUBLIC_SHA,
            "emulator_raw_command_sha256": QEMU_RAW_SHA,
        },
        "screen": {
            "width_px": width_px,
            "height_px": height_px,
            "width_dp": width_dp,
            "height_dp": height_dp,
            "density_dpi": density,
            "font_scale": 1.0,
        },
        "launch": {"cold_total_ms": 1200, "cold_wait_ms": 1100, "warm_total_ms": 400, "warm_process_pid": 8123},
        "frames": _frames(profile),
        "memory": {"total_pss_kb": 250_000, "total_rss_kb": 320_000},
        "collector": {
            "source_digest_algorithm": "sha256-git-tree-contents-v1",
            "source_file_count": 123,
            "git_object_format": "sha1",
            "benchmark_target_apk_device_path": "/data/app/hermes/base.apk",
            "benchmark_test_apk_device_path": "/data/app/hermes-benchmark/base.apk",
            "scenario": "settings-list-fling",
        },
    }
    host_path = performance_dir / f"{profile}.host.raw.json"
    host_path.write_text(json.dumps(_host_raw(profile, payload), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    payload["raw_evidence"] = {
        "host": {
            "path": f"performance/{profile}.host.raw.json",
            "bytes": host_path.stat().st_size,
            "sha256": hashlib.sha256(host_path.read_bytes()).hexdigest(),
        },
        "macrobenchmark": {
            "path": f"performance/{profile}.macrobenchmark.raw.json",
            "bytes": macro_path.stat().st_size,
            "sha256": hashlib.sha256(macro_path.read_bytes()).hexdigest(),
        },
    }
    (performance_dir / f"{profile}.json").write_text(json.dumps(payload), encoding="utf-8")
    return payload


def _model_record(artifact) -> dict:
    method = {
        "litert-lm": "LiteRtLmModelMatrixInstrumentedTest#provisionedLiteRtLmModelLoadsAndAnswersLocally",
        "llama.cpp": "LlamaCppModelMatrixInstrumentedTest#provisionedContentAddressedGgufStartsAndAnswers",
    }[artifact.runtime]
    return {
        "schema": "hermes-model-evidence-v1",
        "release_source_digest": SOURCE_DIGEST,
        "candidate_apk_sha256": UI_TARGET_SHA,
        "instrumentation_apk_sha256": UI_TEST_SHA,
        "evidence_run_id": RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "version_name": VERSION_NAME,
        "version_code": VERSION_CODE,
        "build_variant": "debug",
        "litertlm_coordinate": LITERTLM_COORDINATE,
        "result": "passed",
        "evidence_complete": True,
        "content_addressed": True,
        "backend": artifact.backend,
        "instrumentation_method": method,
        "model_id": artifact.model_id,
        "publisher_repository": artifact.repository,
        "publisher_revision": artifact.revision,
        "file_name": artifact.file_name,
        "device_path": f"/data/local/tmp/{artifact.file_name}",
        "publisher_expected_bytes": artifact.expected_bytes,
        "device_visible_bytes": artifact.expected_bytes,
        "expected_sha256": artifact.sha256,
        "device_sha256": artifact.sha256,
        "runtime_started": True,
        "health_ok": True,
        "completion_nonempty": True,
        "elapsed_ms": 3000,
        "accelerator": "gpu" if artifact.runtime == "litert-lm" else "cpu",
        "status_message": "completion verified",
        "device_model": MODEL,
        "device_serial": "emulator-5566",
        "avd_name": "Hermes_API_35",
        "device_boot_id": BOOT_ID,
        "build_fingerprint": FINGERPRINT,
        "android_sdk": 35,
        "supported_abis": "x86_64",
        "recorded_at_epoch_ms": 1_780_000_000_000,
        "details": {"completion_characters": 2},
        "evidence_file": "/data/user/0/app/files/evidence.json",
    }


def _write_fixture(root: Path, module, artifacts) -> None:
    root.mkdir(parents=True, exist_ok=True)
    for profile in module.PROFILES:
        performance = _write_performance(root, profile)
        screen = performance["screen"]
        for language in module.LANGUAGES:
            directory = root / "ui" / profile / language
            directory.mkdir(parents=True)
            screen_path = directory / "screen.png"
            screen_path.write_bytes(_png(screen["width_px"], screen["height_px"], f"{profile}-{language}"))
            screen_hash = hashlib.sha256(screen_path.read_bytes()).hexdigest()
            shell_tag = "HermesPersistentNavigation" if profile == "tablet" else "HermesShellDrawerButton"
            (directory / "semantics.txt").write_text(
                "\n".join(
                    [
                        f"language={language}",
                        f"screen_width_dp={screen['width_dp']}",
                        f"screen_height_dp={screen['height_dp']}",
                        "font_scale=1.0",
                        f"release_source_digest={SOURCE_DIGEST}",
                        f"candidate_apk_sha256={UI_TARGET_SHA}",
                        f"instrumentation_apk_sha256={UI_TEST_SHA}",
                        f"evidence_run_id={RUN_ID}",
                        "package_id=com.mobilefork.hermesagent",
                        f"version_name={VERSION_NAME}",
                        f"version_code={VERSION_CODE}",
                        "build_variant=debug",
                        f"litertlm_coordinate={LITERTLM_COORDINATE}",
                        "device_serial=emulator-5566",
                        "avd_name=Hermes_API_35",
                        f"device_boot_id={BOOT_ID}",
                        f"build_fingerprint={FINGERPRINT}",
                        f"screenshot_sha256={screen_hash}",
                        "",
                        f"Tag: '{shell_tag}'\nTag: 'HermesDevicePageNavigation'\n"
                        f"Text = '[{module.LOCALIZED_DEVICE_OVERVIEW[language]}]'\n"
                        f"localized {profile} {language}",
                    ]
                ),
                encoding="utf-8",
            )
    models = root / "models"
    models.mkdir()
    for artifact in artifacts:
        (models / f"{artifact.model_id}.json").write_text(json.dumps(_model_record(artifact)), encoding="utf-8")


@pytest.fixture
def evidence_root(tmp_path, evidence_module, artifacts):
    root = tmp_path / "release-evidence"
    _write_fixture(root, evidence_module, artifacts)
    return root


def _rehash_reference(root: Path, profile: str, kind: str) -> None:
    normalized_path = root / "performance" / f"{profile}.json"
    payload = json.loads(normalized_path.read_text(encoding="utf-8"))
    reference = payload["raw_evidence"][kind]
    artifact = root / Path(reference["path"])
    reference["bytes"] = artifact.stat().st_size
    reference["sha256"] = hashlib.sha256(artifact.read_bytes()).hexdigest()
    normalized_path.write_text(json.dumps(payload), encoding="utf-8")


def _rewrite_host(root: Path, profile: str, mutator) -> None:
    path = root / "performance" / f"{profile}.host.raw.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    mutator(payload)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _rehash_reference(root, profile, "host")


def _rewrite_report(root: Path, profile: str, mutator) -> None:
    path = root / "performance" / f"{profile}.macrobenchmark.raw.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    mutator(payload)
    path.write_text(json.dumps(payload), encoding="utf-8")
    _rehash_reference(root, profile, "macrobenchmark")


def _sync_normalized_frames_from_raw_report(root: Path, profile: str, module) -> dict:
    normalized_path = root / "performance" / f"{profile}.json"
    report_path = root / "performance" / f"{profile}.macrobenchmark.raw.json"
    normalized = json.loads(normalized_path.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    normalized["frames"] = module._expected_frames_from_macrobenchmark(
        report,
        normalized,
        profile,
        [trace["source_name"] for trace in normalized["traces"]],
    )
    normalized_path.write_text(json.dumps(normalized), encoding="utf-8")
    return normalized


def test_complete_v2_directory_validates_with_four_distinct_apk_hashes(
    evidence_root, evidence_module, artifacts
):
    validated = evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )
    source = evidence_module.SourceTreeIdentity(
        algorithm=evidence_module.SOURCE_DIGEST_ALGORITHM,
        digest=SOURCE_DIGEST,
        file_count=100,
        git_object_format="sha1",
        excluded_prefix="android/release-evidence/",
    )
    manifest = evidence_module.build_manifest(
        tag=TAG, source=source, artifacts=artifacts, evidence=validated
    )
    assert manifest["schema"] == "hermes-android-release-evidence-manifest-v2"
    assert manifest["tested_binaries"] == {
        "ui_candidate_apk_sha256": UI_TARGET_SHA,
        "ui_instrumentation_apk_sha256": UI_TEST_SHA,
        "benchmark_target_apk_sha256": BENCHMARK_TARGET_SHA,
        "benchmark_test_apk_sha256": BENCHMARK_TEST_SHA,
        "evidence_run_id": RUN_ID,
    }
    paths = {record.path for record in validated.files}
    assert "performance/phone-compact.host.raw.json" in paths
    assert "performance/phone-compact.macrobenchmark.raw.json" in paths
    assert "performance/tablet.traces/iteration-005.perfetto-trace" in paths
    assert manifest["contract"]["avd_metrics_are_validation_signals_not_end_user_benchmarks"]
    assert (
        manifest["contract"][
            "maximum_perfetto_app_deadline_missed_or_dropped_percent"
        ]
        == 10.0
    )
    assert manifest["contract"][
        "requires_zero_perfetto_unknown_or_overlapping_self_other_jank_tags"
    ] is True
    assert manifest["contract"][
        "dropped_frames_are_budgeted_with_app_deadline_misses"
    ] is True
    assert manifest["contract"]["requested_macrobenchmark_compilation_mode"] == "Full"
    assert (
        manifest["contract"]["required_androidx_reporting_package_compilation_mode"]
        == "run-from-apk"
    )
    assert manifest["contract"]["required_measured_target_compiler_filter"] == "speed"
    assert manifest["contract"]["maximum_frame_duration_cpu_p95_ms"] == 50.0
    assert manifest["contract"]["maximum_frame_duration_cpu_p99_ms"] == 100.0
    assert manifest["contract"][
        "frame_timing_positive_overrun_is_nongating_avd_buffer_queue_diagnostic"
    ] is True


def test_old_shell_gfxinfo_layout_is_rejected(evidence_root, evidence_module, artifacts):
    old_raw = evidence_root / "performance" / "phone-compact.raw.json"
    old_raw.write_text("{}", encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="layout mismatch"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    "mutation",
    [
        lambda report: report["context"].__setitem__("compilationMode", "Full"),
        lambda report: report["context"].__setitem__("compilationMode", "speed"),
        lambda report: report["context"]["payload"].__setitem__("evidenceRunId", "stale-run"),
        lambda report: report["context"]["payload"].__setitem__(
            "bootId", "87654321-4321-4abc-8def-1234567890ab"
        ),
        lambda report: report["benchmarks"][0]["metrics"]["hermesEvidenceToken"]["runs"].__setitem__(0, 1),
        lambda report: report["benchmarks"][0].__setitem__("repeatIterations", 4),
    ],
)
def test_androidx_report_identity_compilation_and_iteration_tampering_fails(
    evidence_root, evidence_module, artifacts, mutation
):
    macro = evidence_root / "performance" / "phone-compact.macrobenchmark.raw.json"
    report = json.loads(macro.read_text(encoding="utf-8"))
    mutation(report)
    macro.write_text(json.dumps(report), encoding="utf-8")
    _rehash_reference(evidence_root, "phone-compact", "macrobenchmark")
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    "package_dump",
    (
        _dexopt_dump(),
        _dexopt_dump("speed", "speed"),
        _dexopt_dump("verify"),
    ),
)
def test_raw_target_compiler_filter_requires_one_speed_status(
    evidence_root, evidence_module, artifacts, package_dump
):
    def rewrite(raw):
        record = next(
            item
            for item in raw["records"]
            if item["id"] == "measure.package.target_compiler_filter.initial"
        )
        record["stdout"] = package_dump

    _rewrite_host(evidence_root, "phone-compact", rewrite)
    with pytest.raises(evidence_module.EvidenceError, match="status=speed"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    ("mutation", "message"),
    (
        (
            lambda raw: next(
                item
                for item in raw["records"]
                if item["id"] == "measure.package.target_compiler_filter.initial"
            )["argv"].__setitem__(-1, "com.mobilefork.hermesagent.stale"),
            "required live command",
        ),
        (
            lambda raw: raw["records"].append(
                next(
                    item
                    for item in raw["records"]
                    if item["id"] == "measure.package.target_compiler_filter.initial"
                ).copy()
            ),
            "duplicated|order",
        ),
    ),
)
def test_target_compiler_filter_raw_argv_and_order_are_exact(
    evidence_root, evidence_module, artifacts, mutation, message
):
    _rewrite_host(evidence_root, "phone-compact", mutation)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("reporting_package_compilation_mode", "speed"),
        ("target_compiler_filter", "verify"),
    ),
)
def test_normalized_compilation_provenance_is_exact(
    evidence_root, evidence_module, artifacts, field, value
):
    path = evidence_root / "performance" / "phone-compact.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    payload["benchmark"][field] = value
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match=field):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    ("metric_name", "runs", "message"),
    (
        ("hermesFrameNonDeadlineSelfJankTaggedCount", [1] * 5, "jank counts"),
        ("hermesFrameOtherJankTaggedCount", [24] * 5, "jank counts"),
        (
            "hermesFrameUnknownTagCount",
            [1] * 5,
            "unknown-tag or overlapping Self/Other-tag",
        ),
        (
            "hermesFrameOverlappingJankTagCount",
            [1] * 5,
            "unknown-tag or overlapping Self/Other-tag",
        ),
    ),
)
def test_perfetto_self_other_unknown_and_overlap_contract_fails_closed(
    evidence_root, evidence_module, artifacts, metric_name, runs, message
):
    def rewrite(report):
        report["benchmarks"][0]["metrics"][metric_name] = _single_metric(runs)

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_raw_nonzero_dropped_frames_are_budgeted_with_deadline_misses(
    evidence_root, evidence_module, artifacts
):
    def rewrite(report):
        _set_perfetto_jank_runs(report, dropped=1)

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    normalized = _sync_normalized_frames_from_raw_report(
        evidence_root, "phone-compact", evidence_module
    )
    validated = evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )

    assert normalized["frames"]["perfetto_dropped"] == 5
    assert normalized["frames"]["perfetto_app_deadline_missed_or_dropped"] == 10
    assert (
        normalized["frames"]["perfetto_app_deadline_missed_or_dropped_percent"]
        == 100 / 12
    )
    assert normalized["frames"]["perfetto_app_deadline_missed_and_dropped"] == 0
    assert validated.performance_record_count == 2


def test_raw_deadline_and_dropped_overlap_is_derived_from_union(
    evidence_root, evidence_module, artifacts
):
    def rewrite(report):
        _set_perfetto_jank_runs(
            report,
            dropped=1,
            deadline_or_dropped=1,
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    normalized = _sync_normalized_frames_from_raw_report(
        evidence_root, "phone-compact", evidence_module
    )
    evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )

    assert normalized["frames"]["perfetto_app_deadline_missed_or_dropped"] == 5
    assert normalized["frames"]["perfetto_app_deadline_missed_and_dropped"] == 5
    assert all(
        iteration["perfetto_app_deadline_missed_or_dropped_frames"] == 1
        and iteration["perfetto_app_deadline_missed_and_dropped_frames"] == 1
        for iteration in normalized["frames"]["iterations"]
    )


def test_raw_combined_deadline_and_dropped_budget_accepts_exact_ten_percent(
    evidence_root, evidence_module, artifacts
):
    def rewrite(report):
        _set_perfetto_jank_runs(
            report,
            dropped=[1, 1, 1, 2, 2],
            deadline_or_dropped=[2, 2, 2, 3, 3],
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    normalized = _sync_normalized_frames_from_raw_report(
        evidence_root, "phone-compact", evidence_module
    )
    evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )

    assert (
        normalized["frames"]["perfetto_app_deadline_missed_or_dropped_percent"]
        == 10.0
    )


@pytest.mark.parametrize(
    ("self_jank", "deadline", "dropped", "union"),
    (
        (3, 3, 0, 3),
        (0, 0, 3, 3),
        (1, 1, 2, 3),
    ),
    ids=("deadline", "dropped", "combined"),
)
def test_raw_combined_deadline_and_dropped_budget_rejects_each_over_ten_source(
    evidence_root,
    evidence_module,
    artifacts,
    self_jank,
    deadline,
    dropped,
    union,
):
    def rewrite(report):
        _set_perfetto_jank_runs(
            report,
            self_jank=self_jank,
            deadline=deadline,
            dropped=dropped,
            deadline_or_dropped=union,
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    with pytest.raises(
        evidence_module.EvidenceError,
        match="App Deadline Missed or Dropped Frame",
    ):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    "union",
    (0, 3),
    ids=("below-max", "above-sum"),
)
def test_raw_deadline_dropped_union_divergence_fails_closed(
    evidence_root, evidence_module, artifacts, union
):
    def rewrite(report):
        _set_perfetto_jank_runs(
            report,
            dropped=1,
            deadline_or_dropped=union,
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    with pytest.raises(evidence_module.EvidenceError, match="union does not reconcile"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_raw_androidx_positive_frame_overrun_above_ten_percent_remains_avd_diagnostic(
    evidence_root, evidence_module, artifacts
):
    def rewrite(report):
        runs = [
            [1.0 if sample < 3 else (0.0 if sample == 3 else -1.0) for sample in range(24)]
            for _ in range(5)
        ]
        report["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"] = (
            _sampled_distribution(runs)
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    normalized = _sync_normalized_frames_from_raw_report(
        evidence_root, "phone-compact", evidence_module
    )
    validated = evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )
    assert normalized["frames"]["frame_timing_overrun_positive_percent"] == 12.5
    assert normalized["frames"]["perfetto_app_deadline_missed_percent"] == 100 / 24
    assert normalized["evidence_classification"]["result_kind"] == "validation-signal"
    assert validated.performance_record_count == 2


@pytest.mark.parametrize(
    ("case", "expected_p95", "expected_p99", "rejected"),
    (
        ("p95-boundary", 50.0, 50.0, False),
        ("p95-over", 51.0, 51.0, True),
        ("p99-boundary", 50.0, 100.0, False),
        ("p99-over", 50.0, 101.0, True),
    ),
)
def test_offline_frame_duration_cpu_controlled_avd_boundaries(
    evidence_root,
    evidence_module,
    artifacts,
    case,
    expected_p95,
    expected_p99,
    rejected,
):
    if case == "p95-boundary":
        flattened = [50.0] * 120
    elif case == "p95-over":
        flattened = [51.0] * 120
    elif case == "p99-boundary":
        flattened = [50.0] * 117 + [100.0] * 3
    else:
        flattened = [50.0] * 117 + [101.0] * 3
    runs = [flattened[index : index + 24] for index in range(0, 120, 24)]

    def rewrite(report):
        report["benchmarks"][0]["sampledMetrics"]["frameDurationCpuMs"] = (
            _sampled_distribution(runs)
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    if rejected:
        with pytest.raises(evidence_module.EvidenceError, match="CPU-work ceilings"):
            evidence_module.validate_evidence_directory(
                evidence_root, artifacts, SOURCE_DIGEST, TAG
            )
    else:
        normalized = _sync_normalized_frames_from_raw_report(
            evidence_root, "phone-compact", evidence_module
        )
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )
        assert normalized["frames"]["p95_ms"] == expected_p95
        assert normalized["frames"]["p99_ms"] == expected_p99


def test_offline_frame_duration_cpu_rejects_negative_samples(
    evidence_root, evidence_module, artifacts
):
    runs = [[-1.0] + [5.0] * 23 for _ in range(5)]

    def rewrite(report):
        report["benchmarks"][0]["sampledMetrics"]["frameDurationCpuMs"] = (
            _sampled_distribution(runs)
        )

    _rewrite_report(evidence_root, "phone-compact", rewrite)
    with pytest.raises(evidence_module.EvidenceError, match="cannot contain negative"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_only_exact_emulator_suppression_is_accepted(
    evidence_root, evidence_module, artifacts
):
    host = evidence_root / "performance" / "phone-compact.host.raw.json"
    raw = json.loads(host.read_text(encoding="utf-8"))
    invocation = raw["records"][0]
    index = next(i for i, arg in enumerate(invocation["argv"]) if "suppressErrors=" in arg)
    invocation["argv"][index] += ",NOT-PROFILEABLE"
    host.write_text(json.dumps(raw, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _rehash_reference(evidence_root, "phone-compact", "host")
    with pytest.raises(evidence_module.EvidenceError, match="arguments are not exact"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_raw_host_identity_requires_one_exclusive_adb_endpoint(
    evidence_root, evidence_module, artifacts
):
    def add_endpoint(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "initial.adb.devices"
        )
        record["stdout"] += "emulator-5570 device product:sdk\n"

    _rewrite_host(evidence_root, "phone-compact", add_endpoint)
    with pytest.raises(evidence_module.EvidenceError, match="one exclusive target"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    ("extra_count", "message"),
    ((1, "exactly one total live QEMU"), (2, "absolute two-emulator limit")),
)
def test_raw_host_qemu_inventory_enforces_normal_and_absolute_limits(
    evidence_root, evidence_module, artifacts, extra_count, message
):
    def add_qemu_processes(raw):
        record = next(
            item
            for item in raw["records"]
            if item["id"] == "initial.host.qemu_processes"
        )
        processes = json.loads(record["stdout"])
        for offset in range(extra_count):
            port = 5570 + 2 * offset
            processes.append(
                {
                    "pid": 5000 + offset,
                    "name": "qemu-system-x86_64.exe",
                    "public_command": (
                        "qemu-system-x86_64.exe "
                        f"-avd Spare_{offset + 1} -port {port} -gpu host -accel on"
                    ),
                    "public_command_sha256": hashlib.sha256(
                        (
                            "qemu-system-x86_64.exe "
                            f"-avd Spare_{offset + 1} -port {port} -gpu host -accel on"
                        ).encode("utf-8")
                    ).hexdigest(),
                    "raw_command_sha256": hashlib.sha256(
                        f"private raw command {offset}".encode("utf-8")
                    ).hexdigest(),
                }
            )
        record["stdout"] = json.dumps(processes, separators=(",", ":"))

    _rewrite_host(evidence_root, "phone-compact", add_qemu_processes)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_public_qemu_evidence_preserves_identity_without_private_host_paths(
    evidence_root, evidence_module, artifacts
):
    normalized_path = evidence_root / "performance" / "phone-compact.json"
    host_path = evidence_root / "performance" / "phone-compact.host.raw.json"
    public_blob = normalized_path.read_text(encoding="utf-8") + host_path.read_text(
        encoding="utf-8"
    )
    assert "private-builder" not in public_blob
    assert "C:\\\\Users\\\\" not in public_blob
    assert "userdata.img" not in public_blob
    assert QEMU_PUBLIC_COMMAND in public_blob
    assert QEMU_RAW_SHA in public_blob
    evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )


@pytest.mark.parametrize("mutation", ("public_command", "raw_hash"))
def test_public_qemu_transcript_tampering_fails_closed(
    evidence_root, evidence_module, artifacts, mutation
):
    def tamper(raw):
        record = next(
            item
            for item in raw["records"]
            if item["id"] == "initial.host.qemu_processes"
        )
        processes = json.loads(record["stdout"])
        if mutation == "public_command":
            processes[0]["public_command"] = processes[0]["public_command"].replace(
                "-gpu host", "-gpu guest"
            )
            processes[0]["public_command_sha256"] = hashlib.sha256(
                processes[0]["public_command"].encode("utf-8")
            ).hexdigest()
        else:
            processes[0]["raw_command_sha256"] = "0" * 64
        record["stdout"] = json.dumps(processes, separators=(",", ":"))

    _rewrite_host(evidence_root, "phone-compact", tamper)
    with pytest.raises(evidence_module.EvidenceError, match="QEMU|command|identity"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_missing_tampered_or_extra_trace_fails_closed(
    evidence_root, evidence_module, artifacts
):
    trace = evidence_root / "performance" / "phone-compact.traces" / "iteration-003.perfetto-trace"
    trace.write_bytes(b"tampered")
    with pytest.raises(evidence_module.EvidenceError, match="bytes/hash"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )
    trace.write_bytes(b"PERFETTO\x00" + bytes([3]) * 64)
    extra = trace.with_name("unexpected.perfetto-trace")
    extra.write_bytes(b"extra")
    with pytest.raises(evidence_module.EvidenceError, match="layout mismatch"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_ui_debug_pair_is_not_inferred_from_benchmark_pair(
    evidence_root, evidence_module, artifacts
):
    semantics = evidence_root / "ui" / "tablet" / "fr" / "semantics.txt"
    semantics.write_text(
        semantics.read_text(encoding="utf-8").replace(UI_TARGET_SHA, BENCHMARK_TARGET_SHA),
        encoding="utf-8",
    )
    with pytest.raises(evidence_module.EvidenceError, match="do not share one debug"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_source_and_manifest_helpers_remain_fail_closed(evidence_module, tmp_path):
    entries = [
        ("100644", "blob", "1" * 40, "android/app/source.kt"),
        ("100644", "blob", "2" * 40, "android/release-evidence/v0.1.2/manifest.json"),
    ]
    original = evidence_module.source_digest_from_entries(entries, object_format="sha1")
    changed_evidence = [entries[0], ("100644", "blob", "3" * 40, entries[1][3])]
    assert evidence_module.source_digest_from_entries(changed_evidence, object_format="sha1") == original

    path = tmp_path / "manifest.json"
    evidence_module.write_manifest(path, {"schema": "x", "value": 1})
    evidence_module.verify_manifest(path, {"schema": "x", "value": 1})
    with pytest.raises(evidence_module.EvidenceError, match="does not match"):
        evidence_module.verify_manifest(path, {"schema": "x", "value": 2})


def test_parser_tracks_variable_runtime_registry_entries_without_a_catalog_snapshot(
    evidence_module,
):
    source = """
object VerifiedLocalModelArtifacts {
  val releaseMatrix: List<Artifact> = listOf(
    Artifact(
      modelId = "future-gguf",
      repoId = "org/future",
      revision = "1111111111111111111111111111111111111111",
      fileName = "future.gguf",
      runtime = "llama.cpp",
      expectedBytes = 9_876_543L,
      sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      validationEvidence = "device",
      remoteManifestMatches = true,
    ),
    Artifact(
      modelId = "new-litert",
      repoId = "org/mobile",
      revision = "2222222222222222222222222222222222222222",
      fileName = "new.litertlm",
      runtime = "litert-lm",
      expectedBytes = 1_234L,
      sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      validationEvidence = "device",
      remoteManifestMatches = true,
    ),
  );
}
"""
    parsed = evidence_module.parse_registered_model_matrix(source)
    assert {artifact.model_id for artifact in parsed} == {"future-gguf", "new-litert"}
    assert {artifact.backend for artifact in parsed} == {"llama.cpp", "litert-lm"}
    assert (
        next(artifact for artifact in parsed if artifact.model_id == "future-gguf").expected_bytes
        == 9_876_543
    )


def test_bound_source_identity_rejects_dirty_or_untracked_build_inputs(
    evidence_module, tmp_path
):
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    subprocess.run(
        ["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True
    )
    subprocess.run(["git", "config", "user.name", "Evidence Test"], cwd=repo, check=True)
    source = repo / "source.txt"
    source.write_text("clean\n", encoding="utf-8")
    subprocess.run(["git", "add", "source.txt"], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-q", "-m", "fixture"], cwd=repo, check=True)

    evidence_module.require_clean_worktree(repo)
    clean = evidence_module.git_source_tree_identity(repo)
    assert evidence_module.HEX_64_RE.fullmatch(clean.digest)
    source.write_text("dirty\n", encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="clean worktree"):
        evidence_module.require_clean_worktree(repo)
    subprocess.run(["git", "restore", "source.txt"], cwd=repo, check=True)
    (repo / "untracked.txt").write_text("input\n", encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="clean worktree"):
        evidence_module.require_clean_worktree(repo)


def test_missing_language_capture_is_rejected(evidence_root, evidence_module, artifacts):
    (evidence_root / "ui" / "tablet" / "fr" / "screen.png").unlink()
    with pytest.raises(evidence_module.EvidenceError, match="missing required fixed paths"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_unregistered_extra_model_record_is_rejected(
    evidence_root, evidence_module, artifacts
):
    extra = evidence_root / "models" / "not-in-runtime-registry.json"
    extra.write_text(json.dumps(_model_record(artifacts[0])), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="unexpected"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    ("field", "replacement"),
    (
        ("publisher_revision", "3" * 40),
        ("device_visible_bytes", 1),
        ("device_sha256", "f" * 64),
        ("runtime_started", False),
        ("health_ok", False),
        ("completion_nonempty", False),
        ("elapsed_ms", 0),
    ),
)
def test_model_matrix_requires_exact_registered_bytes_and_real_completion(
    evidence_root, evidence_module, artifacts, field, replacement
):
    target = evidence_root / "models" / f"{artifacts[0].model_id}.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload[field] = replacement
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match=field):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_reused_untranslated_ui_capture_is_rejected(
    evidence_root, evidence_module, artifacts
):
    en = evidence_root / "ui" / "phone-compact" / "en"
    fr = evidence_root / "ui" / "phone-compact" / "fr"
    (fr / "screen.png").write_bytes((en / "screen.png").read_bytes())
    fr_semantics = (en / "semantics.txt").read_text(encoding="utf-8").replace(
        "language=en", "language=fr"
    )
    (fr / "semantics.txt").write_text(fr_semantics, encoding="utf-8")
    with pytest.raises(
        evidence_module.EvidenceError, match="localized Device/Overview sentinel"
    ):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_phone_ui_requires_the_live_device_shell_drawer_tag(
    evidence_root, evidence_module, artifacts
):
    for language in evidence_module.LANGUAGES:
        semantics = evidence_root / "ui" / "phone-compact" / language / "semantics.txt"
        semantics.write_text(
            semantics.read_text(encoding="utf-8").replace(
                "Tag: 'HermesShellDrawerButton'", "Tag: 'HermesChatDrawerButton'"
            ),
            encoding="utf-8",
        )
    with pytest.raises(evidence_module.EvidenceError, match="compact drawer navigation"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_registry_parser_rejects_helper_or_variable_entries(evidence_module):
    source = """
object VerifiedLocalModelArtifacts {
  val releaseMatrix: List<Artifact> = listOf(
    Artifact(
      modelId = "literal",
      repoId = "org/literal",
      revision = "1111111111111111111111111111111111111111",
      fileName = "literal.gguf",
      runtime = "llama.cpp",
      expectedBytes = 123L,
      sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      validationEvidence = "device",
      remoteManifestMatches = true,
    ),
    helperArtifact,
  );
}
"""
    with pytest.raises(evidence_module.EvidenceError, match="literal Artifact"):
        evidence_module.parse_registered_model_matrix(source)
    chained = source.replace("    helperArtifact,\n", "").replace(
        "  );\n}", "  )!!.let { it + helperArtifact };\n}"
    )
    with pytest.raises(evidence_module.EvidenceError, match="explicit semicolon"):
        evidence_module.parse_registered_model_matrix(chained)


@pytest.mark.parametrize(
    "unsafe_name",
    ("folder/model.gguf", r"folder\model.gguf", "../model.gguf", "bad name.gguf"),
)
def test_registry_rejects_platform_specific_or_traversing_artifact_names(
    evidence_module, unsafe_name
):
    base = evidence_module.ArtifactSpec(
        model_id="unsafe",
        repository="org/model",
        revision="1" * 40,
        file_name="safe.gguf",
        runtime="llama.cpp",
        expected_bytes=123,
        sha256="a" * 64,
    )
    with pytest.raises(evidence_module.EvidenceError, match="unsafe portable file name"):
        evidence_module._validate_artifact_spec(
            evidence_module.ArtifactSpec(**{**base.__dict__, "file_name": unsafe_name})
        )


def test_registry_parser_ignores_comment_and_string_decoys(evidence_module):
    decoy = '''
/*
object VerifiedLocalModelArtifacts {
  val releaseMatrix: List<Artifact> = listOf(Artifact(
    modelId = "decoy", repoId = "bad/decoy", revision = "1111111111111111111111111111111111111111",
    fileName = "decoy.gguf", runtime = "llama.cpp", expectedBytes = 1L,
    sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    validationEvidence = "none", remoteManifestMatches = false,
  ));
}
*/
object VerifiedLocalModelArtifacts {
  val text = "val releaseMatrix: List<Artifact> = listOf()"
  val releaseMatrix: List<Artifact> get() = emptyList()
}
'''
    with pytest.raises(
        evidence_module.EvidenceError, match="canonical literal|explicitly typed literal"
    ):
        evidence_module.parse_registered_model_matrix(decoy)


def test_png_decoder_rejects_crc_valid_but_non_pixel_idat(evidence_module, tmp_path):
    path = tmp_path / "fake.png"
    ihdr = struct.pack(">IIBBBBB", 20, 20, 8, 2, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(b"not pixel scanlines"))
        + _chunk(b"IEND", b"")
    )
    with pytest.raises(evidence_module.EvidenceError, match="decoded byte count"):
        evidence_module._decode_png(path)


def test_png_decoder_rejects_hidden_rgb_under_transparency(evidence_module, tmp_path):
    path = tmp_path / "transparent.png"
    width = height = 20
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            rows.extend(
                ((x * 13) & 0xFF, (y * 17) & 0xFF, ((x + y) * 19) & 0xFF, 0)
            )
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(bytes(rows)))
        + _chunk(b"IEND", b"")
    )
    with pytest.raises(evidence_module.EvidenceError, match="non-opaque"):
        evidence_module._decode_png(path)


@pytest.mark.parametrize(
    ("path", "replacement", "message"),
    (
        (("device", "acceleration_check"), "WHPX is NOT usable", "usable acceleration"),
        (("launch", "cold_total_ms"), 999_999, "launch|raw"),
        (("memory", "total_pss_kb"), 600_000, "PSS|release ceiling"),
    ),
)
def test_non_frame_performance_guards_remain_fail_closed(
    evidence_root, evidence_module, artifacts, path, replacement, message
):
    target = evidence_root / "performance" / "phone-compact.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload[path[0]][path[1]] = replacement
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_model_record_must_match_a_measured_device_identity(
    evidence_root, evidence_module, artifacts
):
    target = evidence_root / "models" / f"{artifacts[0].model_id}.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload["device_model"] = "unmeasured-emulator"
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="device model/API/ABI identity"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_manifest_verification_detects_source_tampering(
    evidence_root, evidence_module, artifacts
):
    validated = evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )
    source = evidence_module.SourceTreeIdentity(
        algorithm=evidence_module.SOURCE_DIGEST_ALGORITHM,
        digest=SOURCE_DIGEST,
        file_count=100,
        git_object_format="sha1",
        excluded_prefix="android/release-evidence/",
    )
    manifest = evidence_module.build_manifest(
        tag=TAG, source=source, artifacts=artifacts, evidence=validated
    )
    manifest_path = evidence_root / "manifest.json"
    evidence_module.write_manifest(manifest_path, manifest)
    tampered = json.loads(manifest_path.read_text(encoding="utf-8"))
    tampered["source_tree"]["digest"] = "e" * 64
    manifest_path.write_text(json.dumps(tampered), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="does not match"):
        evidence_module.verify_manifest(manifest_path, manifest)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda raw: raw["records"].pop(2),
        lambda raw: raw["records"].__setitem__(
            slice(0, 2), [raw["records"][1], raw["records"][0]]
        ),
        lambda raw: raw["records"][1].__setitem__("id", raw["records"][0]["id"]),
        lambda raw: raw["records"][1]["argv"].__setitem__(2, "emulator-9999"),
        lambda raw: raw["records"][3].__setitem__("exit_code", 1),
        lambda raw: raw["records"].append(
            _command(
                "legacy.gfxinfo",
                ["adb", "-s", "emulator-5566", "shell", "dumpsys", "gfxinfo"],
            )
        ),
    ),
)
def test_v2_host_transcript_exact_order_argv_status_and_allowlist_are_enforced(
    evidence_root, evidence_module, artifacts, mutation
):
    _rewrite_host(evidence_root, "phone-compact", mutation)
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    "mutation",
    (
        lambda report: report.__setitem__("unexpected", True),
        lambda report: report["context"].__setitem__("unexpected", True),
        lambda report: report["context"]["build"].__setitem__("unexpected", True),
        lambda report: report["benchmarks"][0]["metrics"].__setitem__(
            "unrequestedMetric", _single_metric([1] * 5)
        ),
        lambda report: report["benchmarks"][0]["sampledMetrics"].pop(
            "frameOverrunMs"
        ),
        lambda report: report["benchmarks"][0]["sampledMetrics"][
            "frameDurationCpuMs"
        ].__setitem__("P50", 9.6),
        lambda report: report["benchmarks"][0]["sampledMetrics"][
            "frameOverrunMs"
        ].__setitem__("P99", -1.5),
        lambda report: report["benchmarks"][0]["profilerOutputs"][0].__setitem__(
            "filename", "../escaped.perfetto-trace"
        ),
        lambda report: report["benchmarks"][0]["profilerOutputs"][0].__setitem__(
            "type", "MethodTrace"
        ),
        lambda report: report["benchmarks"][0]["profilerOutputs"][0].__setitem__(
            "label", "Trace Iteration 1"
        ),
    ),
)
def test_androidx_raw_report_uses_an_exact_fail_closed_grammar(
    evidence_root, evidence_module, artifacts, mutation
):
    _rewrite_report(evidence_root, "phone-compact", mutation)
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


@pytest.mark.parametrize(
    "mutation",
    (
        lambda payload: payload["benchmark"].__setitem__(
            "test_id",
            "com.mobilefork.hermesagent.macrobenchmark.HermesSettingsScrollBenchmark",
        ),
        lambda payload: payload["benchmark"].__setitem__("target_debuggable", True),
        lambda payload: payload["benchmark"].__setitem__(
            "target_profileable_by_shell", False
        ),
        lambda payload: payload["evidence_classification"].__setitem__(
            "representative_end_user_benchmark", True
        ),
        lambda payload: payload["frames"].__setitem__(
            "perfetto_surface_frame_timeline_tokens", 121
        ),
        lambda payload: payload["frames"].__setitem__(
            "frame_timing_overrun_positive", 11
        ),
        lambda payload: payload["frames"]["iterations"][0].__setitem__(
            "frame_timing_overrun_positive_percent", 0.0
        ),
        lambda payload: payload["device"].__setitem__("serial", "emulator-9999"),
        lambda payload: payload["memory"].__setitem__("total_rss_kb", 1),
    ),
)
def test_normalized_v2_claims_must_reproduce_raw_identity_and_metrics(
    evidence_root, evidence_module, artifacts, mutation
):
    path = evidence_root / "performance" / "phone-compact.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    mutation(payload)
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_raw_reference_path_traversal_is_rejected(
    evidence_root, evidence_module, artifacts
):
    path = evidence_root / "performance" / "phone-compact.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    payload["raw_evidence"]["host"]["path"] = "../outside.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )


def test_symlinked_trace_is_rejected(evidence_root, evidence_module, artifacts, tmp_path):
    trace = (
        evidence_root
        / "performance"
        / "phone-compact.traces"
        / "iteration-001.perfetto-trace"
    )
    outside = tmp_path / "outside.perfetto-trace"
    outside.write_bytes(trace.read_bytes())
    trace.unlink()
    try:
        trace.symlink_to(outside)
    except OSError as exc:
        pytest.skip(f"symlink creation unavailable: {exc}")
    with pytest.raises(evidence_module.EvidenceError, match="symlink"):
        evidence_module.validate_evidence_directory(
            evidence_root, artifacts, SOURCE_DIGEST, TAG
        )
