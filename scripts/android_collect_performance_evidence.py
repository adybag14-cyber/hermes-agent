#!/usr/bin/env python3
"""Collect fail-closed Android performance evidence from one live headed AVD.

The release evidence validator deliberately does not operate devices.  This
producer supplies that missing live boundary: it binds an independently run
AndroidX Macrobenchmark JSON report and its Perfetto traces to the selected adb
guest, exact benchmark APKs, and Windows QEMU host process.  Host launch,
process, and memory proof is collected separately from frame timing.  One
validator-compatible evidence set is committed only after all identities stay
stable through the end of the run.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Protocol, Sequence


PERFORMANCE_SCHEMA = "hermes-android-performance-evidence-v2"
RAW_PERFORMANCE_SCHEMA = "hermes-android-performance-host-raw-v2"
INVOCATION_SCHEMA = "hermes-android-macrobenchmark-invocation-v1"
PACKAGE_ID = "com.mobilefork.hermesagent"
TEST_PACKAGE_ID = f"{PACKAGE_ID}.macrobenchmark"
MAIN_ACTIVITY = f"{PACKAGE_ID}/.MainActivity"
BUILD_VARIANT = "benchmark"
PROFILES = ("phone-compact", "tablet")
LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
ANDROIDX_BENCHMARK_COORDINATE = "androidx.benchmark:benchmark-macro-junit4:1.4.1"
REPORTING_PACKAGE_COMPILATION_MODE = "run-from-apk"
TARGET_COMPILER_FILTER = "speed"
BENCHMARK_CLASS = "com.mobilefork.hermesagent.macrobenchmark.HermesSettingsScrollBenchmark"
BENCHMARK_METHOD = "settingsListFling"
BENCHMARK_TEST_ID = f"{BENCHMARK_CLASS}#{BENCHMARK_METHOD}"
MIN_BENCHMARK_ITERATIONS = 5
MAX_BENCHMARK_ITERATIONS = 20
MIN_AGGREGATE_FRAMES = 100
MAX_APP_DEADLINE_MISSED_PERCENT = 10.0
MAX_FRAME_DURATION_CPU_P95_MS = 50.0
MAX_FRAME_DURATION_CPU_P99_MS = 100.0
HEX_64_RE = re.compile(r"^[0-9a-f]{64}$")
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{15,79}$")
BOOT_ID_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
AVD_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
SERIAL_RE = re.compile(r"^emulator-([0-9]{4,5})$")
SOFTWARE_RENDERER_MARKERS = (
    "swiftshader",
    "llvmpipe",
    "software rasterizer",
    "microsoft basic render driver",
)
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


class CollectorError(RuntimeError):
    """Raised when live evidence cannot be established without ambiguity."""


@dataclass(frozen=True)
class CommandResult:
    args: tuple[str, ...]
    returncode: int
    stdout: str
    stderr: str


class CommandExecutor(Protocol):
    def run(self, args: Sequence[str], *, timeout_seconds: int) -> CommandResult:
        """Run one argv command without a command shell."""


class SubprocessExecutor:
    def run(self, args: Sequence[str], *, timeout_seconds: int) -> CommandResult:
        argv = tuple(str(part) for part in args)
        startupinfo = None
        creationflags = 0
        if os.name == "nt":
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            creationflags = subprocess.CREATE_NO_WINDOW
        try:
            completed = subprocess.run(
                argv,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="strict",
                timeout=timeout_seconds,
                startupinfo=startupinfo,
                creationflags=creationflags,
            )
        except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
            raise CollectorError(f"Command could not complete: {_display_command(argv)}: {exc}") from exc
        return CommandResult(argv, completed.returncode, completed.stdout, completed.stderr)


@dataclass(frozen=True)
class ProcessInfo:
    pid: int
    name: str
    command_line: str


@dataclass(frozen=True)
class ProcessSnapshot:
    query: CommandResult
    processes: tuple[ProcessInfo, ...]


class ProcessSource(Protocol):
    def qemu_snapshot(self) -> ProcessSnapshot:
        """Return the query transcript and every live QEMU process it observed."""


class WindowsCimProcessSource:
    """Read QEMU process identity through Win32_Process, not remembered launch input."""

    def __init__(self, executor: CommandExecutor, powershell: str = "powershell.exe") -> None:
        self._executor = executor
        self._powershell = powershell

    def qemu_snapshot(self) -> ProcessSnapshot:
        result = _checked_run(
            self._executor,
            (
                self._powershell,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                QEMU_CIM_SCRIPT,
            ),
            timeout_seconds=30,
            context="query live QEMU processes",
        )
        raw = result.stdout.strip()
        if not raw:
            raise CollectorError("Win32_Process returned no JSON process inventory")
        try:
            decoded = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise CollectorError(f"Win32_Process returned invalid JSON: {exc}") from exc
        records = decoded if isinstance(decoded, list) else [decoded]
        processes: list[ProcessInfo] = []
        for index, record in enumerate(records):
            if not isinstance(record, Mapping):
                raise CollectorError(f"Win32_Process entry {index} is not an object")
            pid = record.get("pid")
            name = record.get("name")
            command_line = record.get("command_line")
            if (
                isinstance(pid, bool)
                or not isinstance(pid, int)
                or pid <= 0
                or not isinstance(name, str)
                or not name.strip()
                or not isinstance(command_line, str)
                or not command_line.strip()
            ):
                raise CollectorError(f"Win32_Process entry {index} has incomplete identity")
            processes.append(ProcessInfo(pid, name.strip(), command_line.strip()))
        return ProcessSnapshot(result, tuple(processes))


@dataclass(frozen=True)
class SourceIdentity:
    digest: str
    algorithm: str
    file_count: int
    git_object_format: str


class SourceVerifier(Protocol):
    def verify(self, expected_digest: str) -> SourceIdentity:
        """Bind collection to a committed, clean release source tree."""


class GitSourceVerifier:
    def __init__(self, repo_root: Path) -> None:
        self._repo_root = repo_root.resolve()

    def verify(self, expected_digest: str) -> SourceIdentity:
        release_evidence = _load_release_evidence_module()
        evidence_root = self._repo_root / "android" / "release-evidence"
        try:
            release_evidence.require_source_clean_for_create(self._repo_root, evidence_root)
            observed = release_evidence.git_source_tree_identity(self._repo_root)
            release_evidence.require_source_clean_for_create(self._repo_root, evidence_root)
            confirmed = release_evidence.git_source_tree_identity(self._repo_root)
        except Exception as exc:
            raise CollectorError(f"Unable to establish clean committed release source: {exc}") from exc
        if confirmed != observed:
            raise CollectorError("committed release source identity changed while it was measured")
        if observed.digest != expected_digest:
            raise CollectorError(
                "release source digest does not match the clean committed HEAD: "
                f"expected {expected_digest}, observed {observed.digest}"
            )
        return SourceIdentity(
            digest=observed.digest,
            algorithm=observed.algorithm,
            file_count=observed.file_count,
            git_object_format=observed.git_object_format,
        )


class PayloadValidator(Protocol):
    def validate(
        self,
        path: Path,
        host_raw_path: Path,
        macrobenchmark_raw_path: Path,
        trace_paths: Sequence[Path],
        config: "CollectorConfig",
    ) -> None:
        """Validate the fully serialized normalized record and every raw artifact."""


class ReleaseEvidencePayloadValidator:
    def validate(
        self,
        path: Path,
        host_raw_path: Path,
        macrobenchmark_raw_path: Path,
        trace_paths: Sequence[Path],
        config: "CollectorConfig",
    ) -> None:
        release_evidence = _load_release_evidence_module()
        try:
            release_evidence._validate_performance(
                path,
                config.profile,
                config.release_source_digest,
                config.version_name,
                config.version_code,
                artifact_path_overrides={
                    f"performance/{config.profile}.host.raw.json": host_raw_path,
                    f"performance/{config.profile}.macrobenchmark.raw.json": macrobenchmark_raw_path,
                    **{
                        f"performance/{config.profile}.traces/iteration-{index:03d}.perfetto-trace": trace_path
                        for index, trace_path in enumerate(trace_paths, start=1)
                    },
                },
            )
        except Exception as exc:
            raise CollectorError(f"Collected JSON fails the release evidence schema: {exc}") from exc


@dataclass(frozen=True)
class CollectorConfig:
    serial: str
    profile: str
    expected_avd_name: str
    expected_boot_id: str
    release_source_digest: str
    benchmark_target_apk_sha256: str
    benchmark_test_apk_sha256: str
    evidence_run_id: str
    version_name: str
    version_code: int
    litertlm_coordinate: str
    macrobenchmark_report: Path
    macrobenchmark_traces: tuple[Path, ...]
    macrobenchmark_invocation: Path
    adb: str = "adb"
    emulator: str = "emulator"

    def validate(self) -> int:
        serial_match = SERIAL_RE.fullmatch(self.serial)
        if not serial_match:
            raise CollectorError("serial must be an exact emulator-<console-port> identifier")
        console_port = int(serial_match.group(1))
        if console_port <= 0 or console_port >= 65_535 or console_port % 2:
            raise CollectorError("emulator console port must be a positive even port below 65535")
        if self.profile not in PROFILES:
            raise CollectorError(f"profile must be one of {', '.join(PROFILES)}")
        if not AVD_NAME_RE.fullmatch(self.expected_avd_name):
            raise CollectorError("expected_avd_name must be one exact AVD identifier")
        if not BOOT_ID_RE.fullmatch(self.expected_boot_id):
            raise CollectorError("expected_boot_id must be one lowercase kernel boot UUID")
        for name, digest in (
            ("release_source_digest", self.release_source_digest),
            ("benchmark_target_apk_sha256", self.benchmark_target_apk_sha256),
            ("benchmark_test_apk_sha256", self.benchmark_test_apk_sha256),
        ):
            if not HEX_64_RE.fullmatch(digest):
                raise CollectorError(f"{name} must be an exact lowercase SHA-256")
        if not RUN_ID_RE.fullmatch(self.evidence_run_id):
            raise CollectorError("evidence_run_id must match the release run-id contract")
        if not self.version_name or any(character.isspace() for character in self.version_name):
            raise CollectorError("version_name must be a nonblank token")
        if isinstance(self.version_code, bool) or self.version_code <= 0:
            raise CollectorError("version_code must be a positive integer")
        if self.litertlm_coordinate != LITERTLM_COORDINATE:
            raise CollectorError(
                f"litertlm_coordinate must equal the release dependency {LITERTLM_COORDINATE}"
            )
        if not self.adb.strip() or not self.emulator.strip():
            raise CollectorError("adb and emulator executable inputs must be nonblank")
        inputs = (
            ("macrobenchmark_report", self.macrobenchmark_report),
            ("macrobenchmark_invocation", self.macrobenchmark_invocation),
        )
        for name, path in inputs:
            if not path.is_file() or path.is_symlink():
                raise CollectorError(f"{name} must be one existing regular non-symlink file")
        if not MIN_BENCHMARK_ITERATIONS <= len(self.macrobenchmark_traces) <= MAX_BENCHMARK_ITERATIONS:
            raise CollectorError(
                f"macrobenchmark_traces must contain {MIN_BENCHMARK_ITERATIONS} to "
                f"{MAX_BENCHMARK_ITERATIONS} files"
            )
        resolved_traces: set[Path] = set()
        for trace in self.macrobenchmark_traces:
            if not trace.is_file() or trace.is_symlink() or trace.stat().st_size <= 0:
                raise CollectorError("every macrobenchmark trace must be a nonempty regular non-symlink file")
            if not trace.name.endswith(".perfetto-trace"):
                raise CollectorError("every macrobenchmark trace must end in .perfetto-trace")
            resolved = trace.resolve()
            if resolved in resolved_traces:
                raise CollectorError("macrobenchmark trace inputs must be unique")
            resolved_traces.add(resolved)
        return console_port


@dataclass(frozen=True)
class DeviceIdentity:
    serial: str
    avd_name: str
    boot_id: str
    model: str
    build_fingerprint: str
    android_sdk: int
    supported_abis: tuple[str, ...]
    font_scale: float
    benchmark_target_apk_path: str
    benchmark_target_apk_sha256: str
    benchmark_test_apk_path: str
    benchmark_test_apk_sha256: str
    version_name: str
    version_code: int


@dataclass(frozen=True)
class QemuIdentity:
    pid: int
    name: str
    command_line: str
    public_command: str
    public_command_sha256: str
    raw_command_sha256: str


@dataclass(frozen=True)
class MacrobenchmarkInputs:
    report_bytes: bytes
    report: Mapping[str, Any]
    invocation: CommandResult
    traces: tuple[Path, ...]
    frames: Mapping[str, Any]
    iteration_count: int
    trace_source_names: tuple[str, ...]


class PerformanceCollector:
    def __init__(
        self,
        config: CollectorConfig,
        executor: CommandExecutor,
        process_source: ProcessSource,
        source_verifier: SourceVerifier,
    ) -> None:
        self.config = config
        self.executor = executor
        self.process_source = process_source
        self.source_verifier = source_verifier
        self._records: list[dict[str, Any]] = []
        self._record_ids: set[str] = set()
        self._raw_transcript: dict[str, Any] | None = None

    @property
    def raw_transcript(self) -> dict[str, Any]:
        if self._raw_transcript is None:
            raise CollectorError("raw transcript is unavailable before a successful collection")
        return self._raw_transcript

    def collect(self) -> dict[str, Any]:
        console_port = self.config.validate()
        source_identity = self.source_verifier.verify(self.config.release_source_digest)
        if source_identity.digest != self.config.release_source_digest:
            raise CollectorError("source verifier returned an identity different from the exact input")
        macrobenchmark = self._macrobenchmark_inputs()
        self._record_result("macrobenchmark.invocation", macrobenchmark.invocation)
        self._verify_adb_target("initial")
        initial_device = self._observe_device_identity("initial")
        self._bind_report_identity(macrobenchmark.report, initial_device)
        qemu = self._resolve_qemu("initial", initial_device.avd_name, console_port)
        target_compiler_filter = self._target_compiler_filter(
            "initial", initial_device.benchmark_target_apk_path
        )
        acceleration = self._acceleration_check()
        width_px, height_px, density_dpi = self._screen_pixels()
        width_dp, height_dp = self._configured_dp()
        self._check_profile_dimensions(width_dp, height_dp)
        gpu_renderer = self._gpu_renderer()
        cold_total, cold_wait = self._cold_launch()
        warm_total, warm_process_pid = self._warm_launch()
        self._foreground_activity("measure.activity.after_launch")
        memory = self._memory(warm_process_pid)
        final_process_pid = _parse_pidof(
            self._adb_text(
                "measure.process.pid_after_measurement",
                "shell",
                "pidof",
                PACKAGE_ID,
                timeout_seconds=30,
            )
        )
        if final_process_pid != warm_process_pid:
            raise CollectorError("Hermes process PID changed during performance measurement")

        self._verify_adb_target("final")
        final_device = self._observe_device_identity("final")
        if final_device != initial_device:
            raise CollectorError("device, boot, package, or APK identity changed during collection")
        final_qemu = self._resolve_qemu("final", initial_device.avd_name, console_port)
        if final_qemu != qemu:
            raise CollectorError("live QEMU PID or command line changed during collection")
        final_target_compiler_filter = self._target_compiler_filter(
            "final", final_device.benchmark_target_apk_path
        )
        if final_target_compiler_filter != target_compiler_filter:
            raise CollectorError("target compiler filter changed during performance collection")
        final_source_identity = self.source_verifier.verify(self.config.release_source_digest)
        if final_source_identity != source_identity:
            raise CollectorError("source identity changed during performance collection")

        host_raw_relative_path = f"performance/{self.config.profile}.host.raw.json"
        macro_raw_relative_path = f"performance/{self.config.profile}.macrobenchmark.raw.json"
        raw_transcript = {
            "schema": RAW_PERFORMANCE_SCHEMA,
            "profile": self.config.profile,
            "release_source_digest": self.config.release_source_digest,
            "benchmark_target_apk_sha256": self.config.benchmark_target_apk_sha256,
            "benchmark_test_apk_sha256": self.config.benchmark_test_apk_sha256,
            "evidence_run_id": self.config.evidence_run_id,
            "package_id": PACKAGE_ID,
            "benchmark_test_package_id": TEST_PACKAGE_ID,
            "version_name": self.config.version_name,
            "version_code": self.config.version_code,
            "build_variant": BUILD_VARIANT,
            "litertlm_coordinate": self.config.litertlm_coordinate,
            "records": self._records,
        }
        host_raw_bytes = _encode_json(raw_transcript)
        trace_records = []
        for iteration, (trace, source_name) in enumerate(
            zip(macrobenchmark.traces, macrobenchmark.trace_source_names, strict=True),
            start=1,
        ):
            trace_bytes = trace.read_bytes()
            trace_records.append(
                {
                    "iteration": iteration,
                    "path": (
                        f"performance/{self.config.profile}.traces/"
                        f"iteration-{iteration:03d}.perfetto-trace"
                    ),
                    "source_name": source_name,
                    "bytes": len(trace_bytes),
                    "sha256": hashlib.sha256(trace_bytes).hexdigest(),
                }
            )
        payload = {
            "schema": PERFORMANCE_SCHEMA,
            "profile": self.config.profile,
            "release_source_digest": self.config.release_source_digest,
            "benchmark_target_apk_sha256": self.config.benchmark_target_apk_sha256,
            "benchmark_test_apk_sha256": self.config.benchmark_test_apk_sha256,
            "evidence_run_id": self.config.evidence_run_id,
            "package_id": PACKAGE_ID,
            "version_name": self.config.version_name,
            "version_code": self.config.version_code,
            "build_variant": BUILD_VARIANT,
            "litertlm_coordinate": self.config.litertlm_coordinate,
            "recorded_at_epoch_ms": int(time.time() * 1_000),
            "evidence_classification": {
                "environment": "headed-hardware-accelerated-avd",
                "result_kind": "validation-signal",
                "representative_end_user_benchmark": False,
            },
            "raw_evidence": {
                "host": {
                    "path": host_raw_relative_path,
                    "bytes": len(host_raw_bytes),
                    "sha256": hashlib.sha256(host_raw_bytes).hexdigest(),
                },
                "macrobenchmark": {
                    "path": macro_raw_relative_path,
                    "bytes": len(macrobenchmark.report_bytes),
                    "sha256": hashlib.sha256(macrobenchmark.report_bytes).hexdigest(),
                },
            },
            "benchmark": {
                "target_package_id": PACKAGE_ID,
                "test_package_id": TEST_PACKAGE_ID,
                "runner": "androidx.test.runner.AndroidJUnitRunner",
                "test_id": BENCHMARK_TEST_ID,
                "androidx_benchmark_coordinate": ANDROIDX_BENCHMARK_COORDINATE,
                "compilation_mode": "Full",
                "reporting_package_compilation_mode": REPORTING_PACKAGE_COMPILATION_MODE,
                "target_compiler_filter": target_compiler_filter,
                "iteration_count": macrobenchmark.iteration_count,
                "suppressed_errors": ["EMULATOR"],
                "profiling_mode": "None",
                "target_debuggable": False,
                "target_profileable_by_shell": True,
            },
            "traces": trace_records,
            "device": {
                "serial": initial_device.serial,
                "avd_name": initial_device.avd_name,
                "boot_id": initial_device.boot_id,
                "model": initial_device.model,
                "build_fingerprint": initial_device.build_fingerprint,
                "android_sdk": initial_device.android_sdk,
                "supported_abis": list(initial_device.supported_abis),
                "hardware_acceleration": True,
                "acceleration_check": acceleration["output"],
                "acceleration_check_exit_code": acceleration["exit_code"],
                "gpu_renderer": gpu_renderer,
                "active_qemu_process_count": 1,
                "emulator_pid": qemu.pid,
                "emulator_process_name": qemu.name,
                "emulator_public_command": qemu.public_command,
                "emulator_public_command_sha256": qemu.public_command_sha256,
                "emulator_raw_command_sha256": qemu.raw_command_sha256,
            },
            "screen": {
                "width_px": width_px,
                "height_px": height_px,
                "width_dp": width_dp,
                "height_dp": height_dp,
                "density_dpi": density_dpi,
                "font_scale": initial_device.font_scale,
            },
            "launch": {
                "cold_total_ms": cold_total,
                "cold_wait_ms": cold_wait,
                "warm_total_ms": warm_total,
                "warm_process_pid": warm_process_pid,
            },
            "frames": macrobenchmark.frames,
            "memory": memory,
            "collector": {
                "source_digest_algorithm": source_identity.algorithm,
                "source_file_count": source_identity.file_count,
                "git_object_format": source_identity.git_object_format,
                "benchmark_target_apk_device_path": initial_device.benchmark_target_apk_path,
                "benchmark_test_apk_device_path": initial_device.benchmark_test_apk_path,
                "scenario": "settings-list-fling",
            },
        }
        self._raw_transcript = raw_transcript
        return payload

    def _macrobenchmark_inputs(self) -> MacrobenchmarkInputs:
        try:
            report_bytes = self.config.macrobenchmark_report.read_bytes()
            report = json.loads(report_bytes.decode("utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise CollectorError(f"macrobenchmark report is not strict UTF-8 JSON: {exc}") from exc
        if not isinstance(report, Mapping):
            raise CollectorError("macrobenchmark report root must be one JSON object")

        try:
            invocation_payload = json.loads(
                self.config.macrobenchmark_invocation.read_text(encoding="utf-8")
            )
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise CollectorError(f"macrobenchmark invocation capture is invalid: {exc}") from exc
        invocation = _parse_macrobenchmark_invocation(invocation_payload, self.config)
        frames, iteration_count, source_names = _parse_macrobenchmark_report(report, self.config)

        traces_by_name: dict[str, Path] = {}
        for trace in self.config.macrobenchmark_traces:
            if trace.name in traces_by_name:
                raise CollectorError(f"duplicate macrobenchmark trace basename: {trace.name}")
            traces_by_name[trace.name] = trace
        if set(traces_by_name) != set(source_names):
            raise CollectorError(
                "trace inputs do not exactly match AndroidX profilerOutputs: "
                f"expected={list(source_names)!r}, observed={sorted(traces_by_name)!r}"
            )
        traces = tuple(traces_by_name[name] for name in source_names)
        if len(traces) != iteration_count:
            raise CollectorError("AndroidX trace count does not equal repeatIterations")
        return MacrobenchmarkInputs(
            report_bytes=report_bytes,
            report=report,
            invocation=invocation,
            traces=traces,
            frames=frames,
            iteration_count=iteration_count,
            trace_source_names=source_names,
        )

    def _bind_report_identity(
        self, report: Mapping[str, Any], device: DeviceIdentity
    ) -> None:
        context = _mapping_field(report, "context", "macrobenchmark")
        build = _mapping_field(context, "build", "macrobenchmark.context")
        version = _mapping_field(build, "version", "macrobenchmark.context.build")
        expected = {
            "fingerprint": device.build_fingerprint,
            "model": device.model,
        }
        for field, expected_value in expected.items():
            if build.get(field) != expected_value:
                raise CollectorError(
                    f"macrobenchmark context build {field} does not match the live AVD"
                )
        if version.get("sdk") != device.android_sdk:
            raise CollectorError("macrobenchmark context SDK does not match the live AVD")
        if context.get("payload") != {
            "sourceDigest": self.config.release_source_digest,
            "targetApkSha256": self.config.benchmark_target_apk_sha256,
            "benchmarkApkSha256": self.config.benchmark_test_apk_sha256,
            "evidenceRunId": self.config.evidence_run_id,
            "evidenceProfile": self.config.profile,
            "avdName": self.config.expected_avd_name,
            "bootId": self.config.expected_boot_id,
        }:
            raise CollectorError(
                "macrobenchmark context payload does not bind the exact source/APKs/run/profile/boot"
            )

    def _record_result(self, record_id: str, result: CommandResult) -> None:
        if record_id in self._record_ids or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,99}", record_id):
            raise CollectorError(f"duplicate or invalid raw transcript record id: {record_id!r}")
        self._record_ids.add(record_id)
        public_argv = list(result.args)
        if public_argv:
            public_argv[0] = _portable_executable_name(public_argv[0])
        self._records.append(
            {
                "id": record_id,
                "argv": public_argv,
                "exit_code": result.returncode,
                "stdout": result.stdout,
                "stderr": result.stderr,
            }
        )

    def _run(
        self,
        record_id: str,
        args: Sequence[str],
        *,
        timeout_seconds: int,
        context: str,
    ) -> CommandResult:
        result = self.executor.run(args, timeout_seconds=timeout_seconds)
        self._record_result(record_id, result)
        if result.returncode != 0:
            detail = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
            raise CollectorError(
                f"Failed to {context}: exit {result.returncode}: {_display_command(args)}"
                + (f"\n{detail}" if detail else "")
            )
        return result

    def _adb(
        self, record_id: str, *parts: str, timeout_seconds: int = 30
    ) -> CommandResult:
        return self._run(
            record_id,
            (self.config.adb, "-s", self.config.serial, *parts),
            timeout_seconds=timeout_seconds,
            context=f"run adb {parts[0] if parts else 'command'}",
        )

    def _adb_text(self, record_id: str, *parts: str, timeout_seconds: int = 30) -> str:
        result = self._adb(record_id, *parts, timeout_seconds=timeout_seconds)
        value = result.stdout.strip()
        if not value:
            raise CollectorError(f"adb {' '.join(parts)} returned blank output")
        return value

    def _verify_adb_target(self, phase: str) -> None:
        inventory = self._run(
            f"{phase}.adb.devices",
            (self.config.adb, "devices", "-l"),
            timeout_seconds=30,
            context="enumerate adb devices",
        )
        endpoints: list[tuple[str, str]] = []
        for line in inventory.stdout.splitlines():
            fields = line.strip().split()
            if not fields or line.strip() == "List of devices attached":
                continue
            if len(fields) < 2:
                raise CollectorError(f"adb inventory has no state for endpoint {fields[0]}")
            endpoints.append((fields[0], fields[1]))
        if endpoints != [(self.config.serial, "device")]:
            raise CollectorError(
                "normal release collection requires exactly one attached adb endpoint, "
                f"the requested serial in device state; observed {endpoints!r}"
            )
        serial = self._adb_text(f"{phase}.adb.get-serialno", "get-serialno")
        if serial != self.config.serial:
            raise CollectorError(
                f"adb get-serialno returned {serial!r}, expected exact {self.config.serial!r}"
            )
        if self._adb_text(f"{phase}.adb.get-state", "get-state") != "device":
            raise CollectorError(f"adb get-state did not confirm {self.config.serial} as a device")

    def _getprop(self, phase: str, record_name: str, name: str) -> str:
        result = self._adb(f"{phase}.device.getprop.{record_name}", "shell", "getprop", name)
        value = result.stdout.strip()
        if not value:
            raise CollectorError(f"getprop {name} returned blank output")
        if "\n" in value or "\r" in value:
            raise CollectorError(f"getprop {name} returned multiple lines")
        return value

    def _observe_device_identity(self, phase: str) -> DeviceIdentity:
        avd_name = self._getprop(phase, "avd_name", "ro.boot.qemu.avd_name")
        if not AVD_NAME_RE.fullmatch(avd_name):
            raise CollectorError(f"observed AVD name is invalid: {avd_name!r}")
        if avd_name != self.config.expected_avd_name:
            raise CollectorError(
                f"observed AVD name {avd_name!r} does not match the pre-run identity"
            )
        fingerprint = self._getprop(phase, "build_fingerprint", "ro.build.fingerprint")
        model = self._getprop(phase, "model", "ro.product.model")
        sdk_text = self._getprop(phase, "android_sdk", "ro.build.version.sdk")
        if not sdk_text.isdigit() or int(sdk_text) < 24:
            raise CollectorError(f"observed Android SDK is invalid or unsupported: {sdk_text!r}")
        abi_text = self._getprop(phase, "supported_abis", "ro.product.cpu.abilist")
        abis = tuple(part.strip() for part in abi_text.split(",") if part.strip())
        if not abis or len(set(abis)) != len(abis) or "x86_64" not in abis:
            raise CollectorError(f"observed ABI list does not prove one x86_64 AVD: {abis!r}")
        boot_id = self._adb_text(
            f"{phase}.device.boot_id",
            "shell",
            "cat",
            "/proc/sys/kernel/random/boot_id",
        ).lower()
        if not BOOT_ID_RE.fullmatch(boot_id):
            raise CollectorError(f"observed kernel boot_id is invalid: {boot_id!r}")
        if boot_id != self.config.expected_boot_id:
            raise CollectorError(
                f"observed kernel boot_id {boot_id!r} does not match the pre-run identity"
            )
        font_scale_text = self._adb_text(
            f"{phase}.device.settings.font_scale",
            "shell",
            "settings",
            "get",
            "system",
            "font_scale",
        )
        try:
            font_scale = float(font_scale_text)
        except ValueError as exc:
            raise CollectorError(f"observed font_scale is invalid: {font_scale_text!r}") from exc
        if not math.isfinite(font_scale) or font_scale != 1.0:
            raise CollectorError(f"release evidence requires font_scale exactly 1.0, got {font_scale!r}")
        target_path, target_sha = self._installed_apk_identity(
            phase, "benchmark_target", PACKAGE_ID, self.config.benchmark_target_apk_sha256
        )
        test_path, test_sha = self._installed_apk_identity(
            phase, "benchmark_test", TEST_PACKAGE_ID, self.config.benchmark_test_apk_sha256
        )
        version_name, version_code = self._installed_version(phase)
        if version_name != self.config.version_name or version_code != self.config.version_code:
            raise CollectorError(
                "installed package version does not match exact inputs: "
                f"observed {version_name} ({version_code})"
            )
        return DeviceIdentity(
            serial=self.config.serial,
            avd_name=avd_name,
            boot_id=boot_id,
            model=model,
            build_fingerprint=fingerprint,
            android_sdk=int(sdk_text),
            supported_abis=abis,
            font_scale=font_scale,
            benchmark_target_apk_path=target_path,
            benchmark_target_apk_sha256=target_sha,
            benchmark_test_apk_path=test_path,
            benchmark_test_apk_sha256=test_sha,
            version_name=version_name,
            version_code=version_code,
        )

    def _installed_apk_identity(
        self, phase: str, label: str, package_id: str, expected_sha: str
    ) -> tuple[str, str]:
        raw_paths = self._adb_text(
            f"{phase}.package.{label}.path", "shell", "pm", "path", package_id
        )
        paths = [
            line.removeprefix("package:").strip()
            for line in raw_paths.splitlines()
            if line.strip().startswith("package:")
        ]
        if len(paths) != 1 or not paths[0].startswith("/"):
            raise CollectorError(
                f"{package_id} must resolve to exactly one installed APK path; observed {paths!r}"
            )
        checksum = self._adb_text(
            f"{phase}.package.{label}.sha256", "shell", "sha256sum", paths[0]
        )
        match = re.fullmatch(r"([0-9A-Fa-f]{64})\s+(.+)", checksum)
        if not match:
            raise CollectorError(f"could not parse on-device SHA-256 for {package_id}")
        observed_sha = match.group(1).lower()
        if observed_sha != expected_sha:
            raise CollectorError(
                f"installed {package_id} APK SHA-256 differs from exact input: {observed_sha}"
            )
        return paths[0], observed_sha

    def _installed_version(self, phase: str) -> tuple[str, int]:
        package_dump = self._adb_text(
            f"{phase}.package.version", "shell", "dumpsys", "package", PACKAGE_ID
        )
        version_names = re.findall(r"(?m)^\s*versionName=([^\s]+)\s*$", package_dump)
        version_codes = [
            int(value)
            for value in re.findall(r"(?m)^\s*versionCode=([0-9]+)(?:\s|$)", package_dump)
        ]
        if len(version_names) != 1 or len(version_codes) != 1:
            raise CollectorError("dumpsys package did not expose one unambiguous installed version")
        return version_names[0], version_codes[0]

    def _target_compiler_filter(self, phase: str, base_apk_path: str) -> str:
        package_dump = self._adb_text(
            f"measure.package.target_compiler_filter.{phase}",
            "shell",
            "cmd",
            "package",
            "dump",
            PACKAGE_ID,
            timeout_seconds=60,
        )
        return _parse_target_compiler_filter(package_dump, base_apk_path)

    def _resolve_qemu(self, phase: str, avd_name: str, console_port: int) -> QemuIdentity:
        snapshot = self.process_source.qemu_snapshot()
        if snapshot.query.stderr.strip():
            raise CollectorError("live QEMU process query emitted unexpected diagnostics")
        live_qemu = [
            process
            for process in snapshot.processes
            if process.name.casefold().startswith("qemu-system-")
        ]
        if len(live_qemu) > 2:
            raise CollectorError(
                f"live QEMU process count {len(live_qemu)} exceeds the absolute two-emulator limit"
            )
        if len(live_qemu) != 1:
            raise CollectorError(
                "normal release collection requires exactly one total live qemu-system process; "
                f"observed {len(live_qemu)}"
            )
        matches: list[QemuIdentity] = []
        for process in live_qemu:
            tokens = _tokenize_windows_command(process.command_line)
            avd_values = _flag_values(tokens, "-avd")
            port_values = _flag_values(tokens, "-port")
            ports_values = _flag_values(tokens, "-ports")
            exact_port = port_values == [str(console_port)] and not ports_values
            exact_ports = ports_values == [f"{console_port},{console_port + 1}"] and not port_values
            if avd_values == [avd_name] and (exact_port or exact_ports):
                public_command = _public_qemu_command(process, avd_name, console_port)
                matches.append(
                    QemuIdentity(
                        process.pid,
                        process.name,
                        process.command_line,
                        public_command,
                        hashlib.sha256(public_command.encode("utf-8")).hexdigest(),
                        hashlib.sha256(process.command_line.encode("utf-8")).hexdigest(),
                    )
                )
        if len(matches) != 1:
            raise CollectorError(
                "expected exactly one live qemu-system process for "
                f"{self.config.serial}/{avd_name}; observed {len(matches)}"
            )
        qemu = matches[0]
        tokens = _tokenize_windows_command(qemu.command_line)
        if _flag_values(tokens, "-gpu", casefold=True) != ["host"]:
            raise CollectorError("live QEMU command must contain exactly one effective -gpu host")
        if _flag_values(tokens, "-accel", casefold=True) != ["on"]:
            raise CollectorError("live QEMU command must contain exactly one effective -accel on")
        if any(_strip_token(token).casefold() == "-no-window" for token in tokens):
            raise CollectorError("live QEMU command uses -no-window and is not headed")
        public_inventory = [
            {
                "pid": qemu.pid,
                "name": qemu.name,
                "public_command": qemu.public_command,
                "public_command_sha256": qemu.public_command_sha256,
                "raw_command_sha256": qemu.raw_command_sha256,
            }
        ]
        self._record_result(
            f"{phase}.host.qemu_processes",
            CommandResult(
                snapshot.query.args,
                snapshot.query.returncode,
                json.dumps(public_inventory, separators=(",", ":")),
                snapshot.query.stderr,
            ),
        )
        return qemu

    def _acceleration_check(self) -> dict[str, Any]:
        result = self._run(
            "measure.emulator.accel-check",
            (self.config.emulator, "-accel-check"),
            timeout_seconds=30,
            context="run emulator -accel-check",
        )
        combined = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        normalized = combined.casefold()
        if not combined or "usable" not in normalized or re.search(
            r"\b(?:not|isn't|isnt|unusable|failed|unavailable)\b", normalized
        ):
            raise CollectorError(f"emulator -accel-check did not prove usable acceleration: {combined}")
        return {"exit_code": result.returncode, "output": combined}

    def _screen_pixels(self) -> tuple[int, int, int]:
        size_output = self._adb_text("measure.screen.wm_size", "shell", "wm", "size")
        density_output = self._adb_text("measure.screen.wm_density", "shell", "wm", "density")
        width_px, height_px = _parse_wm_size(size_output)
        density_dpi = _parse_wm_density(density_output)
        return width_px, height_px, density_dpi

    def _cold_launch(self) -> tuple[int, int]:
        self._adb("measure.launch.force_stop", "shell", "am", "force-stop", PACKAGE_ID)
        result = self._adb_text(
            "measure.launch.cold",
            "shell",
            "am",
            "start",
            "-W",
            "-S",
            "-n",
            MAIN_ACTIVITY,
        )
        metrics = _parse_start_metrics(result, expected_states={"COLD"})
        return metrics["TotalTime"], metrics["WaitTime"]

    def _warm_launch(self) -> tuple[int, int]:
        process_before = _parse_pidof(
            self._adb_text(
                "measure.launch.pid_before_back",
                "shell",
                "pidof",
                PACKAGE_ID,
            )
        )
        self._adb("measure.launch.back", "shell", "input", "keyevent", "KEYCODE_BACK")
        process_after = _parse_pidof(
            self._adb_text(
                "measure.launch.pid_after_back",
                "shell",
                "pidof",
                PACKAGE_ID,
            )
        )
        if process_after != process_before:
            raise CollectorError(
                "Hermes process PID changed after KEYCODE_BACK; warm launch identity was not preserved: "
                f"{process_before} -> {process_after}"
            )
        result = self._adb_text(
            "measure.launch.warm", "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY
        )
        try:
            metrics = _parse_start_metrics(result, expected_states={"WARM", "HOT"})
        except CollectorError:
            if not _is_retryable_unknown_start(result):
                raise
            retry_before = _parse_pidof(
                self._adb_text(
                    "measure.launch.retry.pid_before_back",
                    "shell",
                    "pidof",
                    PACKAGE_ID,
                )
            )
            if retry_before != process_after:
                raise CollectorError(
                    "Hermes process PID changed after transient UNKNOWN warm launch: "
                    f"{process_after} -> {retry_before}"
                )
            self._adb(
                "measure.launch.retry.back", "shell", "input", "keyevent", "KEYCODE_BACK"
            )
            retry_after = _parse_pidof(
                self._adb_text(
                    "measure.launch.retry.pid_after_back",
                    "shell",
                    "pidof",
                    PACKAGE_ID,
                )
            )
            if retry_after != retry_before:
                raise CollectorError(
                    "Hermes process PID changed during bounded warm-launch retry: "
                    f"{retry_before} -> {retry_after}"
                )
            retry_result = self._adb_text(
                "measure.launch.retry.warm",
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                MAIN_ACTIVITY,
            )
            metrics = _parse_start_metrics(retry_result, expected_states={"WARM", "HOT"})
        return metrics["TotalTime"], process_after

    def _configured_dp(self) -> tuple[int, int]:
        output = self._adb_text("measure.screen.am_config", "shell", "am", "get-config")
        pairs = {
            (int(width), int(height))
            for width, height in re.findall(r"(?:^|[-\s])w([0-9]+)dp-h([0-9]+)dp(?:[-\s]|$)", output)
        }
        if len(pairs) != 1:
            raise CollectorError(f"am get-config did not expose one width/height dp pair: {pairs!r}")
        return next(iter(pairs))

    def _check_profile_dimensions(self, width_dp: int, height_dp: int) -> None:
        if self.config.profile == "phone-compact":
            valid = 320 <= width_dp <= 480 and height_dp >= 480 and height_dp > width_dp
        else:
            valid = 600 <= width_dp <= 1_600 and height_dp >= 600
        if not valid:
            raise CollectorError(
                f"observed {width_dp}x{height_dp}dp does not match {self.config.profile}"
            )

    def _gpu_renderer(self) -> str:
        output = self._adb_text(
            "measure.gpu.surfaceflinger",
            "shell",
            "dumpsys",
            "SurfaceFlinger",
            timeout_seconds=60,
        )
        gles_renderers = [
            match.group(1).strip()
            for match in re.finditer(r"(?mi)^\s*GLES:\s*[^,\r\n]+,\s*([^,\r\n]+),", output)
            if match.group(1).strip()
        ]
        direct_renderers = [
            match.group(1).strip()
            for match in re.finditer(r"(?mi)^\s*GL_RENDERER\s*[:=]\s*([^\r\n]+)$", output)
            if match.group(1).strip()
        ]
        observed = [*gles_renderers, *direct_renderers]
        if not observed or any(
            marker in renderer.casefold()
            for renderer in observed
            for marker in SOFTWARE_RENDERER_MARKERS
        ):
            raise CollectorError(f"SurfaceFlinger reports a missing/software GPU renderer: {observed!r}")
        if len(gles_renderers) > 1 or len(direct_renderers) > 1:
            raise CollectorError(f"SurfaceFlinger exposes duplicate GPU renderer claims: {observed!r}")
        if gles_renderers and direct_renderers and gles_renderers != direct_renderers:
            raise CollectorError(f"SurfaceFlinger exposes contradictory GPU renderers: {observed!r}")
        renderer = observed[0]
        return renderer

    def _foreground_activity(self, record_id: str) -> None:
        output = self._adb_text(
            record_id,
            "shell",
            "dumpsys",
            "activity",
            "activities",
            timeout_seconds=60,
        )
        _require_resumed_activity(output, record_id)

    def _memory(self, expected_pid: int) -> dict[str, int]:
        output = self._adb_text(
            "measure.memory.meminfo",
            "shell",
            "dumpsys",
            "meminfo",
            PACKAGE_ID,
            timeout_seconds=60,
        )
        _require_process_header(output, "MEMINFO in pid", expected_pid, "meminfo")
        matches = re.findall(
            r"(?mi)^\s*TOTAL\s+PSS:\s*([0-9]+)\s+TOTAL\s+RSS:\s*([0-9]+)(?:\s|$)",
            output,
        )
        observed = [(int(pss), int(rss)) for pss, rss in matches]
        if len(observed) != 1:
            raise CollectorError(
                f"dumpsys meminfo did not expose one TOTAL PSS/RSS pair: {observed!r}"
            )
        total_pss, total_rss = observed[0]
        if total_pss <= 0 or total_rss <= 0 or total_pss > total_rss:
            raise CollectorError(f"invalid TOTAL PSS/RSS relationship: {total_pss}/{total_rss} KB")
        return {"total_pss_kb": total_pss, "total_rss_kb": total_rss}


def _mapping_field(value: Mapping[str, Any], field: str, context: str) -> Mapping[str, Any]:
    nested = value.get(field)
    if not isinstance(nested, Mapping):
        raise CollectorError(f"{context}.{field} must be one JSON object")
    return nested


def _finite_number(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CollectorError(f"{context} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise CollectorError(f"{context} must be a finite number")
    return result


def _metric_runs(metrics: Mapping[str, Any], name: str, iterations: int) -> list[float]:
    metric = _mapping_field(metrics, name, "macrobenchmark.metrics")
    runs = metric.get("runs")
    if not isinstance(runs, list) or len(runs) != iterations:
        raise CollectorError(
            f"macrobenchmark.metrics.{name}.runs must contain exactly {iterations} values"
        )
    return [
        _finite_number(value, f"macrobenchmark.metrics.{name}.runs[{index}]")
        for index, value in enumerate(runs)
    ]


def _integral_metric_runs(metrics: Mapping[str, Any], name: str, iterations: int) -> list[int]:
    values = _metric_runs(metrics, name, iterations)
    if any(value < 0 or not value.is_integer() for value in values):
        raise CollectorError(f"macrobenchmark metric {name} must contain nonnegative integers")
    return [int(value) for value in values]


def _sampled_metric(
    sampled_metrics: Mapping[str, Any], name: str, iterations: int
) -> tuple[dict[str, float], list[list[float]]]:
    metric = _mapping_field(sampled_metrics, name, "macrobenchmark.sampledMetrics")
    percentile_keys = ("P50", "P90", "P95", "P99")
    percentiles = {
        key: _finite_number(metric.get(key), f"macrobenchmark.sampledMetrics.{name}.{key}")
        for key in percentile_keys
    }
    ordered = [percentiles[key] for key in percentile_keys]
    if ordered != sorted(ordered):
        raise CollectorError(f"macrobenchmark sampled metric {name} percentiles are not monotonic")
    raw_runs = metric.get("runs")
    if not isinstance(raw_runs, list) or len(raw_runs) != iterations:
        raise CollectorError(
            f"macrobenchmark.sampledMetrics.{name}.runs must contain {iterations} iteration arrays"
        )
    runs: list[list[float]] = []
    for iteration, raw_values in enumerate(raw_runs, start=1):
        if not isinstance(raw_values, list) or not raw_values:
            raise CollectorError(f"sampled metric {name} iteration {iteration} is empty")
        runs.append(
            [
                _finite_number(value, f"macrobenchmark.sampledMetrics.{name}.runs[{iteration - 1}]")
                for value in raw_values
            ]
        )
    pooled = sorted(value for iteration_values in runs for value in iteration_values)
    if name == "frameDurationCpuMs" and any(value < 0 for value in pooled):
        raise CollectorError(
            "macrobenchmark.sampledMetrics.frameDurationCpuMs cannot contain negative samples"
        )
    for key, percentile in (("P50", 50), ("P90", 90), ("P95", 95), ("P99", 99)):
        expected = _linear_interpolated_percentile(pooled, percentile)
        if not math.isclose(
            percentiles[key], expected, rel_tol=1e-9, abs_tol=1e-9
        ):
            raise CollectorError(
                f"macrobenchmark.sampledMetrics.{name}.{key} does not reproduce "
                "the pooled AndroidX runs"
            )
    return percentiles, runs


def _linear_interpolated_percentile(values: Sequence[float], percentile: int) -> float:
    if not values:
        raise CollectorError("cannot calculate a percentile from an empty sample")
    ideal_index = percentile / 100.0 * (len(values) - 1)
    lower_index = math.floor(ideal_index)
    upper_index = math.ceil(ideal_index)
    lower = values[lower_index]
    upper = values[upper_index]
    return lower + (upper - lower) * (ideal_index - lower_index)


def _evidence_token(config: CollectorConfig) -> int:
    canonical = (
        "hermes-macrobenchmark-evidence-v2\n"
        f"{config.release_source_digest}\n"
        f"{config.benchmark_target_apk_sha256}\n"
        f"{config.benchmark_test_apk_sha256}\n"
        f"{config.evidence_run_id}\n"
        f"{config.profile}\n"
        f"{config.expected_avd_name}\n"
        f"{config.expected_boot_id}\n"
    )
    return int(hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:13], 16)


def _parse_macrobenchmark_report(
    report: Mapping[str, Any], config: CollectorConfig
) -> tuple[dict[str, Any], int, tuple[str, ...]]:
    context = _mapping_field(report, "context", "macrobenchmark")
    # AndroidX BenchmarkData 1.4.1 reports the instrumentation targetContext
    # package here.  Hermes uses a self-instrumenting benchmark APK, so this
    # field is not the compilation state of the measured application.
    if context.get("compilationMode") != REPORTING_PACKAGE_COMPILATION_MODE:
        raise CollectorError(
            "AndroidX report context compilationMode must equal run-from-apk for the "
            "self-instrumenting reporting package"
        )
    benchmarks = report.get("benchmarks")
    if not isinstance(benchmarks, list) or len(benchmarks) != 1 or not isinstance(benchmarks[0], Mapping):
        raise CollectorError("AndroidX report must contain exactly one benchmark result")
    benchmark = benchmarks[0]
    if benchmark.get("name") != BENCHMARK_METHOD or benchmark.get("className") != BENCHMARK_CLASS:
        raise CollectorError("AndroidX report benchmark class/method does not match the Hermes scenario")
    if benchmark.get("params") != {}:
        raise CollectorError("Hermes settings benchmark must not have parameterized variants")
    iterations_raw = benchmark.get("repeatIterations")
    if (
        isinstance(iterations_raw, bool)
        or not isinstance(iterations_raw, int)
        or not MIN_BENCHMARK_ITERATIONS <= iterations_raw <= MAX_BENCHMARK_ITERATIONS
    ):
        raise CollectorError("AndroidX repeatIterations must be between 5 and 20")
    iterations = iterations_raw
    if benchmark.get("thermalThrottleSleepSeconds") != 0:
        raise CollectorError("AndroidX report indicates thermal throttling")
    metrics = _mapping_field(benchmark, "metrics", "macrobenchmark.benchmarks[0]")
    sampled = _mapping_field(benchmark, "sampledMetrics", "macrobenchmark.benchmarks[0]")

    frame_counts = _integral_metric_runs(metrics, "frameCount", iterations)
    total_frames = _integral_metric_runs(metrics, "hermesFrameTotalCount", iterations)
    self_jank_tagged_frames = _integral_metric_runs(
        metrics, "hermesFrameSelfJankTaggedCount", iterations
    )
    deadline_frames = _integral_metric_runs(
        metrics, "hermesFrameAppDeadlineMissedCount", iterations
    )
    non_deadline_self_jank_tagged_frames = _integral_metric_runs(
        metrics, "hermesFrameNonDeadlineSelfJankTaggedCount", iterations
    )
    other_jank_tagged_frames = _integral_metric_runs(
        metrics, "hermesFrameOtherJankTaggedCount", iterations
    )
    dropped_frames = _integral_metric_runs(metrics, "hermesFrameDroppedCount", iterations)
    unknown_tag_frames = _integral_metric_runs(
        metrics, "hermesFrameUnknownTagCount", iterations
    )
    overlapping_jank_tag_frames = _integral_metric_runs(
        metrics, "hermesFrameOverlappingJankTagCount", iterations
    )
    self_jank_tagged_percentages = _metric_runs(
        metrics, "hermesFrameSelfJankTaggedPercent", iterations
    )
    evidence_tokens = _integral_metric_runs(metrics, "hermesEvidenceToken", iterations)
    expected_evidence_token = _evidence_token(config)
    if evidence_tokens != [expected_evidence_token] * iterations:
        raise CollectorError(
            "Macrobenchmark hermesEvidenceToken does not bind the exact source/APKs/run/profile/boot"
        )
    duration_percentiles, duration_runs = _sampled_metric(
        sampled, "frameDurationCpuMs", iterations
    )
    overrun_percentiles, overrun_runs = _sampled_metric(sampled, "frameOverrunMs", iterations)

    normalized_iterations: list[dict[str, Any]] = []
    for index in range(iterations):
        frame_count = frame_counts[index]
        total = total_frames[index]
        self_jank_tagged = self_jank_tagged_frames[index]
        deadline = deadline_frames[index]
        non_deadline_self_jank_tagged = non_deadline_self_jank_tagged_frames[index]
        other_jank_tagged = other_jank_tagged_frames[index]
        dropped = dropped_frames[index]
        unknown_tag = unknown_tag_frames[index]
        overlapping_jank_tag = overlapping_jank_tag_frames[index]
        self_jank_tagged_percent = self_jank_tagged_percentages[index]
        if frame_count <= 0 or total <= 0:
            raise CollectorError(f"Macrobenchmark iteration {index + 1} contains no rendered frames")
        if len(duration_runs[index]) != frame_count or len(overrun_runs[index]) != frame_count:
            raise CollectorError(
                f"FrameTimingMetric sampled run length disagrees with frameCount in iteration {index + 1}"
            )
        if (
            deadline + non_deadline_self_jank_tagged != self_jank_tagged
            or self_jank_tagged + other_jank_tagged > total
        ):
            raise CollectorError(f"Perfetto jank counts do not reconcile in iteration {index + 1}")
        if dropped > total or unknown_tag > total or overlapping_jank_tag > total:
            raise CollectorError(
                f"Perfetto dropped/unknown/overlap counts exceed surface tokens in iteration {index + 1}"
            )
        if dropped != 0 or unknown_tag != 0 or overlapping_jank_tag != 0:
            raise CollectorError(
                f"Perfetto iteration {index + 1} contains dropped, unknown-tag, or overlapping-tag frames"
            )
        expected_self_tagged_percent = self_jank_tagged * 100.0 / total
        app_deadline_missed_percent = deadline * 100.0 / total
        if (
            not 0 <= self_jank_tagged_percent <= 100
            or abs(self_jank_tagged_percent - expected_self_tagged_percent) > 1e-6
        ):
            raise CollectorError(
                f"Perfetto Self Jank-tagged percentage is inconsistent in iteration {index + 1}"
            )
        positive_overruns = sum(value > 0.0 for value in overrun_runs[index])
        positive_overrun_percent = positive_overruns * 100.0 / frame_count
        normalized_iterations.append(
            {
                "iteration": index + 1,
                "frame_timing_frame_count": frame_count,
                "frame_timing_overrun_positive_frames": positive_overruns,
                "frame_timing_overrun_positive_percent": positive_overrun_percent,
                "perfetto_surface_frame_timeline_tokens": total,
                "perfetto_self_jank_tagged_frames": self_jank_tagged,
                "perfetto_app_deadline_missed_frames": deadline,
                "perfetto_app_deadline_missed_percent": app_deadline_missed_percent,
                "perfetto_non_deadline_self_jank_tagged_frames": (
                    non_deadline_self_jank_tagged
                ),
                "perfetto_other_jank_tagged_frames": other_jank_tagged,
                "perfetto_dropped_frames": dropped,
                "perfetto_unknown_tag_frames": unknown_tag,
                "perfetto_overlapping_jank_tag_frames": overlapping_jank_tag,
                "perfetto_self_jank_tagged_percent": self_jank_tagged_percent,
            }
        )

    frame_timing_total = sum(frame_counts)
    total = sum(total_frames)
    self_jank_tagged = sum(self_jank_tagged_frames)
    deadline = sum(deadline_frames)
    non_deadline_self_jank_tagged = sum(non_deadline_self_jank_tagged_frames)
    other_jank_tagged = sum(other_jank_tagged_frames)
    dropped = sum(dropped_frames)
    unknown_tag = sum(unknown_tag_frames)
    overlapping_jank_tag = sum(overlapping_jank_tag_frames)
    if frame_timing_total < MIN_AGGREGATE_FRAMES or total < MIN_AGGREGATE_FRAMES:
        raise CollectorError("Macrobenchmark did not capture at least 100 aggregate frames")
    self_jank_tagged_percent = self_jank_tagged * 100.0 / total
    app_deadline_missed_percent = deadline * 100.0 / total
    if app_deadline_missed_percent > MAX_APP_DEADLINE_MISSED_PERCENT:
        raise CollectorError(
            "Macrobenchmark aggregate App Deadline Missed surface tokens exceed the 10% "
            "controlled-AVD budget"
        )
    if deadline + non_deadline_self_jank_tagged != self_jank_tagged:
        raise CollectorError("Macrobenchmark aggregate jank categories do not reconcile")
    if self_jank_tagged + other_jank_tagged > total:
        raise CollectorError("Macrobenchmark aggregate Self/Other Jank tags exceed surface tokens")
    if dropped != 0 or unknown_tag != 0 or overlapping_jank_tag != 0:
        raise CollectorError(
            "Macrobenchmark contains dropped, unknown-tag, or overlapping-tag Perfetto frames"
        )
    overrun_positive = sum(
        value > 0.0 for iteration_values in overrun_runs for value in iteration_values
    )
    overrun_positive_percent = overrun_positive * 100.0 / frame_timing_total
    if (
        duration_percentiles["P95"] > MAX_FRAME_DURATION_CPU_P95_MS
        or duration_percentiles["P99"] > MAX_FRAME_DURATION_CPU_P99_MS
    ):
        raise CollectorError(
            "Macrobenchmark frameDurationCpuMs exceeds the controlled-AVD CPU-work ceilings"
        )

    profiler_outputs = benchmark.get("profilerOutputs")
    if not isinstance(profiler_outputs, list) or len(profiler_outputs) != iterations:
        raise CollectorError("AndroidX profilerOutputs must contain one trace per iteration")
    source_names: list[str] = []
    for index, output in enumerate(profiler_outputs, start=1):
        if not isinstance(output, Mapping) or set(output) != {"type", "label", "filename"}:
            raise CollectorError(f"AndroidX profiler output {index} has an invalid shape")
        if output.get("type") != "PerfettoTrace":
            raise CollectorError(f"AndroidX profiler output {index} is not a Perfetto trace")
        if output.get("label") != f"Trace Iteration {index - 1}":
            raise CollectorError(
                "AndroidX profiler output labels do not bind exact iteration order"
            )
        filename = output.get("filename")
        if not isinstance(filename, str) or not filename.strip():
            raise CollectorError(f"AndroidX profiler output {index} has no filename")
        source_name = Path(filename).name
        if not source_name.endswith(".perfetto-trace") or source_name in source_names:
            raise CollectorError("AndroidX Perfetto trace filenames must be unique .perfetto-trace files")
        source_names.append(source_name)

    frames = {
        "metric_source": "androidx.macrobenchmark.FrameTimingMetric+HermesFrameJankMetric",
        "iterations": normalized_iterations,
        "frame_timing_total_rendered": frame_timing_total,
        "frame_timing_overrun_positive": overrun_positive,
        "frame_timing_overrun_positive_percent": overrun_positive_percent,
        "perfetto_surface_frame_timeline_tokens": total,
        "perfetto_self_jank_tagged": self_jank_tagged,
        "perfetto_app_deadline_missed": deadline,
        "perfetto_app_deadline_missed_percent": app_deadline_missed_percent,
        "perfetto_non_deadline_self_jank_tagged": non_deadline_self_jank_tagged,
        "perfetto_other_jank_tagged": other_jank_tagged,
        "perfetto_dropped": dropped,
        "perfetto_unknown_tag": unknown_tag,
        "perfetto_overlapping_jank_tag": overlapping_jank_tag,
        "perfetto_self_jank_tagged_percent": self_jank_tagged_percent,
        "p50_ms": duration_percentiles["P50"],
        "p90_ms": duration_percentiles["P90"],
        "p95_ms": duration_percentiles["P95"],
        "p99_ms": duration_percentiles["P99"],
        "frame_overrun_ms": {
            "p50": overrun_percentiles["P50"],
            "p90": overrun_percentiles["P90"],
            "p95": overrun_percentiles["P95"],
            "p99": overrun_percentiles["P99"],
        },
    }
    return frames, iterations, tuple(source_names)


def _parse_macrobenchmark_invocation(
    payload: Any, config: CollectorConfig
) -> CommandResult:
    if not isinstance(payload, Mapping) or set(payload) != {
        "schema",
        "argv",
        "exit_code",
        "stdout",
        "stderr",
    }:
        raise CollectorError("macrobenchmark invocation capture has an invalid key set")
    if payload.get("schema") != INVOCATION_SCHEMA:
        raise CollectorError(f"macrobenchmark invocation schema must equal {INVOCATION_SCHEMA}")
    argv = payload.get("argv")
    if not isinstance(argv, list) or not argv or any(not isinstance(part, str) or not part for part in argv):
        raise CollectorError("macrobenchmark invocation argv must be a nonempty string array")
    executable = Path(argv[0]).name.casefold()
    if executable not in {"gradlew", "gradlew.bat"}:
        raise CollectorError("macrobenchmark invocation must use the repository Gradle wrapper")
    expected_args = [
        ":macrobenchmark:connectedBenchmarkAndroidTest",
        f"-PhermesBenchmarkExpectedSourceDigest={config.release_source_digest}",
        f"-PhermesBenchmarkExpectedVersionName={config.version_name}",
        f"-PhermesBenchmarkExpectedVersionCode={config.version_code}",
        f"-PhermesBenchmarkExpectedLiteRtLmCoordinate={config.litertlm_coordinate}",
        f"-PhermesBenchmarkTargetApkSha256={config.benchmark_target_apk_sha256}",
        f"-PhermesBenchmarkApkSha256={config.benchmark_test_apk_sha256}",
        f"-PhermesBenchmarkEvidenceRunId={config.evidence_run_id}",
        f"-PhermesBenchmarkEvidenceProfile={config.profile}",
        f"-PhermesBenchmarkExpectedAvdName={config.expected_avd_name}",
        f"-PhermesBenchmarkExpectedBootId={config.expected_boot_id}",
        f"-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_TEST_ID}",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.sourceDigest={config.release_source_digest}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.targetApkSha256={config.benchmark_target_apk_sha256}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.benchmarkApkSha256={config.benchmark_test_apk_sha256}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.evidenceRunId={config.evidence_run_id}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.evidenceProfile={config.profile}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.avdName={config.expected_avd_name}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.bootId={config.expected_boot_id}",
        "--no-daemon",
        "--console=plain",
    ]
    if argv[1:] != expected_args:
        raise CollectorError("macrobenchmark invocation argv does not match the exact release command")
    exit_code = payload.get("exit_code")
    stdout = payload.get("stdout")
    stderr = payload.get("stderr")
    if isinstance(exit_code, bool) or not isinstance(exit_code, int):
        raise CollectorError("macrobenchmark invocation exit_code must be an integer")
    if not isinstance(stdout, str) or not isinstance(stderr, str):
        raise CollectorError("macrobenchmark invocation stdout/stderr must be strings")
    combined = f"{stdout}\n{stderr}"
    if exit_code != 0 or "BUILD SUCCESSFUL" not in combined:
        raise CollectorError("macrobenchmark invocation did not prove a successful Gradle run")
    forbidden = ("BUILD FAILED", "FAILURE:", "INSTRUMENTATION_FAILED")
    if any(marker in combined for marker in forbidden):
        raise CollectorError("macrobenchmark invocation output contains a failure marker")
    return CommandResult(tuple(argv), exit_code, stdout, stderr)


def _display_command(args: Sequence[str]) -> str:
    return subprocess.list2cmdline(tuple(args))


def _checked_run(
    executor: CommandExecutor,
    args: Sequence[str],
    *,
    timeout_seconds: int,
    context: str,
) -> CommandResult:
    result = executor.run(args, timeout_seconds=timeout_seconds)
    if result.returncode != 0:
        detail = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        raise CollectorError(
            f"Failed to {context}: exit {result.returncode}: {_display_command(args)}"
            + (f"\n{detail}" if detail else "")
        )
    return result


def _strip_token(token: str) -> str:
    return token.strip().strip('"\'')


def _portable_executable_name(value: str) -> str:
    parts = [part for part in re.split(r"[\\/]", value.strip()) if part]
    return parts[-1] if parts else value.strip()


def _tokenize_windows_command(command_line: str) -> tuple[str, ...]:
    try:
        tokens = tuple(shlex.split(command_line, posix=False))
    except ValueError as exc:
        raise CollectorError(f"live QEMU command line cannot be tokenized: {exc}") from exc
    if not tokens:
        raise CollectorError("live QEMU command line is blank after tokenization")
    return tokens


def _public_qemu_command(
    process: ProcessInfo, avd_name: str, console_port: int
) -> str:
    process_name = process.name.casefold()
    if not re.fullmatch(r"qemu-system-[a-z0-9_.-]+", process_name):
        raise CollectorError("live QEMU process name is not public-safe")
    tokens = _tokenize_windows_command(process.command_line)
    if _flag_values(tokens, "-avd") != [avd_name]:
        raise CollectorError("live QEMU command does not contain the exact AVD identity")
    port_values = _flag_values(tokens, "-port")
    ports_values = _flag_values(tokens, "-ports")
    if port_values == [str(console_port)] and not ports_values:
        port_flag = "-port"
        port_value = str(console_port)
    elif ports_values == [f"{console_port},{console_port + 1}"] and not port_values:
        port_flag = "-ports"
        port_value = f"{console_port},{console_port + 1}"
    else:
        raise CollectorError("live QEMU command does not contain the exact console port identity")
    if _flag_values(tokens, "-gpu", casefold=True) != ["host"]:
        raise CollectorError("live QEMU command must contain exactly one effective -gpu host")
    if _flag_values(tokens, "-accel", casefold=True) != ["on"]:
        raise CollectorError("live QEMU command must contain exactly one effective -accel on")
    if any(_strip_token(token).casefold() == "-no-window" for token in tokens):
        raise CollectorError("live QEMU command uses -no-window and is not headed")
    return (
        f"{process_name} -avd {avd_name} {port_flag} {port_value} "
        "-gpu host -accel on"
    )


def _flag_values(tokens: Sequence[str], flag: str, *, casefold: bool = False) -> list[str]:
    values: list[str] = []
    for index, token in enumerate(tokens):
        if _strip_token(token).casefold() != flag.casefold():
            continue
        if index + 1 >= len(tokens):
            raise CollectorError(f"live QEMU command has incomplete {flag}")
        value = _strip_token(tokens[index + 1])
        values.append(value.casefold() if casefold else value)
    return values


def _parse_wm_size(output: str) -> tuple[int, int]:
    override = [
        (int(width), int(height))
        for width, height in re.findall(
            r"(?mi)^\s*Override size:\s*([0-9]+)x([0-9]+)\s*$", output
        )
    ]
    physical = [
        (int(width), int(height))
        for width, height in re.findall(
            r"(?mi)^\s*Physical size:\s*([0-9]+)x([0-9]+)\s*$", output
        )
    ]
    if len(physical) != 1 or len(override) > 1:
        raise CollectorError(
            f"wm size did not expose one physical and at most one override size: {physical!r}/{override!r}"
        )
    width, height = (override or physical)[0]
    if width <= 0 or height <= 0:
        raise CollectorError("wm size returned non-positive dimensions")
    return width, height


def _parse_wm_density(output: str) -> int:
    override = [
        int(value)
        for value in re.findall(r"(?mi)^\s*Override density:\s*([0-9]+)\s*$", output)
    ]
    physical = [
        int(value)
        for value in re.findall(r"(?mi)^\s*Physical density:\s*([0-9]+)\s*$", output)
    ]
    if len(physical) != 1 or len(override) > 1:
        raise CollectorError(
            "wm density did not expose one physical and at most one override density: "
            f"{physical!r}/{override!r}"
        )
    density = (override or physical)[0]
    if density <= 0:
        raise CollectorError("wm density returned a non-positive value")
    return density


def _parse_pidof(output: str) -> int:
    value = output.strip()
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise CollectorError(f"pidof did not expose one positive Hermes process PID: {value!r}")
    return int(value)


def _parse_target_compiler_filter(output: str, base_apk_path: str) -> str:
    """Parse the API 35 package-manager Dexopt status for one exact base APK.

    AndroidX Benchmark 1.4.1 reads the first ``[status=...]`` value following
    ``Dexopt state:`` on API 28 and newer.  Release evidence narrows that
    grammar to the installed target's exact base APK and rejects ambiguity.
    """
    if not base_apk_path.startswith("/") or any(character.isspace() for character in base_apk_path):
        raise CollectorError("target base APK path is invalid for Dexopt state parsing")
    lines = output.splitlines()
    dexopt_headers = [
        index
        for index, line in enumerate(lines)
        if re.fullmatch(r"[ \t]*Dexopt state:[ \t]*", line)
    ]
    if len(dexopt_headers) != 1:
        raise CollectorError("cmd package dump must expose exactly one Dexopt state section")

    path_matches: list[tuple[int, int]] = []
    for index in range(dexopt_headers[0] + 1, len(lines)):
        match = re.fullmatch(r"(?P<indent>[ \t]+)path:[ \t]*(?P<path>\S+)[ \t]*", lines[index])
        if match and match.group("path") == base_apk_path:
            path_matches.append((index, len(match.group("indent").expandtabs(8))))
    if len(path_matches) != 1:
        raise CollectorError(
            "cmd package dump must expose exactly one Dexopt state path for the target base APK"
        )

    path_index, path_indent = path_matches[0]
    relevant_lines: list[str] = []
    for line in lines[path_index + 1 :]:
        if not line.strip():
            continue
        indentation = len(line) - len(line.lstrip(" \t"))
        if "\t" in line[:indentation]:
            indentation = len(line[:indentation].expandtabs(8))
        if indentation <= path_indent:
            break
        relevant_lines.append(line)
    statuses = [
        "".join(match.split())
        for match in re.findall(r"\[status=([^]]+?)]", "\n".join(relevant_lines), re.DOTALL)
    ]
    if statuses != [TARGET_COMPILER_FILTER]:
        raise CollectorError(
            "target base APK Dexopt state must expose exactly one status=speed compiler filter; "
            f"observed {statuses!r}"
        )
    return statuses[0]


def _require_process_header(output: str, label: str, expected_pid: int, context: str) -> None:
    observed = [
        (int(pid), package.strip())
        for pid, package in re.findall(
            rf"(?mi)^\s*\*\*\s*{re.escape(label)}\s+([1-9][0-9]*)\s+\[([^\]\r\n]+)\]\s*\*\*\s*$",
            output,
        )
    ]
    expected = [(expected_pid, PACKAGE_ID)]
    if observed != expected:
        raise CollectorError(
            f"{context} process header {observed!r} does not match the measured Hermes PID {expected!r}"
        )


def _require_resumed_activity(output: str, context: str) -> None:
    activity_claims = re.findall(
        r"(?mi)^\s*(?:(?:topResumedActivity|mResumedActivity)\s*=|ResumedActivity\s*:)\s*",
        output,
    )
    activities = re.findall(
        r"(?mi)^\s*(?:(?:topResumedActivity|mResumedActivity)\s*=|ResumedActivity\s*:)\s*"
        r"ActivityRecord\{[^\r\n]*?\s([A-Za-z0-9._]+/[A-Za-z0-9._$]+)(?:\s|\})[^\r\n]*$",
        output,
    )
    if not activity_claims or any(activity != MAIN_ACTIVITY for activity in activities) or len(
        activities
    ) != len(activity_claims):
        raise CollectorError(
            f"{context} does not prove only resumed Hermes MainActivity claims: {activities!r}"
        )


def _is_retryable_unknown_start(output: str) -> bool:
    statuses = [
        value.strip().casefold()
        for value in re.findall(r"(?mi)^\s*Status:\s*([^\r\n]*)$", output)
    ]
    states = [
        value.strip().upper()
        for value in re.findall(r"(?mi)^\s*LaunchState:\s*([^\r\n]*)$", output)
    ]
    activities = [
        value.strip()
        for value in re.findall(r"(?mi)^\s*Activity:\s*([^\r\n]*)$", output)
    ]
    total = [int(value) for value in re.findall(r"(?mi)^\s*TotalTime:\s*([0-9]+)\s*$", output)]
    wait = [int(value) for value in re.findall(r"(?mi)^\s*WaitTime:\s*([0-9]+)\s*$", output)]
    return (
        statuses == ["ok"]
        and states in ([], ["UNKNOWN"], ["UNKNOWN (0)"])
        and activities == [MAIN_ACTIVITY]
        and total == [0]
        and len(wait) == 1
        and 0 <= wait[0] <= 1_000
    )


def _parse_start_metrics(output: str, *, expected_states: set[str]) -> dict[str, int]:
    statuses = [
        value.strip().casefold()
        for value in re.findall(r"(?mi)^\s*Status:\s*([^\r\n]*)$", output)
    ]
    if statuses != ["ok"]:
        raise CollectorError(f"am start -W did not report exactly one Status: ok: {statuses!r}")
    launch_states = [
        value.strip().upper()
        for value in re.findall(r"(?mi)^\s*LaunchState:\s*([^\r\n]*)$", output)
    ]
    if len(launch_states) != 1 or launch_states[0] not in expected_states:
        raise CollectorError(
            f"am start launch state {launch_states!r} does not match {sorted(expected_states)!r}"
        )
    activities = [
        value.strip()
        for value in re.findall(r"(?mi)^\s*Activity:\s*([^\r\n]*)$", output)
    ]
    if activities != [MAIN_ACTIVITY]:
        raise CollectorError(f"am start did not report exactly the intended Activity: {activities!r}")
    totals = [int(value) for value in re.findall(r"(?mi)^\s*TotalTime:\s*([0-9]+)\s*$", output)]
    waits = [int(value) for value in re.findall(r"(?mi)^\s*WaitTime:\s*([0-9]+)\s*$", output)]
    if len(totals) != 1 or len(waits) != 1:
        raise CollectorError("am start did not expose exactly one TotalTime and WaitTime")
    total, wait = totals[0], waits[0]
    if total <= 0 or wait <= 0:
        raise CollectorError("am start timings must be positive")
    if wait > total + 1_000:
        raise CollectorError("am start WaitTime is inconsistent with TotalTime")
    return {"TotalTime": total, "WaitTime": wait}


def _encode_json(payload: Mapping[str, Any]) -> bytes:
    return (json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def write_atomic_validated_evidence(
    output: Path,
    payload: Mapping[str, Any],
    raw_transcript: Mapping[str, Any],
    config: CollectorConfig,
    validator: PayloadValidator,
    source_verifier: SourceVerifier,
    *,
    overwrite: bool,
) -> None:
    destination = output.resolve()
    if destination.parent.name != "performance" or destination.name != f"{config.profile}.json":
        raise CollectorError(
            "performance output must be the profile layout "
            f"performance/{config.profile}.json"
        )
    host_raw_destination = destination.with_name(f"{config.profile}.host.raw.json")
    macro_raw_destination = destination.with_name(f"{config.profile}.macrobenchmark.raw.json")
    trace_directory = destination.with_name(f"{config.profile}.traces")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if trace_directory.exists() and (not trace_directory.is_dir() or trace_directory.is_symlink()):
        raise CollectorError("performance trace destination must be a regular non-symlink directory")
    existing_trace_files: list[Path] = []
    if trace_directory.is_dir():
        for path in trace_directory.iterdir():
            if not path.is_file() or path.is_symlink():
                raise CollectorError("performance trace directory may contain regular files only")
            existing_trace_files.append(path)

    encoded = _encode_json(payload)
    host_raw_encoded = _encode_json(raw_transcript)
    traces = payload.get("traces")
    if not isinstance(traces, list) or len(traces) != len(config.macrobenchmark_traces):
        raise CollectorError("normalized trace references do not match input trace count")
    trace_paths_by_name = {trace.name: trace for trace in config.macrobenchmark_traces}
    try:
        ordered_trace_paths = [trace_paths_by_name[trace["source_name"]] for trace in traces]
    except (KeyError, TypeError) as exc:
        raise CollectorError("normalized trace source names do not match trace inputs") from exc
    try:
        macro_raw_encoded = config.macrobenchmark_report.read_bytes()
        trace_inputs = [trace.read_bytes() for trace in ordered_trace_paths]
    except OSError as exc:
        raise CollectorError(f"unable to reread Macrobenchmark artifacts for commit: {exc}") from exc
    trace_destinations = [
        trace_directory / f"iteration-{index:03d}.perfetto-trace"
        for index in range(1, len(trace_inputs) + 1)
    ]
    final_artifacts = [destination, host_raw_destination, macro_raw_destination, *trace_destinations]
    invalid_destinations = [path for path in final_artifacts[:3] if path.exists() and not path.is_file()]
    if invalid_destinations:
        raise CollectorError(
            "performance evidence destinations must be regular files: "
            + ", ".join(str(path) for path in invalid_destinations)
        )
    existing = [path for path in final_artifacts if path.exists()]
    existing.extend(path for path in existing_trace_files if path not in existing)
    if existing and not overwrite:
        raise CollectorError(
            "refusing to overwrite existing performance evidence: "
            + ", ".join(str(path) for path in existing)
        )
    raw_reference = payload.get("raw_evidence")
    expected_reference = {
        "host": {
            "path": f"performance/{config.profile}.host.raw.json",
            "bytes": len(host_raw_encoded),
            "sha256": hashlib.sha256(host_raw_encoded).hexdigest(),
        },
        "macrobenchmark": {
            "path": f"performance/{config.profile}.macrobenchmark.raw.json",
            "bytes": len(macro_raw_encoded),
            "sha256": hashlib.sha256(macro_raw_encoded).hexdigest(),
        },
    }
    if raw_reference != expected_reference:
        raise CollectorError("normalized raw_evidence references do not match raw artifact bytes")
    for index, (trace_reference, trace_bytes) in enumerate(
        zip(traces, trace_inputs, strict=True), start=1
    ):
        expected_trace = {
            "iteration": index,
            "path": (
                f"performance/{config.profile}.traces/"
                f"iteration-{index:03d}.perfetto-trace"
            ),
            "source_name": ordered_trace_paths[index - 1].name,
            "bytes": len(trace_bytes),
            "sha256": hashlib.sha256(trace_bytes).hexdigest(),
        }
        if trace_reference != expected_trace:
            raise CollectorError(f"normalized trace reference {index} does not match trace bytes")

    artifact_bytes = [encoded, host_raw_encoded, macro_raw_encoded, *trace_inputs]
    prior_contents = {path: path.read_bytes() for path in existing if path.is_file()}
    trace_directory_existed = trace_directory.exists()
    temporary_paths: list[Path] = []
    transaction_started = False
    try:
        for final_path, contents in zip(final_artifacts, artifact_bytes, strict=True):
            with tempfile.NamedTemporaryFile(
                mode="wb",
                prefix=f".{final_path.name}.",
                suffix=".tmp",
                dir=destination.parent,
                delete=False,
            ) as handle:
                temporary_paths.append(Path(handle.name))
                handle.write(contents)
                handle.flush()
                os.fsync(handle.fileno())
        validator.validate(
            temporary_paths[0],
            temporary_paths[1],
            temporary_paths[2],
            temporary_paths[3:],
            config,
        )
        source_verifier.verify(config.release_source_digest)
        appeared = [
            path for path in final_artifacts if path.exists() and path not in existing
        ]
        if appeared and not overwrite:
            raise CollectorError(
                "performance evidence appeared during collection: "
                + ", ".join(str(path) for path in appeared)
            )
        trace_directory.mkdir(parents=False, exist_ok=True)
        # Commit all raw diagnostic artifacts first and the normalized claim last.
        commit_order = [1, 2, *range(3, len(final_artifacts)), 0]
        for index in commit_order:
            os.replace(temporary_paths[index], final_artifacts[index])
            temporary_paths[index] = Path()
            transaction_started = True
            source_verifier.verify(config.release_source_digest)
        for stale in existing_trace_files:
            if stale not in trace_destinations:
                stale.unlink()
        source_verifier.verify(config.release_source_digest)
    except Exception:
        if transaction_started:
            touched = set(final_artifacts) | set(existing_trace_files)
            for path in touched:
                if path in prior_contents:
                    _atomic_replace_bytes(path, prior_contents[path])
                else:
                    try:
                        path.unlink()
                    except FileNotFoundError:
                        pass
            if not trace_directory_existed:
                try:
                    trace_directory.rmdir()
                except OSError:
                    pass
        raise
    finally:
        for unfinished in temporary_paths:
            if unfinished == Path():
                continue
            try:
                unfinished.unlink()
            except FileNotFoundError:
                pass


def _atomic_replace_bytes(path: Path, contents: bytes) -> None:
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{path.name}.", suffix=".restore.tmp", dir=path.parent, delete=False
        ) as handle:
            temporary = Path(handle.name)
            handle.write(contents)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def _load_release_evidence_module() -> Any:
    scripts_dir = Path(__file__).resolve().parent
    module_path = scripts_dir / "android_release_evidence.py"
    module_name = "_hermes_android_release_evidence_for_collector"
    loaded = sys.modules.get(module_name)
    if loaded is not None:
        loaded_path = Path(getattr(loaded, "__file__", "")).resolve()
        if loaded_path != module_path:
            raise CollectorError(f"Unexpected cached release validator module: {loaded_path}")
        return loaded
    try:
        spec = importlib.util.spec_from_file_location(module_name, module_path)
        if spec is None or spec.loader is None:
            raise CollectorError(f"Unable to create an import spec for {module_path}")
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
    except Exception as exc:
        sys.modules.pop(module_name, None)
        raise CollectorError(f"Unable to load sibling release evidence validator: {exc}") from exc
    return module


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--profile", required=True, choices=PROFILES)
    parser.add_argument("--expected-avd-name", required=True)
    parser.add_argument("--expected-boot-id", required=True)
    parser.add_argument("--release-source-digest", required=True)
    parser.add_argument("--benchmark-target-apk-sha256", required=True)
    parser.add_argument("--benchmark-test-apk-sha256", required=True)
    parser.add_argument("--evidence-run-id", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--litertlm-coordinate", required=True)
    parser.add_argument("--macrobenchmark-report", required=True, type=Path)
    parser.add_argument(
        "--macrobenchmark-trace",
        required=True,
        action="append",
        type=Path,
        dest="macrobenchmark_traces",
    )
    parser.add_argument("--macrobenchmark-invocation", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--emulator", default="emulator")
    parser.add_argument("--powershell", default="powershell.exe")
    parser.add_argument("--overwrite", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    repo_root = args.repo_root.resolve()

    def input_path(path: Path) -> Path:
        return path.resolve() if path.is_absolute() else (repo_root / path).resolve()

    config = CollectorConfig(
        serial=args.serial,
        profile=args.profile,
        expected_avd_name=args.expected_avd_name,
        expected_boot_id=args.expected_boot_id.lower(),
        release_source_digest=args.release_source_digest,
        benchmark_target_apk_sha256=args.benchmark_target_apk_sha256,
        benchmark_test_apk_sha256=args.benchmark_test_apk_sha256,
        evidence_run_id=args.evidence_run_id,
        version_name=args.version_name,
        version_code=args.version_code,
        litertlm_coordinate=args.litertlm_coordinate,
        macrobenchmark_report=input_path(args.macrobenchmark_report),
        macrobenchmark_traces=tuple(input_path(path) for path in args.macrobenchmark_traces),
        macrobenchmark_invocation=input_path(args.macrobenchmark_invocation),
        adb=args.adb,
        emulator=args.emulator,
    )
    try:
        executor = SubprocessExecutor()
        source_verifier = GitSourceVerifier(repo_root)
        collector = PerformanceCollector(
            config,
            executor,
            WindowsCimProcessSource(executor, args.powershell),
            source_verifier,
        )
        payload = collector.collect()
        output = args.output if args.output.is_absolute() else repo_root / args.output
        write_atomic_validated_evidence(
            output,
            payload,
            collector.raw_transcript,
            config,
            ReleaseEvidencePayloadValidator(),
            source_verifier,
            overwrite=args.overwrite,
        )
    except CollectorError as exc:
        print(f"Android performance evidence collection failed: {exc}", file=sys.stderr)
        return 1
    print(f"Wrote validated Android performance evidence: {output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
