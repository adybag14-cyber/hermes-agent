#!/usr/bin/env python3
"""Capture identity-bound Android launcher and deep-link splash evidence.

This host-side lane exists because instrumentation starts after Android's system-owned
starting window. It records the relevant launch interval but deliberately leaves the
visual splash/handoff verdict pending for frame-by-frame human review.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
import xml.etree.ElementTree as ET


PACKAGE_ID = "com.mobilefork.hermesagent"
TEST_PACKAGE_ID = f"{PACKAGE_ID}.test"
MAIN_ACTIVITY = f"{PACKAGE_ID}/.MainActivity"
DEEP_LINK = "hermesagent://auth/callback"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{15,79}$")
AVD_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
SERIAL_RE = re.compile(r"^emulator-[0-9]+$")
BOOT_ID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")
SAFE_THEME_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,79}$")
REVIEWER_RE = re.compile(r"^[^\r\n]{2,120}$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MANIFEST_SCHEMA = "hermes-host-launch-theme-evidence-v2"
PALETTE_STATE_SCHEMA = "hermes-persisted-palette-state-v1"
PREFERENCES_FILE = "shared_prefs/hermes_android_settings.xml"
PALETTE_FIELDS = (
    "theme_primary",
    "theme_secondary",
    "theme_background",
    "theme_surface",
    "theme_surface_variant",
    "card_shape",
    "ui_font_scale",
)
PREFERENCE_KEYS = {
    "theme_primary": "theme_primary_hex",
    "theme_secondary": "theme_secondary_hex",
    "theme_background": "theme_background_hex",
    "theme_surface": "theme_surface_hex",
    "theme_surface_variant": "theme_surface_variant_hex",
    "card_shape": "theme_card_shape",
    "ui_font_scale": "ui_font_scale",
}
SCREENRECORD_TIME_LIMIT_SECONDS = 10
SCREENRECORD_STARTUP_DELAY_SECONDS = 0.6
POST_RESUME_DELAY_SECONDS = 1.0
SCREENRECORD_TIMEOUT_HEADROOM_SECONDS = 8
SCREENRECORD_BIT_RATE = 8_000_000


class EvidenceError(RuntimeError):
    """Raised when host evidence cannot be bound to the requested lane."""


@dataclass(frozen=True)
class EvidenceIdentity:
    serial: str
    avd_name: str
    expected_profile: str
    evidence_run_id: str
    source_digest: str
    candidate_apk_sha256: str
    instrumentation_apk_sha256: str

    def validate(self) -> None:
        if not SERIAL_RE.fullmatch(self.serial):
            raise EvidenceError("serial must identify one explicit emulator-N device")
        if not AVD_NAME_RE.fullmatch(self.avd_name):
            raise EvidenceError("avd_name does not match the release evidence contract")
        if self.expected_profile not in {"phone", "tablet"}:
            raise EvidenceError("expected_profile must be phone or tablet")
        if not RUN_ID_RE.fullmatch(self.evidence_run_id):
            raise EvidenceError("evidence_run_id does not match the release evidence contract")
        if not SHA256_RE.fullmatch(self.source_digest):
            raise EvidenceError("source_digest must be a lowercase SHA-256")
        if not SHA256_RE.fullmatch(self.candidate_apk_sha256):
            raise EvidenceError("candidate_apk_sha256 must be a lowercase SHA-256")
        if not SHA256_RE.fullmatch(self.instrumentation_apk_sha256):
            raise EvidenceError("instrumentation_apk_sha256 must be a lowercase SHA-256")


@dataclass(frozen=True)
class PaletteState:
    theme_primary: str
    theme_secondary: str
    theme_background: str
    theme_surface: str
    theme_surface_variant: str
    card_shape: str
    ui_font_scale: float

    def normalized(self) -> dict[str, str | float]:
        return {
            "theme_primary": self.theme_primary.upper(),
            "theme_secondary": self.theme_secondary.upper(),
            "theme_background": self.theme_background.upper(),
            "theme_surface": self.theme_surface.upper(),
            "theme_surface_variant": self.theme_surface_variant.upper(),
            "card_shape": self.card_shape,
            "ui_font_scale": self.ui_font_scale,
        }

    def validate(self, context: str) -> None:
        for field in PALETTE_FIELDS[:5]:
            value = getattr(self, field)
            if not HEX_COLOR_RE.fullmatch(value):
                raise EvidenceError(f"{context}.{field} must be one #RRGGBB colour")
        if self.card_shape not in {"square", "soft", "rounded"}:
            raise EvidenceError(f"{context}.card_shape is not a supported persisted shape")
        if not math.isfinite(self.ui_font_scale) or not 0.75 <= self.ui_font_scale <= 1.5:
            raise EvidenceError(f"{context}.ui_font_scale is outside the supported range")


@dataclass(frozen=True)
class PaletteProof:
    path: Path
    sha256: str
    evidence_identity: str
    theme_id: str
    profile: str
    language: str
    source_digest: str
    candidate_apk_sha256: str
    instrumentation_apk_sha256: str
    evidence_run_id: str
    device_serial: str
    avd_name: str
    device_boot_id: str
    palette: PaletteState


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.is_file():
            return str(candidate)
    resolved = shutil.which("adb.exe" if os.name == "nt" else "adb")
    if resolved:
        return resolved
    raise EvidenceError("adb was not found; set ANDROID_HOME/ANDROID_SDK_ROOT or PATH")


class Adb:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.executable = adb_path()

    def argv(self, *args: str) -> list[str]:
        return [self.executable, "-s", self.serial, *args]

    def text(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self.argv(*args),
            check=check,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def bytes(self, *args: str) -> bytes:
        result = subprocess.run(
            self.argv(*args),
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return result.stdout


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _strict_key_value_header(path: Path) -> dict[str, str]:
    try:
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    except (OSError, UnicodeDecodeError) as exc:
        raise EvidenceError(f"could not read palette proof {path}: {exc}") from exc
    header_text, separator, body = text.partition("\n\n")
    if not separator or not body.strip():
        raise EvidenceError("palette proof must contain a nonblank headed UI semantics body")
    result: dict[str, str] = {}
    sentinels = 0
    for line in header_text.splitlines():
        key, marker, value = line.partition("=")
        if not marker or not key:
            raise EvidenceError(f"invalid palette proof header line: {line!r}")
        if key == "sentinel":
            sentinels += 1
            continue
        if key in result:
            raise EvidenceError(f"duplicate palette proof field {key!r}")
        result[key] = value.strip()
    if sentinels == 0:
        raise EvidenceError("palette proof does not contain a visible UI sentinel")
    return result


def load_palette_proof(path: Path, identity: EvidenceIdentity) -> PaletteProof:
    resolved = path.resolve()
    header = _strict_key_value_header(resolved)
    required = {
        "evidence_type",
        "evidence_identity",
        "artifact",
        "coverage_kind",
        "page_id",
        "profile",
        "language",
        "theme_id",
        *PALETTE_FIELDS,
        "source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "evidence_run_id",
        "device_serial",
        "avd_name",
        "device_boot_id",
    }
    missing = required - set(header)
    if missing:
        raise EvidenceError(f"palette proof is missing fields {sorted(missing)}")
    if header["evidence_type"] != "headed-ui-coverage-bound":
        raise EvidenceError("palette proof was not produced by the headed UI coverage lane")
    if header["evidence_identity"] != "appearance-custom-light":
        raise EvidenceError("palette proof must be the rendered appearance-custom-light capture")
    if header["coverage_kind"] != "custom-light-palette":
        raise EvidenceError("palette proof does not carry the custom-light-palette coverage kind")
    if header["language"] != "en":
        raise EvidenceError("palette proof must use the deterministic English appearance capture")
    if not SAFE_THEME_ID_RE.fullmatch(header["theme_id"]):
        raise EvidenceError("palette proof theme_id is invalid")
    expected_identity = {
        "source_digest": identity.source_digest,
        "candidate_apk_sha256": identity.candidate_apk_sha256,
        "instrumentation_apk_sha256": identity.instrumentation_apk_sha256,
        "evidence_run_id": identity.evidence_run_id,
        "device_serial": identity.serial,
        "avd_name": identity.avd_name,
    }
    for field, expected in expected_identity.items():
        if header[field] != expected:
            raise EvidenceError(f"palette proof {field} does not match the requested launch lane")
    profile_match = re.fullmatch(r"(phone|tablet)-([0-9]+)x([0-9]+)dp", header["profile"])
    if profile_match is None or profile_match.group(1) != identity.expected_profile:
        raise EvidenceError("palette proof profile does not match the requested launch lane")
    if not BOOT_ID_RE.fullmatch(header["device_boot_id"].lower()):
        raise EvidenceError("palette proof device_boot_id is invalid")
    try:
        ui_font_scale = float(header["ui_font_scale"])
    except ValueError as exc:
        raise EvidenceError("palette proof ui_font_scale is invalid") from exc
    palette = PaletteState(
        theme_primary=header["theme_primary"],
        theme_secondary=header["theme_secondary"],
        theme_background=header["theme_background"],
        theme_surface=header["theme_surface"],
        theme_surface_variant=header["theme_surface_variant"],
        card_shape=header["card_shape"],
        ui_font_scale=ui_font_scale,
    )
    palette.validate("palette_proof")
    return PaletteProof(
        path=resolved,
        sha256=sha256_file(resolved),
        evidence_identity=header["evidence_identity"],
        theme_id=header["theme_id"],
        profile=header["profile"],
        language=header["language"],
        source_digest=header["source_digest"],
        candidate_apk_sha256=header["candidate_apk_sha256"],
        instrumentation_apk_sha256=header["instrumentation_apk_sha256"],
        evidence_run_id=header["evidence_run_id"],
        device_serial=header["device_serial"],
        avd_name=header["avd_name"],
        device_boot_id=header["device_boot_id"].lower(),
        palette=palette,
    )


def parse_persisted_palette_xml(payload: str) -> PaletteState:
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exc:
        raise EvidenceError(f"persisted app settings are not valid SharedPreferences XML: {exc}") from exc
    if root.tag != "map":
        raise EvidenceError("persisted app settings do not contain a SharedPreferences map")
    entries: dict[str, str] = {}
    for child in root:
        name = child.attrib.get("name", "")
        if not name or name in entries:
            if name in PREFERENCE_KEYS.values():
                raise EvidenceError(f"persisted app settings contain duplicate palette key {name!r}")
            continue
        if child.tag == "string":
            entries[name] = child.text or ""
        elif child.tag == "float":
            entries[name] = child.attrib.get("value", "")
    missing = set(PREFERENCE_KEYS.values()) - set(entries)
    if missing:
        raise EvidenceError(f"persisted app settings are missing palette keys {sorted(missing)}")
    try:
        ui_font_scale = float(entries[PREFERENCE_KEYS["ui_font_scale"]])
    except ValueError as exc:
        raise EvidenceError("persisted app ui_font_scale is invalid") from exc
    state = PaletteState(
        theme_primary=entries[PREFERENCE_KEYS["theme_primary"]],
        theme_secondary=entries[PREFERENCE_KEYS["theme_secondary"]],
        theme_background=entries[PREFERENCE_KEYS["theme_background"]],
        theme_surface=entries[PREFERENCE_KEYS["theme_surface"]],
        theme_surface_variant=entries[PREFERENCE_KEYS["theme_surface_variant"]],
        card_shape=entries[PREFERENCE_KEYS["card_shape"]],
        ui_font_scale=ui_font_scale,
    )
    state.validate("persisted_palette")
    return state


def read_persisted_palette(adb: Adb) -> tuple[PaletteState, str]:
    result = adb.text("exec-out", "run-as", PACKAGE_ID, "cat", PREFERENCES_FILE, check=False)
    if result.returncode != 0:
        raise EvidenceError(
            "could not read the installed app's persisted palette with run-as: "
            + (result.stderr or result.stdout).strip()
        )
    state = parse_persisted_palette_xml(result.stdout)
    return state, hashlib.sha256(result.stdout.encode("utf-8")).hexdigest()


def require_matching_palette(observed: PaletteState, proof: PaletteProof) -> None:
    expected = proof.palette.normalized()
    actual = observed.normalized()
    if actual != expected:
        differing = sorted(field for field in PALETTE_FIELDS if actual[field] != expected[field])
        raise EvidenceError(
            "installed app persisted palette does not match the rendered UI proof; "
            f"differing_fields={differing}"
        )


def parse_profile(am_config: str) -> tuple[str, int, int]:
    pairs = {
        (int(width), int(height))
        for width, height in re.findall(
            r"(?<![A-Za-z0-9])w([0-9]+)dp-h([0-9]+)dp(?=$|[-\s])",
            am_config,
        )
    }
    if len(pairs) != 1:
        raise EvidenceError(f"am get-config did not expose one unambiguous dp profile: {sorted(pairs)}")
    width_dp, height_dp = pairs.pop()
    return ("tablet" if width_dp >= 600 else "phone", width_dp, height_dp)


def installed_base_apk(adb: Adb, package_id: str) -> tuple[str, str]:
    result = adb.text("shell", "pm", "path", package_id)
    paths = [line.removeprefix("package:").strip() for line in result.stdout.splitlines() if line.startswith("package:")]
    base_path = next((path for path in paths if path.endswith("/base.apk")), paths[0] if paths else "")
    if not base_path.startswith("/") or not base_path.endswith(".apk"):
        raise EvidenceError(f"pm path did not resolve the installed {package_id} base APK")
    digest = sha256_bytes(adb.bytes("exec-out", "cat", base_path))
    return base_path, digest


def require_resumed_main_activity(adb: Adb, timeout_seconds: float = 8.0) -> str:
    deadline = time.monotonic() + timeout_seconds
    latest = ""
    while time.monotonic() <= deadline:
        result = adb.text("shell", "dumpsys", "activity", "activities", check=False)
        latest = result.stdout + result.stderr
        resumed_lines = [line.strip() for line in latest.splitlines() if "mResumedActivity" in line or "topResumedActivity" in line]
        if any(PACKAGE_ID in line and "MainActivity" in line for line in resumed_lines):
            return latest
        time.sleep(0.25)
    raise EvidenceError("MainActivity did not become the resumed foreground activity")


def write_verified_png(adb: Adb, path: Path) -> None:
    payload = adb.bytes("exec-out", "screencap", "-p")
    if len(payload) <= 24 or not payload.startswith(PNG_SIGNATURE) or payload[12:16] != b"IHDR":
        raise EvidenceError("screencap did not return a decodable PNG header")
    width = int.from_bytes(payload[16:20], "big")
    height = int.from_bytes(payload[20:24], "big")
    if width <= 0 or height <= 0:
        raise EvidenceError("screencap PNG dimensions are invalid")
    path.write_bytes(payload)


def record_launch(
    adb: Adb,
    output_dir: Path,
    remote_prefix: str,
    label: str,
    launch: Callable[[], subprocess.CompletedProcess[str]],
) -> dict[str, object]:
    remote_video = f"/sdcard/{remote_prefix}-{label}.mp4"
    video = output_dir / f"{remote_prefix}-{label}.mp4"
    screenshot = output_dir / f"{remote_prefix}-{label}-settled.png"
    activity_dump = output_dir / f"{remote_prefix}-{label}-activity.txt"

    adb.text("shell", "am", "force-stop", PACKAGE_ID)
    adb.text("shell", "input", "keyevent", "KEYCODE_HOME")
    adb.text("shell", "rm", "-f", remote_video, check=False)
    recorder = subprocess.Popen(
        adb.argv(
            "shell",
            "screenrecord",
            "--time-limit",
            str(SCREENRECORD_TIME_LIMIT_SECONDS),
            "--bit-rate",
            str(SCREENRECORD_BIT_RATE),
            remote_video,
        ),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        time.sleep(SCREENRECORD_STARTUP_DELAY_SECONDS)
        launch_result = launch()
        activity_text = require_resumed_main_activity(adb)
        time.sleep(POST_RESUME_DELAY_SECONDS)
        recorder_stdout, recorder_stderr = recorder.communicate(
            timeout=SCREENRECORD_TIME_LIMIT_SECONDS + SCREENRECORD_TIMEOUT_HEADROOM_SECONDS
        )
    except BaseException:
        recorder.kill()
        recorder.communicate()
        raise
    if recorder.returncode != 0:
        raise EvidenceError(
            "screenrecord failed: " + (recorder_stderr or recorder_stdout).decode("utf-8", "replace")
        )
    pull = adb.text("pull", remote_video, str(video), check=False)
    adb.text("shell", "rm", "-f", remote_video, check=False)
    if pull.returncode != 0 or not video.is_file() or video.stat().st_size <= 0:
        raise EvidenceError(f"could not pull launch recording {label}: {pull.stderr}")
    write_verified_png(adb, screenshot)
    activity_dump.write_text(activity_text, encoding="utf-8")
    return {
        "label": label,
        "launch_stdout": launch_result.stdout,
        "launch_stderr": launch_result.stderr,
        "video": video.name,
        "video_sha256": sha256_file(video),
        "settled_screenshot": screenshot.name,
        "settled_screenshot_sha256": sha256_file(screenshot),
        "activity_dump": activity_dump.name,
        "activity_dump_sha256": sha256_file(activity_dump),
        "automated_state_verdict": "main_activity_resumed_and_artifacts_decoded",
        "visual_splash_verdict": "manual_review_required",
    }


def capture(args: argparse.Namespace) -> int:
    identity = EvidenceIdentity(
        serial=args.serial,
        avd_name=args.avd_name,
        expected_profile=args.expected_profile,
        evidence_run_id=args.evidence_run_id,
        source_digest=args.source_digest,
        candidate_apk_sha256=args.candidate_apk_sha256,
        instrumentation_apk_sha256=args.instrumentation_apk_sha256,
    )
    identity.validate()
    palette_proof = load_palette_proof(args.palette_proof, identity)
    if args.launcher_x < 0 or args.launcher_y < 0:
        raise EvidenceError("launcher coordinates must be non-negative")
    adb = Adb(identity.serial)
    if adb.text("get-state").stdout.strip() != "device":
        raise EvidenceError(f"{identity.serial} is not in adb device state")
    observed_avd = adb.text("shell", "getprop", "ro.boot.qemu.avd_name").stdout.strip()
    if observed_avd != identity.avd_name:
        raise EvidenceError(f"executing AVD {observed_avd!r} does not match {identity.avd_name!r}")
    actual_profile, width_dp, height_dp = parse_profile(adb.text("shell", "am", "get-config").stdout)
    if actual_profile != identity.expected_profile:
        raise EvidenceError(
            f"executing {width_dp}x{height_dp}dp profile is {actual_profile}, expected {identity.expected_profile}"
        )
    sdk_text = adb.text("shell", "getprop", "ro.build.version.sdk").stdout.strip()
    if not sdk_text.isdigit() or int(sdk_text) < 31:
        raise EvidenceError(f"system-owned splash evidence requires Android 12+; device reported SDK {sdk_text!r}")
    build_fingerprint = adb.text("shell", "getprop", "ro.build.fingerprint").stdout.strip()
    if not build_fingerprint:
        raise EvidenceError("device did not expose a build fingerprint")
    device_boot_id = adb.text("shell", "cat", "/proc/sys/kernel/random/boot_id").stdout.strip().lower()
    if not BOOT_ID_RE.fullmatch(device_boot_id):
        raise EvidenceError("device did not expose a valid kernel boot identity")
    if palette_proof.device_boot_id != device_boot_id:
        raise EvidenceError("palette proof was captured during a different emulator boot")
    installed_path, installed_digest = installed_base_apk(adb, PACKAGE_ID)
    if installed_digest != identity.candidate_apk_sha256:
        raise EvidenceError(
            f"installed APK SHA-256 {installed_digest} does not match {identity.candidate_apk_sha256}"
        )
    instrumentation_path, instrumentation_digest = installed_base_apk(adb, TEST_PACKAGE_ID)
    if instrumentation_digest != identity.instrumentation_apk_sha256:
        raise EvidenceError(
            "installed instrumentation APK SHA-256 "
            f"{instrumentation_digest} does not match {identity.instrumentation_apk_sha256}"
        )
    persisted_palette, preferences_sha256 = read_persisted_palette(adb)
    require_matching_palette(persisted_palette, palette_proof)

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    prefix = (
        f"{identity.evidence_run_id}-{identity.expected_profile}-"
        f"{palette_proof.theme_id}-launch-theme"
    )
    palette_state_path = output_dir / f"{prefix}-persisted-palette.json"
    palette_state = {
        "schema": PALETTE_STATE_SCHEMA,
        "identity": {
            "source_digest": identity.source_digest,
            "candidate_apk_sha256": identity.candidate_apk_sha256,
            "instrumentation_apk_sha256": identity.instrumentation_apk_sha256,
            "evidence_run_id": identity.evidence_run_id,
            "device_serial": identity.serial,
            "avd_name": identity.avd_name,
            "device_boot_id": device_boot_id,
            "profile": palette_proof.profile,
        },
        "theme_id": palette_proof.theme_id,
        "palette": persisted_palette.normalized(),
        "shared_preferences_xml_sha256": preferences_sha256,
        "contains_only_filtered_palette_state": True,
    }
    palette_state_path.write_text(
        json.dumps(palette_state, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    captures = [
        record_launch(
            adb,
            output_dir,
            prefix,
            "cold-launcher-tap",
            lambda: adb.text("shell", "input", "tap", str(args.launcher_x), str(args.launcher_y)),
        ),
        record_launch(
            adb,
            output_dir,
            prefix,
            "cold-deep-link",
            lambda: adb.text(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                MAIN_ACTIVITY,
                "-a",
                "android.intent.action.VIEW",
                "-c",
                "android.intent.category.BROWSABLE",
                "-d",
                DEEP_LINK,
            ),
        ),
    ]
    manifest = {
        "schema": MANIFEST_SCHEMA,
        "identity": {
            **identity.__dict__,
            "observed_avd_name": observed_avd,
            "observed_profile": actual_profile,
            "width_dp": width_dp,
            "height_dp": height_dp,
            "sdk_int": int(sdk_text),
            "build_fingerprint": build_fingerprint,
            "device_boot_id": device_boot_id,
            "installed_apk_path": installed_path,
            "installed_apk_sha256": installed_digest,
            "installed_instrumentation_apk_path": instrumentation_path,
            "installed_instrumentation_apk_sha256": instrumentation_digest,
        },
        "palette": {
            "theme_id": palette_proof.theme_id,
            "profile": palette_proof.profile,
            "proof_evidence_identity": palette_proof.evidence_identity,
            "proof_sha256": palette_proof.sha256,
            **persisted_palette.normalized(),
            "persisted_state_file": palette_state_path.name,
            "persisted_state_file_sha256": sha256_file(palette_state_path),
            "shared_preferences_xml_sha256": preferences_sha256,
            "verified_against_persisted_app_state": True,
        },
        "captures": captures,
        "automated_verdict": "identity_bound_launch_capture_complete",
        "visual_review": {
            "status": "pending",
            "reviewer": None,
            "reviewed_at_utc": None,
            "decision": None,
            "notes": None,
            "method": "manual-frame-by-frame",
            "automated_pixel_certification": False,
        },
        "manual_acceptance": [
            "Inspect both MP4 files frame by frame; each must visibly show the Android 12+ static Hermes splash.",
            "Reject any black, white, legacy preview, stale-task, or third-party frame before the first Hermes frame.",
            f"Verify the first Hermes frame hands off without a contrasting flash to the saved {palette_proof.theme_id} palette.",
            "Match source digest, run ID, candidate/test APK hashes, AVD, profile, and boot ID to the headed UI inventory.",
            "Use this script's review command to persist the human reviewer, UTC timestamp, and pass/fail decision; capture never self-certifies pixels.",
        ],
    }
    manifest_path = output_dir / f"{prefix}-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(manifest_path)
    return 0


def _load_json_object(path: Path) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"could not read launch evidence manifest {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvidenceError("launch evidence manifest must contain one JSON object")
    return value


def _referenced_file(manifest_path: Path, name: Any, digest: Any, context: str) -> Path:
    if not isinstance(name, str) or not name or Path(name).name != name:
        raise EvidenceError(f"{context} contains an unsafe artifact name")
    if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
        raise EvidenceError(f"{context} contains an invalid artifact SHA-256")
    path = manifest_path.parent / name
    if not path.is_file() or path.stat().st_size <= 0 or sha256_file(path) != digest:
        raise EvidenceError(f"{context} artifact is missing, empty, or does not match its SHA-256")
    return path


def validate_captured_manifest_for_review(manifest_path: Path) -> dict[str, Any]:
    payload = _load_json_object(manifest_path)
    if payload.get("schema") != MANIFEST_SCHEMA:
        raise EvidenceError(f"review requires a {MANIFEST_SCHEMA} capture")
    if payload.get("automated_verdict") != "identity_bound_launch_capture_complete":
        raise EvidenceError("launch capture does not carry the completed automated verdict")
    captures = payload.get("captures")
    if not isinstance(captures, list) or {record.get("label") for record in captures if isinstance(record, dict)} != {
        "cold-launcher-tap",
        "cold-deep-link",
    }:
        raise EvidenceError("launch capture must contain the launcher-tap and deep-link lanes")
    for index, record in enumerate(captures, start=1):
        if not isinstance(record, dict):
            raise EvidenceError(f"capture[{index}] must be an object")
        _referenced_file(manifest_path, record.get("video"), record.get("video_sha256"), f"capture[{index}].video")
        _referenced_file(
            manifest_path,
            record.get("settled_screenshot"),
            record.get("settled_screenshot_sha256"),
            f"capture[{index}].settled_screenshot",
        )
        _referenced_file(
            manifest_path,
            record.get("activity_dump"),
            record.get("activity_dump_sha256"),
            f"capture[{index}].activity_dump",
        )
        if record.get("visual_splash_verdict") != "manual_review_required":
            raise EvidenceError(f"capture[{index}] improperly claims an automated visual verdict")
    palette = payload.get("palette")
    if not isinstance(palette, dict) or palette.get("verified_against_persisted_app_state") is not True:
        raise EvidenceError("launch capture did not verify the installed app's persisted palette")
    _referenced_file(
        manifest_path,
        palette.get("persisted_state_file"),
        palette.get("persisted_state_file_sha256"),
        "palette.persisted_state_file",
    )
    review = payload.get("visual_review")
    expected_review_keys = {
        "status",
        "reviewer",
        "reviewed_at_utc",
        "decision",
        "notes",
        "method",
        "automated_pixel_certification",
    }
    if not isinstance(review, dict) or set(review) != expected_review_keys:
        raise EvidenceError("launch capture visual_review object is malformed")
    if review.get("method") != "manual-frame-by-frame" or review.get("automated_pixel_certification") is not False:
        raise EvidenceError("launch capture visual review contract is malformed")
    return payload


def _review_timestamp(value: str | None) -> str:
    if value is None:
        return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise EvidenceError("reviewed_at_utc must use YYYY-MM-DDTHH:MM:SSZ") from exc
    return parsed.isoformat().replace("+00:00", "Z")


def review(args: argparse.Namespace) -> int:
    manifest_path = args.manifest.resolve()
    payload = validate_captured_manifest_for_review(manifest_path)
    reviewer = args.reviewer.strip()
    notes = args.notes.strip()
    if not REVIEWER_RE.fullmatch(reviewer):
        raise EvidenceError("reviewer must be 2-120 printable characters on one line")
    if not notes or "\r" in notes or "\n" in notes or len(notes) > 500:
        raise EvidenceError("notes must be a nonblank single line of at most 500 characters")
    existing = payload["visual_review"]
    if existing.get("status") != "pending" and not args.replace_existing_review:
        raise EvidenceError("visual review is already recorded; use --replace-existing-review explicitly")
    payload["visual_review"] = {
        "status": "reviewed",
        "reviewer": reviewer,
        "reviewed_at_utc": _review_timestamp(args.reviewed_at_utc),
        "decision": args.decision,
        "notes": notes,
        "method": "manual-frame-by-frame",
        "automated_pixel_certification": False,
    }
    temporary = manifest_path.with_name(f"{manifest_path.name}.tmp")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(manifest_path)
    print(manifest_path)
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture", help="capture identity-bound launcher/deep-link evidence")
    capture_parser.add_argument("--serial", required=True)
    capture_parser.add_argument("--avd-name", required=True)
    capture_parser.add_argument("--expected-profile", required=True, choices=("phone", "tablet"))
    capture_parser.add_argument(
        "--palette-proof",
        required=True,
        type=Path,
        help="appearance-custom-light semantics proof from the matching headed UI coverage run",
    )
    capture_parser.add_argument("--evidence-run-id", required=True)
    capture_parser.add_argument("--source-digest", required=True)
    capture_parser.add_argument("--candidate-apk-sha256", required=True)
    capture_parser.add_argument("--instrumentation-apk-sha256", required=True)
    capture_parser.add_argument("--launcher-x", required=True, type=int, help="X coordinate of the Hermes icon on the visible launcher home screen")
    capture_parser.add_argument("--launcher-y", required=True, type=int, help="Y coordinate of the Hermes icon on the visible launcher home screen")
    capture_parser.add_argument("--output-dir", required=True)
    capture_parser.set_defaults(func=capture)

    review_parser = commands.add_parser("review", help="record a human frame-by-frame visual decision")
    review_parser.add_argument("--manifest", required=True, type=Path)
    review_parser.add_argument("--reviewer", required=True)
    review_parser.add_argument("--decision", required=True, choices=("pass", "fail"))
    review_parser.add_argument("--reviewed-at-utc")
    review_parser.add_argument("--notes", required=True)
    review_parser.add_argument("--replace-existing-review", action="store_true")
    review_parser.set_defaults(func=review)
    return result


def main() -> int:
    try:
        args = parser().parse_args()
        return args.func(args)
    except (EvidenceError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    raise SystemExit(main())
