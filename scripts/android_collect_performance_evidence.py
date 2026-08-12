#!/usr/bin/env python3
"""Collect fail-closed Android performance evidence from one live headed AVD.

The release evidence validator deliberately does not operate devices.  This
producer supplies that missing live boundary: it observes the selected adb
guest and its Windows QEMU host process, exercises the installed candidate,
and writes one validator-compatible JSON record only after all observations
remain stable through the end of the run.
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
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Protocol, Sequence


PERFORMANCE_SCHEMA = "hermes-android-performance-evidence-v1"
RAW_PERFORMANCE_SCHEMA = "hermes-android-performance-raw-v1"
PACKAGE_ID = "com.mobilefork.hermesagent"
TEST_PACKAGE_ID = f"{PACKAGE_ID}.test"
MAIN_ACTIVITY = f"{PACKAGE_ID}/.MainActivity"
UI_DUMP_PATH_PREFIX = "/data/local/tmp/hermes-performance-ui-"
PHONE_DRAWER_TAG = "HermesChatDrawerButton"
PHONE_SETTINGS_TAG = "HermesNavSettings"
TABLET_SETTINGS_TAG = "HermesRailSettings"
SETTINGS_CONTENT_TAG = "HermesSettingsContentList"
BUILD_VARIANT = "debug"
PROFILES = ("phone-compact", "tablet")
LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
HEX_64_RE = re.compile(r"^[0-9a-f]{64}$")
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{15,79}$")
BOOT_ID_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
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
    def validate(self, path: Path, raw_path: Path, config: "CollectorConfig") -> None:
        """Validate the fully serialized normalized and raw records."""


class ReleaseEvidencePayloadValidator:
    def validate(self, path: Path, raw_path: Path, config: "CollectorConfig") -> None:
        release_evidence = _load_release_evidence_module()
        try:
            release_evidence._validate_performance(
                path,
                config.profile,
                config.release_source_digest,
                config.version_name,
                config.version_code,
                raw_path_override=raw_path,
            )
        except Exception as exc:
            raise CollectorError(f"Collected JSON fails the release evidence schema: {exc}") from exc


@dataclass(frozen=True)
class CollectorConfig:
    serial: str
    profile: str
    release_source_digest: str
    candidate_apk_sha256: str
    instrumentation_apk_sha256: str
    evidence_run_id: str
    version_name: str
    version_code: int
    litertlm_coordinate: str
    adb: str = "adb"
    emulator: str = "emulator"
    max_exercise_rounds: int = 4
    swipes_per_round: int = 30
    swipe_duration_ms: int = 180

    def validate(self) -> int:
        serial_match = SERIAL_RE.fullmatch(self.serial)
        if not serial_match:
            raise CollectorError("serial must be an exact emulator-<console-port> identifier")
        console_port = int(serial_match.group(1))
        if console_port <= 0 or console_port >= 65_535 or console_port % 2:
            raise CollectorError("emulator console port must be a positive even port below 65535")
        if self.profile not in PROFILES:
            raise CollectorError(f"profile must be one of {', '.join(PROFILES)}")
        for name, digest in (
            ("release_source_digest", self.release_source_digest),
            ("candidate_apk_sha256", self.candidate_apk_sha256),
            ("instrumentation_apk_sha256", self.instrumentation_apk_sha256),
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
        if not 1 <= self.max_exercise_rounds <= 20:
            raise CollectorError("max_exercise_rounds must be between 1 and 20")
        if not 1 <= self.swipes_per_round <= 100:
            raise CollectorError("swipes_per_round must be between 1 and 100")
        if not 50 <= self.swipe_duration_ms <= 2_000:
            raise CollectorError("swipe_duration_ms must be between 50 and 2000")
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
    candidate_apk_path: str
    candidate_apk_sha256: str
    instrumentation_apk_path: str
    instrumentation_apk_sha256: str
    version_name: str
    version_code: int


@dataclass(frozen=True)
class QemuIdentity:
    pid: int
    name: str
    command_line: str


@dataclass(frozen=True)
class UiBounds:
    left: int
    top: int
    right: int
    bottom: int


@dataclass(frozen=True)
class UiTarget:
    resource_id: str
    bounds: UiBounds


@dataclass(frozen=True)
class SettingsNavigation:
    route: str
    bounds: UiBounds
    swipe_x: int
    swipe_top_y: int
    swipe_bottom_y: int


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
        self._verify_adb_target("initial")
        initial_device = self._observe_device_identity("initial")
        qemu = self._resolve_qemu("initial", initial_device.avd_name, console_port)
        acceleration = self._acceleration_check()
        width_px, height_px, density_dpi = self._screen_pixels()
        cold_total, cold_wait = self._cold_launch()
        warm_total, warm_process_pid = self._warm_launch()
        navigation = self._navigate_to_settings(width_px, height_px)
        width_dp, height_dp = self._configured_dp()
        self._check_profile_dimensions(width_dp, height_dp)
        gpu_renderer = self._gpu_renderer()
        self._foreground_activity("measure.activity.before_gfx")
        frames = self._exercise_and_measure_frames(navigation, warm_process_pid)
        self._foreground_activity("measure.activity.after_gfx")
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
        final_source_identity = self.source_verifier.verify(self.config.release_source_digest)
        if final_source_identity != source_identity:
            raise CollectorError("source identity changed during performance collection")

        command_sha = hashlib.sha256(qemu.command_line.encode("utf-8")).hexdigest()
        exercise_rounds = frames.pop("_exercise_rounds")
        raw_relative_path = f"performance/{self.config.profile}.raw.json"
        raw_transcript = {
            "schema": RAW_PERFORMANCE_SCHEMA,
            "profile": self.config.profile,
            "release_source_digest": self.config.release_source_digest,
            "candidate_apk_sha256": self.config.candidate_apk_sha256,
            "instrumentation_apk_sha256": self.config.instrumentation_apk_sha256,
            "evidence_run_id": self.config.evidence_run_id,
            "package_id": PACKAGE_ID,
            "version_name": self.config.version_name,
            "version_code": self.config.version_code,
            "build_variant": BUILD_VARIANT,
            "litertlm_coordinate": self.config.litertlm_coordinate,
            "records": self._records,
        }
        raw_sha256 = hashlib.sha256(_encode_json(raw_transcript)).hexdigest()
        payload = {
            "schema": PERFORMANCE_SCHEMA,
            "profile": self.config.profile,
            "release_source_digest": self.config.release_source_digest,
            "candidate_apk_sha256": self.config.candidate_apk_sha256,
            "instrumentation_apk_sha256": self.config.instrumentation_apk_sha256,
            "evidence_run_id": self.config.evidence_run_id,
            "package_id": PACKAGE_ID,
            "version_name": self.config.version_name,
            "version_code": self.config.version_code,
            "build_variant": BUILD_VARIANT,
            "litertlm_coordinate": self.config.litertlm_coordinate,
            "recorded_at_epoch_ms": int(time.time() * 1_000),
            "raw_evidence": {
                "path": raw_relative_path,
                "sha256": raw_sha256,
            },
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
                "emulator_pid": qemu.pid,
                "emulator_process_name": qemu.name,
                "emulator_command": qemu.command_line,
                "emulator_command_sha256": command_sha,
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
            "frames": frames,
            "memory": memory,
            "collector": {
                "source_digest_algorithm": source_identity.algorithm,
                "source_file_count": source_identity.file_count,
                "git_object_format": source_identity.git_object_format,
                "candidate_apk_device_path": initial_device.candidate_apk_path,
                "instrumentation_apk_device_path": initial_device.instrumentation_apk_path,
                "ui_navigation_route": navigation.route,
                "settings_scroll_bounds_px": [
                    navigation.bounds.left,
                    navigation.bounds.top,
                    navigation.bounds.right,
                    navigation.bounds.bottom,
                ],
                "gfx_swipe_coordinates": [
                    navigation.swipe_x,
                    navigation.swipe_bottom_y,
                    navigation.swipe_x,
                    navigation.swipe_top_y,
                ],
                "gfxinfo_exercise_rounds": exercise_rounds,
            },
        }
        self._raw_transcript = raw_transcript
        return payload

    def _record_result(self, record_id: str, result: CommandResult) -> None:
        if record_id in self._record_ids or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,99}", record_id):
            raise CollectorError(f"duplicate or invalid raw transcript record id: {record_id!r}")
        self._record_ids.add(record_id)
        self._records.append(
            {
                "id": record_id,
                "argv": list(result.args),
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
        matching_states: list[str] = []
        for line in inventory.stdout.splitlines():
            fields = line.strip().split()
            if fields and fields[0] == self.config.serial:
                if len(fields) < 2:
                    raise CollectorError(f"adb inventory has no state for {self.config.serial}")
                matching_states.append(fields[1])
        if matching_states != ["device"]:
            raise CollectorError(
                f"adb target {self.config.serial} must appear exactly once in device state; "
                f"observed {matching_states!r}"
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
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", avd_name):
            raise CollectorError(f"observed AVD name is invalid: {avd_name!r}")
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
        candidate_path, candidate_sha = self._installed_apk_identity(
            phase, "candidate", PACKAGE_ID, self.config.candidate_apk_sha256
        )
        test_path, test_sha = self._installed_apk_identity(
            phase, "instrumentation", TEST_PACKAGE_ID, self.config.instrumentation_apk_sha256
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
            candidate_apk_path=candidate_path,
            candidate_apk_sha256=candidate_sha,
            instrumentation_apk_path=test_path,
            instrumentation_apk_sha256=test_sha,
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

    def _resolve_qemu(self, phase: str, avd_name: str, console_port: int) -> QemuIdentity:
        snapshot = self.process_source.qemu_snapshot()
        self._record_result(f"{phase}.host.qemu_processes", snapshot.query)
        matches: list[QemuIdentity] = []
        for process in snapshot.processes:
            if not process.name.casefold().startswith("qemu-system-"):
                continue
            tokens = _tokenize_windows_command(process.command_line)
            avd_values = _flag_values(tokens, "-avd")
            port_values = _flag_values(tokens, "-port")
            ports_values = _flag_values(tokens, "-ports")
            exact_port = port_values == [str(console_port)] and not ports_values
            exact_ports = ports_values == [f"{console_port},{console_port + 1}"] and not port_values
            if avd_values == [avd_name] and (exact_port or exact_ports):
                matches.append(QemuIdentity(process.pid, process.name, process.command_line))
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

    def _ui_hierarchy(self, phase: str) -> str:
        if phase not in {"initial", "drawer", "settings"}:
            raise CollectorError(f"unsupported UI hierarchy phase: {phase!r}")
        dump_path = f"{UI_DUMP_PATH_PREFIX}{phase}.xml"
        self._adb(
            f"measure.ui.{phase}.remove",
            "shell",
            "rm",
            "-f",
            dump_path,
            timeout_seconds=30,
        )
        dump_result = self._adb(
            f"measure.ui.{phase}.dump",
            "shell",
            "uiautomator",
            "dump",
            dump_path,
            timeout_seconds=60,
        )
        expected_success = f"UI hierchary dumped to: {dump_path}"
        if dump_result.stdout.strip() != expected_success or dump_result.stderr.strip():
            raise CollectorError(
                f"uiautomator {phase} dump did not report exact success for fresh path: "
                f"stdout={dump_result.stdout!r}, stderr={dump_result.stderr!r}"
            )
        return self._adb_text(
            f"measure.ui.{phase}.cat",
            "shell",
            "cat",
            dump_path,
            timeout_seconds=30,
        )

    def _tap_target(self, record_id: str, target: UiTarget) -> None:
        x = (target.bounds.left + target.bounds.right) // 2
        y = (target.bounds.top + target.bounds.bottom) // 2
        if not (
            target.bounds.left < x < target.bounds.right
            and target.bounds.top < y < target.bounds.bottom
        ):
            raise CollectorError(f"{target.resource_id} has no safe interior tap coordinate")
        self._adb(record_id, "shell", "input", "tap", str(x), str(y))

    def _navigate_to_settings(self, width_px: int, height_px: int) -> SettingsNavigation:
        initial = self._ui_hierarchy("initial")
        if self.config.profile == "phone-compact":
            _reject_ui_resource(initial, TABLET_SETTINGS_TAG, "phone initial hierarchy")
            drawer = _ui_target(
                initial,
                PHONE_DRAWER_TAG,
                width_px,
                height_px,
                clickable=True,
            )
            self._tap_target("measure.ui.phone.drawer.tap", drawer)
            drawer_xml = self._ui_hierarchy("drawer")
            settings = _ui_target(
                drawer_xml,
                PHONE_SETTINGS_TAG,
                width_px,
                height_px,
                clickable=True,
            )
            self._tap_target("measure.ui.phone.settings.tap", settings)
            route = "phone-drawer-settings"
        else:
            _reject_ui_resource(initial, PHONE_DRAWER_TAG, "tablet initial hierarchy")
            settings = _ui_target(
                initial,
                TABLET_SETTINGS_TAG,
                width_px,
                height_px,
                clickable=True,
            )
            self._tap_target("measure.ui.tablet.settings.tap", settings)
            route = "tablet-rail-settings"

        settings_xml = self._ui_hierarchy("settings")
        content = _ui_target(
            settings_xml,
            SETTINGS_CONTENT_TAG,
            width_px,
            height_px,
            scrollable=True,
        )
        swipe_x, swipe_top_y, swipe_bottom_y = _safe_swipe_coordinates(content.bounds)
        return SettingsNavigation(
            route=route,
            bounds=content.bounds,
            swipe_x=swipe_x,
            swipe_top_y=swipe_top_y,
            swipe_bottom_y=swipe_bottom_y,
        )

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

    def _exercise_and_measure_frames(
        self, navigation: SettingsNavigation, expected_pid: int
    ) -> dict[str, Any]:
        self._adb(
            "measure.gfx.reset",
            "shell",
            "dumpsys",
            "gfxinfo",
            PACKAGE_ID,
            "reset",
            timeout_seconds=60,
        )
        latest: dict[str, Any] | None = None
        gesture_number = 0
        for round_number in range(1, self.config.max_exercise_rounds + 1):
            for gesture in range(self.config.swipes_per_round):
                gesture_number += 1
                start_y, end_y = (
                    (navigation.swipe_bottom_y, navigation.swipe_top_y)
                    if gesture % 2 == 0
                    else (navigation.swipe_top_y, navigation.swipe_bottom_y)
                )
                self._adb(
                    f"measure.gfx.swipe.{gesture_number:04d}",
                    "shell",
                    "input",
                    "swipe",
                    str(navigation.swipe_x),
                    str(start_y),
                    str(navigation.swipe_x),
                    str(end_y),
                    str(self.config.swipe_duration_ms),
                    timeout_seconds=10,
                )
            raw = self._adb_text(
                f"measure.gfx.framestats.{round_number:02d}",
                "shell",
                "dumpsys",
                "gfxinfo",
                PACKAGE_ID,
                "framestats",
                timeout_seconds=60,
            )
            _require_process_header(raw, "Graphics info for pid", expected_pid, "gfxinfo")
            latest = _parse_gfxinfo(raw)
            if latest["total_rendered"] >= 100:
                latest["_exercise_rounds"] = round_number
                return latest
        observed = 0 if latest is None else latest["total_rendered"]
        raise CollectorError(
            f"gfxinfo produced only {observed} frames after {self.config.max_exercise_rounds} rounds"
        )

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


def _tokenize_windows_command(command_line: str) -> tuple[str, ...]:
    try:
        tokens = tuple(shlex.split(command_line, posix=False))
    except ValueError as exc:
        raise CollectorError(f"live QEMU command line cannot be tokenized: {exc}") from exc
    if not tokens:
        raise CollectorError("live QEMU command line is blank after tokenization")
    return tokens


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


def _ui_nodes(xml_text: str, context: str) -> tuple[Mapping[str, str], ...]:
    encoded_size = len(xml_text.encode("utf-8"))
    if encoded_size <= 0 or encoded_size > 4 * 1024 * 1024:
        raise CollectorError(f"{context} UI hierarchy has an unsafe byte size: {encoded_size}")
    if "<!DOCTYPE" in xml_text.upper() or "<!ENTITY" in xml_text.upper():
        raise CollectorError(f"{context} UI hierarchy contains a forbidden XML declaration")
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise CollectorError(f"{context} UI hierarchy is invalid XML: {exc}") from exc
    if root.tag != "hierarchy":
        raise CollectorError(f"{context} UI hierarchy has unexpected root {root.tag!r}")
    nodes = tuple(dict(node.attrib) for node in root.iter("node"))
    if not nodes:
        raise CollectorError(f"{context} UI hierarchy contains no accessibility nodes")
    return nodes


def _matching_ui_nodes(xml_text: str, resource_id: str, context: str) -> tuple[Mapping[str, str], ...]:
    return tuple(
        node
        for node in _ui_nodes(xml_text, context)
        if node.get("resource-id", "") == resource_id
    )


def _reject_ui_resource(xml_text: str, resource_id: str, context: str) -> None:
    if _matching_ui_nodes(xml_text, resource_id, context):
        raise CollectorError(f"{context} exposes wrong-profile resource ID {resource_id}")


def _ui_target(
    xml_text: str,
    resource_id: str,
    width_px: int,
    height_px: int,
    *,
    clickable: bool = False,
    scrollable: bool = False,
) -> UiTarget:
    context = f"UI target {resource_id}"
    matches = _matching_ui_nodes(xml_text, resource_id, context)
    if len(matches) != 1:
        raise CollectorError(f"{context} must appear exactly once; observed {len(matches)}")
    node = matches[0]
    if node.get("package") != PACKAGE_ID:
        raise CollectorError(f"{context} belongs to wrong package {node.get('package')!r}")
    if node.get("enabled") != "true":
        raise CollectorError(f"{context} is not enabled")
    if clickable and node.get("clickable") != "true":
        raise CollectorError(f"{context} is not clickable")
    if scrollable and node.get("scrollable") != "true":
        raise CollectorError(f"{context} is not scrollable")
    bounds_match = re.fullmatch(
        r"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]", node.get("bounds", "")
    )
    if bounds_match is None:
        raise CollectorError(f"{context} has invalid bounds {node.get('bounds')!r}")
    bounds = UiBounds(*(int(value) for value in bounds_match.groups()))
    if not (
        0 <= bounds.left < bounds.right <= width_px
        and 0 <= bounds.top < bounds.bottom <= height_px
    ):
        raise CollectorError(
            f"{context} bounds {bounds} are outside the effective {width_px}x{height_px} display"
        )
    return UiTarget(resource_id, bounds)


def _safe_swipe_coordinates(bounds: UiBounds) -> tuple[int, int, int]:
    width = bounds.right - bounds.left
    height = bounds.bottom - bounds.top
    if width < 48 or height < 160:
        raise CollectorError(f"settings scroll bounds are too small for a safe swipe: {bounds}")
    x = (bounds.left + bounds.right) // 2
    inset = max(16, height // 5)
    top_y = bounds.top + inset
    bottom_y = bounds.bottom - inset
    if not (
        bounds.left < x < bounds.right
        and bounds.top < top_y < bottom_y < bounds.bottom
        and bottom_y - top_y >= 48
    ):
        raise CollectorError(f"settings scroll bounds cannot yield safe interior swipe coordinates: {bounds}")
    return x, top_y, bottom_y


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


def _gfx_number(output: str, label: str, *, integer: bool) -> tuple[int | float, int]:
    pattern = rf"(?mi)^\s*{re.escape(label)}:\s*([0-9]+(?:\.[0-9]+)?)\s*(?:ms)?\s*$"
    raw_values = re.findall(pattern, output)
    if len(raw_values) not in (1, 2) or len(set(raw_values)) != 1:
        raise CollectorError(f"gfxinfo did not expose one unambiguous {label}: {raw_values!r}")
    raw = raw_values[0]
    if integer and not raw.isdigit():
        raise CollectorError(f"gfxinfo {label} must be an integer")
    return (int(raw) if integer else float(raw)), len(raw_values)


def _parse_gfxinfo(output: str) -> dict[str, Any]:
    total_value, total_count = _gfx_number(output, "Total frames rendered", integer=True)
    total = int(total_value)
    jank_matches = re.findall(
        r"(?mi)^\s*Janky frames:\s*([0-9]+)(?:\s*\(([0-9]+(?:\.[0-9]+)?)%\))?\s*$",
        output,
    )
    unique_jank = [(int(count), percent) for count, percent in jank_matches]
    if len(unique_jank) not in (1, 2) or len(set(unique_jank)) != 1:
        raise CollectorError(
            f"gfxinfo did not expose one unambiguous Janky frames summary: {unique_jank!r}"
        )
    janky, printed_percent = unique_jank[0]
    if total <= 0 or not 0 <= janky <= total:
        raise CollectorError(f"gfxinfo frame counts are invalid: {janky}/{total}")
    derived_percent = janky * 100.0 / total
    if printed_percent and abs(float(printed_percent) - derived_percent) > 0.25:
        raise CollectorError(
            "gfxinfo printed jank percentage disagrees with its counts: "
            f"{printed_percent}% vs {derived_percent:.4f}%"
        )
    percentile_pairs = {
        "p50_ms": _gfx_number(output, "50th percentile", integer=False),
        "p90_ms": _gfx_number(output, "90th percentile", integer=False),
        "p95_ms": _gfx_number(output, "95th percentile", integer=False),
        "p99_ms": _gfx_number(output, "99th percentile", integer=False),
    }
    occurrence_counts = {total_count, len(unique_jank)} | {
        count for _, count in percentile_pairs.values()
    }
    if len(occurrence_counts) != 1:
        raise CollectorError(
            f"gfxinfo summary metrics have inconsistent occurrence counts: {sorted(occurrence_counts)!r}"
        )
    percentiles = {field: float(value) for field, (value, _) in percentile_pairs.items()}
    ordered = list(percentiles.values())
    if any(value <= 0 for value in ordered) or ordered != sorted(ordered):
        raise CollectorError(f"gfxinfo percentile values are invalid or non-monotonic: {ordered!r}")
    return {
        "total_rendered": total,
        "janky": janky,
        "janky_percent": round(derived_percent, 4),
        **percentiles,
    }


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
    raw_destination = destination.with_name(f"{config.profile}.raw.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    invalid_destinations = [
        path for path in (destination, raw_destination) if path.exists() and not path.is_file()
    ]
    if invalid_destinations:
        raise CollectorError(
            "performance evidence destinations must be regular files: "
            + ", ".join(str(path) for path in invalid_destinations)
        )
    existing = [path for path in (destination, raw_destination) if path.exists()]
    if existing and not overwrite:
        raise CollectorError(
            "refusing to overwrite existing performance evidence: "
            + ", ".join(str(path) for path in existing)
        )
    encoded = _encode_json(payload)
    raw_encoded = _encode_json(raw_transcript)
    prior_contents = {
        path: path.read_bytes() if path.is_file() else None
        for path in (destination, raw_destination)
    }
    raw_reference = payload.get("raw_evidence")
    expected_reference = {
        "path": f"performance/{config.profile}.raw.json",
        "sha256": hashlib.sha256(raw_encoded).hexdigest(),
    }
    if raw_reference != expected_reference:
        raise CollectorError("normalized raw_evidence reference does not match raw transcript bytes")
    temporary_path: Path | None = None
    raw_temporary_path: Path | None = None
    transaction_started = False
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent, delete=False
        ) as handle:
            temporary_path = Path(handle.name)
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{raw_destination.name}.",
            suffix=".tmp",
            dir=destination.parent,
            delete=False,
        ) as handle:
            raw_temporary_path = Path(handle.name)
            handle.write(raw_encoded)
            handle.flush()
            os.fsync(handle.fileno())
        validator.validate(temporary_path, raw_temporary_path, config)
        source_verifier.verify(config.release_source_digest)
        appeared = [
            path for path in (destination, raw_destination) if path.exists() and path not in existing
        ]
        if appeared and not overwrite:
            raise CollectorError(
                "performance evidence appeared during collection: "
                + ", ".join(str(path) for path in appeared)
            )
        os.replace(raw_temporary_path, raw_destination)
        raw_temporary_path = None
        transaction_started = True
        source_verifier.verify(config.release_source_digest)
        os.replace(temporary_path, destination)
        temporary_path = None
        source_verifier.verify(config.release_source_digest)
    except Exception:
        if transaction_started:
            for path, contents in prior_contents.items():
                if contents is None:
                    try:
                        path.unlink()
                    except FileNotFoundError:
                        pass
                    continue
                _atomic_replace_bytes(path, contents)
        raise
    finally:
        for unfinished in (temporary_path, raw_temporary_path):
            if unfinished is None:
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
    parser.add_argument("--release-source-digest", required=True)
    parser.add_argument("--candidate-apk-sha256", required=True)
    parser.add_argument("--instrumentation-apk-sha256", required=True)
    parser.add_argument("--evidence-run-id", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--litertlm-coordinate", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--emulator", default="emulator")
    parser.add_argument("--powershell", default="powershell.exe")
    parser.add_argument("--max-exercise-rounds", type=int, default=4)
    parser.add_argument("--swipes-per-round", type=int, default=30)
    parser.add_argument("--swipe-duration-ms", type=int, default=180)
    parser.add_argument("--overwrite", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    config = CollectorConfig(
        serial=args.serial,
        profile=args.profile,
        release_source_digest=args.release_source_digest,
        candidate_apk_sha256=args.candidate_apk_sha256,
        instrumentation_apk_sha256=args.instrumentation_apk_sha256,
        evidence_run_id=args.evidence_run_id,
        version_name=args.version_name,
        version_code=args.version_code,
        litertlm_coordinate=args.litertlm_coordinate,
        adb=args.adb,
        emulator=args.emulator,
        max_exercise_rounds=args.max_exercise_rounds,
        swipes_per_round=args.swipes_per_round,
        swipe_duration_ms=args.swipe_duration_ms,
    )
    try:
        executor = SubprocessExecutor()
        source_verifier = GitSourceVerifier(args.repo_root)
        collector = PerformanceCollector(
            config,
            executor,
            WindowsCimProcessSource(executor, args.powershell),
            source_verifier,
        )
        payload = collector.collect()
        output = args.output if args.output.is_absolute() else args.repo_root / args.output
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
