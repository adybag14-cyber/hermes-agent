#!/usr/bin/env python3
"""Create and verify committed headed-device evidence for Android releases.

The source binding is a SHA-256 digest over the path, mode, type, and SHA-256
of the Git blob bytes for every tracked entry except
``android/release-evidence/**``. This lets maintainers commit the evidence after
the tested source commit without a circular dependency on the final evidence
commit SHA.

This script validates evidence produced elsewhere. It never starts an emulator
and never treats an instrumentation compile as device certification.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shlex
import struct
import subprocess
import sys
import zipfile
import zlib
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence
import xml.etree.ElementTree as ET


MANIFEST_SCHEMA_V2 = "hermes-android-release-evidence-manifest-v2"
MANIFEST_SCHEMA_V3 = "hermes-android-release-evidence-manifest-v3"
# Kept as the current schema for callers which display rather than select it.
MANIFEST_SCHEMA = MANIFEST_SCHEMA_V3
MODEL_EVIDENCE_SCHEMA = "hermes-model-evidence-v1"
PERFORMANCE_SCHEMA = "hermes-android-performance-evidence-v2"
RAW_PERFORMANCE_SCHEMA = "hermes-android-performance-host-raw-v2"
HOST_LAUNCH_THEME_SCHEMA = "hermes-host-launch-theme-evidence-v2"
PERSISTED_PALETTE_SCHEMA = "hermes-persisted-palette-state-v1"
ISSUE8_EVIDENCE_SCHEMA = "hermes-android-issue-8-tool-and-preflight-v1"
ISSUE16_EVIDENCE_SCHEMA = "hermes-android-issue-16-debian-sandbox-v1"
PHYSICAL_NANBEIGE_REPAIR_SCHEMA = "hermes-android-physical-nanbeige-repair-v1"
SOURCE_DIGEST_ALGORITHM = "sha256-git-tree-contents-v1"
EVIDENCE_PREFIX = PurePosixPath("android/release-evidence")
COMPREHENSIVE_UI_EVIDENCE_MIN_VERSION = (0, 13, 148)
LITERTLM_0161_MIN_VERSION = (0, 13, 148)
PHYSICAL_NANBEIGE_REPAIR_MIN_VERSION = (0, 13, 151)
LANGUAGES = ("en", "zh", "es", "de", "pt", "fr")
PROFILES = ("phone-compact", "tablet")
UI_COVERAGE_PREFIX = PurePosixPath("ui-coverage")
LAUNCH_THEME_PREFIX = PurePosixPath("launch-theme")
ISSUE_EVIDENCE_PREFIX = PurePosixPath("issues")
PHYSICAL_DEVICE_PREFIX = PurePosixPath("physical-device")
ISSUE8_EVIDENCE_PATH = ISSUE_EVIDENCE_PREFIX / "issue-8-tool-and-preflight.json"
ISSUE16_EVIDENCE_PATH = ISSUE_EVIDENCE_PREFIX / "issue-16-debian-sandbox.json"
PHYSICAL_NANBEIGE_REPAIR_PATH = (
    PHYSICAL_DEVICE_PREFIX / "nanbeige4.2-3b-q4-k-m-repair.json"
)
COMPLETE_UI_INVENTORY = "complete-inventory.txt"
LOCALIZED_UI_INVENTORY = "localized-inventory.txt"
APP_SECTION_SOURCE = PurePosixPath(
    "android/app/src/main/java/com/mobilefork/hermesagent/ui/shell/ShellModels.kt"
)
DEVICE_PAGE_SOURCE = PurePosixPath(
    "android/app/src/main/java/com/mobilefork/hermesagent/ui/device/DeviceScreen.kt"
)
SETTINGS_PAGE_SOURCE = PurePosixPath(
    "android/app/src/main/java/com/mobilefork/hermesagent/ui/settings/SettingsScreen.kt"
)
RECOMMENDED_MODEL_SOURCE = PurePosixPath(
    "android/app/src/main/java/com/mobilefork/hermesagent/ui/settings/LocalModelDownloadsViewModel.kt"
)
UI_EVIDENCE_PRODUCER_SOURCE = PurePosixPath(
    "android/app/src/androidTest/java/com/mobilefork/hermesagent/HermesUiCoverageInstrumentedTest.kt"
)
TAG_RE = re.compile(
    r"^v0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"(?:-(?:alpha|beta|rc)(?:\.[0-9]+)?)?$"
)
HEX_40_RE = re.compile(r"^[0-9a-f]{40}$")
HEX_64_RE = re.compile(r"^[0-9a-f]{64}$")
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")
HEADED_UI_PROFILE_RE = re.compile(r"^(phone|tablet)-([0-9]+)x([0-9]+)dp$")
SAFE_ARTIFACT_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,239}$")
SAFE_THEME_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,79}$")
REVIEWER_RE = re.compile(r"^[^\r\n]{2,120}$")
SOFTWARE_RENDERER_MARKERS = (
    "swiftshader",
    "llvmpipe",
    "software rasterizer",
    "microsoft basic render driver",
)
PACKAGE_ID = "com.mobilefork.hermesagent"
EXPECTED_RELEASE_SIGNER_SHA256 = (
    "2cbdb94d6081413055af1e903d4c1d6714300b4240402d1d4c8182fda777d14e"
)
TEST_PACKAGE_ID = f"{PACKAGE_ID}.test"
BENCHMARK_TEST_PACKAGE_ID = f"{PACKAGE_ID}.macrobenchmark"
MAIN_ACTIVITY = f"{PACKAGE_ID}/.MainActivity"
PHONE_UI_DRAWER_TAG = "HermesShellDrawerButton"
BUILD_VARIANT = "debug"
PERFORMANCE_BUILD_VARIANT = "benchmark"
LEGACY_LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.1"
ANDROIDX_BENCHMARK_COORDINATE = "androidx.benchmark:benchmark-macro-junit4:1.4.1"
REPORTING_PACKAGE_COMPILATION_MODE = "run-from-apk"
TARGET_COMPILER_FILTER = "speed"
BENCHMARK_CLASS = "com.mobilefork.hermesagent.macrobenchmark.HermesSettingsScrollBenchmark"
BENCHMARK_METHOD = "settingsListFling"
BENCHMARK_TEST_ID = f"{BENCHMARK_CLASS}#{BENCHMARK_METHOD}"
MIN_BENCHMARK_ITERATIONS = 5
MAX_BENCHMARK_ITERATIONS = 20
MAX_FRAME_DURATION_CPU_P95_MS = 50.0
MAX_FRAME_DURATION_CPU_P99_MS = 100.0
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{15,79}$")
BOOT_ID_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
AVD_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
LOCALIZED_DEVICE_OVERVIEW = {
    "en": "Device / Overview",
    "zh": "设备 / 概览",
    "es": "Dispositivo / Resumen",
    "de": "Gerät / Übersicht",
    "pt": "Dispositivo / Visão geral",
    "fr": "Appareil / Aperçu",
}
MEMORY_BUDGET_KB = {
    "phone-compact": {"total_pss_kb": 512 * 1024, "total_rss_kb": 768 * 1024},
    "tablet": {"total_pss_kb": 768 * 1024, "total_rss_kb": 1_024 * 1024},
}
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


class EvidenceError(ValueError):
    """Raised when release evidence fails closed."""


@dataclass(frozen=True)
class ArtifactSpec:
    model_id: str
    repository: str
    revision: str
    file_name: str
    runtime: str
    expected_bytes: int
    sha256: str
    required_llama_cpp_runtime_lane: str | None = None

    @property
    def backend(self) -> str:
        return {"litert-lm": "litert-lm", "llama.cpp": "llama.cpp"}[self.runtime]

    @property
    def evidence_path(self) -> PurePosixPath:
        return PurePosixPath("models") / f"{self.model_id}.json"


HISTORICAL_E4B_ARTIFACT = ArtifactSpec(
    model_id="gemma-4-e4b-litert-lm",
    repository="litert-community/gemma-4-E4B-it-litert-lm",
    revision="9695417f248178c63a9f318c6e0c56cb917cb837",
    file_name="gemma-4-E4B-it.litertlm",
    runtime="litert-lm",
    expected_bytes=3_654_467_584,
    sha256="f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
)
HISTORICAL_E4B_EVIDENCE_PATH = HISTORICAL_E4B_ARTIFACT.evidence_path

NANBEIGE_REPAIR_ARTIFACT = ArtifactSpec(
    model_id="nanbeige4.2-3b-q4-k-m",
    repository="Tdamre/Nanbeige4.2-3B-GGUF",
    revision="128d8e87d69f9c1a30c37e40530c69deda96475d",
    file_name="Nanbeige4.2-3B-Q4_K_M.gguf",
    runtime="llama.cpp",
    expected_bytes=2_574_807_840,
    sha256="99c7bfb88907f7eee0a04c4314f1c46bca391819478d8cb90b3e164f09576489",
    required_llama_cpp_runtime_lane="turboquant",
)

ISSUE8_TWELVE_B_MODEL_ID = "gemma-4-12b-litert-lm"
ISSUE8_TWELVE_B_REPOSITORY = "litert-community/gemma-4-12B-it-litert-lm"
ISSUE8_TWELVE_B_REVISION = "d7de8ec6dcf035c90999ff38560bf4c6eb45a947"
ISSUE8_TWELVE_B_FILE_NAME = "gemma-4-12B-it.litertlm"
ISSUE8_TWELVE_B_BYTES = 6_547_589_312
ISSUE8_TWELVE_B_SHA256 = "74fc29a10c20eb5b3ced6c389471a7994a0ffd657255b2a1c764262fb9054aef"
NOMINAL_SIXTEEN_GIB_BYTES = 16 * 1024**3
ISSUE8_INSTRUMENTATION_METHOD = (
    "NativeAppUiChatInstrumentedTest#"
    "mainActivityRunsIssueEightReadOnlyToolsBeforeAnyRemoteProviderRequest"
)
ISSUE16_INSTRUMENTATION_METHOD = (
    "LiveDebianSandboxInstrumentedTest#"
    "oneClickDebianRunsGuestBinariesWithoutWritableHostFallback"
)
GUEST_ONLY_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
PHYSICAL_ORDINARY_CHAT_PROMPT = "Reply exactly NANBEIGE_OK"
PHYSICAL_ORDINARY_CHAT_EXPECTED_REPLY = "NANBEIGE_OK"
PHYSICAL_STOP_CHAT_PROMPT = "Write a long numbered list, continuing until I press Stop."
PHYSICAL_STOP_TERMINAL_MESSAGE = "This reply was stopped by the user."
PHYSICAL_DEVICE_EMULATOR_MARKERS = (
    "emulator",
    "generic",
    "sdk_gphone",
    "goldfish",
    "ranchu",
    "cuttlefish",
    "vbox",
    "genymotion",
)
VISIBLE_THINK_MARKER_RE = re.compile(
    r"(?is)(?:</?\s*(?:think|analysis|reasoning)\b[^>]*>|"
    r"\[(?:/?think|/?analysis|/?reasoning)\])"
)
PHYSICAL_MODEL_PATH_ROOT = (
    f"/storage/emulated/0/Android/data/{PACKAGE_ID}/files/Download/models/"
)
PHYSICAL_STABLE_RUNTIME_EXECUTABLE = "libhermes_android_llama_server.so"
PHYSICAL_STABLE_RUNTIME_PORT = 18081
PHYSICAL_STABLE_RUNTIME_APK_ENTRIES = (
    "lib/arm64-v8a/libandroid-spawn.so",
    "lib/arm64-v8a/libc++_shared.so",
    "lib/arm64-v8a/libcrypto.so",
    "lib/arm64-v8a/libggml-base.so",
    "lib/arm64-v8a/libggml-cpu.so",
    "lib/arm64-v8a/libggml.so",
    f"lib/arm64-v8a/{PHYSICAL_STABLE_RUNTIME_EXECUTABLE}",
    "lib/arm64-v8a/libllama-common.so",
    "lib/arm64-v8a/libllama-server-impl.so",
    "lib/arm64-v8a/libllama.so",
    "lib/arm64-v8a/libmtmd.so",
    "lib/arm64-v8a/libssl.so",
)
PHYSICAL_STABLE_RUNTIME_SYSTEM_LIBRARIES = (
    "libc.so",
    "libm.so",
    "libdl.so",
)
PHYSICAL_STABLE_RUNTIME_ROLES = {
    "libandroid-spawn.so": "process-spawn-support",
    "libc++_shared.so": "cxx-runtime",
    "libcrypto.so": "tls-crypto",
    "libggml-base.so": "ggml-core",
    "libggml-cpu.so": "ggml-cpu-dlopen-backend",
    "libggml.so": "ggml-backend-loader",
    PHYSICAL_STABLE_RUNTIME_EXECUTABLE: "server-executable",
    "libllama-common.so": "llama-common",
    "libllama-server-impl.so": "server-implementation",
    "libllama.so": "llama-model-runtime",
    "libmtmd.so": "multimodal-runtime",
    "libssl.so": "tls-runtime",
}
PHYSICAL_STABLE_RUNTIME_DT_NEEDED = {
    "libandroid-spawn.so": ("libc++_shared.so", "libdl.so", "libc.so"),
    "libc++_shared.so": ("libc.so", "libm.so", "libdl.so"),
    "libcrypto.so": ("libdl.so", "libc.so"),
    "libggml-base.so": ("libm.so", "libdl.so", "libc++_shared.so", "libc.so"),
    "libggml-cpu.so": (
        "libggml-base.so",
        "libc++_shared.so",
        "libm.so",
        "libdl.so",
        "libc.so",
    ),
    "libggml.so": ("libggml-base.so", "libc++_shared.so", "libdl.so", "libc.so"),
    PHYSICAL_STABLE_RUNTIME_EXECUTABLE: ("libllama-server-impl.so", "libc.so"),
    "libllama-common.so": (
        "libllama.so",
        "libssl.so",
        "libcrypto.so",
        "libggml.so",
        "libggml-base.so",
        "libc.so",
        "libc++_shared.so",
        "libm.so",
        "libdl.so",
    ),
    "libllama-server-impl.so": (
        "libandroid-spawn.so",
        "libc.so",
        "libllama-common.so",
        "libmtmd.so",
        "libllama.so",
        "libggml.so",
        "libggml-base.so",
        "libssl.so",
        "libcrypto.so",
        "libc++_shared.so",
        "libm.so",
        "libdl.so",
    ),
    "libllama.so": (
        "libggml.so",
        "libggml-base.so",
        "libc++_shared.so",
        "libm.so",
        "libdl.so",
        "libc.so",
    ),
    "libmtmd.so": (
        "libandroid-spawn.so",
        "libllama.so",
        "libggml.so",
        "libggml-base.so",
        "libc.so",
        "libc++_shared.so",
        "libm.so",
        "libdl.so",
    ),
    "libssl.so": ("libcrypto.so", "libc.so"),
}
PHYSICAL_STABLE_RUNTIME_LOADER_ERROR_RE = re.compile(
    r"(?:cannot link executable|dlopen failed|cannot locate symbol|"
    r"library [^\r\n]+ not found|not accessible for the namespace)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class SourceTreeIdentity:
    algorithm: str
    digest: str
    file_count: int
    git_object_format: str
    excluded_prefix: str


@dataclass(frozen=True)
class UiEvidenceSourceContract:
    app_sections: tuple[str, ...]
    settings_pages: tuple[str, ...]
    device_pages: tuple[str, ...]
    recommended_model_ids: tuple[str, ...]


@dataclass(frozen=True)
class EvidenceFile:
    path: str
    bytes: int
    sha256: str


@dataclass(frozen=True)
class PhysicalNanbeigeRepairEvidence:
    candidate_apk_sha256: str
    candidate_apk_bytes: int
    apk_signer_sha256: str
    device_model: str
    device_manufacturer: str
    adb_serial_sha256: str
    model_device_path: str


@dataclass(frozen=True)
class ValidatedEvidence:
    files: tuple[EvidenceFile, ...]
    model_count: int
    ui_capture_count: int
    performance_record_count: int
    device_models: tuple[str, ...]
    ui_candidate_apk_sha256: str
    ui_instrumentation_apk_sha256: str
    benchmark_target_apk_sha256: str
    benchmark_test_apk_sha256: str
    evidence_run_id: str
    comprehensive_ui_capture_count: int
    launch_theme_capture_count: int
    launch_theme_review_count: int
    historical_issue8_model_count: int
    issue8_tool_and_preflight_count: int
    issue16_debian_sandbox_count: int
    required_app_sections: tuple[str, ...] = ()
    required_settings_pages: tuple[str, ...] = ()
    required_device_pages: tuple[str, ...] = ()
    required_recommended_model_ids: tuple[str, ...] = ()
    physical_nanbeige_repair_count: int = 0
    physical_candidate_apk_sha256: str = ""
    physical_candidate_apk_bytes: int = 0
    physical_apk_signer_sha256: str = ""
    physical_device_models: tuple[str, ...] = ()
    physical_device_manufacturers: tuple[str, ...] = ()
    physical_adb_serial_sha256: str = ""
    physical_nanbeige_model_path: str = ""


@dataclass(frozen=True)
class DecodedPng:
    width: int
    height: int
    content_pixel_sha256: str
    sampled_unique_colors: int


def _read_ui_contract_source(repo_root: Path, relative: PurePosixPath) -> str:
    path = repo_root / Path(relative.as_posix())
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise EvidenceError(f"Cannot read the v3 UI source contract {relative}: {exc}") from exc


def _balanced_kotlin_region(source: str, opening_index: int, opener: str, closer: str, context: str) -> str:
    """Return one Kotlin delimiter region while ignoring strings and comments."""

    if opening_index < 0 or opening_index >= len(source) or source[opening_index] != opener:
        raise EvidenceError(f"{context} has no opening {opener!r}")
    depth = 0
    index = opening_index
    quote = ""
    block_comment_depth = 0
    while index < len(source):
        if block_comment_depth:
            if source.startswith("/*", index):
                block_comment_depth += 1
                index += 2
            elif source.startswith("*/", index):
                block_comment_depth -= 1
                index += 2
            else:
                index += 1
            continue
        if quote:
            if quote == '"""':
                if source.startswith(quote, index):
                    quote = ""
                    index += 3
                else:
                    index += 1
                continue
            character = source[index]
            if character == "\\":
                index += 2
            elif character == quote:
                quote = ""
                index += 1
            else:
                index += 1
            continue
        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            index = len(source) if newline < 0 else newline + 1
            continue
        if source.startswith("/*", index):
            block_comment_depth = 1
            index += 2
            continue
        if source.startswith('"""', index):
            quote = '"""'
            index += 3
            continue
        character = source[index]
        if character in {'"', "'"}:
            quote = character
            index += 1
            continue
        if character == opener:
            depth += 1
        elif character == closer:
            depth -= 1
            if depth == 0:
                return source[opening_index : index + 1]
            if depth < 0:
                break
        index += 1
    raise EvidenceError(f"{context} has an unterminated {opener}{closer} region")


def _kotlin_enum_entries(source: str, enum_name: str, context: str) -> tuple[str, ...]:
    declaration = re.search(rf"\benum\s+class\s+{re.escape(enum_name)}\b", source)
    if declaration is None:
        raise EvidenceError(f"{context} does not declare enum class {enum_name}")
    opening = source.find("{", declaration.end())
    body = _balanced_kotlin_region(source, opening, "{", "}", context)[1:-1]
    entries_text, _, _ = body.partition(";")
    entries = tuple(
        match.group(1)
        for match in re.finditer(
            r"(?m)^\s*([A-Z][A-Za-z0-9_]*)\s*(?:\([^\n]*\))?\s*,?\s*$",
            entries_text,
        )
    )
    if not entries or len(entries) != len(set(entries)):
        raise EvidenceError(f"{context} enum {enum_name} entries are empty or duplicated")
    return entries


def _recommended_model_ids(source: str, context: str) -> tuple[str, ...]:
    declaration = re.search(r"\bval\s+recommendedModelPresets\s*=\s*listOf\s*\(", source)
    if declaration is None:
        raise EvidenceError(f"{context} does not declare recommendedModelPresets as a source list")
    opening = source.find("(", declaration.start())
    region = _balanced_kotlin_region(source, opening, "(", ")", context)
    # Recommended cards may use the verified factory so repository/revision/size/hash stay bound
    # to VerifiedLocalModelArtifacts instead of being duplicated as drifting literals here.
    preset_declaration_count = len(
        re.findall(
            r"\b(?:RecommendedLocalModelPreset|verifiedRecommendedModelPreset)\s*\(",
            region,
        )
    )
    model_ids = tuple(
        re.findall(r'(?m)^\s*id\s*=\s*"([a-z0-9][a-z0-9._-]*)"\s*,\s*$', region)
    )
    if (
        not model_ids
        or preset_declaration_count != len(model_ids)
        or len(model_ids) != len(set(model_ids))
    ):
        raise EvidenceError(
            f"{context} recommendedModelPresets must have one unique literal safe id per preset"
        )
    return model_ids


def _require_ui_producer_relation(source: str, pattern: str, relation: str) -> None:
    if re.search(pattern, source, flags=re.DOTALL) is None:
        raise EvidenceError(f"The headed UI producer no longer proves the source-derived {relation}")


def load_ui_evidence_source_contract(repo_root: Path | None = None) -> UiEvidenceSourceContract:
    """Derive the closed v3 inventory from production source and its headed producer."""

    root = (repo_root or Path(__file__).resolve().parents[1]).resolve()
    app_sections = _kotlin_enum_entries(
        _read_ui_contract_source(root, APP_SECTION_SOURCE),
        "AppSection",
        APP_SECTION_SOURCE.as_posix(),
    )
    settings_pages = _kotlin_enum_entries(
        _read_ui_contract_source(root, SETTINGS_PAGE_SOURCE),
        "SettingsPage",
        SETTINGS_PAGE_SOURCE.as_posix(),
    )
    all_device_pages = _kotlin_enum_entries(
        _read_ui_contract_source(root, DEVICE_PAGE_SOURCE),
        "DevicePage",
        DEVICE_PAGE_SOURCE.as_posix(),
    )
    if all_device_pages.count("Overview") != 1:
        raise EvidenceError("DevicePage must contain exactly one Overview entry for v3 evidence exclusion")
    device_pages = tuple(page for page in all_device_pages if page != "Overview")
    if not device_pages:
        raise EvidenceError("DevicePage has no non-Overview pages to certify")
    recommended_model_ids = _recommended_model_ids(
        _read_ui_contract_source(root, RECOMMENDED_MODEL_SOURCE),
        RECOMMENDED_MODEL_SOURCE.as_posix(),
    )

    producer = _read_ui_contract_source(root, UI_EVIDENCE_PRODUCER_SOURCE)
    _require_ui_producer_relation(
        producer,
        r'AppSection\.entries\.forEachIndexed\s*\{\s*index,\s*section\s*->.*?'
        r'identity\s*=\s*"section:\$\{section\.name\}".*?'
        r'coverageKind\s*=\s*"app-section".*?pageId\s*=\s*section\.name',
        "AppSection identity and page-ID relationship",
    )
    _require_ui_producer_relation(
        producer,
        r'SettingsPage\.entries\.forEachIndexed\s*\{\s*index,\s*page\s*->.*?'
        r'identity\s*=\s*"settings:\$\{page\.name\}".*?'
        r'coverageKind\s*=\s*"settings-subpage".*?'
        r'pageId\s*=\s*"Settings\.\$\{page\.name\}"',
        "SettingsPage identity and page-ID relationship",
    )
    _require_ui_producer_relation(
        producer,
        r'DevicePage\.entries\.filterNot\s*\{\s*it\s*==\s*DevicePage\.Overview\s*\}'
        r'\.forEachIndexed\s*\{\s*index,\s*page\s*->.*?val\s+pageName\s*=\s*page\.name.*?'
        r'identity\s*=\s*"device:\$\{page\.name\}".*?'
        r'coverageKind\s*=\s*"device-subpage".*?pageId\s*=\s*pageName',
        "non-Overview DevicePage identity and page-ID relationship",
    )
    _require_ui_producer_relation(
        producer,
        r'val\s+targetPresets\s*=\s*LocalModelDownloadsViewModel\.recommendedModelPresets.*?'
        r'AppLanguage\.entries\.forEach\s*\{\s*language\s*->.*?'
        r'targetPresets\.forEach\s*\{\s*preset\s*->.*?'
        r'identity\s*=\s*"localized-model:\$\{language\.tag\}:\$\{preset\.id\}".*?'
        r'coverageKind\s*=\s*"six-language-recommended-model".*?'
        r'pageId\s*=\s*"Settings\.Models\.\$\{preset\.id\}"',
        "recommended-model identity and Settings.Models page-ID relationship",
    )
    return UiEvidenceSourceContract(
        app_sections=app_sections,
        settings_pages=settings_pages,
        device_pages=device_pages,
        recommended_model_ids=recommended_model_ids,
    )


def _run_git(repo_root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            ["git", *args],
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=check,
        )
    except FileNotFoundError as exc:
        raise EvidenceError("git is required to bind release evidence to tracked source") from exc
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode("utf-8", errors="replace").strip()
        raise EvidenceError(f"git {' '.join(args)} failed: {detail}") from exc


def validate_tag(tag: str) -> str:
    normalized = tag.strip()
    if not TAG_RE.fullmatch(normalized):
        raise EvidenceError(f"Android evidence tag must be a v0 SemVer tag, got {tag!r}")
    return normalized


def android_identity_for_tag(tag: str) -> tuple[str, int]:
    normalized = validate_tag(tag)
    match = re.fullmatch(
        r"v(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(?:\.([0-9]+))?)?",
        normalized,
    )
    if match is None:  # pragma: no cover - validate_tag and the regex intentionally agree
        raise EvidenceError(f"Unable to derive Android identity from tag {tag}")
    major, minor, patch = (int(match.group(index)) for index in (1, 2, 3))
    prerelease = match.group(4) or ""
    prerelease_sequence = min(int(match.group(5) or "0"), 9)
    rank = {"alpha": 1, "beta": 2, "rc": 3, "": 9}[prerelease]
    version_code = major * 1_000_000 + minor * 10_000 + patch * 100 + rank * 10 + prerelease_sequence
    return normalized.removeprefix("v"), version_code


def _tag_version_tuple(tag: str) -> tuple[int, int, int]:
    normalized = validate_tag(tag)
    match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)(?:-(?:alpha|beta|rc)(?:\.[0-9]+)?)?", normalized)
    if match is None:  # pragma: no cover - validate_tag and the regex intentionally agree
        raise EvidenceError(f"Unable to derive release-evidence policy from tag {tag}")
    return tuple(int(match.group(index)) for index in (1, 2, 3))


def requires_comprehensive_ui_evidence(tag: str) -> bool:
    """Return whether the tag is subject to the v3 headed UI/launch contract."""

    return _tag_version_tuple(tag) >= COMPREHENSIVE_UI_EVIDENCE_MIN_VERSION


def requires_physical_nanbeige_repair_evidence(tag: str) -> bool:
    """Return whether the tag requires the physical ARM64 Nanbeige repair gate."""

    return _tag_version_tuple(tag) >= PHYSICAL_NANBEIGE_REPAIR_MIN_VERSION


def litertlm_coordinate_for_tag(tag: str) -> str:
    """Return the release dependency required by the tag's immutable evidence contract."""

    if _tag_version_tuple(tag) >= LITERTLM_0161_MIN_VERSION:
        return LITERTLM_COORDINATE
    return LEGACY_LITERTLM_COORDINATE


def manifest_schema_for_tag(tag: str) -> str:
    return MANIFEST_SCHEMA_V3 if requires_comprehensive_ui_evidence(tag) else MANIFEST_SCHEMA_V2


def parse_registered_model_matrix(source: str) -> tuple[ArtifactSpec, ...]:
    """Parse the structured Artifact literals used by VerifiedLocalModelArtifacts.

    The parser deliberately reads the runtime registry rather than maintaining a
    second release-model snapshot in Python. Tests exercise this parser with
    synthetic registries whose entries and ordering vary.
    """

    code_mask = _kotlin_code_mask(source)
    object_declaration = re.compile(r"\bobject\s+VerifiedLocalModelArtifacts\s*\{")
    object_matches = list(object_declaration.finditer(code_mask))
    if len(object_matches) != 1:
        raise EvidenceError("Android model registry must define exactly one VerifiedLocalModelArtifacts object")
    object_open = code_mask.find("{", object_matches[0].start())
    object_close = _matching_kotlin_brace(code_mask, object_open)
    object_mask = code_mask[object_open + 1 : object_close]
    declaration_names = list(re.finditer(r"\b(?:val|var|fun)\s+releaseMatrix\b", object_mask))
    if len(declaration_names) != 1:
        raise EvidenceError(
            "VerifiedLocalModelArtifacts must contain exactly one releaseMatrix declaration"
        )
    declaration = re.compile(
        r"\bval\s+releaseMatrix\s*:\s*List\s*<\s*Artifact\s*>\s*=\s*listOf\s*\("
    )
    matches = list(declaration.finditer(object_mask))
    if len(matches) != 1:
        raise EvidenceError(
            "Android model registry must contain exactly one explicitly typed literal releaseMatrix listOf"
        )
    declaration_global_start = object_open + 1 + matches[0].start()
    if declaration_global_start != object_open + 1 + declaration_names[0].start():
        raise EvidenceError("Android releaseMatrix declaration is not the canonical literal property")
    matrix_open = object_open + matches[0].end()
    matrix_body, matrix_end = _kotlin_parenthesized_body(source, matrix_open)
    if not re.match(r"^[ \t]*;", source[matrix_end:]):
        raise EvidenceError(
            "Android releaseMatrix literal must end with an explicit semicolon and no continuation"
        )
    entries = _split_kotlin_top_level_arguments(matrix_body)
    if not entries:
        raise EvidenceError("Android releaseMatrix contains no Artifact entries")

    blocks: list[str] = []
    for entry in entries:
        artifact_match = re.match(r"^Artifact\s*\(", entry)
        if not artifact_match:
            raise EvidenceError(
                "Every top-level Android releaseMatrix entry must be one literal Artifact(...); "
                f"found {entry[:80]!r}"
            )
        body, end = _kotlin_parenthesized_body(entry, artifact_match.end() - 1)
        if entry[end:].strip():
            raise EvidenceError(f"Unexpected tokens after releaseMatrix Artifact: {entry[end:][:80]!r}")
        blocks.append(body)

    artifacts: list[ArtifactSpec] = []
    for block in blocks:
        raw_fields: dict[str, str] = {}
        for argument in _split_kotlin_top_level_arguments(block):
            assignment = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*)\s*=\s*(.+)", argument, re.DOTALL)
            if not assignment or assignment.group(1) in raw_fields:
                raise EvidenceError(f"Model registry Artifact has an invalid/duplicate argument: {argument!r}")
            raw_fields[assignment.group(1)] = assignment.group(2).strip()
        required_fields = {
            "modelId", "repoId", "revision", "fileName", "runtime", "expectedBytes",
            "sha256", "validationEvidence", "remoteManifestMatches",
        }
        optional_fields = {"requiredLlamaCppRuntimeLane"}
        if not required_fields.issubset(raw_fields) or set(raw_fields) - required_fields - optional_fields:
            raise EvidenceError(
                "Model registry Artifact fields do not match the canonical contract; "
                f"missing={sorted(required_fields - set(raw_fields))}, "
                f"unexpected={sorted(set(raw_fields) - required_fields - optional_fields)}"
            )

        def string_field(name: str) -> str:
            match = re.fullmatch(r'"([^"\\\r\n]+)"', raw_fields[name])
            if not match:
                raise EvidenceError(f"Model registry Artifact field {name} must be one literal string")
            return match.group(1)

        bytes_match = re.fullmatch(r"([0-9][0-9_]*)L?", raw_fields["expectedBytes"])
        if not bytes_match:
            raise EvidenceError("Model registry Artifact expectedBytes must be one numeric literal")
        if raw_fields["remoteManifestMatches"] not in {"true", "false"}:
            raise EvidenceError("Model registry Artifact remoteManifestMatches must be a boolean literal")
        string_field("validationEvidence")
        required_lane = (
            string_field("requiredLlamaCppRuntimeLane")
            if "requiredLlamaCppRuntimeLane" in raw_fields
            else None
        )
        if required_lane is not None and required_lane not in {"stable", "turboquant"}:
            raise EvidenceError(
                "Model registry Artifact requiredLlamaCppRuntimeLane must be 'stable' or 'turboquant'"
            )
        artifact = ArtifactSpec(
            model_id=string_field("modelId"),
            repository=string_field("repoId"),
            revision=string_field("revision").lower(),
            file_name=string_field("fileName"),
            runtime=string_field("runtime"),
            expected_bytes=int(bytes_match.group(1).replace("_", "")),
            sha256=string_field("sha256").lower(),
            required_llama_cpp_runtime_lane=required_lane,
        )
        if artifact.required_llama_cpp_runtime_lane is not None and artifact.runtime != "llama.cpp":
            raise EvidenceError(
                "Model registry Artifact requiredLlamaCppRuntimeLane is valid only for llama.cpp"
            )
        _validate_artifact_spec(artifact)
        artifacts.append(artifact)

    model_ids = [artifact.model_id for artifact in artifacts]
    file_names = [artifact.file_name.casefold() for artifact in artifacts]
    if len(set(model_ids)) != len(model_ids):
        raise EvidenceError("Android model registry contains duplicate modelId values")
    if len(set(file_names)) != len(file_names):
        raise EvidenceError("Android model registry contains duplicate fileName values")
    return tuple(sorted(artifacts, key=lambda artifact: artifact.model_id))


def _kotlin_code_mask(source: str) -> str:
    """Preserve Kotlin code positions while blanking comments and literals."""

    masked = list(source)
    index = 0
    state = "code"
    block_depth = 0
    while index < len(source):
        following = source[index + 1] if index + 1 < len(source) else ""
        triple = source[index : index + 3]
        if state == "code":
            if source[index] == "/" and following == "/":
                masked[index] = masked[index + 1] = " "
                state = "line-comment"
                index += 2
                continue
            if source[index] == "/" and following == "*":
                masked[index] = masked[index + 1] = " "
                state = "block-comment"
                block_depth = 1
                index += 2
                continue
            if triple == '\"\"\"':
                masked[index : index + 3] = "   "
                state = "triple-string"
                index += 3
                continue
            if source[index] == '"':
                masked[index] = " "
                state = "string"
            elif source[index] == "'":
                masked[index] = " "
                state = "char"
            index += 1
            continue
        if state == "line-comment":
            if source[index] == "\n":
                state = "code"
            else:
                masked[index] = " "
            index += 1
            continue
        if state == "block-comment":
            if source[index] == "/" and following == "*":
                masked[index] = masked[index + 1] = " "
                block_depth += 1
                index += 2
            elif source[index] == "*" and following == "/":
                masked[index] = masked[index + 1] = " "
                block_depth -= 1
                index += 2
                if block_depth == 0:
                    state = "code"
            else:
                if source[index] != "\n":
                    masked[index] = " "
                index += 1
            continue
        if state == "triple-string":
            if triple == '\"\"\"':
                masked[index : index + 3] = "   "
                state = "code"
                index += 3
            else:
                if source[index] != "\n":
                    masked[index] = " "
                index += 1
            continue
        escaped = False
        back = index - 1
        while back >= 0 and source[back] == "\\":
            escaped = not escaped
            back -= 1
        terminator = '"' if state == "string" else "'"
        if source[index] == terminator and not escaped:
            masked[index] = " "
            state = "code"
        elif source[index] != "\n":
            masked[index] = " "
        index += 1
    if state in {"block-comment", "triple-string", "string", "char"}:
        raise EvidenceError(f"Unterminated Kotlin {state} in Android model registry")
    return "".join(masked)


def _matching_kotlin_brace(mask: str, open_index: int) -> int:
    if open_index < 0 or mask[open_index] != "{":
        raise EvidenceError("VerifiedLocalModelArtifacts object has no opening brace")
    depth = 0
    for index in range(open_index, len(mask)):
        if mask[index] == "{":
            depth += 1
        elif mask[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    raise EvidenceError("VerifiedLocalModelArtifacts object has unbalanced braces")


def _kotlin_parenthesized_body(source: str, open_index: int) -> tuple[str, int]:
    """Return one balanced Kotlin call body and the index immediately after its close paren."""

    if open_index >= len(source) or source[open_index] != "(":
        raise EvidenceError("Internal model-registry parser error: expected an opening parenthesis")
    depth = 0
    index = open_index
    in_string = False
    in_char = False
    escaped = False
    line_comment = False
    block_comment_depth = 0
    while index < len(source):
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
            index += 1
            continue
        if block_comment_depth:
            if char == "/" and following == "*":
                block_comment_depth += 1
                index += 2
                continue
            if char == "*" and following == "/":
                block_comment_depth -= 1
                index += 2
                continue
            index += 1
            continue
        if in_string or in_char:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif in_string and char == '"':
                in_string = False
            elif in_char and char == "'":
                in_char = False
            index += 1
            continue
        if char == "/" and following == "/":
            line_comment = True
            index += 2
            continue
        if char == "/" and following == "*":
            block_comment_depth = 1
            index += 2
            continue
        if char == '"':
            in_string = True
        elif char == "'":
            in_char = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[open_index + 1 : index], index + 1
            if depth < 0:
                break
        index += 1
    raise EvidenceError("Unbalanced parentheses in Android releaseMatrix")


def _split_kotlin_top_level_arguments(body: str) -> list[str]:
    entries: list[str] = []
    start = 0
    depths = {"(": 0, "[": 0, "{": 0}
    closes = {")": "(", "]": "[", "}": "{"}
    in_string = False
    in_char = False
    escaped = False
    line_comment = False
    block_comment_depth = 0
    index = 0
    while index < len(body):
        char = body[index]
        following = body[index + 1] if index + 1 < len(body) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
            index += 1
            continue
        if block_comment_depth:
            if char == "/" and following == "*":
                block_comment_depth += 1
                index += 2
                continue
            if char == "*" and following == "/":
                block_comment_depth -= 1
                index += 2
                continue
            index += 1
            continue
        if in_string or in_char:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif in_string and char == '"':
                in_string = False
            elif in_char and char == "'":
                in_char = False
            index += 1
            continue
        if char == "/" and following == "/":
            line_comment = True
            index += 2
            continue
        if char == "/" and following == "*":
            block_comment_depth = 1
            index += 2
            continue
        if char == '"':
            in_string = True
        elif char == "'":
            in_char = True
        elif char in depths:
            depths[char] += 1
        elif char in closes:
            opener = closes[char]
            depths[opener] -= 1
            if depths[opener] < 0:
                raise EvidenceError("Unbalanced delimiters in Android releaseMatrix")
        elif char == "," and all(depth == 0 for depth in depths.values()):
            entry = body[start:index].strip()
            if entry:
                entries.append(entry)
            start = index + 1
        index += 1
    if in_string or in_char or line_comment or block_comment_depth or any(depths.values()):
        raise EvidenceError("Unbalanced syntax in Android releaseMatrix")
    tail = body[start:].strip()
    if tail:
        entries.append(tail)
    return entries


def load_registered_model_matrix(path: Path) -> tuple[ArtifactSpec, ...]:
    if not path.is_file():
        raise EvidenceError(f"Android model registry does not exist: {path}")
    return parse_registered_model_matrix(path.read_text(encoding="utf-8"))


def _validate_artifact_spec(artifact: ArtifactSpec) -> None:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", artifact.model_id):
        raise EvidenceError(f"Unsafe modelId in Android model registry: {artifact.model_id!r}")
    if artifact.runtime not in {"litert-lm", "llama.cpp"}:
        raise EvidenceError(f"Unsupported registered Android runtime: {artifact.runtime!r}")
    if not artifact.repository or artifact.repository.count("/") != 1:
        raise EvidenceError(f"Invalid publisher repository: {artifact.repository!r}")
    if not HEX_40_RE.fullmatch(artifact.revision):
        raise EvidenceError(f"Model revision must be an exact 40-hex commit: {artifact.revision!r}")
    if artifact.expected_bytes <= 0:
        raise EvidenceError(f"Model expectedBytes must be positive: {artifact.file_name}")
    if not HEX_64_RE.fullmatch(artifact.sha256):
        raise EvidenceError(f"Model SHA-256 must be exact lowercase hex: {artifact.file_name}")
    if (
        not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", artifact.file_name)
        or "/" in artifact.file_name
        or "\\" in artifact.file_name
        or any(ord(character) < 32 for character in artifact.file_name)
    ):
        raise EvidenceError(
            f"Registered {artifact.runtime} artifact has an unsafe portable file name: "
            f"{artifact.file_name!r}"
        )
    suffix = PurePosixPath(artifact.file_name).suffix.casefold()
    expected_suffix = ".litertlm" if artifact.runtime == "litert-lm" else ".gguf"
    if suffix != expected_suffix:
        raise EvidenceError(
            f"Registered {artifact.runtime} artifact has an invalid file name: {artifact.file_name!r}"
        )


def source_digest_from_entries(
    entries: Iterable[tuple[str, str, str, str]],
    *,
    object_format: str,
) -> SourceTreeIdentity:
    """Hash sorted Git tree entries, excluding all committed evidence."""

    normalized: list[tuple[str, str, str, str]] = []
    for mode, entry_type, object_id, raw_path in entries:
        path = PurePosixPath(raw_path)
        if path == EVIDENCE_PREFIX or EVIDENCE_PREFIX in path.parents:
            continue
        if path.is_absolute() or ".." in path.parts:
            raise EvidenceError(f"Unsafe tracked path while calculating source digest: {raw_path!r}")
        normalized.append((mode, entry_type, object_id.lower(), path.as_posix()))
    if not normalized:
        raise EvidenceError("Tracked source digest would contain no files")

    digest = hashlib.sha256()
    for mode, entry_type, object_id, path in sorted(normalized, key=lambda entry: entry[3]):
        for value in (mode, entry_type, object_id, path):
            encoded = value.encode("utf-8")
            digest.update(struct.pack(">Q", len(encoded)))
            digest.update(encoded)
    return SourceTreeIdentity(
        algorithm=SOURCE_DIGEST_ALGORITHM,
        digest=digest.hexdigest(),
        file_count=len(normalized),
        git_object_format=object_format,
        excluded_prefix=f"{EVIDENCE_PREFIX.as_posix()}/",
    )


def git_source_tree_identity(repo_root: Path) -> SourceTreeIdentity:
    raw = _run_git(repo_root, "ls-tree", "-r", "-z", "HEAD").stdout
    entries: list[tuple[str, str, str, str]] = []
    for record in raw.split(b"\0"):
        if not record:
            continue
        try:
            metadata, path_bytes = record.split(b"\t", 1)
            mode, entry_type, object_id = metadata.decode("ascii").split(" ", 2)
            path = path_bytes.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise EvidenceError("Unable to parse git ls-tree output") from exc
        entries.append((mode, entry_type, object_id, path))
    object_format = _run_git(repo_root, "rev-parse", "--show-object-format").stdout.decode().strip()
    blob_content_ids = _git_blob_content_identities(
        repo_root,
        {
            object_id
            for mode, entry_type, object_id, path in entries
            if entry_type == "blob"
            and not (
                PurePosixPath(path) == EVIDENCE_PREFIX
                or EVIDENCE_PREFIX in PurePosixPath(path).parents
            )
        },
    )
    content_entries = [
        (
            mode,
            entry_type,
            blob_content_ids[object_id]
            if entry_type == "blob" and object_id in blob_content_ids
            else f"git-{object_format}:{object_id}",
            path,
        )
        for mode, entry_type, object_id, path in entries
    ]
    return source_digest_from_entries(content_entries, object_format=object_format)


def _git_blob_content_identities(repo_root: Path, object_ids: set[str]) -> dict[str, str]:
    """Hash Git blob bytes through one persistent cat-file process."""

    if not object_ids:
        return {}
    try:
        process = subprocess.Popen(
            ["git", "cat-file", "--batch"],
            cwd=repo_root,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        raise EvidenceError("git is required to hash tracked source blobs") from exc
    if process.stdin is None or process.stdout is None or process.stderr is None:  # pragma: no cover
        raise EvidenceError("Unable to open git cat-file pipes")

    identities: dict[str, str] = {}
    try:
        for requested_id in sorted(object_ids):
            process.stdin.write(requested_id.encode("ascii") + b"\n")
            process.stdin.flush()
            header = process.stdout.readline()
            fields = header.rstrip(b"\n").split(b" ")
            if len(fields) != 3 or fields[1] != b"blob":
                raise EvidenceError(f"git cat-file did not return blob content for {requested_id}")
            try:
                size = int(fields[2])
            except ValueError as exc:
                raise EvidenceError(f"git cat-file returned an invalid size for {requested_id}") from exc
            digest = hashlib.sha256()
            remaining = size
            while remaining:
                chunk = process.stdout.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise EvidenceError(f"git cat-file truncated blob {requested_id}")
                digest.update(chunk)
                remaining -= len(chunk)
            if process.stdout.read(1) != b"\n":
                raise EvidenceError(f"git cat-file returned a malformed blob delimiter for {requested_id}")
            identities[requested_id] = f"sha256:{digest.hexdigest()}"
        process.stdin.close()
        return_code = process.wait(timeout=60)
        if return_code != 0:
            detail = process.stderr.read().decode("utf-8", errors="replace").strip()
            raise EvidenceError(f"git cat-file failed while hashing tracked source: {detail}")
    finally:
        if not process.stdin.closed:
            process.stdin.close()
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=5)
    return identities


def _status_paths(repo_root: Path) -> list[str]:
    raw = _run_git(
        repo_root,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
    ).stdout
    tokens = raw.split(b"\0")
    paths: list[str] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        index += 1
        if not token:
            continue
        if len(token) < 4:
            raise EvidenceError("Unable to parse git status output")
        status = token[:2].decode("ascii", errors="replace")
        path = token[3:].decode("utf-8", errors="strict")
        paths.append(path)
        if "R" in status or "C" in status:
            if index >= len(tokens) or not tokens[index]:
                raise EvidenceError("Unable to parse renamed path in git status output")
            paths.append(tokens[index].decode("utf-8", errors="strict"))
            index += 1
    return paths


def require_source_clean_for_create(repo_root: Path, evidence_dir: Path) -> None:
    evidence_relative = evidence_dir.resolve().relative_to(repo_root.resolve()).as_posix().rstrip("/")
    dirty_source = [
        path
        for path in _status_paths(repo_root)
        if path != evidence_relative and not path.startswith(f"{evidence_relative}/")
    ]
    if dirty_source:
        shown = ", ".join(sorted(dirty_source)[:10])
        raise EvidenceError(
            "Commit the exact tested source before creating evidence; "
            f"non-evidence changes remain: {shown}"
        )


def require_clean_worktree(repo_root: Path) -> None:
    dirty = _status_paths(repo_root)
    if dirty:
        shown = ", ".join(sorted(dirty)[:10])
        raise EvidenceError(f"Release evidence verification requires a clean worktree: {shown}")


def require_tag_points_to_head(repo_root: Path, tag: str) -> None:
    tag_type = _run_git(
        repo_root,
        "cat-file",
        "-t",
        f"refs/tags/{tag}",
    ).stdout.decode("ascii", errors="strict").strip()
    if tag_type != "tag":
        raise EvidenceError(f"Tag {tag} must be an annotated tag object, got {tag_type!r}")
    tag_commit = _run_git(
        repo_root,
        "rev-parse",
        "--verify",
        f"refs/tags/{tag}^{{commit}}",
    ).stdout.strip()
    head_commit = _run_git(repo_root, "rev-parse", "--verify", "HEAD^{commit}").stdout.strip()
    if tag_commit != head_commit:
        raise EvidenceError(f"Tag {tag} does not point to the checked-out evidence commit")


def _json_object(path: Path) -> dict[str, Any]:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError(f"duplicate JSON key {key!r}")
            result[key] = value
        return result

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, EvidenceError) as exc:
        raise EvidenceError(f"Invalid JSON evidence file {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvidenceError(f"Evidence JSON must contain an object: {path}")
    return value


def _required_string(value: Mapping[str, Any], field: str, context: str) -> str:
    result = value.get(field)
    if not isinstance(result, str) or not result.strip():
        raise EvidenceError(f"{context}.{field} must be a nonblank string")
    return result.strip()


def _required_bool(value: Mapping[str, Any], field: str, context: str) -> bool:
    result = value.get(field)
    if not isinstance(result, bool):
        raise EvidenceError(f"{context}.{field} must be a boolean")
    return result


def _number(value: Mapping[str, Any], field: str, context: str, *, positive: bool = False) -> float:
    result = value.get(field)
    if isinstance(result, bool) or not isinstance(result, (int, float)):
        raise EvidenceError(f"{context}.{field} must be numeric")
    numeric = float(result)
    if not math.isfinite(numeric) or (positive and numeric <= 0):
        qualifier = "positive and finite" if positive else "finite"
        raise EvidenceError(f"{context}.{field} must be {qualifier}")
    return numeric


def _integer(value: Mapping[str, Any], field: str, context: str, *, positive: bool = False) -> int:
    result = value.get(field)
    if isinstance(result, bool) or not isinstance(result, int):
        raise EvidenceError(f"{context}.{field} must be an integer")
    if positive and result <= 0:
        raise EvidenceError(f"{context}.{field} must be positive")
    return result


def _nested_object(value: Mapping[str, Any], field: str, context: str) -> dict[str, Any]:
    result = value.get(field)
    if not isinstance(result, dict):
        raise EvidenceError(f"{context}.{field} must be an object")
    return result


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _decode_png(path: Path) -> DecodedPng:
    content = path.read_bytes()
    if len(content) < 33 or content[:8] != b"\x89PNG\r\n\x1a\n":
        raise EvidenceError(f"UI screenshot is not a PNG: {path}")
    offset = 8
    dimensions: tuple[int, int] | None = None
    bit_depth: int | None = None
    color_type: int | None = None
    interlace: int | None = None
    image_data = bytearray()
    saw_image_data = False
    saw_end = False
    chunk_index = 0
    while offset < len(content):
        if offset + 12 > len(content):
            raise EvidenceError(f"UI screenshot has a truncated PNG chunk: {path}")
        length = struct.unpack(">I", content[offset : offset + 4])[0]
        kind = content[offset + 4 : offset + 8]
        payload_start = offset + 8
        payload_end = payload_start + length
        crc_end = payload_end + 4
        if crc_end > len(content):
            raise EvidenceError(f"UI screenshot has a truncated PNG payload: {path}")
        payload = content[payload_start:payload_end]
        recorded_crc = struct.unpack(">I", content[payload_end:crc_end])[0]
        calculated_crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
        if recorded_crc != calculated_crc:
            raise EvidenceError(f"UI screenshot has a bad PNG chunk checksum: {path}")
        if chunk_index == 0:
            if kind != b"IHDR" or length != 13:
                raise EvidenceError(f"UI screenshot has no leading PNG IHDR: {path}")
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            dimensions = (width, height)
            if compression != 0 or filtering != 0:
                raise EvidenceError(f"UI screenshot uses unsupported PNG compression/filtering: {path}")
        elif kind == b"IDAT":
            saw_image_data = True
            image_data.extend(payload)
        elif kind == b"IEND":
            if length != 0 or crc_end != len(content):
                raise EvidenceError(f"UI screenshot has a malformed PNG end chunk: {path}")
            saw_end = True
        offset = crc_end
        chunk_index += 1
    if dimensions is None or not saw_image_data or not saw_end:
        raise EvidenceError(f"UI screenshot is not a complete PNG image: {path}")
    width, height = dimensions
    if width <= 0 or height <= 0:
        raise EvidenceError(f"UI screenshot has invalid dimensions: {path}")
    if bit_depth != 8 or color_type not in {2, 6} or interlace != 0:
        raise EvidenceError(
            f"UI screenshot must be a non-interlaced 8-bit RGB/RGBA PNG: {path}"
        )
    bytes_per_pixel = 3 if color_type == 2 else 4
    row_bytes = width * bytes_per_pixel
    expected_bytes = height * (row_bytes + 1)
    try:
        decoded = zlib.decompress(bytes(image_data))
    except zlib.error as exc:
        raise EvidenceError(f"UI screenshot PNG image data cannot be decoded: {path}: {exc}") from exc
    if len(decoded) != expected_bytes:
        raise EvidenceError(
            f"UI screenshot PNG decoded byte count is invalid: {path}; "
            f"expected {expected_bytes}, found {len(decoded)}"
        )

    previous = bytearray(row_bytes)
    content_digest = hashlib.sha256()
    sampled_colors: set[bytes] = set()
    sample_x_step = max(1, width // 64)
    sample_y_step = max(1, height // 64)
    content_start = height // 10
    content_end = height - (height // 10)
    cursor = 0
    for y in range(height):
        filter_type = decoded[cursor]
        cursor += 1
        filtered = decoded[cursor : cursor + row_bytes]
        cursor += row_bytes
        if filter_type > 4:
            raise EvidenceError(f"UI screenshot PNG uses invalid row filter {filter_type}: {path}")
        # UiAutomation screenshots are often emitted with filter 0.  Copy that
        # row in C instead of running the generic per-byte predictor loop: a
        # release evidence set contains twelve full-resolution screenshots,
        # and the byte-at-a-time no-op path otherwise dominates validation.
        if filter_type == 0:
            reconstructed = bytearray(filtered)
        else:
            reconstructed = bytearray(row_bytes)
            for index, value in enumerate(filtered):
                left = reconstructed[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                above = previous[index]
                upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                if filter_type == 1:
                    predictor = left
                elif filter_type == 2:
                    predictor = above
                elif filter_type == 3:
                    predictor = (left + above) // 2
                else:
                    predictor = _paeth_predictor(left, above, upper_left)
                reconstructed[index] = (value + predictor) & 0xFF
        if content_start <= y < content_end:
            if bytes_per_pixel == 4:
                if any(reconstructed[index] != 255 for index in range(3, row_bytes, 4)):
                    raise EvidenceError(
                        f"UI screenshot contains non-opaque pixels and cannot be compared canonically: {path}"
                    )
                visible_row = bytearray(width * 3)
                visible_row[0::3] = reconstructed[0::4]
                visible_row[1::3] = reconstructed[1::4]
                visible_row[2::3] = reconstructed[2::4]
            else:
                visible_row = reconstructed
            content_digest.update(visible_row)
            if (y - content_start) % sample_y_step == 0:
                for x in range(0, width, sample_x_step):
                    offset = x * bytes_per_pixel
                    pixel = bytes(reconstructed[offset : offset + bytes_per_pixel])
                    if bytes_per_pixel == 4 and pixel[3] == 0:
                        continue
                    sampled_colors.add(pixel[:3])
        previous = reconstructed
    if len(sampled_colors) < 8:
        raise EvidenceError(
            f"UI screenshot has insufficient visible color variation ({len(sampled_colors)} colors): {path}"
        )
    return DecodedPng(width, height, content_digest.hexdigest(), len(sampled_colors))


def _paeth_predictor(left: int, above: int, upper_left: int) -> int:
    estimate = left + above - upper_left
    distance_left = abs(estimate - left)
    distance_above = abs(estimate - above)
    distance_upper_left = abs(estimate - upper_left)
    if distance_left <= distance_above and distance_left <= distance_upper_left:
        return left
    if distance_above <= distance_upper_left:
        return above
    return upper_left


def _semantics_evidence(path: Path, language: str) -> tuple[dict[str, str], str]:
    try:
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    except (OSError, UnicodeDecodeError) as exc:
        raise EvidenceError(f"Invalid UTF-8 semantics evidence {path}: {exc}") from exc
    header_text, separator, body = text.partition("\n\n")
    if not separator or not body.strip():
        raise EvidenceError(f"Semantics evidence has no nonblank tree body: {path}")
    header: dict[str, str] = {}
    for line in header_text.splitlines():
        key, marker, raw_value = line.partition("=")
        if not marker or not key or key in header:
            raise EvidenceError(f"Invalid semantics header line in {path}: {line!r}")
        header[key] = raw_value.strip()
    required = {
        "language",
        "screen_width_dp",
        "screen_height_dp",
        "font_scale",
        "release_source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "screenshot_sha256",
        "evidence_run_id",
        "package_id",
        "version_name",
        "version_code",
        "build_variant",
        "litertlm_coordinate",
        "device_serial",
        "avd_name",
        "device_boot_id",
        "build_fingerprint",
    }
    if not required.issubset(header):
        raise EvidenceError(f"Semantics evidence is missing headers {sorted(required - set(header))}: {path}")
    if header["language"] != language:
        raise EvidenceError(f"Semantics language mismatch in {path}: {header['language']!r}")
    try:
        width_dp = int(header["screen_width_dp"])
        height_dp = int(header["screen_height_dp"])
        font_scale = float(header["font_scale"])
    except ValueError as exc:
        raise EvidenceError(f"Semantics dimensions/font scale are invalid in {path}") from exc
    if width_dp <= 0 or height_dp <= 0 or not math.isfinite(font_scale) or font_scale <= 0:
        raise EvidenceError(f"Semantics dimensions/font scale must be positive in {path}")
    for field in (
        "release_source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "screenshot_sha256",
    ):
        if not HEX_64_RE.fullmatch(header[field]):
            raise EvidenceError(f"Semantics {field} must be lowercase SHA-256 in {path}")
    if not RUN_ID_RE.fullmatch(header["evidence_run_id"]):
        raise EvidenceError(f"Semantics evidence_run_id is invalid in {path}")
    if not BOOT_ID_RE.fullmatch(header["device_boot_id"].lower()):
        raise EvidenceError(f"Semantics device_boot_id is not a kernel boot UUID in {path}")
    for field in (
        "package_id",
        "version_name",
        "version_code",
        "build_variant",
        "litertlm_coordinate",
        "device_serial",
        "avd_name",
        "device_boot_id",
        "build_fingerprint",
    ):
        if not header[field]:
            raise EvidenceError(f"Semantics {field} is blank in {path}")
    return header, body.strip()


def _validate_profile_dimensions(profile: str, width_dp: int, height_dp: int, context: str) -> None:
    if height_dp <= 0 or width_dp <= 0:
        raise EvidenceError(f"{context} has invalid non-positive dimensions")
    if profile == "phone-compact":
        if width_dp < 320 or width_dp > 480 or height_dp < 480 or height_dp <= width_dp:
            raise EvidenceError(
                f"{context} is not a compact portrait phone (expected width <= 480dp): "
                f"{width_dp}x{height_dp}dp"
            )
    elif profile == "tablet":
        if width_dp < 600 or width_dp > 1_600 or height_dp < 600:
            raise EvidenceError(
                f"{context} is not a tablet (expected width >= 600dp): {width_dp}x{height_dp}dp"
            )
    else:  # pragma: no cover - callers use the fixed profile contract
        raise EvidenceError(f"Unknown UI profile: {profile}")


def _raw_command_records(
    raw_payload: Mapping[str, Any], context: str
) -> tuple[list[str], dict[str, Mapping[str, Any]]]:
    raw_records = raw_payload.get("records")
    if not isinstance(raw_records, list) or not raw_records:
        raise EvidenceError(f"{context}.records must be a nonempty command list")
    order: list[str] = []
    records: dict[str, Mapping[str, Any]] = {}
    required_fields = {"id", "argv", "exit_code", "stdout", "stderr"}
    for index, record in enumerate(raw_records):
        record_context = f"{context}.records[{index}]"
        if not isinstance(record, Mapping) or set(record) != required_fields:
            raise EvidenceError(f"{record_context} must contain exactly {sorted(required_fields)}")
        record_id = record.get("id")
        argv = record.get("argv")
        exit_code = record.get("exit_code")
        if (
            not isinstance(record_id, str)
            or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,99}", record_id)
            or record_id in records
        ):
            raise EvidenceError(f"{record_context}.id is invalid or duplicated")
        if not isinstance(argv, list) or not argv or not all(
            isinstance(argument, str) and argument for argument in argv
        ):
            raise EvidenceError(f"{record_context}.argv must be a nonempty string list")
        if isinstance(exit_code, bool) or not isinstance(exit_code, int):
            raise EvidenceError(f"{record_context}.exit_code must be an integer")
        if not isinstance(record.get("stdout"), str) or not isinstance(record.get("stderr"), str):
            raise EvidenceError(f"{record_context} stdout/stderr must be strings")
        if exit_code != 0:
            raise EvidenceError(f"{record_context} records a failed command exit {exit_code}")
        order.append(record_id)
        records[record_id] = record
    return order, records


def _raw_record(
    records: Mapping[str, Mapping[str, Any]], record_id: str, context: str
) -> Mapping[str, Any]:
    record = records.get(record_id)
    if record is None:
        raise EvidenceError(f"{context} is missing required raw command {record_id}")
    return record


def _raw_stdout(record: Mapping[str, Any], context: str, *, allow_blank: bool = False) -> str:
    value = str(record["stdout"]).strip()
    if not value and not allow_blank:
        raise EvidenceError(f"{context}.stdout is blank")
    return value


def _portable_executable_name(value: str) -> str:
    return re.split(r"[\\/]", value)[-1].casefold()


def _raw_expect_argv(record: Mapping[str, Any], expected: Sequence[str], context: str) -> None:
    if list(record["argv"]) != list(expected):
        raise EvidenceError(
            f"{context}.argv does not match the required live command: {record['argv']!r}"
        )


def _raw_parse_wm_size(output: str, context: str) -> tuple[int, int]:
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
        raise EvidenceError(f"{context} does not expose one effective wm size")
    width, height = (override or physical)[0]
    if width <= 0 or height <= 0:
        raise EvidenceError(f"{context} exposes a non-positive wm size")
    return width, height


def _raw_parse_wm_density(output: str, context: str) -> int:
    override = [
        int(value)
        for value in re.findall(r"(?mi)^\s*Override density:\s*([0-9]+)\s*$", output)
    ]
    physical = [
        int(value)
        for value in re.findall(r"(?mi)^\s*Physical density:\s*([0-9]+)\s*$", output)
    ]
    if len(physical) != 1 or len(override) > 1:
        raise EvidenceError(f"{context} does not expose one effective wm density")
    density = (override or physical)[0]
    if density <= 0:
        raise EvidenceError(f"{context} exposes a non-positive wm density")
    return density


def _raw_parse_start(output: str, expected_states: set[str], context: str) -> tuple[int, int]:
    statuses = [
        value.strip().casefold()
        for value in re.findall(r"(?mi)^\s*Status:\s*([^\r\n]*)$", output)
    ]
    if statuses != ["ok"]:
        raise EvidenceError(f"{context} does not contain exactly one Status: ok")
    states = [
        value.strip().upper()
        for value in re.findall(r"(?mi)^\s*LaunchState:\s*([^\r\n]*)$", output)
    ]
    if len(states) != 1 or states[0] not in expected_states:
        raise EvidenceError(f"{context} launch state does not match {sorted(expected_states)}")
    activities = [
        value.strip()
        for value in re.findall(r"(?mi)^\s*Activity:\s*([^\r\n]*)$", output)
    ]
    if activities != [MAIN_ACTIVITY]:
        raise EvidenceError(f"{context} does not report exactly the intended Activity")

    def one(field: str) -> int:
        values = [
            int(value)
            for value in re.findall(rf"(?mi)^\s*{re.escape(field)}:\s*([0-9]+)\s*$", output)
        ]
        if len(values) != 1:
            raise EvidenceError(f"{context} does not expose one {field}")
        return values[0]

    total, wait = one("TotalTime"), one("WaitTime")
    if total <= 0 or wait <= 0 or wait > total + 1_000:
        raise EvidenceError(f"{context} contains invalid launch timings")
    return total, wait


def _raw_parse_pidof(output: str, context: str) -> int:
    value = output.strip()
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise EvidenceError(f"{context} does not expose one positive Hermes process PID")
    return int(value)


def _raw_parse_target_compiler_filter(
    output: str, base_apk_path: str, context: str
) -> str:
    """Independently reparse API 35 Dexopt state for the exact target base APK."""
    if not base_apk_path.startswith("/") or any(character.isspace() for character in base_apk_path):
        raise EvidenceError(f"{context} target base APK path is invalid")
    lines = output.splitlines()
    dexopt_headers = [
        index
        for index, line in enumerate(lines)
        if re.fullmatch(r"[ \t]*Dexopt state:[ \t]*", line)
    ]
    if len(dexopt_headers) != 1:
        raise EvidenceError(f"{context} must expose exactly one Dexopt state section")

    base_path_rows: list[tuple[int, int]] = []
    for index in range(dexopt_headers[0] + 1, len(lines)):
        match = re.fullmatch(r"(?P<indent>[ \t]+)path:[ \t]*(?P<path>\S+)[ \t]*", lines[index])
        if match and match.group("path") == base_apk_path:
            base_path_rows.append((index, len(match.group("indent").expandtabs(8))))
    if len(base_path_rows) != 1:
        raise EvidenceError(
            f"{context} must expose exactly one Dexopt state path for the target base APK"
        )

    path_index, path_indent = base_path_rows[0]
    status_scope: list[str] = []
    for line in lines[path_index + 1 :]:
        if not line.strip():
            continue
        prefix = line[: len(line) - len(line.lstrip(" \t"))]
        if len(prefix.expandtabs(8)) <= path_indent:
            break
        status_scope.append(line)
    statuses = [
        "".join(value.split())
        for value in re.findall(r"\[status=([^]]+?)]", "\n".join(status_scope), re.DOTALL)
    ]
    if statuses != [TARGET_COMPILER_FILTER]:
        raise EvidenceError(
            f"{context} target base APK must expose exactly one status=speed compiler filter; "
            f"observed {statuses!r}"
        )
    return statuses[0]


def _raw_require_process_header(
    output: str, label: str, expected_pid: int, context: str
) -> None:
    observed = [
        (int(pid), package.strip())
        for pid, package in re.findall(
            rf"(?mi)^\s*\*\*\s*{re.escape(label)}\s+([1-9][0-9]*)\s+\[([^\]\r\n]+)\]\s*\*\*\s*$",
            output,
        )
    ]
    expected = [(expected_pid, PACKAGE_ID)]
    if observed != expected:
        raise EvidenceError(
            f"{context} process header does not match the measured Hermes PID"
        )


def _raw_require_resumed_activity(output: str, context: str) -> None:
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
        raise EvidenceError(f"{context} does not prove only resumed Hermes MainActivity claims")


def _raw_retryable_unknown_start(output: str) -> bool:
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


def _raw_parse_gpu_renderer(output: str, context: str) -> str:
    gles = [
        match.group(1).strip()
        for match in re.finditer(r"(?mi)^\s*GLES:\s*[^,\r\n]+,\s*([^,\r\n]+),", output)
        if match.group(1).strip()
    ]
    direct = [
        match.group(1).strip()
        for match in re.finditer(r"(?mi)^\s*GL_RENDERER\s*[:=]\s*([^\r\n]+)$", output)
        if match.group(1).strip()
    ]
    observed = [*gles, *direct]
    if not observed or any(
        marker in renderer.casefold()
        for renderer in observed
        for marker in SOFTWARE_RENDERER_MARKERS
    ):
        raise EvidenceError(f"{context} exposes a missing/software GPU renderer")
    if len(gles) > 1 or len(direct) > 1:
        raise EvidenceError(f"{context} exposes duplicate GPU renderer claims")
    if gles and direct and gles != direct:
        raise EvidenceError(f"{context} exposes contradictory GPU renderers")
    if len(observed) not in (1, 2):
        raise EvidenceError(f"{context} does not expose one GPU renderer")
    return observed[0]


def _raw_qemu_inventory(output: str, context: str) -> tuple[Mapping[str, Any], ...]:
    try:
        decoded = json.loads(output)
    except json.JSONDecodeError as exc:
        raise EvidenceError(f"{context} QEMU inventory is invalid JSON: {exc}") from exc
    items = decoded if isinstance(decoded, list) else [decoded]
    processes: list[Mapping[str, Any]] = []
    for index, item in enumerate(items):
        if not isinstance(item, Mapping) or set(item) != {
            "pid",
            "name",
            "public_command",
            "public_command_sha256",
            "raw_command_sha256",
        }:
            raise EvidenceError(f"{context} QEMU inventory entry {index} has invalid fields")
        if (
            isinstance(item["pid"], bool)
            or not isinstance(item["pid"], int)
            or item["pid"] <= 0
            or not isinstance(item["name"], str)
            or re.fullmatch(r"qemu-system-[a-z0-9_.-]+", item["name"].casefold()) is None
            or not isinstance(item["public_command"], str)
            or not item["public_command"]
            or not isinstance(item["public_command_sha256"], str)
            or not HEX_64_RE.fullmatch(item["public_command_sha256"])
            or not isinstance(item["raw_command_sha256"], str)
            or not HEX_64_RE.fullmatch(item["raw_command_sha256"])
        ):
            raise EvidenceError(f"{context} QEMU inventory entry {index} has invalid identity")
        expected_public_sha = hashlib.sha256(item["public_command"].encode("utf-8")).hexdigest()
        if item["public_command_sha256"] != expected_public_sha:
            raise EvidenceError(f"{context} QEMU inventory entry {index} public hash is wrong")
        processes.append(item)
    return tuple(processes)


def _raw_qemu_match(
    record: Mapping[str, Any],
    normalized_device: Mapping[str, Any],
    serial: str,
    context: str,
) -> None:
    argv = list(record["argv"])
    if (
        len(argv) != 6
        or _portable_executable_name(argv[0])
        not in {"powershell", "powershell.exe", "pwsh", "pwsh.exe"}
        or argv[1:] != ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", QEMU_CIM_SCRIPT]
    ):
        raise EvidenceError(f"{context}.argv is not the fixed Win32_Process QEMU query")
    serial_match = re.fullmatch(r"emulator-([0-9]{4,5})", serial)
    if serial_match is None:
        raise EvidenceError(f"{context} normalized serial has no emulator console port")
    console_port = int(serial_match.group(1))
    processes = _raw_qemu_inventory(_raw_stdout(record, context), context)
    if len(processes) > 2:
        raise EvidenceError(f"{context} exceeds the absolute two-emulator limit")
    if len(processes) != 1:
        raise EvidenceError(f"{context} does not prove exactly one total live QEMU process")
    if normalized_device.get("active_qemu_process_count") != len(processes):
        raise EvidenceError(f"{context} QEMU count disagrees with normalized evidence")
    matches: list[Mapping[str, Any]] = []
    for process in processes:
        try:
            tokens = shlex.split(str(process["public_command"]), posix=False)
        except ValueError as exc:
            raise EvidenceError(f"{context} contains an untokenizable QEMU command: {exc}") from exc
        expected_prefix = [process["name"].casefold(), "-avd", normalized_device["avd_name"]]
        expected_suffix = ["-gpu", "host", "-accel", "on"]
        expected_port = [*expected_prefix, "-port", str(console_port), *expected_suffix]
        expected_ports = [
            *expected_prefix,
            "-ports",
            f"{console_port},{console_port + 1}",
            *expected_suffix,
        ]
        normalized_tokens = [token.strip('"\'') for token in tokens]
        if normalized_tokens in (expected_port, expected_ports):
            matches.append(process)
    if len(matches) != 1:
        raise EvidenceError(f"{context} does not prove exactly one serial/AVD QEMU process")
    process = matches[0]
    exact = {
        "pid": normalized_device.get("emulator_pid"),
        "name": normalized_device.get("emulator_process_name"),
        "public_command": normalized_device.get("emulator_public_command"),
        "public_command_sha256": normalized_device.get("emulator_public_command_sha256"),
        "raw_command_sha256": normalized_device.get("emulator_raw_command_sha256"),
    }
    if any(process.get(field) != expected for field, expected in exact.items()):
        raise EvidenceError(f"{context} QEMU process identity disagrees with normalized evidence")


def _validate_raw_performance(
    raw_payload: Mapping[str, Any],
    normalized: Mapping[str, Any],
    profile: str,
    source_digest: str,
    version_name: str,
    version_code: int,
    litertlm_coordinate: str,
) -> None:
    context = f"performance[{profile}].host_raw"
    exact_header: dict[str, Any] = {
        "schema": RAW_PERFORMANCE_SCHEMA,
        "profile": profile,
        "release_source_digest": source_digest,
        "benchmark_target_apk_sha256": normalized["benchmark_target_apk_sha256"],
        "benchmark_test_apk_sha256": normalized["benchmark_test_apk_sha256"],
        "evidence_run_id": normalized["evidence_run_id"],
        "package_id": PACKAGE_ID,
        "benchmark_test_package_id": BENCHMARK_TEST_PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "build_variant": PERFORMANCE_BUILD_VARIANT,
        "litertlm_coordinate": litertlm_coordinate,
    }
    if set(raw_payload) != set(exact_header) | {"records"}:
        raise EvidenceError(f"{context} top-level fields do not match the v2 host transcript")
    for field, expected in exact_header.items():
        if raw_payload.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")

    order, records = _raw_command_records(raw_payload, context)
    identity_suffix = [
        "adb.devices",
        "adb.get-serialno",
        "adb.get-state",
        "device.getprop.avd_name",
        "device.getprop.build_fingerprint",
        "device.getprop.model",
        "device.getprop.android_sdk",
        "device.getprop.supported_abis",
        "device.boot_id",
        "device.settings.font_scale",
        "package.benchmark_target.path",
        "package.benchmark_target.sha256",
        "package.benchmark_test.path",
        "package.benchmark_test.sha256",
        "package.version",
        "host.qemu_processes",
    ]
    initial_ids = [f"initial.{suffix}" for suffix in identity_suffix]
    final_ids = [f"final.{suffix}" for suffix in identity_suffix]
    measure_prefix = [
        "measure.emulator.accel-check",
        "measure.screen.wm_size",
        "measure.screen.wm_density",
        "measure.screen.am_config",
        "measure.gpu.surfaceflinger",
        "measure.launch.force_stop",
        "measure.launch.cold",
        "measure.launch.pid_before_back",
        "measure.launch.back",
        "measure.launch.pid_after_back",
        "measure.launch.warm",
    ]
    retry_ids = [
        "measure.launch.retry.pid_before_back",
        "measure.launch.retry.back",
        "measure.launch.retry.pid_after_back",
        "measure.launch.retry.warm",
    ]
    has_retry = any(record_id in records for record_id in retry_ids)
    if has_retry and not all(record_id in records for record_id in retry_ids):
        raise EvidenceError(f"{context} contains an incomplete bounded warm-launch retry")
    measure_suffix = [
        "measure.activity.after_launch",
        "measure.memory.meminfo",
        "measure.process.pid_after_measurement",
    ]
    expected_order = [
        "macrobenchmark.invocation",
        *initial_ids,
        "measure.package.target_compiler_filter.initial",
        *measure_prefix,
        *(retry_ids if has_retry else []),
        *measure_suffix,
        *final_ids,
        "measure.package.target_compiler_filter.final",
    ]
    if order != expected_order:
        raise EvidenceError(f"{context} command order is incomplete, unexpected, or reordered")

    invocation = _raw_record(records, "macrobenchmark.invocation", context)
    invocation_argv = list(invocation["argv"])
    if _portable_executable_name(invocation_argv[0]) not in {"gradlew", "gradlew.bat"}:
        raise EvidenceError(f"{context} Macrobenchmark invocation did not use the Gradle wrapper")
    exact_invocation_args = [
        ":macrobenchmark:connectedBenchmarkAndroidTest",
        f"-PhermesBenchmarkExpectedSourceDigest={source_digest}",
        f"-PhermesBenchmarkExpectedVersionName={version_name}",
        f"-PhermesBenchmarkExpectedVersionCode={version_code}",
        f"-PhermesBenchmarkExpectedLiteRtLmCoordinate={litertlm_coordinate}",
        f"-PhermesBenchmarkTargetApkSha256={normalized['benchmark_target_apk_sha256']}",
        f"-PhermesBenchmarkApkSha256={normalized['benchmark_test_apk_sha256']}",
        f"-PhermesBenchmarkEvidenceRunId={normalized['evidence_run_id']}",
        f"-PhermesBenchmarkEvidenceProfile={profile}",
        f"-PhermesBenchmarkExpectedAvdName={normalized['device']['avd_name']}",
        f"-PhermesBenchmarkExpectedBootId={normalized['device']['boot_id']}",
        f"-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_TEST_ID}",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.sourceDigest={source_digest}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.targetApkSha256={normalized['benchmark_target_apk_sha256']}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.benchmarkApkSha256={normalized['benchmark_test_apk_sha256']}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.evidenceRunId={normalized['evidence_run_id']}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.evidenceProfile={profile}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.avdName={normalized['device']['avd_name']}",
        f"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.bootId={normalized['device']['boot_id']}",
        "--no-daemon",
        "--console=plain",
    ]
    if invocation_argv[1:] != exact_invocation_args:
        raise EvidenceError(f"{context} Macrobenchmark invocation arguments are not exact")
    invocation_output = f"{invocation['stdout']}\n{invocation['stderr']}"
    if "BUILD SUCCESSFUL" not in invocation_output or any(
        marker in invocation_output for marker in ("BUILD FAILED", "FAILURE:", "INSTRUMENTATION_FAILED")
    ):
        raise EvidenceError(f"{context} Macrobenchmark invocation does not prove one successful run")

    device = _nested_object(normalized, "device", f"performance[{profile}]")
    screen = _nested_object(normalized, "screen", f"performance[{profile}]")
    launch = _nested_object(normalized, "launch", f"performance[{profile}]")
    collector = _nested_object(normalized, "collector", f"performance[{profile}]")
    serial = _required_string(device, "serial", f"performance[{profile}].device")
    first_adb = _raw_record(records, "initial.adb.devices", context)
    adb = str(first_adb["argv"][0])
    if _portable_executable_name(adb) not in {"adb", "adb.exe"}:
        raise EvidenceError(f"{context} uses an unexpected adb executable")

    def adb_command(record_id: str, *tail: str, targeted: bool = True) -> Mapping[str, Any]:
        record = _raw_record(records, record_id, context)
        expected = [adb, "-s", serial, *tail] if targeted else [adb, *tail]
        _raw_expect_argv(record, expected, f"{context}.{record_id}")
        return record

    def validate_identity(phase: str) -> None:
        inventory = adb_command(f"{phase}.adb.devices", "devices", "-l", targeted=False)
        endpoints: list[tuple[str, str]] = []
        for line in str(inventory["stdout"]).splitlines():
            fields = line.strip().split()
            if not fields or line.strip() == "List of devices attached":
                continue
            endpoints.append((fields[0], fields[1] if len(fields) > 1 else ""))
        if endpoints != [(serial, "device")]:
            raise EvidenceError(
                f"{context}.{phase} adb inventory does not prove one exclusive target"
            )
        if _raw_stdout(adb_command(f"{phase}.adb.get-serialno", "get-serialno"), context) != serial:
            raise EvidenceError(f"{context}.{phase} serial does not match")
        if _raw_stdout(adb_command(f"{phase}.adb.get-state", "get-state"), context) != "device":
            raise EvidenceError(f"{context}.{phase} adb state is not device")

        properties: tuple[tuple[str, str, str], ...] = (
            ("avd_name", "ro.boot.qemu.avd_name", str(device["avd_name"])),
            ("build_fingerprint", "ro.build.fingerprint", str(device["build_fingerprint"])),
            ("model", "ro.product.model", str(device["model"])),
            ("android_sdk", "ro.build.version.sdk", str(device["android_sdk"])),
        )
        for label, prop, expected in properties:
            observed = _raw_stdout(
                adb_command(f"{phase}.device.getprop.{label}", "shell", "getprop", prop),
                context,
            )
            if observed != expected:
                raise EvidenceError(f"{context}.{phase} {label} changed")
        observed_abis = tuple(
            part.strip()
            for part in _raw_stdout(
                adb_command(
                    f"{phase}.device.getprop.supported_abis",
                    "shell",
                    "getprop",
                    "ro.product.cpu.abilist",
                ),
                context,
            ).split(",")
            if part.strip()
        )
        if observed_abis != tuple(device["supported_abis"]):
            raise EvidenceError(f"{context}.{phase} ABI identity changed")
        boot_id = _raw_stdout(
            adb_command(
                f"{phase}.device.boot_id",
                "shell",
                "cat",
                "/proc/sys/kernel/random/boot_id",
            ),
            context,
        ).lower()
        if boot_id != str(device["boot_id"]).lower():
            raise EvidenceError(f"{context}.{phase} boot ID changed")
        font_scale = _raw_stdout(
            adb_command(
                f"{phase}.device.settings.font_scale",
                "shell",
                "settings",
                "get",
                "system",
                "font_scale",
            ),
            context,
        )
        try:
            observed_font_scale = float(font_scale)
        except ValueError as exc:
            raise EvidenceError(f"{context}.{phase} font scale is invalid") from exc
        if observed_font_scale != 1.0 or screen.get("font_scale") != observed_font_scale:
            raise EvidenceError(f"{context}.{phase} font scale is not exactly 1.0")

        package_contract = (
            (
                "benchmark_target",
                PACKAGE_ID,
                collector.get("benchmark_target_apk_device_path"),
                normalized["benchmark_target_apk_sha256"],
            ),
            (
                "benchmark_test",
                BENCHMARK_TEST_PACKAGE_ID,
                collector.get("benchmark_test_apk_device_path"),
                normalized["benchmark_test_apk_sha256"],
            ),
        )
        for label, package_id, expected_path, expected_sha in package_contract:
            if not isinstance(expected_path, str) or not expected_path.startswith("/"):
                raise EvidenceError(f"{context} {label} device path is invalid")
            path_output = _raw_stdout(
                adb_command(f"{phase}.package.{label}.path", "shell", "pm", "path", package_id),
                context,
            )
            if path_output != f"package:{expected_path}":
                raise EvidenceError(f"{context}.{phase} {label} APK path changed")
            sha_output = _raw_stdout(
                adb_command(
                    f"{phase}.package.{label}.sha256",
                    "shell",
                    "sha256sum",
                    str(expected_path),
                ),
                context,
            )
            sha_parts = sha_output.split()
            if len(sha_parts) != 2 or sha_parts != [expected_sha, expected_path]:
                raise EvidenceError(f"{context}.{phase} {label} APK hash changed")

        version_record = adb_command(
            f"{phase}.package.version", "shell", "dumpsys", "package", PACKAGE_ID
        )
        version_output = _raw_stdout(version_record, context)
        version_names = set(re.findall(r"(?m)^\s*versionName=([^\s]+)\s*$", version_output))
        version_codes = set(re.findall(r"(?m)^\s*versionCode=([0-9]+)(?:\s|$)", version_output))
        if version_names != {version_name} or version_codes != {str(version_code)}:
            raise EvidenceError(f"{context}.{phase} installed version changed")

        qemu_record = _raw_record(records, f"{phase}.host.qemu_processes", context)
        _raw_qemu_match(qemu_record, device, serial, f"{context}.{phase}")

    benchmark = _nested_object(normalized, "benchmark", f"performance[{profile}]")

    def validate_target_compiler_filter(phase: str) -> str:
        base_apk_path = collector.get("benchmark_target_apk_device_path")
        if not isinstance(base_apk_path, str):
            raise EvidenceError(f"{context} target base APK path is invalid")
        record_id = f"measure.package.target_compiler_filter.{phase}"
        package_dump = _raw_stdout(
            adb_command(
                record_id,
                "shell",
                "cmd",
                "package",
                "dump",
                PACKAGE_ID,
            ),
            f"{context}.{record_id}",
        )
        observed = _raw_parse_target_compiler_filter(
            package_dump, base_apk_path, f"{context}.{record_id}"
        )
        if benchmark.get("target_compiler_filter") != observed:
            raise EvidenceError(
                f"{context}.{record_id} disagrees with normalized target compiler filter"
            )
        return observed

    validate_identity("initial")
    initial_target_compiler_filter = validate_target_compiler_filter("initial")

    accel = _raw_record(records, "measure.emulator.accel-check", context)
    if len(accel["argv"]) != 2 or accel["argv"][1] != "-accel-check":
        raise EvidenceError(f"{context} acceleration command is not emulator -accel-check")
    accel_output = "\n".join(
        part.strip() for part in (str(accel["stdout"]), str(accel["stderr"])) if part.strip()
    )
    if accel_output != device.get("acceleration_check"):
        raise EvidenceError(f"{context} acceleration output disagrees with normalized evidence")

    wm_size = _raw_parse_wm_size(
        _raw_stdout(adb_command("measure.screen.wm_size", "shell", "wm", "size"), context),
        context,
    )
    wm_density = _raw_parse_wm_density(
        _raw_stdout(adb_command("measure.screen.wm_density", "shell", "wm", "density"), context),
        context,
    )
    if wm_size != (screen["width_px"], screen["height_px"]) or wm_density != screen["density_dpi"]:
        raise EvidenceError(f"{context} wm size/density disagrees with normalized evidence")
    am_config = _raw_stdout(
        adb_command("measure.screen.am_config", "shell", "am", "get-config"), context
    )
    dp_pairs = {
        (int(width), int(height))
        for width, height in re.findall(r"(?:^|[-\s])w([0-9]+)dp-h([0-9]+)dp(?:[-\s]|$)", am_config)
    }
    if dp_pairs != {(screen["width_dp"], screen["height_dp"])}:
        raise EvidenceError(f"{context} configured dp dimensions disagree with normalized evidence")
    gpu_output = _raw_stdout(
        adb_command("measure.gpu.surfaceflinger", "shell", "dumpsys", "SurfaceFlinger"), context
    )
    if _raw_parse_gpu_renderer(gpu_output, context) != device["gpu_renderer"]:
        raise EvidenceError(f"{context} GPU renderer disagrees with normalized evidence")

    adb_command("measure.launch.force_stop", "shell", "am", "force-stop", PACKAGE_ID)
    cold_total, cold_wait = _raw_parse_start(
        _raw_stdout(
            adb_command(
                "measure.launch.cold",
                "shell",
                "am",
                "start",
                "-W",
                "-S",
                "-n",
                MAIN_ACTIVITY,
            ),
            context,
        ),
        {"COLD"},
        context,
    )
    if (cold_total, cold_wait) != (launch["cold_total_ms"], launch["cold_wait_ms"]):
        raise EvidenceError(f"{context} cold launch timings disagree with normalized evidence")
    pid_before = _raw_parse_pidof(
        _raw_stdout(
            adb_command("measure.launch.pid_before_back", "shell", "pidof", PACKAGE_ID),
            context,
        ),
        context,
    )
    adb_command("measure.launch.back", "shell", "input", "keyevent", "KEYCODE_BACK")
    pid_after = _raw_parse_pidof(
        _raw_stdout(
            adb_command("measure.launch.pid_after_back", "shell", "pidof", PACKAGE_ID),
            context,
        ),
        context,
    )
    if pid_before != pid_after or pid_after != launch["warm_process_pid"]:
        raise EvidenceError(f"{context} warm process PID is not stable across KEYCODE_BACK")
    warm_record = adb_command(
        "measure.launch.warm", "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY
    )
    warm_output = _raw_stdout(warm_record, context)
    if has_retry:
        if not _raw_retryable_unknown_start(warm_output):
            raise EvidenceError(f"{context} unexpected warm retry")
        retry_before = _raw_parse_pidof(
            _raw_stdout(
                adb_command("measure.launch.retry.pid_before_back", "shell", "pidof", PACKAGE_ID),
                context,
            ),
            context,
        )
        adb_command("measure.launch.retry.back", "shell", "input", "keyevent", "KEYCODE_BACK")
        retry_after = _raw_parse_pidof(
            _raw_stdout(
                adb_command("measure.launch.retry.pid_after_back", "shell", "pidof", PACKAGE_ID),
                context,
            ),
            context,
        )
        if retry_before != pid_after or retry_after != retry_before:
            raise EvidenceError(f"{context} process changed during bounded warm retry")
        warm_output = _raw_stdout(
            adb_command(
                "measure.launch.retry.warm",
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                MAIN_ACTIVITY,
            ),
            context,
        )
    elif _raw_retryable_unknown_start(warm_output):
        raise EvidenceError(f"{context} retryable UNKNOWN warm launch was not retried")
    warm_total, _ = _raw_parse_start(warm_output, {"WARM", "HOT"}, context)
    if warm_total != launch["warm_total_ms"]:
        raise EvidenceError(f"{context} warm launch timing disagrees with normalized evidence")

    foreground = _raw_stdout(
        adb_command(
            "measure.activity.after_launch", "shell", "dumpsys", "activity", "activities"
        ),
        context,
    )
    _raw_require_resumed_activity(foreground, f"{context}.measure.activity.after_launch")
    meminfo = _raw_stdout(
        adb_command("measure.memory.meminfo", "shell", "dumpsys", "meminfo", PACKAGE_ID),
        context,
    )
    _raw_require_process_header(
        meminfo, "MEMINFO in pid", launch["warm_process_pid"], f"{context}.measure.memory.meminfo"
    )
    memory_pairs = [
        (int(pss), int(rss))
        for pss, rss in re.findall(
            r"(?mi)^\s*TOTAL\s+PSS:\s*([0-9]+)\s+TOTAL\s+RSS:\s*([0-9]+)(?:\s|$)",
            meminfo,
        )
    ]
    memory = _nested_object(normalized, "memory", f"performance[{profile}]")
    if memory_pairs != [(memory["total_pss_kb"], memory["total_rss_kb"])]:
        raise EvidenceError(f"{context} meminfo disagrees with normalized evidence")
    final_pid = _raw_parse_pidof(
        _raw_stdout(
            adb_command("measure.process.pid_after_measurement", "shell", "pidof", PACKAGE_ID),
            context,
        ),
        context,
    )
    if final_pid != launch["warm_process_pid"]:
        raise EvidenceError(f"{context} process PID changed during memory collection")
    validate_identity("final")
    if validate_target_compiler_filter("final") != initial_target_compiler_filter:
        raise EvidenceError(f"{context} target compiler filter changed during collection")


def _finite_json_number(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"{context} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise EvidenceError(f"{context} must be a finite number")
    return result


def _androidx_metric_runs(
    metrics: Mapping[str, Any], name: str, iterations: int, *, integral: bool
) -> list[int] | list[float]:
    metric = _nested_object(metrics, name, "macrobenchmark.metrics")
    expected_keys = {"minimum", "maximum", "median", "coefficientOfVariation", "runs"}
    if set(metric) != expected_keys:
        raise EvidenceError(f"macrobenchmark.metrics.{name} has an unexpected AndroidX shape")
    runs = metric.get("runs")
    if not isinstance(runs, list) or len(runs) != iterations:
        raise EvidenceError(f"macrobenchmark.metrics.{name}.runs must contain {iterations} values")
    values = [
        _finite_json_number(value, f"macrobenchmark.metrics.{name}.runs[{index}]")
        for index, value in enumerate(runs)
    ]
    for stat in ("minimum", "maximum", "median", "coefficientOfVariation"):
        _finite_json_number(metric.get(stat), f"macrobenchmark.metrics.{name}.{stat}")
    if metric["minimum"] != min(values) or metric["maximum"] != max(values):
        raise EvidenceError(f"macrobenchmark.metrics.{name} min/max do not match runs")
    if integral:
        if any(value < 0 or not value.is_integer() for value in values):
            raise EvidenceError(f"macrobenchmark.metrics.{name} must contain nonnegative integers")
        return [int(value) for value in values]
    return values


def _androidx_sampled_metric(
    sampled_metrics: Mapping[str, Any], name: str, iterations: int
) -> tuple[dict[str, float], list[list[float]]]:
    metric = _nested_object(sampled_metrics, name, "macrobenchmark.sampledMetrics")
    if set(metric) != {"P50", "P90", "P95", "P99", "runs"}:
        raise EvidenceError(f"macrobenchmark.sampledMetrics.{name} has an unexpected shape")
    percentile_keys = ("P50", "P90", "P95", "P99")
    percentiles = {
        key: _finite_json_number(metric.get(key), f"macrobenchmark.sampledMetrics.{name}.{key}")
        for key in percentile_keys
    }
    if list(percentiles.values()) != sorted(percentiles.values()):
        raise EvidenceError(f"macrobenchmark.sampledMetrics.{name} percentiles are not monotonic")
    raw_runs = metric.get("runs")
    if not isinstance(raw_runs, list) or len(raw_runs) != iterations:
        raise EvidenceError(
            f"macrobenchmark.sampledMetrics.{name}.runs must contain {iterations} arrays"
        )
    runs: list[list[float]] = []
    for iteration, raw_values in enumerate(raw_runs, start=1):
        if not isinstance(raw_values, list) or not raw_values:
            raise EvidenceError(f"macrobenchmark sampled {name} iteration {iteration} is empty")
        runs.append(
            [
                _finite_json_number(
                    value, f"macrobenchmark.sampledMetrics.{name}.runs[{iteration - 1}]"
                )
                for value in raw_values
            ]
        )
    pooled = sorted(value for iteration_values in runs for value in iteration_values)
    if name == "frameDurationCpuMs" and any(value < 0 for value in pooled):
        raise EvidenceError(
            "macrobenchmark.sampledMetrics.frameDurationCpuMs cannot contain negative samples"
        )
    for key, percentile in (("P50", 50), ("P90", 90), ("P95", 95), ("P99", 99)):
        expected = _linear_interpolated_percentile(pooled, percentile)
        if not math.isclose(
            percentiles[key], expected, rel_tol=1e-9, abs_tol=1e-9
        ):
            raise EvidenceError(
                f"macrobenchmark.sampledMetrics.{name}.{key} does not reproduce "
                "the pooled AndroidX runs"
            )
    return percentiles, runs


def _linear_interpolated_percentile(values: Sequence[float], percentile: int) -> float:
    """Reproduce AndroidX MetricResult percentile interpolation over pooled samples."""
    if not values:
        raise EvidenceError("cannot calculate a percentile from an empty sample")
    ideal_index = percentile / 100.0 * (len(values) - 1)
    lower_index = math.floor(ideal_index)
    upper_index = math.ceil(ideal_index)
    lower = values[lower_index]
    upper = values[upper_index]
    return lower + (upper - lower) * (ideal_index - lower_index)


def _expected_frames_from_macrobenchmark(
    report: Mapping[str, Any],
    normalized: Mapping[str, Any],
    profile: str,
    trace_source_names: Sequence[str],
) -> dict[str, Any]:
    context = f"performance[{profile}].macrobenchmark_raw"
    if set(report) != {"context", "benchmarks"}:
        raise EvidenceError(f"{context} root does not match AndroidX BenchmarkData 1.4.1")
    report_context = _nested_object(report, "context", context)
    expected_context_keys = {
        "build",
        "cpuCoreCount",
        "cpuLocked",
        "cpuMaxFreqHz",
        "memTotalBytes",
        "sustainedPerformanceModeEnabled",
        "artMainlineVersion",
        "osCodenameAbbreviated",
        "compilationMode",
        "payload",
    }
    if set(report_context) != expected_context_keys:
        raise EvidenceError(f"{context}.context does not match AndroidX BenchmarkData 1.4.1")
    if report_context.get("compilationMode") != REPORTING_PACKAGE_COMPILATION_MODE:
        raise EvidenceError(
            f"{context}.context.compilationMode must equal run-from-apk for the "
            "self-instrumenting reporting package"
        )
    normalized_device = _nested_object(normalized, "device", f"performance[{profile}]")
    if report_context.get("payload") != {
        "sourceDigest": normalized["release_source_digest"],
        "targetApkSha256": normalized["benchmark_target_apk_sha256"],
        "benchmarkApkSha256": normalized["benchmark_test_apk_sha256"],
        "evidenceRunId": normalized["evidence_run_id"],
        "evidenceProfile": profile,
        "avdName": normalized_device.get("avd_name"),
        "bootId": normalized_device.get("boot_id"),
    }:
        raise EvidenceError(
            f"{context}.context.payload does not bind the exact source/APKs/run/profile/boot"
        )
    build = _nested_object(report_context, "build", f"{context}.context")
    if set(build) != {"brand", "device", "fingerprint", "id", "model", "type", "version"}:
        raise EvidenceError(f"{context}.context.build has an unexpected key set")
    version = _nested_object(build, "version", f"{context}.context.build")
    if set(version) != {"codename", "sdk"}:
        raise EvidenceError(f"{context}.context.build.version has an unexpected key set")
    if (
        build.get("fingerprint") != normalized_device.get("build_fingerprint")
        or build.get("model") != normalized_device.get("model")
        or version.get("sdk") != normalized_device.get("android_sdk")
    ):
        raise EvidenceError(f"{context} build identity does not match the exact live AVD")

    benchmarks = report.get("benchmarks")
    if not isinstance(benchmarks, list) or len(benchmarks) != 1 or not isinstance(benchmarks[0], Mapping):
        raise EvidenceError(f"{context} must contain exactly one benchmark")
    result = benchmarks[0]
    expected_result_keys = {
        "name",
        "params",
        "className",
        "totalRunTimeNs",
        "metrics",
        "sampledMetrics",
        "warmupIterations",
        "repeatIterations",
        "thermalThrottleSleepSeconds",
        "profilerOutputs",
    }
    if set(result) != expected_result_keys:
        raise EvidenceError(f"{context}.benchmarks[0] does not match AndroidX 1.4.1")
    if result.get("name") != BENCHMARK_METHOD or result.get("className") != BENCHMARK_CLASS:
        raise EvidenceError(f"{context} benchmark class/method is wrong")
    if result.get("params") != {}:
        raise EvidenceError(f"{context} must contain one unparameterized benchmark")
    iterations = result.get("repeatIterations")
    if (
        isinstance(iterations, bool)
        or not isinstance(iterations, int)
        or not MIN_BENCHMARK_ITERATIONS <= iterations <= MAX_BENCHMARK_ITERATIONS
    ):
        raise EvidenceError(f"{context}.repeatIterations must be between 5 and 20")
    if result.get("thermalThrottleSleepSeconds") != 0:
        raise EvidenceError(f"{context} reports thermal throttling")
    warmup_iterations = result.get("warmupIterations")
    if isinstance(warmup_iterations, bool) or not isinstance(warmup_iterations, int) or warmup_iterations < 0:
        raise EvidenceError(f"{context}.warmupIterations must be nonnegative")
    total_run_time = result.get("totalRunTimeNs")
    if isinstance(total_run_time, bool) or not isinstance(total_run_time, int) or total_run_time <= 0:
        raise EvidenceError(f"{context}.totalRunTimeNs must be positive")

    metrics = _nested_object(result, "metrics", f"{context}.benchmarks[0]")
    expected_metric_names = {
        "frameCount",
        "hermesFrameTotalCount",
        "hermesFrameSelfJankTaggedCount",
        "hermesFrameAppDeadlineMissedCount",
        "hermesFrameAppDeadlineMissedOrDroppedCount",
        "hermesFrameNonDeadlineSelfJankTaggedCount",
        "hermesFrameOtherJankTaggedCount",
        "hermesFrameDroppedCount",
        "hermesFrameUnknownTagCount",
        "hermesFrameOverlappingJankTagCount",
        "hermesFrameSelfJankTaggedPercent",
        "hermesEvidenceToken",
    }
    if set(metrics) != expected_metric_names:
        raise EvidenceError(f"{context}.metrics does not contain the exact Hermes metric set")
    sampled = _nested_object(result, "sampledMetrics", f"{context}.benchmarks[0]")
    if set(sampled) != {"frameDurationCpuMs", "frameOverrunMs"}:
        raise EvidenceError(f"{context}.sampledMetrics does not contain both frame distributions")

    frame_counts = _androidx_metric_runs(metrics, "frameCount", iterations, integral=True)
    totals = _androidx_metric_runs(metrics, "hermesFrameTotalCount", iterations, integral=True)
    self_jank_tagged = _androidx_metric_runs(
        metrics, "hermesFrameSelfJankTaggedCount", iterations, integral=True
    )
    deadline = _androidx_metric_runs(
        metrics, "hermesFrameAppDeadlineMissedCount", iterations, integral=True
    )
    deadline_or_dropped = _androidx_metric_runs(
        metrics,
        "hermesFrameAppDeadlineMissedOrDroppedCount",
        iterations,
        integral=True,
    )
    non_deadline_self_jank_tagged = _androidx_metric_runs(
        metrics, "hermesFrameNonDeadlineSelfJankTaggedCount", iterations, integral=True
    )
    other_jank_tagged = _androidx_metric_runs(
        metrics, "hermesFrameOtherJankTaggedCount", iterations, integral=True
    )
    dropped = _androidx_metric_runs(metrics, "hermesFrameDroppedCount", iterations, integral=True)
    unknown_tag = _androidx_metric_runs(
        metrics, "hermesFrameUnknownTagCount", iterations, integral=True
    )
    overlapping_jank_tag = _androidx_metric_runs(
        metrics, "hermesFrameOverlappingJankTagCount", iterations, integral=True
    )
    percentages = _androidx_metric_runs(
        metrics, "hermesFrameSelfJankTaggedPercent", iterations, integral=False
    )
    evidence_tokens = _androidx_metric_runs(metrics, "hermesEvidenceToken", iterations, integral=True)
    assert isinstance(frame_counts, list)
    assert isinstance(totals, list)
    assert isinstance(self_jank_tagged, list)
    assert isinstance(deadline, list)
    assert isinstance(deadline_or_dropped, list)
    assert isinstance(non_deadline_self_jank_tagged, list)
    assert isinstance(other_jank_tagged, list)
    assert isinstance(dropped, list)
    assert isinstance(unknown_tag, list)
    assert isinstance(overlapping_jank_tag, list)
    assert isinstance(percentages, list)
    assert isinstance(evidence_tokens, list)
    canonical_token_input = (
        "hermes-macrobenchmark-evidence-v2\n"
        f"{normalized['release_source_digest']}\n"
        f"{normalized['benchmark_target_apk_sha256']}\n"
        f"{normalized['benchmark_test_apk_sha256']}\n"
        f"{normalized['evidence_run_id']}\n"
        f"{profile}\n"
        f"{normalized_device['avd_name']}\n"
        f"{normalized_device['boot_id']}\n"
    )
    expected_evidence_token = int(
        hashlib.sha256(canonical_token_input.encode("utf-8")).hexdigest()[:13], 16
    )
    if evidence_tokens != [expected_evidence_token] * iterations:
        raise EvidenceError(
            f"{context}.hermesEvidenceToken does not bind the exact source/APKs/run/profile/boot"
        )
    duration_percentiles, duration_runs = _androidx_sampled_metric(
        sampled, "frameDurationCpuMs", iterations
    )
    overrun_percentiles, overrun_runs = _androidx_sampled_metric(
        sampled, "frameOverrunMs", iterations
    )

    normalized_iterations: list[dict[str, Any]] = []
    for index in range(iterations):
        frame_count = int(frame_counts[index])
        total = int(totals[index])
        self_jank_tagged_count = int(self_jank_tagged[index])
        deadline_count = int(deadline[index])
        deadline_or_dropped_count = int(deadline_or_dropped[index])
        non_deadline_self_jank_tagged_count = int(
            non_deadline_self_jank_tagged[index]
        )
        other_jank_tagged_count = int(other_jank_tagged[index])
        dropped_count = int(dropped[index])
        unknown_tag_count = int(unknown_tag[index])
        overlapping_jank_tag_count = int(overlapping_jank_tag[index])
        self_jank_tagged_percent = float(percentages[index])
        if frame_count <= 0 or total <= 0:
            raise EvidenceError(f"{context} iteration {index + 1} contains no frames")
        if len(duration_runs[index]) != frame_count or len(overrun_runs[index]) != frame_count:
            raise EvidenceError(f"{context} FrameTiming samples disagree with iteration frameCount")
        if (
            deadline_count + non_deadline_self_jank_tagged_count
            != self_jank_tagged_count
            or self_jank_tagged_count + other_jank_tagged_count > total
        ):
            raise EvidenceError(f"{context} iteration {index + 1} jank counts do not reconcile")
        if (
            dropped_count > total
            or unknown_tag_count > total
            or overlapping_jank_tag_count > total
        ):
            raise EvidenceError(
                f"{context} iteration {index + 1} dropped/unknown/overlap counts exceed surface tokens"
            )
        if not (
            max(deadline_count, dropped_count)
            <= deadline_or_dropped_count
            <= min(total, deadline_count + dropped_count)
        ):
            raise EvidenceError(
                f"{context} iteration {index + 1} App Deadline Missed/Dropped Frame "
                "union does not reconcile"
            )
        deadline_and_dropped_count = (
            deadline_count + dropped_count - deadline_or_dropped_count
        )
        if not 0 <= deadline_and_dropped_count <= min(deadline_count, dropped_count):
            raise EvidenceError(
                f"{context} iteration {index + 1} App Deadline Missed/Dropped Frame "
                "intersection does not reconcile"
            )
        if unknown_tag_count != 0 or overlapping_jank_tag_count != 0:
            raise EvidenceError(
                f"{context} iteration {index + 1} contains unknown-tag or overlapping "
                "Self/Other-tag frames"
            )
        expected_self_tagged_percent = self_jank_tagged_count * 100.0 / total
        app_deadline_missed_percent = deadline_count * 100.0 / total
        app_deadline_missed_or_dropped_percent = (
            deadline_or_dropped_count * 100.0 / total
        )
        if (
            not 0 <= self_jank_tagged_percent <= 100
            or abs(self_jank_tagged_percent - expected_self_tagged_percent) > 1e-6
        ):
            raise EvidenceError(
                f"{context} iteration {index + 1} Self Jank-tagged percentage is inconsistent"
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
                "perfetto_self_jank_tagged_frames": self_jank_tagged_count,
                "perfetto_app_deadline_missed_frames": deadline_count,
                "perfetto_app_deadline_missed_percent": app_deadline_missed_percent,
                "perfetto_app_deadline_missed_or_dropped_frames": (
                    deadline_or_dropped_count
                ),
                "perfetto_app_deadline_missed_or_dropped_percent": (
                    app_deadline_missed_or_dropped_percent
                ),
                "perfetto_app_deadline_missed_and_dropped_frames": (
                    deadline_and_dropped_count
                ),
                "perfetto_non_deadline_self_jank_tagged_frames": (
                    non_deadline_self_jank_tagged_count
                ),
                "perfetto_other_jank_tagged_frames": other_jank_tagged_count,
                "perfetto_dropped_frames": dropped_count,
                "perfetto_unknown_tag_frames": unknown_tag_count,
                "perfetto_overlapping_jank_tag_frames": overlapping_jank_tag_count,
                "perfetto_self_jank_tagged_percent": self_jank_tagged_percent,
            }
        )

    frame_timing_total = sum(int(value) for value in frame_counts)
    total_frames = sum(int(value) for value in totals)
    self_jank_tagged_frames = sum(int(value) for value in self_jank_tagged)
    deadline_frames = sum(int(value) for value in deadline)
    deadline_or_dropped_frames = sum(int(value) for value in deadline_or_dropped)
    non_deadline_self_jank_tagged_frames = sum(
        int(value) for value in non_deadline_self_jank_tagged
    )
    other_jank_tagged_frames = sum(int(value) for value in other_jank_tagged)
    dropped_frames = sum(int(value) for value in dropped)
    unknown_tag_frames = sum(int(value) for value in unknown_tag)
    overlapping_jank_tag_frames = sum(int(value) for value in overlapping_jank_tag)
    if frame_timing_total < 100 or total_frames < 100:
        raise EvidenceError(f"{context} must contain at least 100 aggregate frames")
    self_jank_tagged_percent = self_jank_tagged_frames * 100.0 / total_frames
    app_deadline_missed_percent = deadline_frames * 100.0 / total_frames
    app_deadline_missed_or_dropped_percent = (
        deadline_or_dropped_frames * 100.0 / total_frames
    )
    if app_deadline_missed_or_dropped_percent > 10.0:
        raise EvidenceError(
            f"{context} App Deadline Missed or Dropped Frame surface tokens exceed "
            "the 10% controlled-AVD budget"
        )
    if (
        deadline_frames + non_deadline_self_jank_tagged_frames
        != self_jank_tagged_frames
    ):
        raise EvidenceError(f"{context} pooled jank categories do not reconcile")
    if self_jank_tagged_frames + other_jank_tagged_frames > total_frames:
        raise EvidenceError(f"{context} pooled Self/Other Jank tags exceed surface tokens")
    if not (
        max(deadline_frames, dropped_frames)
        <= deadline_or_dropped_frames
        <= min(total_frames, deadline_frames + dropped_frames)
    ):
        raise EvidenceError(
            f"{context} pooled App Deadline Missed/Dropped Frame union does not reconcile"
        )
    deadline_and_dropped_frames = (
        deadline_frames + dropped_frames - deadline_or_dropped_frames
    )
    if not 0 <= deadline_and_dropped_frames <= min(deadline_frames, dropped_frames):
        raise EvidenceError(
            f"{context} pooled App Deadline Missed/Dropped Frame intersection does not reconcile"
        )
    if (
        unknown_tag_frames != 0
        or overlapping_jank_tag_frames != 0
    ):
        raise EvidenceError(
            f"{context} contains unknown-tag or overlapping Self/Other-tag Perfetto frames"
        )
    overrun_positive = sum(
        value > 0.0 for iteration_values in overrun_runs for value in iteration_values
    )
    overrun_positive_percent = overrun_positive * 100.0 / frame_timing_total
    if (
        duration_percentiles["P95"] > MAX_FRAME_DURATION_CPU_P95_MS
        or duration_percentiles["P99"] > MAX_FRAME_DURATION_CPU_P99_MS
    ):
        raise EvidenceError(
            f"{context} frameDurationCpuMs exceeds the controlled-AVD CPU-work ceilings"
        )

    profiler_outputs = result.get("profilerOutputs")
    if not isinstance(profiler_outputs, list) or len(profiler_outputs) != iterations:
        raise EvidenceError(f"{context} must contain one profiler output per iteration")
    raw_source_names: list[str] = []
    for index, output in enumerate(profiler_outputs, start=1):
        if not isinstance(output, Mapping) or set(output) != {"type", "label", "filename"}:
            raise EvidenceError(f"{context} profiler output {index} has an invalid shape")
        if output.get("type") != "PerfettoTrace":
            raise EvidenceError(f"{context} profiler output {index} is not a Perfetto trace")
        label = output.get("label")
        filename = output.get("filename")
        if label != f"Trace Iteration {index - 1}" or not isinstance(filename, str):
            raise EvidenceError(f"{context} profiler output {index} is incomplete")
        source_name = PurePosixPath(filename.replace("\\", "/")).name
        if not source_name.endswith(".perfetto-trace") or source_name in raw_source_names:
            raise EvidenceError(f"{context} profiler output filenames are invalid or duplicated")
        raw_source_names.append(source_name)
    if raw_source_names != list(trace_source_names):
        raise EvidenceError(f"{context} profiler outputs do not match the bound trace files")

    return {
        "metric_source": "androidx.macrobenchmark.FrameTimingMetric+HermesFrameJankMetric",
        "iterations": normalized_iterations,
        "frame_timing_total_rendered": frame_timing_total,
        "frame_timing_overrun_positive": overrun_positive,
        "frame_timing_overrun_positive_percent": overrun_positive_percent,
        "perfetto_surface_frame_timeline_tokens": total_frames,
        "perfetto_self_jank_tagged": self_jank_tagged_frames,
        "perfetto_app_deadline_missed": deadline_frames,
        "perfetto_app_deadline_missed_percent": app_deadline_missed_percent,
        "perfetto_app_deadline_missed_or_dropped": deadline_or_dropped_frames,
        "perfetto_app_deadline_missed_or_dropped_percent": (
            app_deadline_missed_or_dropped_percent
        ),
        "perfetto_app_deadline_missed_and_dropped": deadline_and_dropped_frames,
        "perfetto_non_deadline_self_jank_tagged": non_deadline_self_jank_tagged_frames,
        "perfetto_other_jank_tagged": other_jank_tagged_frames,
        "perfetto_dropped": dropped_frames,
        "perfetto_unknown_tag": unknown_tag_frames,
        "perfetto_overlapping_jank_tag": overlapping_jank_tag_frames,
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


def _validate_performance(
    path: Path,
    profile: str,
    source_digest: str,
    version_name: str,
    version_code: int,
    *,
    litertlm_coordinate: str = LITERTLM_COORDINATE,
    artifact_path_overrides: Mapping[str, Path] | None = None,
    perfetto_root: Path | None = None,
) -> dict[str, Any]:
    payload = _json_object(path)
    context = f"performance[{profile}]"
    expected_top_keys = {
        "schema",
        "profile",
        "release_source_digest",
        "benchmark_target_apk_sha256",
        "benchmark_test_apk_sha256",
        "evidence_run_id",
        "package_id",
        "version_name",
        "version_code",
        "build_variant",
        "litertlm_coordinate",
        "recorded_at_epoch_ms",
        "evidence_classification",
        "raw_evidence",
        "benchmark",
        "traces",
        "device",
        "screen",
        "launch",
        "frames",
        "memory",
        "collector",
    }
    if set(payload) != expected_top_keys:
        raise EvidenceError(f"{context} top-level key set does not match performance v2")
    exact_identity = {
        "schema": PERFORMANCE_SCHEMA,
        "profile": profile,
        "release_source_digest": source_digest,
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "build_variant": PERFORMANCE_BUILD_VARIANT,
        "litertlm_coordinate": litertlm_coordinate,
    }
    for field, expected in exact_identity.items():
        if payload.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")
    for field in ("benchmark_target_apk_sha256", "benchmark_test_apk_sha256"):
        if not isinstance(payload.get(field), str) or not HEX_64_RE.fullmatch(payload[field]):
            raise EvidenceError(f"{context}.{field} must be one lowercase SHA-256")
    if not isinstance(payload.get("evidence_run_id"), str) or not RUN_ID_RE.fullmatch(
        payload["evidence_run_id"]
    ):
        raise EvidenceError(f"{context}.evidence_run_id is invalid")
    _integer(payload, "recorded_at_epoch_ms", context, positive=True)
    if payload.get("evidence_classification") != {
        "environment": "headed-hardware-accelerated-avd",
        "result_kind": "validation-signal",
        "representative_end_user_benchmark": False,
    }:
        raise EvidenceError(f"{context} must label AVD metrics as non-representative validation signals")

    benchmark = _nested_object(payload, "benchmark", context)
    expected_benchmark = {
        "target_package_id": PACKAGE_ID,
        "test_package_id": BENCHMARK_TEST_PACKAGE_ID,
        "runner": "androidx.test.runner.AndroidJUnitRunner",
        "test_id": BENCHMARK_TEST_ID,
        "androidx_benchmark_coordinate": ANDROIDX_BENCHMARK_COORDINATE,
        "compilation_mode": "Full",
        "reporting_package_compilation_mode": REPORTING_PACKAGE_COMPILATION_MODE,
        "target_compiler_filter": TARGET_COMPILER_FILTER,
        "suppressed_errors": ["EMULATOR"],
        "profiling_mode": "None",
        "target_debuggable": False,
        "target_profileable_by_shell": True,
    }
    if set(benchmark) != set(expected_benchmark) | {"iteration_count"}:
        raise EvidenceError(f"{context}.benchmark key set is invalid")
    for field, expected in expected_benchmark.items():
        if benchmark.get(field) != expected:
            raise EvidenceError(f"{context}.benchmark.{field} must equal {expected!r}")
    iteration_count = _integer(benchmark, "iteration_count", f"{context}.benchmark", positive=True)
    if not MIN_BENCHMARK_ITERATIONS <= iteration_count <= MAX_BENCHMARK_ITERATIONS:
        raise EvidenceError(f"{context}.benchmark.iteration_count must be between 5 and 20")

    evidence_root = path.parent.parent
    raw_evidence = _nested_object(payload, "raw_evidence", context)
    if set(raw_evidence) != {"host", "macrobenchmark"}:
        raise EvidenceError(f"{context}.raw_evidence must bind host and Macrobenchmark raw files")
    traces = payload.get("traces")
    if not isinstance(traces, list) or len(traces) != iteration_count:
        raise EvidenceError(f"{context}.traces must contain one entry per iteration")
    expected_references = {
        f"performance/{profile}.host.raw.json",
        f"performance/{profile}.macrobenchmark.raw.json",
        *{
            f"performance/{profile}.traces/iteration-{index:03d}.perfetto-trace"
            for index in range(1, iteration_count + 1)
        },
    }
    overrides = dict(artifact_path_overrides or {})
    if overrides and perfetto_root is not None:
        raise EvidenceError(
            f"{context} cannot combine temporary artifact overrides with an external Perfetto root"
        )
    if overrides and set(overrides) != expected_references:
        raise EvidenceError(f"{context} temporary artifact override set is incomplete or unexpected")

    def validate_reference(
        reference: Any,
        expected_path: str,
        *,
        nonempty: bool = True,
        external_perfetto: bool = False,
    ) -> Path:
        if not isinstance(reference, Mapping) or set(reference) != {"path", "bytes", "sha256"}:
            raise EvidenceError(f"{context} artifact reference for {expected_path} is invalid")
        if reference.get("path") != expected_path:
            raise EvidenceError(f"{context} artifact path must equal {expected_path}")
        size = reference.get("bytes")
        digest = reference.get("sha256")
        if isinstance(size, bool) or not isinstance(size, int) or (size <= 0 if nonempty else size < 0):
            raise EvidenceError(f"{context} artifact {expected_path} has an invalid byte count")
        if not isinstance(digest, str) or not HEX_64_RE.fullmatch(digest):
            raise EvidenceError(f"{context} artifact {expected_path} has an invalid SHA-256")
        if external_perfetto and perfetto_root is not None:
            relative = PurePosixPath(expected_path)
            if not relative.parts or relative.parts[0] != "performance":
                raise EvidenceError(f"{context} external Perfetto path is not canonical")
            artifact_path = perfetto_root.joinpath(*relative.parts[1:])
        else:
            artifact_path = overrides.get(expected_path, evidence_root / Path(expected_path))
        if not artifact_path.is_file() or artifact_path.is_symlink():
            raise EvidenceError(f"{context} artifact {expected_path} is missing or unsafe")
        if artifact_path.stat().st_size != size or _sha256_file(artifact_path) != digest:
            raise EvidenceError(f"{context} artifact {expected_path} bytes/hash do not match")
        return artifact_path

    host_path = validate_reference(
        raw_evidence["host"], f"performance/{profile}.host.raw.json"
    )
    macro_path = validate_reference(
        raw_evidence["macrobenchmark"], f"performance/{profile}.macrobenchmark.raw.json"
    )
    trace_paths: list[Path] = []
    trace_source_names: list[str] = []
    seen_trace_hashes: set[str] = set()
    for index, trace in enumerate(traces, start=1):
        expected_path = f"performance/{profile}.traces/iteration-{index:03d}.perfetto-trace"
        if not isinstance(trace, Mapping) or set(trace) != {
            "iteration",
            "path",
            "source_name",
            "bytes",
            "sha256",
        }:
            raise EvidenceError(f"{context}.traces[{index - 1}] has an invalid key set")
        if trace.get("iteration") != index:
            raise EvidenceError(f"{context}.traces must use contiguous one-based iteration numbers")
        source_name = trace.get("source_name")
        if (
            not isinstance(source_name, str)
            or PurePosixPath(source_name).name != source_name
            or not source_name.endswith(".perfetto-trace")
            or source_name in trace_source_names
        ):
            raise EvidenceError(f"{context}.traces[{index - 1}].source_name is invalid")
        reference = {field: trace[field] for field in ("path", "bytes", "sha256")}
        trace_path = validate_reference(reference, expected_path, external_perfetto=True)
        if trace["sha256"] in seen_trace_hashes:
            raise EvidenceError(f"{context} trace hashes must be unique per iteration")
        seen_trace_hashes.add(trace["sha256"])
        trace_paths.append(trace_path)
        trace_source_names.append(source_name)

    macro_report = _json_object(macro_path)
    expected_frames = _expected_frames_from_macrobenchmark(
        macro_report, payload, profile, trace_source_names
    )
    if payload.get("frames") != expected_frames:
        raise EvidenceError(f"{context}.frames does not exactly reproduce the AndroidX raw report")
    if benchmark["iteration_count"] != len(expected_frames["iterations"]):
        raise EvidenceError(f"{context} iteration count disagrees with AndroidX raw data")

    device = _nested_object(payload, "device", context)
    required_device_keys = {
        "serial",
        "avd_name",
        "boot_id",
        "model",
        "build_fingerprint",
        "android_sdk",
        "supported_abis",
        "hardware_acceleration",
        "acceleration_check",
        "acceleration_check_exit_code",
        "gpu_renderer",
        "active_qemu_process_count",
        "emulator_pid",
        "emulator_process_name",
        "emulator_public_command",
        "emulator_public_command_sha256",
        "emulator_raw_command_sha256",
    }
    if set(device) != required_device_keys:
        raise EvidenceError(f"{context}.device key set is invalid")
    serial = _required_string(device, "serial", f"{context}.device")
    serial_match = re.fullmatch(r"emulator-([0-9]{4,5})", serial)
    if not serial_match or int(serial_match.group(1)) % 2:
        raise EvidenceError(f"{context}.device.serial is not one exact emulator console serial")
    avd_name = _required_string(device, "avd_name", f"{context}.device")
    if not AVD_NAME_RE.fullmatch(avd_name):
        raise EvidenceError(f"{context}.device.avd_name is invalid")
    boot_id = _required_string(device, "boot_id", f"{context}.device").lower()
    if not BOOT_ID_RE.fullmatch(boot_id):
        raise EvidenceError(f"{context}.device.boot_id is invalid")
    _required_string(device, "model", f"{context}.device")
    _required_string(device, "build_fingerprint", f"{context}.device")
    if _integer(device, "android_sdk", f"{context}.device", positive=True) < 31:
        raise EvidenceError(f"{context}.device.android_sdk must support FrameTimeline")
    supported_abis = _normalized_abis(device.get("supported_abis"), f"{context}.device.supported_abis")
    if "x86_64" not in supported_abis:
        raise EvidenceError(f"{context}.device does not prove the x86_64 AVD")
    if _required_bool(device, "hardware_acceleration", f"{context}.device") is not True:
        raise EvidenceError(f"{context}.device is not hardware accelerated")
    if _integer(device, "acceleration_check_exit_code", f"{context}.device") != 0:
        raise EvidenceError(f"{context}.device acceleration check failed")
    acceleration = _required_string(device, "acceleration_check", f"{context}.device")
    acceleration_normalized = acceleration.casefold()
    if "usable" not in acceleration_normalized or re.search(
        r"\b(?:not|isn't|isnt|unusable|failed|unavailable)\b",
        acceleration_normalized,
    ):
        raise EvidenceError(f"{context}.device acceleration output does not prove usable acceleration")
    renderer = _required_string(device, "gpu_renderer", f"{context}.device")
    if any(marker in renderer.casefold() for marker in SOFTWARE_RENDERER_MARKERS):
        raise EvidenceError(f"{context}.device uses a software renderer")
    if _integer(device, "active_qemu_process_count", f"{context}.device") != 1:
        raise EvidenceError(f"{context}.device must prove exactly one active QEMU process")
    _integer(device, "emulator_pid", f"{context}.device", positive=True)
    process_name = _required_string(device, "emulator_process_name", f"{context}.device")
    if re.fullmatch(r"qemu-system-[a-z0-9_.-]+", process_name.casefold()) is None:
        raise EvidenceError(f"{context}.device emulator process is not QEMU")
    emulator_command = _required_string(
        device, "emulator_public_command", f"{context}.device"
    )
    try:
        tokens = tuple(shlex.split(emulator_command, posix=False))
    except ValueError as exc:
        raise EvidenceError(f"{context}.device emulator command cannot be tokenized") from exc
    normalized_tokens = [token.strip('"\'') for token in tokens]
    port = int(serial_match.group(1))
    expected_prefix = [process_name.casefold(), "-avd", avd_name]
    expected_suffix = ["-gpu", "host", "-accel", "on"]
    expected_commands = (
        [*expected_prefix, "-port", str(port), *expected_suffix],
        [*expected_prefix, "-ports", f"{port},{port + 1}", *expected_suffix],
    )
    if normalized_tokens not in expected_commands:
        raise EvidenceError(
            f"{context}.device public emulator command is not the canonical headed identity"
        )
    public_sha = _required_string(
        device, "emulator_public_command_sha256", f"{context}.device"
    )
    if not HEX_64_RE.fullmatch(public_sha) or public_sha != hashlib.sha256(
        emulator_command.encode("utf-8")
    ).hexdigest():
        raise EvidenceError(f"{context}.device public emulator command hash is wrong")
    raw_sha = _required_string(
        device, "emulator_raw_command_sha256", f"{context}.device"
    )
    if not HEX_64_RE.fullmatch(raw_sha):
        raise EvidenceError(f"{context}.device raw emulator command hash is invalid")

    screen = _nested_object(payload, "screen", context)
    if set(screen) != {"width_px", "height_px", "width_dp", "height_dp", "density_dpi", "font_scale"}:
        raise EvidenceError(f"{context}.screen key set is invalid")
    width_px = _integer(screen, "width_px", f"{context}.screen", positive=True)
    height_px = _integer(screen, "height_px", f"{context}.screen", positive=True)
    width_dp = _integer(screen, "width_dp", f"{context}.screen", positive=True)
    height_dp = _integer(screen, "height_dp", f"{context}.screen", positive=True)
    density = _integer(screen, "density_dpi", f"{context}.screen", positive=True)
    if _number(screen, "font_scale", f"{context}.screen", positive=True) != 1.0:
        raise EvidenceError(f"{context}.screen.font_scale must equal 1.0")
    _validate_profile_dimensions(profile, width_dp, height_dp, f"{context}.screen")
    physical_width_dp = width_px * 160 / density
    physical_height_dp = height_px * 160 / density
    if (
        width_dp > physical_width_dp + 3
        or height_dp > physical_height_dp + 3
        or physical_width_dp - width_dp > 160
        or physical_height_dp - height_dp > 160
    ):
        raise EvidenceError(f"{context}.screen pixel/dp/density values disagree")

    launch = _nested_object(payload, "launch", context)
    if set(launch) != {"cold_total_ms", "cold_wait_ms", "warm_total_ms", "warm_process_pid"}:
        raise EvidenceError(f"{context}.launch key set is invalid")
    for field in ("cold_total_ms", "cold_wait_ms", "warm_total_ms"):
        _number(launch, field, f"{context}.launch", positive=True)
    _integer(launch, "warm_process_pid", f"{context}.launch", positive=True)
    if launch["cold_total_ms"] > 15_000 or launch["warm_total_ms"] > 5_000:
        raise EvidenceError(f"{context}.launch exceeds the release budget")
    if launch["cold_wait_ms"] > launch["cold_total_ms"] + 1_000:
        raise EvidenceError(f"{context}.launch cold wait/total values disagree")

    memory = _nested_object(payload, "memory", context)
    if set(memory) != {"total_pss_kb", "total_rss_kb"}:
        raise EvidenceError(f"{context}.memory key set is invalid")
    total_pss = _integer(memory, "total_pss_kb", f"{context}.memory", positive=True)
    total_rss = _integer(memory, "total_rss_kb", f"{context}.memory", positive=True)
    if total_pss > total_rss:
        raise EvidenceError(f"{context}.memory PSS cannot exceed RSS")
    budget = MEMORY_BUDGET_KB[profile]
    if total_pss > budget["total_pss_kb"] or total_rss > budget["total_rss_kb"]:
        raise EvidenceError(f"{context}.memory exceeds the {profile} release ceiling")

    collector = _nested_object(payload, "collector", context)
    if set(collector) != {
        "source_digest_algorithm",
        "source_file_count",
        "git_object_format",
        "benchmark_target_apk_device_path",
        "benchmark_test_apk_device_path",
        "scenario",
    }:
        raise EvidenceError(f"{context}.collector key set is invalid")
    if collector.get("source_digest_algorithm") != SOURCE_DIGEST_ALGORITHM:
        raise EvidenceError(f"{context}.collector source digest algorithm is wrong")
    _integer(collector, "source_file_count", f"{context}.collector", positive=True)
    _required_string(collector, "git_object_format", f"{context}.collector")
    for field in ("benchmark_target_apk_device_path", "benchmark_test_apk_device_path"):
        if not _required_string(collector, field, f"{context}.collector").startswith("/"):
            raise EvidenceError(f"{context}.collector.{field} is not an absolute guest path")
    if collector.get("scenario") != "settings-list-fling":
        raise EvidenceError(f"{context}.collector.scenario is wrong")

    host_payload = _json_object(host_path)
    _validate_raw_performance(
        host_payload,
        payload,
        profile,
        source_digest,
        version_name,
        version_code,
        litertlm_coordinate,
    )
    return payload


def _normalized_abis(value: Any, context: str) -> tuple[str, ...]:
    if isinstance(value, str):
        abis = tuple(part.strip() for part in value.split(",") if part.strip())
    elif isinstance(value, list) and all(isinstance(part, str) for part in value):
        abis = tuple(part.strip() for part in value if part.strip())
    else:
        raise EvidenceError(f"{context}.supported_abis must be a comma string or string list")
    if not abis:
        raise EvidenceError(f"{context}.supported_abis is empty")
    return abis


def _validate_model_evidence(
    path: Path,
    artifact: ArtifactSpec,
    performance_records: Sequence[Mapping[str, Any]],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
    *,
    litertlm_coordinate: str = LITERTLM_COORDINATE,
) -> dict[str, Any]:
    payload = _json_object(path)
    context = f"model[{artifact.model_id}]"
    exact_values = {
        "schema": MODEL_EVIDENCE_SCHEMA,
        "release_source_digest": source_digest,
        "candidate_apk_sha256": candidate_apk_sha256,
        "instrumentation_apk_sha256": instrumentation_apk_sha256,
        "evidence_run_id": evidence_run_id,
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "build_variant": BUILD_VARIANT,
        "litertlm_coordinate": litertlm_coordinate,
        "result": "passed",
        "evidence_complete": True,
        "content_addressed": True,
        "backend": artifact.backend,
        "model_id": artifact.model_id,
        "publisher_repository": artifact.repository,
        "publisher_revision": artifact.revision,
        "file_name": artifact.file_name,
        "publisher_expected_bytes": artifact.expected_bytes,
        "device_visible_bytes": artifact.expected_bytes,
        "expected_sha256": artifact.sha256,
        "device_sha256": artifact.sha256,
        "runtime_started": True,
        "health_ok": True,
        "completion_nonempty": True,
    }
    for field, expected in exact_values.items():
        actual = payload.get(field)
        if isinstance(expected, str) and field in {
            "publisher_revision",
            "expected_sha256",
            "device_sha256",
        }:
            actual = actual.lower() if isinstance(actual, str) else actual
        if actual != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}, got {actual!r}")

    expected_method = {
        "litert-lm": (
            "LiteRtLmModelMatrixInstrumentedTest#"
            "provisionedLiteRtLmModelLoadsAndAnswersLocally"
        ),
        "llama.cpp": (
            "LlamaCppModelMatrixInstrumentedTest#"
            "provisionedContentAddressedGgufStartsAndAnswers"
        ),
    }[artifact.runtime]
    if payload.get("instrumentation_method") != expected_method:
        raise EvidenceError(f"{context}.instrumentation_method is not the release matrix test")
    _required_string(payload, "device_path", context)
    _required_string(payload, "status_message", context)
    if _integer(payload, "elapsed_ms", context, positive=True) <= 0:
        raise EvidenceError(f"{context}.elapsed_ms must be positive")
    accelerator = _required_string(payload, "accelerator", context)
    allowed_accelerators = {"cpu", "gpu"} if artifact.runtime == "litert-lm" else {"cpu"}
    if accelerator not in allowed_accelerators:
        raise EvidenceError(f"{context}.accelerator must be one of {sorted(allowed_accelerators)}")
    _integer(payload, "recorded_at_epoch_ms", context, positive=True)
    details = _nested_object(payload, "details", context)
    if _integer(details, "completion_characters", f"{context}.details", positive=True) <= 0:
        raise EvidenceError(f"{context}.details.completion_characters must be positive")
    if artifact.required_llama_cpp_runtime_lane is not None:
        required_lane_values = {
            "runtime_lane": artifact.required_llama_cpp_runtime_lane,
        }
        if artifact.required_llama_cpp_runtime_lane == "turboquant":
            required_lane_values.update(
                {
                    "cache_type_k": "turbo3",
                    "cache_type_v": "turbo3",
                    "flash_attention": "on",
                }
            )
        for field, expected in required_lane_values.items():
            if details.get(field) != expected:
                raise EvidenceError(
                    f"{context}.details.{field} must equal {expected!r}, got {details.get(field)!r}"
                )

    model = _required_string(payload, "device_model", context)
    serial = _required_string(payload, "device_serial", context)
    avd_name = _required_string(payload, "avd_name", context)
    fingerprint = _required_string(payload, "build_fingerprint", context)
    boot_id = _required_string(payload, "device_boot_id", context).lower()
    if not BOOT_ID_RE.fullmatch(boot_id):
        raise EvidenceError(f"{context}.device_boot_id must be a kernel boot UUID")
    sdk = _integer(payload, "android_sdk", context, positive=True)
    abis = _normalized_abis(payload.get("supported_abis"), context)
    if "x86_64" not in abis:
        raise EvidenceError(f"{context}.supported_abis does not identify the x86_64 AVD lane")
    device_match = any(
        record["device"]["model"] == model
        and record["device"]["serial"] == serial
        and record["device"]["avd_name"] == avd_name
        and record["device"]["boot_id"].lower() == boot_id
        and record["device"]["build_fingerprint"] == fingerprint
        and record["device"]["android_sdk"] == sdk
        and tuple(record["device"]["supported_abis"]) == abis
        for record in performance_records
    )
    if not device_match:
        raise EvidenceError(
            f"{context} device model/API/ABI identity does not match a hardware-accelerated profile record"
        )
    return payload


def _validate_historical_e4b_evidence(
    path: Path,
    performance_records: Sequence[Mapping[str, Any]],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
) -> dict[str, Any]:
    payload = _validate_model_evidence(
        path,
        HISTORICAL_E4B_ARTIFACT,
        performance_records,
        source_digest,
        candidate_apk_sha256,
        instrumentation_apk_sha256,
        evidence_run_id,
        version_name,
        version_code,
    )
    context = "model[gemma-4-e4b-litert-lm]"
    expected_top_level_keys = {
        "schema",
        "release_source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "evidence_run_id",
        "package_id",
        "version_name",
        "version_code",
        "build_variant",
        "litertlm_coordinate",
        "result",
        "evidence_complete",
        "content_addressed",
        "backend",
        "instrumentation_method",
        "model_id",
        "publisher_repository",
        "publisher_revision",
        "file_name",
        "device_path",
        "publisher_expected_bytes",
        "device_visible_bytes",
        "expected_sha256",
        "device_sha256",
        "runtime_started",
        "health_ok",
        "completion_nonempty",
        "elapsed_ms",
        "accelerator",
        "status_message",
        "device_model",
        "device_serial",
        "avd_name",
        "device_boot_id",
        "build_fingerprint",
        "android_sdk",
        "supported_abis",
        "recorded_at_epoch_ms",
        "details",
        "evidence_file",
    }
    _exact_keys(payload, expected_top_level_keys, context)
    if payload["accelerator"] != "cpu":
        raise EvidenceError(f"{context}.accelerator must equal 'cpu'")
    evidence_file = _required_string(payload, "evidence_file", context)
    if not re.fullmatch(
        r"/data/user/0/com\.mobilefork\.hermesagent/files/hermes-model-evidence/"
        r"litert-lm-gemma-4-E4B-it\.litertlm-[0-9]+\.json",
        evidence_file,
    ):
        raise EvidenceError(f"{context}.evidence_file does not match ModelMatrixEvidence output")
    details = _nested_object(payload, "details", context)
    expected_detail_keys = {
        "health_backend",
        "runtime_entrypoint",
        "provisioning_method",
        "accelerator_attempts",
        "requested_accelerator",
        "gpu_attempted",
        "requested_speculative_decoding",
        "speculative_decoding",
        "mtp_policy",
        "image_input_supported",
        "audio_input_supported",
        "clean_shutdown",
        "completion_characters",
        "artifact_summary",
    }
    _exact_keys(details, expected_detail_keys, f"{context}.details")
    exact_details = {
        "health_backend": "litert-lm",
        "runtime_entrypoint": "on-device-backend-manager",
        "provisioning_method": "content-addressed-preprovisioned-preferred-download-record",
        "requested_accelerator": "cpu",
        "gpu_attempted": False,
        "requested_speculative_decoding": "disabled",
        "speculative_decoding": False,
        "image_input_supported": False,
        "audio_input_supported": False,
        "clean_shutdown": True,
    }
    for field, expected in exact_details.items():
        if details.get(field) != expected:
            raise EvidenceError(f"{context}.details.{field} must equal {expected!r}")
    mtp_policy = _required_string(details, "mtp_policy", f"{context}.details")
    if not mtp_policy.startswith("disabled:"):
        raise EvidenceError(f"{context}.details.mtp_policy must start with 'disabled:'")
    attempts = details.get("accelerator_attempts")
    if not isinstance(attempts, list) or not attempts or any(
        not isinstance(attempt, str) or not attempt.strip() for attempt in attempts
    ):
        raise EvidenceError(f"{context}.details.accelerator_attempts must be nonblank strings")
    if not any("cpu/" in attempt.lower() for attempt in attempts):
        raise EvidenceError(f"{context}.details.accelerator_attempts does not contain a CPU engine attempt")
    _required_string(details, "artifact_summary", f"{context}.details")
    return payload


ISSUE_RELEASE_IDENTITY_KEYS = {
    "release_source_digest",
    "candidate_apk_sha256",
    "instrumentation_apk_sha256",
    "evidence_run_id",
    "package_id",
    "version_name",
    "version_code",
    "release_tag",
    "build_variant",
    "lite_rt_lm_coordinate",
    "device_serial",
    "avd_name",
    "device_boot_id",
    "device_model",
    "build_fingerprint",
    "android_sdk",
    "supported_abis",
    "profile",
}


def _validate_issue_release_identity(
    identity: Mapping[str, Any],
    context: str,
    performance_records: Sequence[Mapping[str, Any]],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
    tag: str,
) -> Mapping[str, Any]:
    _exact_keys(identity, ISSUE_RELEASE_IDENTITY_KEYS, context)
    exact_values = {
        "release_source_digest": source_digest,
        "candidate_apk_sha256": candidate_apk_sha256,
        "instrumentation_apk_sha256": instrumentation_apk_sha256,
        "evidence_run_id": evidence_run_id,
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "release_tag": tag,
        "build_variant": BUILD_VARIANT,
        "lite_rt_lm_coordinate": litertlm_coordinate_for_tag(tag),
    }
    for field, expected in exact_values.items():
        if identity.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")

    profile = _required_string(identity, "profile", context)
    if profile not in PROFILES:
        raise EvidenceError(f"{context}.profile must be one of {list(PROFILES)}")
    performance = performance_records[PROFILES.index(profile)]
    device = performance["device"]
    exact_device_values = {
        "device_serial": device["serial"],
        "avd_name": device["avd_name"],
        "device_boot_id": device["boot_id"],
        "device_model": device["model"],
        "build_fingerprint": device["build_fingerprint"],
        "android_sdk": device["android_sdk"],
    }
    for field, expected in exact_device_values.items():
        actual = identity.get(field)
        if field == "device_boot_id" and isinstance(actual, str):
            actual = actual.lower()
            expected = expected.lower()
        if actual != expected:
            raise EvidenceError(f"{context}.{field} does not match performance[{profile}]")
    supported_abis = _normalized_abis(identity.get("supported_abis"), context)
    if supported_abis != tuple(device["supported_abis"]):
        raise EvidenceError(f"{context}.supported_abis does not match performance[{profile}]")
    return performance


def _validate_issue8_evidence(
    path: Path,
    performance_records: Sequence[Mapping[str, Any]],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
    tag: str,
) -> dict[str, Any]:
    payload = _json_object(path)
    context = "issue_evidence[8]"
    _exact_keys(
        payload,
        {
            "schema",
            "issue_number",
            "result",
            "overall_exit_code",
            "evidence_source",
            "instrumentation_method",
            "release_identity",
            "direct_tool_routes",
            "catalog_policy",
            "twelve_b_preflight",
            "validation_errors",
        },
        context,
    )
    exact_values = {
        "schema": ISSUE8_EVIDENCE_SCHEMA,
        "issue_number": 8,
        "result": "pass",
        "overall_exit_code": 0,
        "evidence_source": "instrumentation",
        "instrumentation_method": ISSUE8_INSTRUMENTATION_METHOD,
    }
    for field, expected in exact_values.items():
        if payload.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")
    if payload.get("validation_errors") != []:
        raise EvidenceError(f"{context}.validation_errors must be an empty list")
    identity = _nested_object(payload, "release_identity", context)
    _validate_issue_release_identity(
        identity,
        f"{context}.release_identity",
        performance_records,
        source_digest,
        candidate_apk_sha256,
        instrumentation_apk_sha256,
        evidence_run_id,
        version_name,
        version_code,
        tag,
    )
    if identity["profile"] != "phone-compact":
        raise EvidenceError(f"{context}.release_identity.profile must equal 'phone-compact'")

    routes = payload.get("direct_tool_routes")
    if not isinstance(routes, list) or len(routes) != 2 or any(not isinstance(route, dict) for route in routes):
        raise EvidenceError(f"{context}.direct_tool_routes must contain exactly two objects")
    expected_routes = {
        "Run a command to tell me what time it is.": ("terminal_tool", "date", "time"),
        "Check my device status": ("android_device_diagnostics_tool", "status", "status"),
    }
    observed_prompts: set[str] = set()
    for index, route in enumerate(routes):
        route_context = f"{context}.direct_tool_routes[{index}]"
        _exact_keys(
            route,
            {
                "prompt",
                "tool_name",
                "tool_action",
                "visible_tool_event",
                "visible_result_event",
                "visible_result_text",
                "executed_tool_calls",
                "model_request_count",
                "provider_network_request_count",
            },
            route_context,
        )
        prompt = _required_string(route, "prompt", route_context)
        if prompt not in expected_routes or prompt in observed_prompts:
            raise EvidenceError(f"{route_context}.prompt is not one unique required issue-8 prompt")
        observed_prompts.add(prompt)
        expected_tool, expected_action, result_kind = expected_routes[prompt]
        if route.get("tool_name") != expected_tool or route.get("tool_action") != expected_action:
            raise EvidenceError(f"{route_context} does not prove the expected direct native route")
        if route.get("visible_tool_event") is not True or route.get("visible_result_event") is not True:
            raise EvidenceError(f"{route_context} must prove visible tool and result events")
        if route.get("executed_tool_calls") != 1:
            raise EvidenceError(f"{route_context}.executed_tool_calls must equal 1")
        if route.get("model_request_count") != 0 or route.get("provider_network_request_count") != 0:
            raise EvidenceError(f"{route_context} must make zero model and provider network requests")
        visible_result = _required_string(route, "visible_result_text", route_context)
        if result_kind == "time" and not re.search(r"\b[0-9]{4}\b", visible_result):
            raise EvidenceError(f"{route_context}.visible_result_text lacks a four-digit year")
        if result_kind == "status" and '"status"' not in visible_result:
            raise EvidenceError(f"{route_context}.visible_result_text lacks the native status result")
    if observed_prompts != set(expected_routes):
        raise EvidenceError(f"{context}.direct_tool_routes does not cover both exact prompts")

    catalog = _nested_object(payload, "catalog_policy", context)
    catalog_context = f"{context}.catalog_policy"
    _exact_keys(
        catalog,
        {
            "evaluation_source",
            "model_id",
            "repository",
            "revision",
            "file_name",
            "catalog_declared_bytes",
            "expected_sha256",
            "release_certified",
            "quick_start_eligible",
            "present_in_mobile_quick_catalog",
            "automatically_selected",
            "artifact_file_present",
        },
        catalog_context,
    )
    exact_catalog_values = {
        "evaluation_source": "production-mobile-catalog-policy",
        "model_id": ISSUE8_TWELVE_B_MODEL_ID,
        "repository": ISSUE8_TWELVE_B_REPOSITORY,
        "revision": ISSUE8_TWELVE_B_REVISION,
        "file_name": ISSUE8_TWELVE_B_FILE_NAME,
        "catalog_declared_bytes": ISSUE8_TWELVE_B_BYTES,
        "expected_sha256": ISSUE8_TWELVE_B_SHA256,
        "release_certified": False,
        "quick_start_eligible": False,
        "present_in_mobile_quick_catalog": False,
        "automatically_selected": False,
        "artifact_file_present": False,
    }
    for field, expected in exact_catalog_values.items():
        if catalog.get(field) != expected:
            raise EvidenceError(f"{catalog_context}.{field} must equal {expected!r}")

    preflight = _nested_object(payload, "twelve_b_preflight", context)
    preflight_context = f"{context}.twelve_b_preflight"
    _exact_keys(
        preflight,
        {
            "model_id",
            "repository",
            "revision",
            "file_name",
            "catalog_declared_bytes",
            "model_bytes_evaluated",
            "expected_sha256",
            "backend",
            "artifact_path",
            "artifact_file_present",
            "evaluation_source",
            "memory_profile",
            "requested_context_tokens",
            "effective_context_tokens",
            "estimated_additional_bytes",
            "preflight_allowed",
            "preflight_level",
            "blocked_before_native_engine",
            "native_engine_start_attempted",
            "native_engine_started",
            "requires_app_restart",
            "reason",
        },
        preflight_context,
    )
    exact_preflight_values = {
        "model_id": ISSUE8_TWELVE_B_MODEL_ID,
        "repository": ISSUE8_TWELVE_B_REPOSITORY,
        "revision": ISSUE8_TWELVE_B_REVISION,
        "file_name": ISSUE8_TWELVE_B_FILE_NAME,
        "catalog_declared_bytes": ISSUE8_TWELVE_B_BYTES,
        "model_bytes_evaluated": ISSUE8_TWELVE_B_BYTES,
        "expected_sha256": ISSUE8_TWELVE_B_SHA256,
        "backend": "litert-lm",
        "artifact_path": "",
        "artifact_file_present": False,
        "evaluation_source": "production-local-model-runtime-preflight",
        "requested_context_tokens": 32_000,
        "effective_context_tokens": 2_048,
        "estimated_additional_bytes": 10_440_486_640,
        "preflight_allowed": False,
        "preflight_level": "blocked",
        "blocked_before_native_engine": True,
        "native_engine_start_attempted": False,
        "native_engine_started": False,
        "requires_app_restart": False,
    }
    for field, expected in exact_preflight_values.items():
        if preflight.get(field) != expected:
            raise EvidenceError(f"{preflight_context}.{field} must equal {expected!r}")
    memory = _nested_object(preflight, "memory_profile", preflight_context)
    memory_context = f"{preflight_context}.memory_profile"
    _exact_keys(
        memory,
        {
            "source",
            "classification",
            "total_bytes",
            "available_bytes",
            "threshold_bytes",
            "usable_available_bytes",
            "low_memory",
        },
        memory_context,
    )
    exact_memory = {
        "source": "controlled-instrumentation-memory-snapshot",
        "classification": "nominal-16-gib",
        "total_bytes": NOMINAL_SIXTEEN_GIB_BYTES,
        "available_bytes": 10_000_000_000,
        "threshold_bytes": 500_000_000,
        "usable_available_bytes": 9_500_000_000,
        "low_memory": False,
    }
    for field, expected in exact_memory.items():
        if memory.get(field) != expected:
            raise EvidenceError(f"{memory_context}.{field} must equal {expected!r}")
    reason = _required_string(preflight, "reason", preflight_context).lower()
    if "usable ram" not in reason or "choose a smaller model" not in reason:
        raise EvidenceError(f"{preflight_context}.reason is not the actionable production memory block")
    return payload


def _validate_issue16_command(
    value: Mapping[str, Any],
    context: str,
    expected_command: str,
    expected_output: str,
) -> None:
    _exact_keys(
        value,
        {"command", "exit_code", "stdout", "stderr", "sandbox_execution_mode"},
        context,
    )
    if value.get("command") != expected_command:
        raise EvidenceError(f"{context}.command must equal {expected_command!r}")
    if value.get("exit_code") != 0:
        raise EvidenceError(f"{context}.exit_code must equal 0")
    if value.get("sandbox_execution_mode") != "proot_distro_qemu":
        raise EvidenceError(f"{context}.sandbox_execution_mode must equal 'proot_distro_qemu'")
    stdout = _required_string(value, "stdout", context)
    stderr = value.get("stderr")
    if not isinstance(stderr, str) or stderr.strip():
        raise EvidenceError(f"{context}.stderr must be blank")
    if expected_output not in stdout:
        raise EvidenceError(f"{context}.stdout lacks {expected_output!r}")
    if "permission denied" in stdout.lower():
        raise EvidenceError(f"{context}.stdout contains Permission denied")


def _validate_issue16_native_route(
    value: Mapping[str, Any],
    context: str,
    expected_file_name: str,
) -> str:
    _exact_keys(
        value,
        {"route_path", "path", "expected_file_name", "exists", "executable", "trusted"},
        context,
    )
    if value.get("expected_file_name") != expected_file_name:
        raise EvidenceError(f"{context}.expected_file_name must equal {expected_file_name!r}")
    if value.get("exists") is not True or value.get("executable") is not True or value.get("trusted") is not True:
        raise EvidenceError(f"{context} must be an existing executable trusted APK-native route")
    _required_string(value, "route_path", context)
    resolved = _required_string(value, "path", context).replace("\\", "/")
    if not resolved.endswith(f"/{expected_file_name}") or "/data/app/" not in resolved:
        raise EvidenceError(f"{context}.path is not the package-manager-extracted native library")
    if "/data/user/" in resolved or "/data/data/" in resolved:
        raise EvidenceError(f"{context}.path resolves into writable app data")
    return resolved


def _validate_issue16_evidence(
    path: Path,
    performance_records: Sequence[Mapping[str, Any]],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
    tag: str,
) -> dict[str, Any]:
    payload = _json_object(path)
    context = "issue_evidence[16]"
    _exact_keys(
        payload,
        {
            "schema",
            "issue_number",
            "result",
            "overall_exit_code",
            "release_identity",
            "packaged_runtime",
            "sandbox",
            "guest_routing",
            "commands",
            "cleanup",
            "validation_errors",
        },
        context,
    )
    exact_values = {
        "schema": ISSUE16_EVIDENCE_SCHEMA,
        "issue_number": 16,
        "result": "pass",
        "overall_exit_code": 0,
    }
    for field, expected in exact_values.items():
        if payload.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")
    if payload.get("validation_errors") != []:
        raise EvidenceError(f"{context}.validation_errors must be an empty list")
    identity = _nested_object(payload, "release_identity", context)
    _validate_issue_release_identity(
        identity,
        f"{context}.release_identity",
        performance_records,
        source_digest,
        candidate_apk_sha256,
        instrumentation_apk_sha256,
        evidence_run_id,
        version_name,
        version_code,
        tag,
    )
    if identity["profile"] != "phone-compact":
        raise EvidenceError(f"{context}.release_identity.profile must equal 'phone-compact'")

    packaged = _nested_object(payload, "packaged_runtime", context)
    packaged_context = f"{context}.packaged_runtime"
    _exact_keys(
        packaged,
        {
            "packaged_asset_path",
            "packaged_asset_sha256",
            "packaged_asset_skipped",
            "packaged_assets_present",
            "execution_mode",
            "uses_termux",
            "android_abi",
            "asset_manifest_sha256",
            "asset_refresh_error",
            "native_execution_route",
            "proot_direct_exec_patch_ready",
            "host_printenv_command",
            "host_printenv_exit_code",
            "host_printenv_stdout",
            "host_printenv_stderr",
            "proot_executable",
            "trusted_native_routes",
        },
        packaged_context,
    )
    android_abi = _required_string(packaged, "android_abi", packaged_context)
    if android_abi not in _normalized_abis(identity.get("supported_abis"), packaged_context):
        raise EvidenceError(f"{packaged_context}.android_abi is not in the bound device ABI list")
    exact_packaged = {
        "packaged_asset_path": f"hermes-linux/{android_abi}/manifest.json",
        "packaged_asset_skipped": False,
        "packaged_assets_present": True,
        "execution_mode": "embedded_termux",
        "uses_termux": True,
        "asset_refresh_error": "",
        "native_execution_route": "apk_native_library_direct",
        "proot_direct_exec_patch_ready": True,
        "host_printenv_command": "printenv HERMES_ANDROID_PROOT_EXECUTABLE",
        "host_printenv_exit_code": 0,
        "host_printenv_stderr": "",
    }
    for field, expected in exact_packaged.items():
        if packaged.get(field) != expected:
            raise EvidenceError(f"{packaged_context}.{field} must equal {expected!r}")
    asset_sha = packaged.get("packaged_asset_sha256")
    if not isinstance(asset_sha, str) or not HEX_64_RE.fullmatch(asset_sha):
        raise EvidenceError(f"{packaged_context}.packaged_asset_sha256 must be one lowercase SHA-256")
    if packaged.get("asset_manifest_sha256") != asset_sha:
        raise EvidenceError(f"{packaged_context}.asset_manifest_sha256 does not match packaged asset bytes")
    routes = _nested_object(packaged, "trusted_native_routes", packaged_context)
    _exact_keys(routes, {"proot", "qemu_user", "coreutils"}, f"{packaged_context}.trusted_native_routes")
    proot_path = _validate_issue16_native_route(
        _nested_object(routes, "proot", f"{packaged_context}.trusted_native_routes"),
        f"{packaged_context}.trusted_native_routes.proot",
        "libhermes_exec_bin_proot.so",
    )
    qemu = _nested_object(routes, "qemu_user", f"{packaged_context}.trusted_native_routes")
    qemu_name = qemu.get("expected_file_name")
    if qemu_name not in {
        "libhermes_exec_bin_qemu_aarch64.so",
        "libhermes_exec_bin_qemu_x86_64.so",
    }:
        raise EvidenceError(f"{packaged_context}.trusted_native_routes.qemu_user has an invalid QEMU binary")
    _validate_issue16_native_route(qemu, f"{packaged_context}.trusted_native_routes.qemu_user", qemu_name)
    _validate_issue16_native_route(
        _nested_object(routes, "coreutils", f"{packaged_context}.trusted_native_routes"),
        f"{packaged_context}.trusted_native_routes.coreutils",
        "libhermes_exec_bin_coreutils.so",
    )
    if packaged.get("proot_executable") != proot_path or packaged.get("host_printenv_stdout", "").strip() != proot_path:
        raise EvidenceError(f"{packaged_context} printenv does not resolve the trusted packaged PRoot")

    sandbox = _nested_object(payload, "sandbox", context)
    sandbox_context = f"{context}.sandbox"
    _exact_keys(
        sandbox,
        {
            "name",
            "fresh_requested",
            "sandbox_existed_before",
            "deploy_exit_code",
            "deployment_completed",
            "failed_phase",
            "sandbox_state",
            "sandbox_preserved_for_retry",
            "update_exit_code",
            "update_command",
            "requested_timeout_seconds",
            "guest_ca_bundle",
        },
        sandbox_context,
    )
    sandbox_name = _required_string(sandbox, "name", sandbox_context)
    if not SAFE_ARTIFACT_NAME_RE.fullmatch(sandbox_name):
        raise EvidenceError(f"{sandbox_context}.name is unsafe")
    exact_sandbox = {
        "fresh_requested": True,
        "sandbox_existed_before": False,
        "deploy_exit_code": 0,
        "deployment_completed": True,
        "failed_phase": "",
        "sandbox_state": "ready",
        "sandbox_preserved_for_retry": False,
        "update_exit_code": 0,
        "update_command": (
            "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && "
            "DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install curl"
        ),
        "requested_timeout_seconds": 900,
    }
    for field, expected in exact_sandbox.items():
        if sandbox.get(field) != expected:
            raise EvidenceError(f"{sandbox_context}.{field} must equal {expected!r}")
    ca = _nested_object(sandbox, "guest_ca_bundle", sandbox_context)
    ca_context = f"{sandbox_context}.guest_ca_bundle"
    ca_base_keys = {"exit_code", "path", "source", "certificate_count", "android_certificate_count", "sha256"}
    ca_replace_keys = ca_base_keys | {"replaced_truncated_guest_bundle", "previous_certificate_count"}
    if frozenset(ca) not in {frozenset(ca_base_keys), frozenset(ca_replace_keys)}:
        raise EvidenceError(f"{ca_context} key set is not one closed producer shape")
    if ca.get("exit_code") != 0:
        raise EvidenceError(f"{ca_context}.exit_code must equal 0")
    ca_path = _required_string(ca, "path", ca_context).replace("\\", "/")
    if not ca_path.endswith("/etc/ssl/certs/ca-certificates.crt"):
        raise EvidenceError(f"{ca_context}.path is not the guest CA bundle")
    ca_source = _required_string(ca, "source", ca_context).replace("\\", "/")
    if ca_source != "existing_guest_bundle" and ca_source not in {
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts",
    }:
        raise EvidenceError(f"{ca_context}.source is not an Android trust-root source")
    certificate_count = _integer(ca, "certificate_count", ca_context, positive=True)
    android_certificate_count = _integer(ca, "android_certificate_count", ca_context, positive=True)
    if certificate_count < android_certificate_count:
        raise EvidenceError(f"{ca_context}.certificate_count is below the Android trust-root count")
    if not isinstance(ca.get("sha256"), str) or not HEX_64_RE.fullmatch(ca["sha256"]):
        raise EvidenceError(f"{ca_context}.sha256 must be one lowercase SHA-256")

    guest_routing = _nested_object(payload, "guest_routing", context)
    routing_context = f"{context}.guest_routing"
    _exact_keys(
        guest_routing,
        {
            "expected_path",
            "observed_path",
            "path_command",
            "path_exit_code",
            "guest_only_path",
            "id_route",
            "uname_route",
            "curl_route",
            "id_path",
            "uname_path",
            "curl_path",
        },
        routing_context,
    )
    exact_routing = {
        "expected_path": GUEST_ONLY_PATH,
        "observed_path": GUEST_ONLY_PATH,
        "path_command": "printf '%s\\n' \"$PATH\"",
        "path_exit_code": 0,
        "guest_only_path": True,
        "id_path": "/usr/bin/id",
        "uname_path": "/usr/bin/uname",
        "curl_path": "/usr/bin/curl",
    }
    for field, expected in exact_routing.items():
        if guest_routing.get(field) != expected:
            raise EvidenceError(f"{routing_context}.{field} must equal {expected!r}")
    if "/data/" in guest_routing["observed_path"]:
        raise EvidenceError(f"{routing_context}.observed_path falls through to writable Android storage")
    for field, command, output in (
        ("id_route", "command -v id", "/usr/bin/id"),
        ("uname_route", "command -v uname", "/usr/bin/uname"),
        ("curl_route", "command -v curl", "/usr/bin/curl"),
    ):
        _validate_issue16_command(
            _nested_object(guest_routing, field, routing_context),
            f"{routing_context}.{field}",
            command,
            output,
        )

    commands = _nested_object(payload, "commands", context)
    _exact_keys(commands, {"id", "uname", "curl_version", "https"}, f"{context}.commands")
    for field, command, output in (
        ("id", "id", "uid=0(root)"),
        ("uname", "uname -a", "GNU/Linux"),
        ("curl_version", "curl --version", "curl "),
        (
            "https",
            "curl -fsS https://example.com/ >/dev/null && printf 'HTTPS_OK\\n'",
            "HTTPS_OK",
        ),
    ):
        _validate_issue16_command(
            _nested_object(commands, field, f"{context}.commands"),
            f"{context}.commands.{field}",
            command,
            output,
        )

    cleanup = _nested_object(payload, "cleanup", context)
    cleanup_context = f"{context}.cleanup"
    _exact_keys(
        cleanup,
        {
            "action",
            "exit_code",
            "status_exit_code",
            "agent_shell_enabled",
            "active_sandbox_name",
            "sandbox_name",
            "sandbox_present",
            "sandbox_preserved",
            "sandbox_removed",
            "disposition",
        },
        cleanup_context,
    )
    exact_cleanup = {
        "action": "uninstall",
        "exit_code": 0,
        "status_exit_code": 0,
        "agent_shell_enabled": False,
        "active_sandbox_name": "",
        "sandbox_name": sandbox_name,
        "sandbox_present": False,
        "sandbox_preserved": False,
        "sandbox_removed": True,
        "disposition": "sandbox_removed_stopped",
    }
    for field, expected in exact_cleanup.items():
        if cleanup.get(field) != expected:
            raise EvidenceError(f"{cleanup_context}.{field} must equal {expected!r}")
    return payload


UI_METADATA_KEYS = frozenset(
    {
        "profile",
        "language",
        "theme_id",
        "theme_primary",
        "theme_secondary",
        "theme_background",
        "theme_surface",
        "theme_surface_variant",
        "card_shape",
        "ui_font_scale",
        "screen_width_dp",
        "screen_height_dp",
        "system_font_scale",
        "package_id",
        "version_name",
        "version_code",
        "build_variant",
        "source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "evidence_run_id",
        "device_serial",
        "avd_name",
        "device_boot_id",
        "build_fingerprint",
        "screenshot_sha256",
    }
)
UI_PROOF_BASE_KEYS = frozenset(
    {"evidence_type", "evidence_identity", "artifact", "coverage_kind", "page_id"}
)
PALETTE_KEYS = (
    "theme_primary",
    "theme_secondary",
    "theme_background",
    "theme_surface",
    "theme_surface_variant",
    "card_shape",
    "ui_font_scale",
)


def _safe_artifact_name(value: Any, context: str, suffixes: tuple[str, ...]) -> str:
    if not isinstance(value, str) or not SAFE_ARTIFACT_NAME_RE.fullmatch(value):
        raise EvidenceError(f"{context} must be one safe basename")
    if Path(value).name != value or not value.endswith(suffixes):
        raise EvidenceError(f"{context} has an invalid suffix or path")
    return value


def _strict_key_value_file(path: Path, context: str) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines()
    except (OSError, UnicodeDecodeError) as exc:
        raise EvidenceError(f"Invalid UTF-8 {context} {path}: {exc}") from exc
    if not lines or any(not line for line in lines):
        raise EvidenceError(f"{context} must contain only nonblank key=value lines: {path}")
    result: dict[str, str] = {}
    for line in lines:
        key, marker, raw_value = line.partition("=")
        if not marker or not key or key in result:
            raise EvidenceError(f"Invalid or duplicate {context} line in {path}: {line!r}")
        result[key] = raw_value.strip()
    return result


def _parse_ui_inventory(path: Path, context: str) -> tuple[dict[str, str], list[dict[str, str]]]:
    payload = _strict_key_value_file(path, context)
    base_keys = {
        "evidence_type",
        "coverage_kind",
        "profile",
        "capture_count",
        "source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "evidence_run_id",
        "device_serial",
        "avd_name",
        "device_boot_id",
    }
    if not base_keys.issubset(payload):
        raise EvidenceError(f"{context} is missing fields {sorted(base_keys - set(payload))}")
    try:
        count = int(payload["capture_count"])
    except ValueError as exc:
        raise EvidenceError(f"{context}.capture_count must be an integer") from exc
    if count <= 0 or count > 500:
        raise EvidenceError(f"{context}.capture_count is outside the bounded release range")
    capture_keys = {
        f"capture.{index}.{field}"
        for index in range(1, count + 1)
        for field in ("identity", "screenshot", "proof")
    }
    expected_keys = base_keys | capture_keys
    if set(payload) != expected_keys:
        raise EvidenceError(
            f"{context} key set is invalid; missing={sorted(expected_keys - set(payload))}, "
            f"unexpected={sorted(set(payload) - expected_keys)}"
        )
    captures = []
    for index in range(1, count + 1):
        identity = payload[f"capture.{index}.identity"]
        if not identity or "\n" in identity or len(identity) > 240:
            raise EvidenceError(f"{context}.capture.{index}.identity is invalid")
        captures.append(
            {
                "identity": identity,
                "screenshot": _safe_artifact_name(
                    payload[f"capture.{index}.screenshot"],
                    f"{context}.capture.{index}.screenshot",
                    (".png",),
                ),
                "proof": _safe_artifact_name(
                    payload[f"capture.{index}.proof"],
                    f"{context}.capture.{index}.proof",
                    ("-semantics.txt", "-ui.xml"),
                ),
            }
        )
    if len({capture["identity"] for capture in captures}) != count:
        raise EvidenceError(f"{context} contains duplicate evidence identities")
    if len({capture["screenshot"] for capture in captures}) != count:
        raise EvidenceError(f"{context} contains duplicate screenshot names")
    if len({capture["proof"] for capture in captures}) != count:
        raise EvidenceError(f"{context} contains duplicate proof names")
    return payload, captures


def _parse_compose_ui_proof(path: Path, context: str) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    except (OSError, UnicodeDecodeError) as exc:
        raise EvidenceError(f"Invalid UTF-8 {context} {path}: {exc}") from exc
    header_text, separator, body = text.partition("\n\n")
    if not separator or not body.strip():
        raise EvidenceError(f"{context} has no nonblank semantics body")
    values: dict[str, str] = {}
    sentinels: list[str] = []
    for line in header_text.splitlines():
        key, marker, raw_value = line.partition("=")
        if not marker or not key:
            raise EvidenceError(f"Invalid {context} header line {line!r}")
        if key == "sentinel":
            if not raw_value.strip():
                raise EvidenceError(f"{context} contains a blank visible sentinel")
            sentinels.append(raw_value.strip())
        elif key in values:
            raise EvidenceError(f"{context} contains duplicate field {key!r}")
        else:
            values[key] = raw_value.strip()
    expected = UI_PROOF_BASE_KEYS | UI_METADATA_KEYS
    if set(values) != expected:
        raise EvidenceError(
            f"{context} key set is invalid; missing={sorted(expected - set(values))}, "
            f"unexpected={sorted(set(values) - expected)}"
        )
    if not sentinels:
        raise EvidenceError(f"{context} contains no visible sentinels")
    return {
        "evidence_identity": values["evidence_identity"],
        "artifact": values["artifact"],
        "coverage_kind": values["coverage_kind"],
        "page_id": values["page_id"],
        "metadata": {key: values[key] for key in UI_METADATA_KEYS},
        "sentinels": tuple(sentinels),
        "body": body.strip(),
        "evidence_type": values["evidence_type"],
    }


def _parse_xml_ui_proof(path: Path, context: str) -> dict[str, Any]:
    try:
        root = ET.fromstring(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ET.ParseError) as exc:
        raise EvidenceError(f"Invalid XML {context} {path}: {exc}") from exc
    if root.tag != "hermes-ui-evidence":
        raise EvidenceError(f"{context} root must be hermes-ui-evidence")
    expected_attributes = {"artifact", "evidence-identity", "coverage-kind", "page-id"}
    if set(root.attrib) != expected_attributes:
        raise EvidenceError(f"{context} root attribute set is invalid")
    metadata_node = root.find("metadata")
    hierarchy = root.find("view-hierarchy")
    if metadata_node is None or hierarchy is None or not list(hierarchy):
        raise EvidenceError(f"{context} lacks metadata or a nonblank View hierarchy")
    if [child.tag for child in root] != ["metadata", "view-hierarchy"]:
        raise EvidenceError(f"{context} has unexpected root children")
    metadata: dict[str, str] = {}
    sentinels: list[str] = []
    for child in metadata_node:
        if child.tag == "entry" and set(child.attrib) == {"key", "value"}:
            key = child.attrib["key"]
            if key in metadata:
                raise EvidenceError(f"{context} contains duplicate metadata field {key!r}")
            metadata[key] = child.attrib["value"]
        elif child.tag == "sentinel" and set(child.attrib) == {"value"}:
            value = child.attrib["value"].strip()
            if not value:
                raise EvidenceError(f"{context} contains a blank visible sentinel")
            sentinels.append(value)
        else:
            raise EvidenceError(f"{context} contains an unexpected metadata element")
    if set(metadata) != UI_METADATA_KEYS or not sentinels:
        raise EvidenceError(f"{context} metadata key set or sentinel list is invalid")
    body_fragments: list[str] = []
    for element in hierarchy.iter():
        body_fragments.extend(element.attrib.values())
        if element.text and element.text.strip():
            body_fragments.append(element.text.strip())
        if element.tail and element.tail.strip():
            body_fragments.append(element.tail.strip())
    return {
        "evidence_identity": root.attrib["evidence-identity"],
        "artifact": root.attrib["artifact"],
        "coverage_kind": root.attrib["coverage-kind"],
        "page_id": root.attrib["page-id"],
        "metadata": metadata,
        "sentinels": tuple(sentinels),
        # ElementTree has already decoded XML entities, so exact visible
        # sentinels containing punctuation are compared to proof-body values
        # rather than to an escaped serialization.
        "body": "\n".join(body_fragments),
        "evidence_type": "headed-ui-coverage-bound",
    }


def _normalized_palette(metadata: Mapping[str, str], context: str) -> dict[str, str | float]:
    for field in PALETTE_KEYS[:5]:
        if not HEX_COLOR_RE.fullmatch(metadata.get(field, "")):
            raise EvidenceError(f"{context}.{field} must be one #RRGGBB colour")
    if metadata.get("card_shape") not in {"square", "soft", "rounded"}:
        raise EvidenceError(f"{context}.card_shape is invalid")
    try:
        scale = float(metadata["ui_font_scale"])
    except (KeyError, ValueError) as exc:
        raise EvidenceError(f"{context}.ui_font_scale is invalid") from exc
    if not math.isfinite(scale) or not 0.75 <= scale <= 1.5:
        raise EvidenceError(f"{context}.ui_font_scale is outside the supported range")
    return {
        **{field: metadata[field].upper() for field in PALETTE_KEYS[:5]},
        "card_shape": metadata["card_shape"],
        "ui_font_scale": scale,
    }


def _validate_ui_coverage_capture(
    directory: Path,
    capture: Mapping[str, str],
    inventory: Mapping[str, str],
    performance: Mapping[str, Any],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
    context: str,
) -> dict[str, Any]:
    screenshot_path = directory / capture["screenshot"]
    proof_path = directory / capture["proof"]
    if not screenshot_path.is_file() or not proof_path.is_file():
        raise EvidenceError(f"{context} is missing its screenshot or proof file")
    decoded = _decode_png(screenshot_path)
    proof = (
        _parse_compose_ui_proof(proof_path, context)
        if proof_path.name.endswith("-semantics.txt")
        else _parse_xml_ui_proof(proof_path, context)
    )
    if proof["evidence_type"] != "headed-ui-coverage-bound":
        raise EvidenceError(f"{context} has the wrong evidence_type")
    if proof["evidence_identity"] != capture["identity"]:
        raise EvidenceError(f"{context} evidence identity disagrees with its inventory")
    artifact = screenshot_path.name.removesuffix(".png")
    if proof["artifact"] != artifact:
        raise EvidenceError(f"{context} artifact name disagrees with its screenshot")
    expected_proof_name = (
        f"{artifact}-semantics.txt" if proof_path.name.endswith("-semantics.txt") else f"{artifact}-ui.xml"
    )
    if proof_path.name != expected_proof_name:
        raise EvidenceError(f"{context} proof filename is not derived from its screenshot artifact")
    missing_sentinels = [sentinel for sentinel in proof["sentinels"] if sentinel not in proof["body"]]
    if missing_sentinels:
        raise EvidenceError(
            f"{context} declares sentinels absent from its proof body: {missing_sentinels}"
        )
    metadata = proof["metadata"]
    expected_device = performance["device"]
    exact = {
        "profile": inventory["profile"],
        "source_digest": source_digest,
        "candidate_apk_sha256": candidate_apk_sha256,
        "instrumentation_apk_sha256": instrumentation_apk_sha256,
        "evidence_run_id": evidence_run_id,
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": str(version_code),
        "build_variant": BUILD_VARIANT,
        "device_serial": expected_device["serial"],
        "avd_name": expected_device["avd_name"],
        "device_boot_id": expected_device["boot_id"],
        "build_fingerprint": expected_device["build_fingerprint"],
        "screenshot_sha256": _sha256_file(screenshot_path),
    }
    for field, expected in exact.items():
        if metadata.get(field) != expected:
            raise EvidenceError(f"{context}.{field} does not match {expected}")
    if metadata["language"] not in LANGUAGES:
        raise EvidenceError(f"{context}.language is not a supported release language")
    if not SAFE_THEME_ID_RE.fullmatch(metadata["theme_id"]):
        raise EvidenceError(f"{context}.theme_id is invalid")
    try:
        width_dp = int(metadata["screen_width_dp"])
        height_dp = int(metadata["screen_height_dp"])
        system_font_scale = float(metadata["system_font_scale"])
    except ValueError as exc:
        raise EvidenceError(f"{context} dimensions or system font scale are invalid") from exc
    expected_screen = performance["screen"]
    if (width_dp, height_dp) != (expected_screen["width_dp"], expected_screen["height_dp"]):
        raise EvidenceError(f"{context} dp dimensions disagree with performance evidence")
    if (decoded.width, decoded.height) != (expected_screen["width_px"], expected_screen["height_px"]):
        raise EvidenceError(f"{context} PNG dimensions disagree with performance evidence")
    if not math.isfinite(system_font_scale) or system_font_scale != 1.0:
        raise EvidenceError(f"{context} system_font_scale must equal the release value 1.0")
    palette = _normalized_palette(metadata, context)
    return {
        "identity": capture["identity"],
        "coverage_kind": proof["coverage_kind"],
        "page_id": proof["page_id"],
        "language": metadata["language"],
        "theme_id": metadata["theme_id"],
        "palette": palette,
        "profile": metadata["profile"],
        "proof_sha256": _sha256_file(proof_path),
        "screenshot_content_sha256": decoded.content_pixel_sha256,
        "paths": {
            PurePosixPath(UI_COVERAGE_PREFIX / performance["profile"] / capture["screenshot"]),
            PurePosixPath(UI_COVERAGE_PREFIX / performance["profile"] / capture["proof"]),
        },
    }


def _require_exact_source_pages(
    records: Sequence[Mapping[str, Any]],
    *,
    prefix: str,
    expected_names: Sequence[str],
    expected_kind: str,
    page_id_prefix: str,
    label: str,
    context: str,
) -> None:
    names = [record["identity"].removeprefix(prefix) for record in records]
    expected = set(expected_names)
    actual = set(names)
    if len(names) != len(expected_names) or actual != expected:
        raise EvidenceError(
            f"{context} must cover exactly the source-derived {label}; "
            f"omitted={sorted(expected - actual)}, invented={sorted(actual - expected)}"
        )
    for record, name in zip(records, names, strict=True):
        if record["coverage_kind"] != expected_kind:
            raise EvidenceError(f"{context} {label} capture {name!r} has the wrong coverage kind")
        expected_page_id = f"{page_id_prefix}{name}"
        if record["page_id"] != expected_page_id:
            raise EvidenceError(
                f"{context} {label} {name!r} must use proof page ID {expected_page_id!r}"
            )


def _require_complete_ui_contract(
    records: Sequence[Mapping[str, Any]],
    source_contract: UiEvidenceSourceContract,
    context: str,
) -> Mapping[str, Any]:
    by_prefix: dict[str, list[Mapping[str, Any]]] = {
        prefix: []
        for prefix in (
            "section:",
            "settings:",
            "device:",
            "appearance-preset:",
            "shape:",
            "font:",
            "framework:en:",
        )
    }
    custom = []
    for record in records:
        identity = record["identity"]
        if identity == "appearance-custom-light":
            custom.append(record)
        else:
            matches = [prefix for prefix in by_prefix if identity.startswith(prefix)]
            if len(matches) != 1:
                raise EvidenceError(f"{context} contains an unknown comprehensive identity {identity!r}")
            by_prefix[matches[0]].append(record)
        if record["language"] != "en":
            raise EvidenceError(f"{context} comprehensive profile captures must use English")
    minimums = {"appearance-preset:": 5, "shape:": 3, "font:": 3, "framework:en:": 4}
    for prefix, minimum in minimums.items():
        if len(by_prefix[prefix]) < minimum:
            raise EvidenceError(f"{context} does not cover enough {prefix.removesuffix(':')} surfaces")
    expected_kinds = {
        "appearance-preset:": "appearance-preset",
        "shape:": "rendered-card-shape",
        "font:": "rendered-font-scale",
        "framework:en:": "framework-view-activity",
    }
    for prefix, expected_kind in expected_kinds.items():
        if any(record["coverage_kind"] != expected_kind for record in by_prefix[prefix]):
            raise EvidenceError(f"{context} {prefix} capture has the wrong coverage kind")
    _require_exact_source_pages(
        by_prefix["section:"],
        prefix="section:",
        expected_names=source_contract.app_sections,
        expected_kind="app-section",
        page_id_prefix="",
        label="AppSection destinations",
        context=context,
    )
    _require_exact_source_pages(
        by_prefix["settings:"],
        prefix="settings:",
        expected_names=source_contract.settings_pages,
        expected_kind="settings-subpage",
        page_id_prefix="Settings.",
        label="nested Settings destinations",
        context=context,
    )
    _require_exact_source_pages(
        by_prefix["device:"],
        prefix="device:",
        expected_names=source_contract.device_pages,
        expected_kind="device-subpage",
        page_id_prefix="",
        label="non-Overview DevicePage destinations",
        context=context,
    )
    shapes = {record["identity"].removeprefix("shape:") for record in by_prefix["shape:"]}
    if shapes != {"square", "soft", "rounded"}:
        raise EvidenceError(f"{context} does not prove all supported card shapes")
    font_scales = {record["palette"]["ui_font_scale"] for record in by_prefix["font:"]}
    if not (any(scale < 1.0 for scale in font_scales) and 1.0 in font_scales and any(scale > 1.0 for scale in font_scales)):
        raise EvidenceError(f"{context} does not prove smaller, default, and larger UI font scales")
    if len(custom) != 1 or custom[0]["coverage_kind"] != "custom-light-palette":
        raise EvidenceError(f"{context} must contain one rendered custom-light palette capture")
    return custom[0]


def _require_localized_ui_contract(
    records: Sequence[Mapping[str, Any]],
    english_framework_pages: set[str],
    source_contract: UiEvidenceSourceContract,
    context: str,
) -> None:
    models_by_language: dict[str, set[str]] = {language: set() for language in LANGUAGES}
    framework_by_language: dict[str, set[str]] = {language: set() for language in LANGUAGES if language != "en"}
    for record in records:
        identity = record["identity"]
        if identity.startswith("localized-model:"):
            parts = identity.split(":", 2)
            if len(parts) != 3 or parts[1] != record["language"] or not parts[2]:
                raise EvidenceError(f"{context} contains an invalid localized model identity")
            if record["coverage_kind"] != "six-language-recommended-model":
                raise EvidenceError(f"{context} localized model capture has the wrong coverage kind")
            expected_page_id = f"Settings.Models.{parts[2]}"
            if record["page_id"] != expected_page_id:
                raise EvidenceError(
                    f"{context} recommended model {parts[2]!r} must use proof page ID "
                    f"{expected_page_id!r}"
                )
            models_by_language.get(parts[1], set()).add(parts[2])
        elif identity.startswith("framework:"):
            parts = identity.split(":", 2)
            if len(parts) != 3 or parts[1] != record["language"] or parts[1] == "en" or not parts[2]:
                raise EvidenceError(f"{context} contains an invalid localized framework identity")
            if record["coverage_kind"] != "framework-view-activity":
                raise EvidenceError(f"{context} localized framework capture has the wrong coverage kind")
            framework_by_language.get(parts[1], set()).add(parts[2])
        else:
            raise EvidenceError(f"{context} contains an unknown localized identity {identity!r}")
    expected_models = set(source_contract.recommended_model_ids)
    for language, actual_models in models_by_language.items():
        if actual_models != expected_models:
            raise EvidenceError(
                f"{context} must cover exactly the source-derived recommended models in {language}; "
                f"omitted={sorted(expected_models - actual_models)}, "
                f"invented={sorted(actual_models - expected_models)}"
            )
    if not english_framework_pages:
        raise EvidenceError(f"{context} has no English framework reference set")
    if any(values != english_framework_pages for values in framework_by_language.values()):
        raise EvidenceError(f"{context} does not cover every framework page in each non-English language")


def _validate_ui_coverage_inventory(
    evidence_dir: Path,
    canonical_profile: str,
    inventory_name: str,
    expected_coverage_kind: str,
    performance: Mapping[str, Any],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
) -> tuple[list[dict[str, Any]], set[PurePosixPath]]:
    relative = UI_COVERAGE_PREFIX / canonical_profile / inventory_name
    path = evidence_dir / Path(relative.as_posix())
    context = f"ui_coverage[{canonical_profile}/{inventory_name}]"
    inventory, captures = _parse_ui_inventory(path, context)
    if inventory["evidence_type"] != "headed-ui-coverage-inventory-bound":
        raise EvidenceError(f"{context}.evidence_type is invalid")
    if inventory["coverage_kind"] != expected_coverage_kind:
        raise EvidenceError(f"{context}.coverage_kind is invalid")
    profile_match = HEADED_UI_PROFILE_RE.fullmatch(inventory["profile"])
    expected_kind = "phone" if canonical_profile == "phone-compact" else "tablet"
    if profile_match is None or profile_match.group(1) != expected_kind:
        raise EvidenceError(f"{context}.profile does not match {canonical_profile}")
    width_dp, height_dp = (int(profile_match.group(index)) for index in (2, 3))
    expected_screen = performance["screen"]
    if (width_dp, height_dp) != (expected_screen["width_dp"], expected_screen["height_dp"]):
        raise EvidenceError(f"{context}.profile dimensions disagree with performance evidence")
    expected_device = performance["device"]
    exact = {
        "source_digest": source_digest,
        "candidate_apk_sha256": candidate_apk_sha256,
        "instrumentation_apk_sha256": instrumentation_apk_sha256,
        "evidence_run_id": evidence_run_id,
        "device_serial": expected_device["serial"],
        "avd_name": expected_device["avd_name"],
        "device_boot_id": expected_device["boot_id"],
    }
    for field, expected in exact.items():
        if inventory[field] != expected:
            raise EvidenceError(f"{context}.{field} does not match {expected}")
    directory = path.parent
    records = [
        _validate_ui_coverage_capture(
            directory,
            capture,
            inventory,
            performance,
            source_digest,
            candidate_apk_sha256,
            instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
            f"{context}.capture[{index}]",
        )
        for index, capture in enumerate(captures, start=1)
    ]
    referenced_paths = {relative}
    for record in records:
        referenced_paths.update(record["paths"])
    return records, referenced_paths


def _validate_comprehensive_ui_evidence(
    evidence_dir: Path,
    performance_records: Sequence[Mapping[str, Any]],
    source_contract: UiEvidenceSourceContract,
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
    version_name: str,
    version_code: int,
) -> tuple[set[PurePosixPath], int, dict[str, Mapping[str, Any]]]:
    paths: set[PurePosixPath] = set()
    all_records: list[dict[str, Any]] = []
    custom_light_by_profile: dict[str, Mapping[str, Any]] = {}
    english_framework_pages: set[str] = set()
    for profile, performance in zip(PROFILES, performance_records, strict=True):
        complete, inventory_paths = _validate_ui_coverage_inventory(
            evidence_dir,
            profile,
            COMPLETE_UI_INVENTORY,
            "complete-current-profile",
            performance,
            source_digest,
            candidate_apk_sha256,
            instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
        )
        paths.update(inventory_paths)
        all_records.extend(complete)
        custom_light_by_profile[profile] = _require_complete_ui_contract(
            complete,
            source_contract,
            f"ui_coverage[{profile}/complete]",
        )
        framework_pages = {
            record["identity"].removeprefix("framework:en:")
            for record in complete
            if record["identity"].startswith("framework:en:")
        }
        if not english_framework_pages:
            english_framework_pages = framework_pages
        elif framework_pages != english_framework_pages:
            raise EvidenceError("Phone and tablet comprehensive UI inventories cover different framework pages")

    phone_performance = performance_records[PROFILES.index("phone-compact")]
    localized, localized_paths = _validate_ui_coverage_inventory(
        evidence_dir,
        "phone-compact",
        LOCALIZED_UI_INVENTORY,
        "six-language-and-framework-localization",
        phone_performance,
        source_digest,
        candidate_apk_sha256,
        instrumentation_apk_sha256,
        evidence_run_id,
        version_name,
        version_code,
    )
    _require_localized_ui_contract(
        localized,
        english_framework_pages,
        source_contract,
        "ui_coverage[phone-compact/localized]",
    )
    paths.update(localized_paths)
    all_records.extend(localized)
    all_names = [path.as_posix() for path in paths]
    if len(all_names) != len(set(all_names)):
        raise EvidenceError("Comprehensive UI inventories reference duplicate paths")
    return paths, len(all_records), custom_light_by_profile


def _exact_keys(value: Mapping[str, Any], expected: set[str], context: str) -> None:
    if set(value) != expected:
        raise EvidenceError(
            f"{context} key set is invalid; missing={sorted(expected - set(value))}, "
            f"unexpected={sorted(set(value) - expected)}"
        )


def _registered_nanbeige_repair_artifact(
    artifacts: Sequence[ArtifactSpec],
) -> ArtifactSpec:
    matches = [
        artifact
        for artifact in artifacts
        if artifact.model_id == NANBEIGE_REPAIR_ARTIFACT.model_id
    ]
    if len(matches) != 1:
        raise EvidenceError(
            "The v0.13.151+ physical repair contract requires exactly one registered "
            f"{NANBEIGE_REPAIR_ARTIFACT.model_id} artifact"
        )
    if matches[0] != NANBEIGE_REPAIR_ARTIFACT:
        raise EvidenceError(
            "The registered Nanbeige repair artifact no longer matches its pinned "
            "repository, revision, file name, runtime lane, byte length, or SHA-256"
        )
    return matches[0]


def _validate_physical_nanbeige_repair_evidence(
    path: Path,
    artifacts: Sequence[ArtifactSpec],
    source_digest: str,
    version_name: str,
    version_code: int,
    tag: str,
) -> PhysicalNanbeigeRepairEvidence:
    """Validate one privacy-safe, physical ARM64 before/after Nanbeige record."""

    context = "physical_nanbeige_repair"
    artifact = _registered_nanbeige_repair_artifact(artifacts)
    payload = _json_object(path)
    _exact_keys(
        payload,
        {
            "schema",
            "result",
            "evidence_complete",
            "recorded_at_epoch_ms",
            "release_identity",
            "device_identity",
            "model_identity",
            "stable_precondition",
            "automatic_reconciliation",
            "readiness",
            "ordinary_chat",
            "stop_control",
            "validation_errors",
        },
        context,
    )
    for field, expected in {
        "schema": PHYSICAL_NANBEIGE_REPAIR_SCHEMA,
        "result": "passed",
        "evidence_complete": True,
        "validation_errors": [],
    }.items():
        if payload.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")
    _integer(payload, "recorded_at_epoch_ms", context, positive=True)

    release = _nested_object(payload, "release_identity", context)
    release_context = f"{context}.release_identity"
    _exact_keys(
        release,
        {
            "release_source_digest",
            "release_tag",
            "package_id",
            "version_name",
            "version_code",
            "build_variant",
            "candidate_artifact_name",
            "candidate_apk_bytes",
            "candidate_apk_sha256",
            "installed_base_apk_bytes",
            "installed_base_apk_sha256",
            "candidate_apk_signer_sha256",
            "installed_apk_signer_sha256",
            "source_binding_verified",
            "signer_verified",
        },
        release_context,
    )
    exact_release_identity = {
        "release_source_digest": source_digest,
        "release_tag": tag,
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "build_variant": "release",
        "candidate_artifact_name": f"hermes-agent-android-{tag}-device-candidate.apk",
        "candidate_apk_signer_sha256": EXPECTED_RELEASE_SIGNER_SHA256,
        "installed_apk_signer_sha256": EXPECTED_RELEASE_SIGNER_SHA256,
        "source_binding_verified": True,
        "signer_verified": True,
    }
    for field, expected in exact_release_identity.items():
        if release.get(field) != expected:
            raise EvidenceError(f"{release_context}.{field} must equal {expected!r}")
    candidate_sha = release.get("candidate_apk_sha256")
    installed_sha = release.get("installed_base_apk_sha256")
    if not isinstance(candidate_sha, str) or not HEX_64_RE.fullmatch(candidate_sha):
        raise EvidenceError(f"{release_context}.candidate_apk_sha256 must be lowercase SHA-256")
    if installed_sha != candidate_sha:
        raise EvidenceError(
            f"{release_context}.installed_base_apk_sha256 must equal candidate_apk_sha256"
        )
    candidate_bytes = _integer(release, "candidate_apk_bytes", release_context, positive=True)
    if _integer(release, "installed_base_apk_bytes", release_context, positive=True) != candidate_bytes:
        raise EvidenceError(
            f"{release_context}.installed_base_apk_bytes must equal candidate_apk_bytes"
        )

    device = _nested_object(payload, "device_identity", context)
    device_context = f"{context}.device_identity"
    _exact_keys(
        device,
        {
            "physical_device",
            "adb_transport",
            "adb_serial_sha256",
            "model",
            "manufacturer",
            "product",
            "device",
            "hardware",
            "build_fingerprint",
            "boot_id",
            "android_sdk",
            "primary_abi",
            "supported_abis",
            "ro_kernel_qemu",
            "avd_name",
        },
        device_context,
    )
    if device.get("physical_device") is not True:
        raise EvidenceError(f"{device_context}.physical_device must be true")
    if device.get("adb_transport") not in {"usb", "wireless-tls"}:
        raise EvidenceError(f"{device_context}.adb_transport must be usb or wireless-tls")
    serial_sha = device.get("adb_serial_sha256")
    if (
        not isinstance(serial_sha, str)
        or not HEX_64_RE.fullmatch(serial_sha)
        or serial_sha == hashlib.sha256(b"").hexdigest()
    ):
        raise EvidenceError(f"{device_context}.adb_serial_sha256 must bind one nonblank serial")
    boot_id = _required_string(device, "boot_id", device_context).casefold()
    if BOOT_ID_RE.fullmatch(boot_id) is None:
        raise EvidenceError(f"{device_context}.boot_id is invalid")
    identity_fields = {
        field: _required_string(device, field, device_context)
        for field in (
            "model",
            "manufacturer",
            "product",
            "device",
            "hardware",
            "build_fingerprint",
        )
    }
    identity_text = " ".join(identity_fields.values()).casefold()
    if any(marker in identity_text for marker in PHYSICAL_DEVICE_EMULATOR_MARKERS):
        raise EvidenceError(f"{device_context} contains an emulator identity marker")
    if device.get("ro_kernel_qemu") != "0" or device.get("avd_name") != "":
        raise EvidenceError(f"{device_context} does not prove a non-QEMU physical device")
    if _integer(device, "android_sdk", device_context, positive=True) < 31:
        raise EvidenceError(f"{device_context}.android_sdk must be at least 31")
    if device.get("primary_abi") != "arm64-v8a":
        raise EvidenceError(f"{device_context}.primary_abi must equal 'arm64-v8a'")
    supported_abis = _normalized_abis(device.get("supported_abis"), device_context)
    if supported_abis[0] != "arm64-v8a" or len(set(supported_abis)) != len(supported_abis):
        raise EvidenceError(
            f"{device_context}.supported_abis must be unique and begin with arm64-v8a"
        )

    model = _nested_object(payload, "model_identity", context)
    model_context = f"{context}.model_identity"
    _exact_keys(
        model,
        {
            "model_id",
            "publisher_repository",
            "publisher_revision",
            "file_name",
            "runtime",
            "required_runtime_lane",
            "expected_bytes",
            "device_visible_bytes",
            "expected_sha256",
            "device_sha256",
            "device_path",
            "content_addressed_verification_passed",
        },
        model_context,
    )
    exact_model_identity = {
        "model_id": artifact.model_id,
        "publisher_repository": artifact.repository,
        "publisher_revision": artifact.revision,
        "file_name": artifact.file_name,
        "runtime": artifact.runtime,
        "required_runtime_lane": artifact.required_llama_cpp_runtime_lane,
        "expected_bytes": artifact.expected_bytes,
        "device_visible_bytes": artifact.expected_bytes,
        "expected_sha256": artifact.sha256,
        "device_sha256": artifact.sha256,
        "content_addressed_verification_passed": True,
    }
    for field, expected in exact_model_identity.items():
        if model.get(field) != expected:
            raise EvidenceError(f"{model_context}.{field} must equal {expected!r}")
    model_path = _required_string(model, "device_path", model_context)
    if model_path.casefold() != (PHYSICAL_MODEL_PATH_ROOT + artifact.file_name).casefold():
        raise EvidenceError(
            f"{model_context}.device_path must be the exact app-scoped external model path"
        )

    stable = _nested_object(payload, "stable_precondition", context)
    stable_context = f"{context}.stable_precondition"
    _exact_keys(
        stable,
        {
            "capture_route",
            "model_path",
            "source_candidate_apk_sha256",
            "runtime_directory_path",
            "runtime_closure",
            "runtime_closure_file_count",
            "runtime_closure_total_bytes",
            "runtime_closure_manifest_sha256",
            "system_library_allowlist",
            "unresolved_non_system_dependencies",
            "command_executable_path",
            "command_working_directory",
            "command_library_path",
            "command_model_path",
            "command_environment",
            "command_environment_sha256",
            "command_argv",
            "selected_runtime_lane",
            "exact_artifact_verified",
            "runtime_process_spawned",
            "ready",
            "process_exit_code",
            "failure_stage",
            "unknown_model_architecture",
            "error_message",
            "loader_error_absent",
            "command_argv_sha256",
            "device_runtime_cleanup_verified",
        },
        stable_context,
    )
    expected_runtime_directory = (
        f"/data/local/tmp/hermes-{tag}-{candidate_sha[:16]}-llama-stable"
    )
    expected_runtime_path = (
        f"{expected_runtime_directory}/{PHYSICAL_STABLE_RUNTIME_EXECUTABLE}"
    )
    expected_command_environment = {
        "GGML_BACKEND_PATH": f"{expected_runtime_directory}/libggml-cpu.so",
        "HOME": f"{expected_runtime_directory}/home",
        "LANG": "C",
        "LC_ALL": "C",
        "LD_LIBRARY_PATH": expected_runtime_directory,
        "PATH": "/system/bin",
        "TMPDIR": f"{expected_runtime_directory}/tmp",
    }
    expected_command_argv = [
        expected_runtime_path,
        "--model",
        model_path,
        "--host",
        "127.0.0.1",
        "--port",
        str(PHYSICAL_STABLE_RUNTIME_PORT),
    ]
    for field, expected in {
        "capture_route": "adb-shell-extracted-stable-runtime",
        "model_path": model_path,
        "source_candidate_apk_sha256": candidate_sha,
        "runtime_directory_path": expected_runtime_directory,
        "command_executable_path": expected_runtime_path,
        "command_working_directory": expected_runtime_directory,
        "command_library_path": expected_runtime_directory,
        "command_model_path": model_path,
        "command_environment": expected_command_environment,
        "command_argv": expected_command_argv,
        "selected_runtime_lane": "stable",
        "exact_artifact_verified": True,
        "runtime_process_spawned": True,
        "ready": False,
        "process_exit_code": 1,
        "failure_stage": "model-load",
        "unknown_model_architecture": "nanbeige",
        "loader_error_absent": True,
        "device_runtime_cleanup_verified": True,
    }.items():
        if stable.get(field) != expected:
            raise EvidenceError(f"{stable_context}.{field} must equal {expected!r}")

    runtime_closure = stable.get("runtime_closure")
    if not isinstance(runtime_closure, list):
        raise EvidenceError(f"{stable_context}.runtime_closure must be an array")
    if len(runtime_closure) != len(PHYSICAL_STABLE_RUNTIME_APK_ENTRIES):
        raise EvidenceError(
            f"{stable_context}.runtime_closure must contain exactly "
            f"{len(PHYSICAL_STABLE_RUNTIME_APK_ENTRIES)} files"
        )
    observed_entries: list[str] = []
    closure_total_bytes = 0
    for index, closure_file in enumerate(runtime_closure):
        closure_context = f"{stable_context}.runtime_closure[{index}]"
        if not isinstance(closure_file, dict):
            raise EvidenceError(f"{closure_context} must be an object")
        _exact_keys(
            closure_file,
            {
                "apk_entry",
                "file_name",
                "role",
                "device_path",
                "dt_needed",
                "extracted_bytes",
                "extracted_sha256",
                "device_bytes",
                "device_sha256",
            },
            closure_context,
        )
        apk_entry = _required_string(closure_file, "apk_entry", closure_context)
        observed_entries.append(apk_entry)
        if apk_entry != PHYSICAL_STABLE_RUNTIME_APK_ENTRIES[index]:
            raise EvidenceError(
                f"{stable_context}.runtime_closure APK entries must equal the exact ordered "
                "Stable runtime dependency closure"
            )
        expected_file_name = PurePosixPath(apk_entry).name
        if closure_file.get("file_name") != expected_file_name:
            raise EvidenceError(
                f"{closure_context}.file_name must equal the APK entry basename"
            )
        if closure_file.get("role") != PHYSICAL_STABLE_RUNTIME_ROLES.get(expected_file_name):
            raise EvidenceError(
                f"{closure_context}.role must identify the exact Stable runtime function"
            )
        expected_device_path = f"{expected_runtime_directory}/{expected_file_name}"
        if closure_file.get("device_path") != expected_device_path:
            raise EvidenceError(
                f"{closure_context}.device_path must remain inside the candidate-bound directory"
            )
        dt_needed = closure_file.get("dt_needed")
        if not isinstance(dt_needed, list) or tuple(dt_needed) != PHYSICAL_STABLE_RUNTIME_DT_NEEDED.get(
            expected_file_name
        ):
            raise EvidenceError(
                f"{closure_context}.dt_needed must equal the inspected direct dependencies"
            )
        extracted_bytes = _integer(
            closure_file,
            "extracted_bytes",
            closure_context,
            positive=True,
        )
        if _integer(closure_file, "device_bytes", closure_context, positive=True) != extracted_bytes:
            raise EvidenceError(
                f"{closure_context}.device_bytes must equal extracted_bytes"
            )
        extracted_sha = closure_file.get("extracted_sha256")
        if not isinstance(extracted_sha, str) or not HEX_64_RE.fullmatch(extracted_sha):
            raise EvidenceError(
                f"{closure_context}.extracted_sha256 must be lowercase SHA-256"
            )
        if closure_file.get("device_sha256") != extracted_sha:
            raise EvidenceError(
                f"{closure_context}.device_sha256 must equal extracted_sha256"
            )
        closure_total_bytes += extracted_bytes
    if tuple(observed_entries) != PHYSICAL_STABLE_RUNTIME_APK_ENTRIES:
        raise EvidenceError(
            f"{stable_context}.runtime_closure APK entries must equal the exact ordered "
            "Stable runtime dependency closure"
        )
    if stable.get("runtime_closure_file_count") != len(runtime_closure):
        raise EvidenceError(
            f"{stable_context}.runtime_closure_file_count does not match runtime_closure"
        )
    _integer(stable, "runtime_closure_file_count", stable_context, positive=True)
    if stable.get("runtime_closure_total_bytes") != closure_total_bytes:
        raise EvidenceError(
            f"{stable_context}.runtime_closure_total_bytes does not match runtime_closure"
        )
    _integer(stable, "runtime_closure_total_bytes", stable_context, positive=True)
    closure_manifest_sha = stable.get("runtime_closure_manifest_sha256")
    expected_closure_manifest_sha = hashlib.sha256(
        json.dumps(
            runtime_closure,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    ).hexdigest()
    if closure_manifest_sha != expected_closure_manifest_sha:
        raise EvidenceError(
            f"{stable_context}.runtime_closure_manifest_sha256 must bind the canonical closure JSON"
        )
    if stable.get("system_library_allowlist") != list(
        PHYSICAL_STABLE_RUNTIME_SYSTEM_LIBRARIES
    ):
        raise EvidenceError(
            f"{stable_context}.system_library_allowlist must equal the exact Android system set"
        )
    if stable.get("unresolved_non_system_dependencies") != []:
        raise EvidenceError(
            f"{stable_context}.unresolved_non_system_dependencies must be empty"
        )
    closure_names = {PurePosixPath(entry).name for entry in observed_entries}
    for file_name, dt_needed in PHYSICAL_STABLE_RUNTIME_DT_NEEDED.items():
        unresolved = [
            dependency
            for dependency in dt_needed
            if dependency not in closure_names
            and dependency not in PHYSICAL_STABLE_RUNTIME_SYSTEM_LIBRARIES
        ]
        if unresolved:
            raise EvidenceError(
                f"{stable_context}.runtime_closure leaves {file_name} unresolved: {unresolved!r}"
            )
    command_environment_sha = stable.get("command_environment_sha256")
    expected_command_environment_sha = hashlib.sha256(
        json.dumps(
            expected_command_environment,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    ).hexdigest()
    if command_environment_sha != expected_command_environment_sha:
        raise EvidenceError(
            f"{stable_context}.command_environment_sha256 must bind the canonical environment JSON"
        )
    stable_error = _required_string(stable, "error_message", stable_context)
    if re.search(r"unknown model architecture:\s*['\"]?nanbeige\b", stable_error, re.IGNORECASE) is None:
        raise EvidenceError(f"{stable_context}.error_message lacks the exact Nanbeige failure")
    if PHYSICAL_STABLE_RUNTIME_LOADER_ERROR_RE.search(stable_error) is not None:
        raise EvidenceError(f"{stable_context}.error_message contains a linker or loader failure")
    command_sha = stable.get("command_argv_sha256")
    expected_command_sha = hashlib.sha256(
        json.dumps(
            expected_command_argv,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    if command_sha != expected_command_sha:
        raise EvidenceError(
            f"{stable_context}.command_argv_sha256 must bind the canonical command argv JSON"
        )

    reconciliation = _nested_object(payload, "automatic_reconciliation", context)
    reconciliation_context = f"{context}.automatic_reconciliation"
    _exact_keys(
        reconciliation,
        {
            "capture_route",
            "model_path",
            "trigger",
            "automatic",
            "exact_artifact_verified_before_reconciliation",
            "settings_before_runtime_lane",
            "required_runtime_lane",
            "settings_after_runtime_lane",
            "settings_save_succeeded",
            "persisted_before_runtime_launch",
            "runtime_launch_observed_after_persist",
            "visible_settings_runtime_lane",
            "visible_settings_matches_persisted_lane",
            "visible_settings_observed_after_ready",
            "user_reselected_lane",
        },
        reconciliation_context,
    )
    exact_reconciliation = {
        "capture_route": "app-managed-local-backend",
        "model_path": model_path,
        "trigger": "verified-artifact-prelaunch",
        "automatic": True,
        "exact_artifact_verified_before_reconciliation": True,
        "settings_before_runtime_lane": "stable",
        "required_runtime_lane": "turboquant",
        "settings_after_runtime_lane": "turboquant",
        "settings_save_succeeded": True,
        "persisted_before_runtime_launch": True,
        "runtime_launch_observed_after_persist": True,
        "visible_settings_runtime_lane": "turboquant",
        "visible_settings_matches_persisted_lane": True,
        "visible_settings_observed_after_ready": True,
        "user_reselected_lane": False,
    }
    for field, expected in exact_reconciliation.items():
        if reconciliation.get(field) != expected:
            raise EvidenceError(f"{reconciliation_context}.{field} must equal {expected!r}")

    readiness = _nested_object(payload, "readiness", context)
    readiness_context = f"{context}.readiness"
    _exact_keys(
        readiness,
        {
            "capture_route",
            "backend",
            "runtime_lane",
            "persisted_runtime_lane",
            "model_path",
            "controller_ready",
            "health_endpoint_ok",
            "completion_canary_nonempty",
            "completion_canary_visible_characters",
            "ready_latency_ms",
            "status_message",
        },
        readiness_context,
    )
    for field, expected in {
        "capture_route": "app-managed-local-backend",
        "backend": "llama.cpp",
        "runtime_lane": "turboquant",
        "persisted_runtime_lane": "turboquant",
        "model_path": model_path,
        "controller_ready": True,
        "health_endpoint_ok": True,
        "completion_canary_nonempty": True,
    }.items():
        if readiness.get(field) != expected:
            raise EvidenceError(f"{readiness_context}.{field} must equal {expected!r}")
    _integer(
        readiness,
        "completion_canary_visible_characters",
        readiness_context,
        positive=True,
    )
    _integer(readiness, "ready_latency_ms", readiness_context, positive=True)
    ready_status = _required_string(readiness, "status_message", readiness_context)
    if not re.search(r"\b(?:ready|serving)\b", ready_status, re.IGNORECASE):
        raise EvidenceError(f"{readiness_context}.status_message does not prove readiness")

    ordinary = _nested_object(payload, "ordinary_chat", context)
    ordinary_context = f"{context}.ordinary_chat"
    _exact_keys(
        ordinary,
        {
            "capture_route",
            "language_tag",
            "tool_mode",
            "tools_available",
            "prompt",
            "prompt_requested_tool",
            "request_completed",
            "visible_reply",
            "visible_reply_characters",
            "visible_progress_observed",
            "progress_event_count",
            "tool_call_count",
            "tool_result_count",
            "terminal_state",
            "completion_latency_ms",
        },
        ordinary_context,
    )
    for field, expected in {
        "capture_route": "app-chat-ui",
        "language_tag": "en",
        "tool_mode": "general",
        "tools_available": True,
        "prompt": PHYSICAL_ORDINARY_CHAT_PROMPT,
        "prompt_requested_tool": False,
        "request_completed": True,
        "visible_progress_observed": True,
        "tool_call_count": 0,
        "tool_result_count": 0,
        "terminal_state": "completed",
    }.items():
        if ordinary.get(field) != expected:
            raise EvidenceError(f"{ordinary_context}.{field} must equal {expected!r}")
    reply = _required_string(ordinary, "visible_reply", ordinary_context)
    if VISIBLE_THINK_MARKER_RE.search(reply):
        raise EvidenceError(f"{ordinary_context}.visible_reply exposes a think marker")
    if reply.casefold() in {"...", "…", "working", "generating", "thinking"}:
        raise EvidenceError(f"{ordinary_context}.visible_reply is a nonterminal placeholder")
    if reply != PHYSICAL_ORDINARY_CHAT_EXPECTED_REPLY:
        raise EvidenceError(
            f"{ordinary_context}.visible_reply must equal the deterministic Nanbeige canary"
        )
    if ordinary.get("visible_reply_characters") != len(reply):
        raise EvidenceError(f"{ordinary_context}.visible_reply_characters does not match the reply")
    _integer(ordinary, "visible_reply_characters", ordinary_context, positive=True)
    _integer(ordinary, "progress_event_count", ordinary_context, positive=True)
    _integer(ordinary, "completion_latency_ms", ordinary_context, positive=True)

    stop = _nested_object(payload, "stop_control", context)
    stop_context = f"{context}.stop_control"
    _exact_keys(
        stop,
        {
            "capture_route",
            "language_tag",
            "prompt",
            "stop_button_visible",
            "visible_progress_observed_before_stop",
            "stop_requested",
            "stop_acknowledged",
            "model_request_cancelled",
            "terminal_state",
            "visible_terminal_message",
            "visible_terminal_message_characters",
            "nonterminal_placeholder",
            "busy_after_stop",
            "stop_button_visible_after_stop",
            "stop_latency_ms",
        },
        stop_context,
    )
    for field, expected in {
        "capture_route": "app-chat-ui",
        "language_tag": "en",
        "prompt": PHYSICAL_STOP_CHAT_PROMPT,
        "stop_button_visible": True,
        "visible_progress_observed_before_stop": True,
        "stop_requested": True,
        "stop_acknowledged": True,
        "model_request_cancelled": True,
        "terminal_state": "stopped",
        "visible_terminal_message": PHYSICAL_STOP_TERMINAL_MESSAGE,
        "nonterminal_placeholder": False,
        "busy_after_stop": False,
        "stop_button_visible_after_stop": False,
    }.items():
        if stop.get(field) != expected:
            raise EvidenceError(f"{stop_context}.{field} must equal {expected!r}")
    terminal_message = _required_string(stop, "visible_terminal_message", stop_context)
    if VISIBLE_THINK_MARKER_RE.search(terminal_message):
        raise EvidenceError(f"{stop_context}.visible_terminal_message exposes a think marker")
    if stop.get("visible_terminal_message_characters") != len(terminal_message):
        raise EvidenceError(
            f"{stop_context}.visible_terminal_message_characters does not match the message"
        )
    _integer(stop, "visible_terminal_message_characters", stop_context, positive=True)
    _integer(stop, "stop_latency_ms", stop_context, positive=True)

    return PhysicalNanbeigeRepairEvidence(
        candidate_apk_sha256=candidate_sha,
        candidate_apk_bytes=candidate_bytes,
        apk_signer_sha256=EXPECTED_RELEASE_SIGNER_SHA256,
        device_model=identity_fields["model"],
        device_manufacturer=identity_fields["manufacturer"],
        adb_serial_sha256=serial_sha,
        model_device_path=model_path,
    )


def _reviewed_utc(value: Any, context: str) -> str:
    if not isinstance(value, str):
        raise EvidenceError(f"{context} must be a UTC timestamp string")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise EvidenceError(f"{context} must use YYYY-MM-DDTHH:MM:SSZ") from exc
    if parsed.year < 2026:
        raise EvidenceError(f"{context} predates the comprehensive release-evidence contract")
    return value


def _validate_launch_artifact(
    directory: Path,
    name: Any,
    digest: Any,
    context: str,
    suffix: str,
) -> tuple[Path, PurePosixPath]:
    safe_name = _safe_artifact_name(name, f"{context}.path", (suffix,))
    if not isinstance(digest, str) or not HEX_64_RE.fullmatch(digest):
        raise EvidenceError(f"{context}.sha256 must be lowercase SHA-256")
    path = directory / safe_name
    if not path.is_file() or path.stat().st_size <= 0 or _sha256_file(path) != digest:
        raise EvidenceError(f"{context} is missing, empty, or does not match its SHA-256")
    relative = PurePosixPath(LAUNCH_THEME_PREFIX / directory.name / safe_name)
    return path, relative


def _validate_persisted_palette_record(
    path: Path,
    manifest_palette: Mapping[str, Any],
    expected_identity: Mapping[str, Any],
    context: str,
) -> None:
    payload = _json_object(path)
    _exact_keys(
        payload,
        {
            "schema",
            "identity",
            "theme_id",
            "palette",
            "shared_preferences_xml_sha256",
            "contains_only_filtered_palette_state",
        },
        context,
    )
    if payload["schema"] != PERSISTED_PALETTE_SCHEMA:
        raise EvidenceError(f"{context}.schema is invalid")
    identity = _nested_object(payload, "identity", context)
    expected_identity_keys = {
        "source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "evidence_run_id",
        "device_serial",
        "avd_name",
        "device_boot_id",
        "profile",
    }
    _exact_keys(identity, expected_identity_keys, f"{context}.identity")
    for field, expected in expected_identity.items():
        if identity.get(field) != expected:
            raise EvidenceError(f"{context}.identity.{field} does not match the launch lane")
    palette = _nested_object(payload, "palette", context)
    _exact_keys(palette, set(PALETTE_KEYS), f"{context}.palette")
    expected_palette = {field: manifest_palette[field] for field in PALETTE_KEYS}
    if palette != expected_palette:
        raise EvidenceError(f"{context}.palette disagrees with the launch manifest")
    if payload["theme_id"] != manifest_palette["theme_id"]:
        raise EvidenceError(f"{context}.theme_id disagrees with the launch manifest")
    if payload["shared_preferences_xml_sha256"] != manifest_palette["shared_preferences_xml_sha256"]:
        raise EvidenceError(f"{context} SharedPreferences digest disagrees with the launch manifest")
    if payload["contains_only_filtered_palette_state"] is not True:
        raise EvidenceError(f"{context} must contain only filtered palette state")


def _validate_launch_theme_manifest(
    evidence_dir: Path,
    canonical_profile: str,
    performance: Mapping[str, Any],
    custom_light_capture: Mapping[str, Any],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
) -> tuple[set[PurePosixPath], int]:
    relative_manifest = LAUNCH_THEME_PREFIX / canonical_profile / "manifest.json"
    path = evidence_dir / Path(relative_manifest.as_posix())
    context = f"launch_theme[{canonical_profile}]"
    payload = _json_object(path)
    _exact_keys(
        payload,
        {"schema", "identity", "palette", "captures", "automated_verdict", "visual_review", "manual_acceptance"},
        context,
    )
    if payload["schema"] != HOST_LAUNCH_THEME_SCHEMA:
        raise EvidenceError(f"{context}.schema is invalid")
    if payload["automated_verdict"] != "identity_bound_launch_capture_complete":
        raise EvidenceError(f"{context}.automated_verdict is invalid")
    acceptance = payload["manual_acceptance"]
    if not isinstance(acceptance, list) or len(acceptance) < 4 or any(
        not isinstance(item, str) or not item.strip() for item in acceptance
    ):
        raise EvidenceError(f"{context}.manual_acceptance is incomplete")

    identity = _nested_object(payload, "identity", context)
    identity_keys = {
        "serial",
        "avd_name",
        "expected_profile",
        "evidence_run_id",
        "source_digest",
        "candidate_apk_sha256",
        "instrumentation_apk_sha256",
        "observed_avd_name",
        "observed_profile",
        "width_dp",
        "height_dp",
        "sdk_int",
        "build_fingerprint",
        "device_boot_id",
        "installed_apk_path",
        "installed_apk_sha256",
        "installed_instrumentation_apk_path",
        "installed_instrumentation_apk_sha256",
    }
    _exact_keys(identity, identity_keys, f"{context}.identity")
    expected_kind = "phone" if canonical_profile == "phone-compact" else "tablet"
    expected_device = performance["device"]
    expected_screen = performance["screen"]
    exact_identity = {
        "serial": expected_device["serial"],
        "avd_name": expected_device["avd_name"],
        "expected_profile": expected_kind,
        "evidence_run_id": evidence_run_id,
        "source_digest": source_digest,
        "candidate_apk_sha256": candidate_apk_sha256,
        "instrumentation_apk_sha256": instrumentation_apk_sha256,
        "observed_avd_name": expected_device["avd_name"],
        "observed_profile": expected_kind,
        "width_dp": expected_screen["width_dp"],
        "height_dp": expected_screen["height_dp"],
        "sdk_int": expected_device["android_sdk"],
        "build_fingerprint": expected_device["build_fingerprint"],
        "device_boot_id": expected_device["boot_id"],
        "installed_apk_sha256": candidate_apk_sha256,
        "installed_instrumentation_apk_sha256": instrumentation_apk_sha256,
    }
    for field, expected in exact_identity.items():
        if identity.get(field) != expected:
            raise EvidenceError(f"{context}.identity.{field} does not match {expected}")
    for field in ("installed_apk_path", "installed_instrumentation_apk_path"):
        value = identity[field]
        if not isinstance(value, str) or not value.startswith("/") or not value.endswith(".apk"):
            raise EvidenceError(f"{context}.identity.{field} is invalid")

    palette = _nested_object(payload, "palette", context)
    palette_keys = {
        "theme_id",
        "profile",
        "proof_evidence_identity",
        "proof_sha256",
        *PALETTE_KEYS,
        "persisted_state_file",
        "persisted_state_file_sha256",
        "shared_preferences_xml_sha256",
        "verified_against_persisted_app_state",
    }
    _exact_keys(palette, palette_keys, f"{context}.palette")
    if palette["theme_id"] != custom_light_capture["theme_id"]:
        raise EvidenceError(f"{context}.palette.theme_id disagrees with the rendered UI proof")
    if palette["profile"] != custom_light_capture["profile"]:
        raise EvidenceError(f"{context}.palette.profile disagrees with the rendered UI proof")
    if palette["proof_evidence_identity"] != "appearance-custom-light":
        raise EvidenceError(f"{context}.palette proof identity is invalid")
    if palette["proof_sha256"] != custom_light_capture["proof_sha256"]:
        raise EvidenceError(f"{context}.palette proof hash disagrees with the rendered UI proof")
    if palette["verified_against_persisted_app_state"] is not True:
        raise EvidenceError(f"{context}.palette was not verified against persisted app state")
    if not HEX_64_RE.fullmatch(str(palette["shared_preferences_xml_sha256"])):
        raise EvidenceError(f"{context}.palette SharedPreferences hash is invalid")
    normalized_palette = _normalized_palette(
        {key: str(palette[key]) for key in PALETTE_KEYS}, f"{context}.palette"
    )
    if normalized_palette != custom_light_capture["palette"]:
        raise EvidenceError(f"{context}.palette disagrees with the rendered custom-light proof")

    directory = path.parent
    persisted_path, persisted_relative = _validate_launch_artifact(
        directory,
        palette["persisted_state_file"],
        palette["persisted_state_file_sha256"],
        f"{context}.palette.persisted_state_file",
        ".json",
    )
    persisted_identity = {
        "source_digest": source_digest,
        "candidate_apk_sha256": candidate_apk_sha256,
        "instrumentation_apk_sha256": instrumentation_apk_sha256,
        "evidence_run_id": evidence_run_id,
        "device_serial": expected_device["serial"],
        "avd_name": expected_device["avd_name"],
        "device_boot_id": expected_device["boot_id"],
        "profile": custom_light_capture["profile"],
    }
    _validate_persisted_palette_record(
        persisted_path, palette, persisted_identity, f"{context}.persisted_palette"
    )

    review = _nested_object(payload, "visual_review", context)
    review_keys = {
        "status",
        "reviewer",
        "reviewed_at_utc",
        "decision",
        "notes",
        "method",
        "automated_pixel_certification",
    }
    _exact_keys(review, review_keys, f"{context}.visual_review")
    if review["status"] != "reviewed" or review["decision"] != "pass":
        raise EvidenceError(f"{context} does not carry a passing completed human visual review")
    if not isinstance(review["reviewer"], str) or not REVIEWER_RE.fullmatch(review["reviewer"]):
        raise EvidenceError(f"{context}.visual_review.reviewer is invalid")
    _reviewed_utc(review["reviewed_at_utc"], f"{context}.visual_review.reviewed_at_utc")
    if not isinstance(review["notes"], str) or not review["notes"].strip() or len(review["notes"]) > 500:
        raise EvidenceError(f"{context}.visual_review.notes is invalid")
    if review["method"] != "manual-frame-by-frame" or review["automated_pixel_certification"] is not False:
        raise EvidenceError(f"{context}.visual_review improperly claims automated pixel certification")

    captures = payload["captures"]
    if not isinstance(captures, list) or len(captures) != 2:
        raise EvidenceError(f"{context}.captures must contain exactly two launch lanes")
    expected_labels = {"cold-launcher-tap", "cold-deep-link"}
    if {record.get("label") for record in captures if isinstance(record, dict)} != expected_labels:
        raise EvidenceError(f"{context}.captures do not contain the required launch lanes")
    paths = {relative_manifest, persisted_relative}
    referenced_names = {palette["persisted_state_file"]}
    capture_keys = {
        "label",
        "launch_stdout",
        "launch_stderr",
        "video",
        "video_sha256",
        "settled_screenshot",
        "settled_screenshot_sha256",
        "activity_dump",
        "activity_dump_sha256",
        "automated_state_verdict",
        "visual_splash_verdict",
    }
    for index, record in enumerate(captures, start=1):
        if not isinstance(record, dict):
            raise EvidenceError(f"{context}.capture[{index}] must be an object")
        _exact_keys(record, capture_keys, f"{context}.capture[{index}]")
        if not isinstance(record["launch_stdout"], str) or not isinstance(record["launch_stderr"], str):
            raise EvidenceError(f"{context}.capture[{index}] launch output fields must be strings")
        if record["automated_state_verdict"] != "main_activity_resumed_and_artifacts_decoded":
            raise EvidenceError(f"{context}.capture[{index}] automated state verdict is invalid")
        if record["visual_splash_verdict"] != "manual_review_required":
            raise EvidenceError(f"{context}.capture[{index}] improperly self-certifies splash pixels")
        video_path, video_relative = _validate_launch_artifact(
            directory, record["video"], record["video_sha256"], f"{context}.capture[{index}].video", ".mp4"
        )
        screenshot_path, screenshot_relative = _validate_launch_artifact(
            directory,
            record["settled_screenshot"],
            record["settled_screenshot_sha256"],
            f"{context}.capture[{index}].settled_screenshot",
            ".png",
        )
        activity_path, activity_relative = _validate_launch_artifact(
            directory,
            record["activity_dump"],
            record["activity_dump_sha256"],
            f"{context}.capture[{index}].activity_dump",
            ".txt",
        )
        with video_path.open("rb") as handle:
            if handle.read(8)[4:8] != b"ftyp":
                raise EvidenceError(f"{context}.capture[{index}] video is not an MP4 container")
        decoded = _decode_png(screenshot_path)
        if (decoded.width, decoded.height) != (expected_screen["width_px"], expected_screen["height_px"]):
            raise EvidenceError(f"{context}.capture[{index}] screenshot dimensions disagree with the profile")
        try:
            activity_text = activity_path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise EvidenceError(f"{context}.capture[{index}] activity dump is not UTF-8") from exc
        if PACKAGE_ID not in activity_text or "MainActivity" not in activity_text:
            raise EvidenceError(f"{context}.capture[{index}] activity dump does not prove resumed Hermes")
        for name in (record["video"], record["settled_screenshot"], record["activity_dump"]):
            if name in referenced_names:
                raise EvidenceError(f"{context} reuses launch artifact name {name!r}")
            referenced_names.add(name)
        paths.update({video_relative, screenshot_relative, activity_relative})
    return paths, len(captures)


def _validate_launch_theme_evidence(
    evidence_dir: Path,
    performance_records: Sequence[Mapping[str, Any]],
    custom_light_by_profile: Mapping[str, Mapping[str, Any]],
    source_digest: str,
    candidate_apk_sha256: str,
    instrumentation_apk_sha256: str,
    evidence_run_id: str,
) -> tuple[set[PurePosixPath], int, int]:
    paths: set[PurePosixPath] = set()
    capture_count = 0
    for profile, performance in zip(PROFILES, performance_records, strict=True):
        profile_paths, profile_capture_count = _validate_launch_theme_manifest(
            evidence_dir,
            profile,
            performance,
            custom_light_by_profile[profile],
            source_digest,
            candidate_apk_sha256,
            instrumentation_apk_sha256,
            evidence_run_id,
        )
        paths.update(profile_paths)
        capture_count += profile_capture_count
    return paths, capture_count, len(PROFILES)


def expected_evidence_paths(
    artifacts: Sequence[ArtifactSpec],
    performance_records: Sequence[Mapping[str, Any]] = (),
    *,
    tag: str | None = None,
    comprehensive_ui_paths: Iterable[PurePosixPath] = (),
    launch_theme_paths: Iterable[PurePosixPath] = (),
) -> set[PurePosixPath]:
    paths = {
        PurePosixPath("performance") / f"{profile}.json"
        for profile in PROFILES
    }
    for record in performance_records:
        raw = record.get("raw_evidence")
        traces = record.get("traces")
        if isinstance(raw, Mapping):
            for reference in raw.values():
                if isinstance(reference, Mapping) and isinstance(reference.get("path"), str):
                    paths.add(PurePosixPath(reference["path"]))
        if isinstance(traces, list):
            for reference in traces:
                if isinstance(reference, Mapping) and isinstance(reference.get("path"), str):
                    paths.add(PurePosixPath(reference["path"]))
    for profile in PROFILES:
        for language in LANGUAGES:
            base = PurePosixPath("ui") / profile / language
            paths.add(base / "screen.png")
            paths.add(base / "semantics.txt")
    paths.update(artifact.evidence_path for artifact in artifacts)
    if tag is not None and requires_comprehensive_ui_evidence(tag):
        paths.update(
            {
                UI_COVERAGE_PREFIX / "phone-compact" / COMPLETE_UI_INVENTORY,
                UI_COVERAGE_PREFIX / "phone-compact" / LOCALIZED_UI_INVENTORY,
                UI_COVERAGE_PREFIX / "tablet" / COMPLETE_UI_INVENTORY,
                LAUNCH_THEME_PREFIX / "phone-compact" / "manifest.json",
                LAUNCH_THEME_PREFIX / "tablet" / "manifest.json",
                HISTORICAL_E4B_EVIDENCE_PATH,
                ISSUE8_EVIDENCE_PATH,
                ISSUE16_EVIDENCE_PATH,
            }
        )
        paths.update(comprehensive_ui_paths)
        paths.update(launch_theme_paths)
    if tag is not None and requires_physical_nanbeige_repair_evidence(tag):
        paths.add(PHYSICAL_NANBEIGE_REPAIR_PATH)
    return paths


def _walk_evidence_files(evidence_dir: Path) -> set[PurePosixPath]:
    files: set[PurePosixPath] = set()
    for path in evidence_dir.rglob("*"):
        if path.is_symlink():
            raise EvidenceError(f"Release evidence must not contain symlinks: {path}")
        if path.is_file():
            relative = PurePosixPath(path.relative_to(evidence_dir).as_posix())
            if relative.is_absolute() or ".." in relative.parts:
                raise EvidenceError(f"Unsafe release evidence path: {relative}")
            files.add(relative)
    return files


def _validated_perfetto_root(evidence_dir: Path, perfetto_root: Path | None) -> Path | None:
    if perfetto_root is None:
        return None
    if perfetto_root.is_symlink() or not perfetto_root.is_dir():
        raise EvidenceError("External Perfetto root must be one existing non-symlink directory")
    resolved = perfetto_root.resolve()
    evidence_resolved = evidence_dir.resolve()
    if resolved == evidence_resolved:
        raise EvidenceError("External Perfetto root must differ from the release evidence directory")
    if resolved.is_relative_to(evidence_resolved) or evidence_resolved.is_relative_to(resolved):
        raise EvidenceError(
            "External Perfetto root and release evidence directory must not contain one another"
        )
    return resolved


def validate_evidence_directory(
    evidence_dir: Path,
    artifacts: Sequence[ArtifactSpec],
    source_digest: str,
    tag: str,
    *,
    repo_root: Path | None = None,
    perfetto_root: Path | None = None,
) -> ValidatedEvidence:
    if not evidence_dir.is_dir():
        raise EvidenceError(f"Release evidence directory does not exist: {evidence_dir}")
    external_perfetto_root = _validated_perfetto_root(evidence_dir, perfetto_root)
    actual_paths = _walk_evidence_files(evidence_dir)
    actual_without_manifest = actual_paths - {PurePosixPath("manifest.json")}
    base_expected_paths = expected_evidence_paths(artifacts, tag=tag)
    missing_base = base_expected_paths - actual_without_manifest
    if missing_base:
        raise EvidenceError(
            "Release evidence is missing required fixed paths: "
            f"{[path.as_posix() for path in sorted(missing_base)]}"
        )

    if not HEX_64_RE.fullmatch(source_digest):
        raise EvidenceError("Current source digest must be one lowercase SHA-256")
    version_name, version_code = android_identity_for_tag(tag)
    litertlm_coordinate = litertlm_coordinate_for_tag(tag)
    performance_records = [
        _validate_performance(
            evidence_dir / "performance" / f"{profile}.json",
            profile,
            source_digest,
            version_name,
            version_code,
            litertlm_coordinate=litertlm_coordinate,
            perfetto_root=external_perfetto_root,
        )
        for profile in PROFILES
    ]
    benchmark_target_digests = {
        record["benchmark_target_apk_sha256"] for record in performance_records
    }
    benchmark_test_digests = {
        record["benchmark_test_apk_sha256"] for record in performance_records
    }
    if len(benchmark_target_digests) != 1 or len(benchmark_test_digests) != 1:
        raise EvidenceError("Performance profiles do not share one benchmark target/test APK pair")
    benchmark_target_apk_sha256 = benchmark_target_digests.pop()
    benchmark_test_apk_sha256 = benchmark_test_digests.pop()
    evidence_run_ids = {record["evidence_run_id"] for record in performance_records}
    if len(evidence_run_ids) != 1:
        raise EvidenceError("Performance profiles do not share one evidence_run_id")
    evidence_run_id = evidence_run_ids.pop()

    ui_candidate_digests: set[str] = set()
    ui_instrumentation_digests: set[str] = set()
    ui_run_ids: set[str] = set()
    for profile in PROFILES:
        for language in LANGUAGES:
            header, _ = _semantics_evidence(
                evidence_dir / "ui" / profile / language / "semantics.txt", language
            )
            candidate_digest = header.get("candidate_apk_sha256", "")
            instrumentation_digest = header.get("instrumentation_apk_sha256", "")
            if not HEX_64_RE.fullmatch(candidate_digest) or not HEX_64_RE.fullmatch(
                instrumentation_digest
            ):
                raise EvidenceError(f"ui[{profile}/{language}] APK hashes are invalid")
            ui_candidate_digests.add(candidate_digest)
            ui_instrumentation_digests.add(instrumentation_digest)
            ui_run_ids.add(header.get("evidence_run_id", ""))
    if len(ui_candidate_digests) != 1 or len(ui_instrumentation_digests) != 1:
        raise EvidenceError("UI captures do not share one debug app/androidTest APK pair")
    if ui_run_ids != {evidence_run_id}:
        raise EvidenceError("UI captures do not share the performance evidence_run_id")
    ui_candidate_apk_sha256 = ui_candidate_digests.pop()
    ui_instrumentation_apk_sha256 = ui_instrumentation_digests.pop()
    profile_screens: dict[str, tuple[int, int]] = {}
    profile_semantics: dict[str, tuple[int, int]] = {}
    for profile in PROFILES:
        bodies: set[str] = set()
        screenshots: set[str] = set()
        for language in LANGUAGES:
            base = evidence_dir / "ui" / profile / language
            screenshot_path = base / "screen.png"
            decoded_png = _decode_png(screenshot_path)
            screenshot_dimensions = (decoded_png.width, decoded_png.height)
            screenshots.add(decoded_png.content_pixel_sha256)
            header, semantics_body = _semantics_evidence(base / "semantics.txt", language)
            exact_binding = {
                "release_source_digest": source_digest,
                "candidate_apk_sha256": ui_candidate_apk_sha256,
                "instrumentation_apk_sha256": ui_instrumentation_apk_sha256,
                "evidence_run_id": evidence_run_id,
                "package_id": PACKAGE_ID,
                "version_name": version_name,
                "version_code": str(version_code),
                "build_variant": BUILD_VARIANT,
                "litertlm_coordinate": litertlm_coordinate,
                "screenshot_sha256": _sha256_file(screenshot_path),
            }
            expected_device = performance_records[PROFILES.index(profile)]["device"]
            exact_binding.update(
                {
                    "device_serial": expected_device["serial"],
                    "avd_name": expected_device["avd_name"],
                    "device_boot_id": expected_device["boot_id"],
                    "build_fingerprint": expected_device["build_fingerprint"],
                }
            )
            for field, expected in exact_binding.items():
                if header.get(field) != expected:
                    raise EvidenceError(
                        f"ui[{profile}/{language}] semantics {field} does not match {expected}"
                    )
            dimensions_dp = (int(header["screen_width_dp"]), int(header["screen_height_dp"]))
            _validate_profile_dimensions(profile, *dimensions_dp, f"ui[{profile}/{language}]")
            expected_screen = performance_records[PROFILES.index(profile)]["screen"]
            if screenshot_dimensions != (expected_screen["width_px"], expected_screen["height_px"]):
                raise EvidenceError(f"ui[{profile}/{language}] PNG dimensions disagree with performance evidence")
            if dimensions_dp != (expected_screen["width_dp"], expected_screen["height_dp"]):
                raise EvidenceError(f"ui[{profile}/{language}] semantics dimensions disagree with performance evidence")
            if (
                float(header["font_scale"]) != expected_screen["font_scale"]
                or float(header["font_scale"]) != 1.0
            ):
                raise EvidenceError(
                    f"ui[{profile}/{language}] semantics font_scale disagrees with release value 1.0"
                )
            if profile_screens.setdefault(profile, screenshot_dimensions) != screenshot_dimensions:
                raise EvidenceError(f"UI screenshot dimensions vary across {profile} language captures")
            if profile_semantics.setdefault(profile, dimensions_dp) != dimensions_dp:
                raise EvidenceError(f"UI semantics dimensions vary across {profile} language captures")
            if "Tag: 'HermesDevicePageNavigation'" not in semantics_body:
                raise EvidenceError(f"ui[{profile}/{language}] is not the certified Hermes Device surface")
            localized_title = LOCALIZED_DEVICE_OVERVIEW[language]
            if f"Text = '[{localized_title}]'" not in semantics_body:
                raise EvidenceError(
                    f"ui[{profile}/{language}] lacks the expected localized Device/Overview sentinel"
                )
            has_rail = "Tag: 'HermesPersistentNavigation'" in semantics_body
            has_drawer = f"Tag: '{PHONE_UI_DRAWER_TAG}'" in semantics_body
            if profile == "tablet" and (not has_rail or has_drawer):
                raise EvidenceError(f"ui[{profile}/{language}] does not prove the tablet navigation rail")
            if profile == "phone-compact" and (has_rail or not has_drawer):
                raise EvidenceError(f"ui[{profile}/{language}] does not prove compact drawer navigation")
            bodies.add(hashlib.sha256(semantics_body.encode("utf-8")).hexdigest())
        if len(bodies) != len(LANGUAGES):
            raise EvidenceError(
                f"UI semantics bodies for {profile} are not distinct across all six switched languages"
            )
        if len(screenshots) != len(LANGUAGES):
            raise EvidenceError(
                f"UI screenshots for {profile} are not distinct across all six switched languages"
            )

    comprehensive_ui_paths: set[PurePosixPath] = set()
    launch_theme_paths: set[PurePosixPath] = set()
    comprehensive_ui_capture_count = 0
    launch_theme_capture_count = 0
    launch_theme_review_count = 0
    ui_source_contract = UiEvidenceSourceContract((), (), (), ())
    if requires_comprehensive_ui_evidence(tag):
        ui_source_contract = load_ui_evidence_source_contract(repo_root)
        (
            comprehensive_ui_paths,
            comprehensive_ui_capture_count,
            custom_light_by_profile,
        ) = _validate_comprehensive_ui_evidence(
            evidence_dir,
            performance_records,
            ui_source_contract,
            source_digest,
            ui_candidate_apk_sha256,
            ui_instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
        )
        (
            launch_theme_paths,
            launch_theme_capture_count,
            launch_theme_review_count,
        ) = _validate_launch_theme_evidence(
            evidence_dir,
            performance_records,
            custom_light_by_profile,
            source_digest,
            ui_candidate_apk_sha256,
            ui_instrumentation_apk_sha256,
            evidence_run_id,
        )

    expected_paths = expected_evidence_paths(
        artifacts,
        performance_records,
        tag=tag,
        comprehensive_ui_paths=comprehensive_ui_paths,
        launch_theme_paths=launch_theme_paths,
    )
    trace_paths = {
        PurePosixPath(reference["path"])
        for record in performance_records
        for reference in record["traces"]
    }
    repository_expected_paths = (
        expected_paths - trace_paths if external_perfetto_root is not None else expected_paths
    )
    missing = repository_expected_paths - actual_without_manifest
    unexpected = actual_without_manifest - repository_expected_paths
    if missing or unexpected:
        raise EvidenceError(
            "Release evidence layout mismatch; "
            f"missing={[path.as_posix() for path in sorted(missing)]}, "
            f"unexpected={[path.as_posix() for path in sorted(unexpected)]}"
        )
    if external_perfetto_root is not None:
        external_expected_paths = {
            PurePosixPath(*relative.parts[1:]) for relative in trace_paths
        }
        external_actual_paths = _walk_evidence_files(external_perfetto_root)
        external_missing = external_expected_paths - external_actual_paths
        external_unexpected = external_actual_paths - external_expected_paths
        if external_missing or external_unexpected:
            raise EvidenceError(
                "External Perfetto layout mismatch; "
                f"missing={[path.as_posix() for path in sorted(external_missing)]}, "
                f"unexpected={[path.as_posix() for path in sorted(external_unexpected)]}"
            )

    for artifact in artifacts:
        _validate_model_evidence(
            evidence_dir / Path(artifact.evidence_path.as_posix()),
            artifact,
            performance_records,
            source_digest,
            ui_candidate_apk_sha256,
            ui_instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
            litertlm_coordinate=litertlm_coordinate,
        )
    historical_issue8_model_count = 0
    issue8_tool_and_preflight_count = 0
    issue16_debian_sandbox_count = 0
    physical_nanbeige_repair: PhysicalNanbeigeRepairEvidence | None = None
    if requires_comprehensive_ui_evidence(tag):
        _validate_historical_e4b_evidence(
            evidence_dir / Path(HISTORICAL_E4B_EVIDENCE_PATH.as_posix()),
            performance_records,
            source_digest,
            ui_candidate_apk_sha256,
            ui_instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
        )
        historical_issue8_model_count = 1
        _validate_issue8_evidence(
            evidence_dir / Path(ISSUE8_EVIDENCE_PATH.as_posix()),
            performance_records,
            source_digest,
            ui_candidate_apk_sha256,
            ui_instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
            tag,
        )
        issue8_tool_and_preflight_count = 1
        _validate_issue16_evidence(
            evidence_dir / Path(ISSUE16_EVIDENCE_PATH.as_posix()),
            performance_records,
            source_digest,
            ui_candidate_apk_sha256,
            ui_instrumentation_apk_sha256,
            evidence_run_id,
            version_name,
            version_code,
            tag,
        )
        issue16_debian_sandbox_count = 1
    if requires_physical_nanbeige_repair_evidence(tag):
        physical_nanbeige_repair = _validate_physical_nanbeige_repair_evidence(
            evidence_dir / Path(PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix()),
            artifacts,
            source_digest,
            version_name,
            version_code,
            tag,
        )

    file_records = tuple(
        EvidenceFile(
            path=relative.as_posix(),
            bytes=(
                external_perfetto_root.joinpath(*relative.parts[1:])
                if external_perfetto_root is not None and relative in trace_paths
                else evidence_dir / Path(relative.as_posix())
            ).stat().st_size,
            sha256=_sha256_file(
                external_perfetto_root.joinpath(*relative.parts[1:])
                if external_perfetto_root is not None and relative in trace_paths
                else evidence_dir / Path(relative.as_posix())
            ),
        )
        for relative in sorted(expected_paths)
    )
    if any(record.bytes <= 0 for record in file_records):
        raise EvidenceError("Release evidence contains an empty required file")
    device_models = tuple(sorted({record["device"]["model"] for record in performance_records}))
    return ValidatedEvidence(
        files=file_records,
        model_count=len(artifacts),
        ui_capture_count=len(PROFILES) * len(LANGUAGES),
        performance_record_count=len(PROFILES),
        device_models=device_models,
        ui_candidate_apk_sha256=ui_candidate_apk_sha256,
        ui_instrumentation_apk_sha256=ui_instrumentation_apk_sha256,
        benchmark_target_apk_sha256=benchmark_target_apk_sha256,
        benchmark_test_apk_sha256=benchmark_test_apk_sha256,
        evidence_run_id=evidence_run_id,
        comprehensive_ui_capture_count=comprehensive_ui_capture_count,
        launch_theme_capture_count=launch_theme_capture_count,
        launch_theme_review_count=launch_theme_review_count,
        historical_issue8_model_count=historical_issue8_model_count,
        issue8_tool_and_preflight_count=issue8_tool_and_preflight_count,
        issue16_debian_sandbox_count=issue16_debian_sandbox_count,
        required_app_sections=ui_source_contract.app_sections,
        required_settings_pages=ui_source_contract.settings_pages,
        required_device_pages=ui_source_contract.device_pages,
        required_recommended_model_ids=ui_source_contract.recommended_model_ids,
        physical_nanbeige_repair_count=int(physical_nanbeige_repair is not None),
        physical_candidate_apk_sha256=(
            physical_nanbeige_repair.candidate_apk_sha256
            if physical_nanbeige_repair is not None
            else ""
        ),
        physical_candidate_apk_bytes=(
            physical_nanbeige_repair.candidate_apk_bytes
            if physical_nanbeige_repair is not None
            else 0
        ),
        physical_apk_signer_sha256=(
            physical_nanbeige_repair.apk_signer_sha256
            if physical_nanbeige_repair is not None
            else ""
        ),
        physical_device_models=(
            (physical_nanbeige_repair.device_model,)
            if physical_nanbeige_repair is not None
            else ()
        ),
        physical_device_manufacturers=(
            (physical_nanbeige_repair.device_manufacturer,)
            if physical_nanbeige_repair is not None
            else ()
        ),
        physical_adb_serial_sha256=(
            physical_nanbeige_repair.adb_serial_sha256
            if physical_nanbeige_repair is not None
            else ""
        ),
        physical_nanbeige_model_path=(
            physical_nanbeige_repair.model_device_path
            if physical_nanbeige_repair is not None
            else ""
        ),
    )


def _artifact_manifest_record(artifact: ArtifactSpec) -> dict[str, Any]:
    """Serialize an artifact without rewriting older manifests with null fields."""

    record = asdict(artifact)
    if record["required_llama_cpp_runtime_lane"] is None:
        del record["required_llama_cpp_runtime_lane"]
    return record


def build_manifest(
    *,
    tag: str,
    source: SourceTreeIdentity,
    artifacts: Sequence[ArtifactSpec],
    evidence: ValidatedEvidence,
) -> dict[str, Any]:
    normalized_tag = validate_tag(tag)
    manifest = {
        "schema": manifest_schema_for_tag(normalized_tag),
        "tag": normalized_tag,
        "source_tree": asdict(source),
        "contract": {
            "languages": list(LANGUAGES),
            "profiles": list(PROFILES),
            "ui_screenshot_and_semantics_per_language_and_profile": True,
            "minimum_frame_timing_samples_per_profile": 100,
            "minimum_perfetto_surface_frame_timeline_tokens_per_profile": 100,
            "minimum_macrobenchmark_iterations_per_profile": 5,
            "maximum_perfetto_app_deadline_missed_or_dropped_percent": 10.0,
            "maximum_frame_duration_cpu_p95_ms": MAX_FRAME_DURATION_CPU_P95_MS,
            "maximum_frame_duration_cpu_p99_ms": MAX_FRAME_DURATION_CPU_P99_MS,
            "frame_timing_positive_overrun_is_nongating_avd_buffer_queue_diagnostic": True,
            "requires_zero_perfetto_unknown_or_overlapping_self_other_jank_tags": True,
            "dropped_frames_are_budgeted_with_app_deadline_misses": True,
            "requires_hardware_accelerated_avd": True,
            "avd_metrics_are_validation_signals_not_end_user_benchmarks": True,
            "requires_host_raw_transcript": True,
            "requires_androidx_macrobenchmark_raw_json": True,
            "requires_one_perfetto_trace_per_iteration": True,
            "requested_macrobenchmark_compilation_mode": "Full",
            "required_androidx_reporting_package_compilation_mode": (
                REPORTING_PACKAGE_COMPILATION_MODE
            ),
            "required_measured_target_compiler_filter": TARGET_COMPILER_FILTER,
            "requires_nondebuggable_profileable_target": True,
            "only_suppressed_macrobenchmark_error": "EMULATOR",
            "requires_runtime_health_and_nonempty_completion": True,
        },
        "registered_model_matrix": [
            _artifact_manifest_record(artifact) for artifact in artifacts
        ],
        "tested_binaries": {
            "ui_candidate_apk_sha256": evidence.ui_candidate_apk_sha256,
            "ui_instrumentation_apk_sha256": evidence.ui_instrumentation_apk_sha256,
            "benchmark_target_apk_sha256": evidence.benchmark_target_apk_sha256,
            "benchmark_test_apk_sha256": evidence.benchmark_test_apk_sha256,
            "evidence_run_id": evidence.evidence_run_id,
        },
        "evidence": {
            "file_count": len(evidence.files),
            "files": [asdict(record) for record in evidence.files],
        },
        "summary": {
            "ui_capture_count": evidence.ui_capture_count,
            "performance_record_count": evidence.performance_record_count,
            "model_count": evidence.model_count,
            "device_models": list(evidence.device_models),
        },
    }
    if requires_comprehensive_ui_evidence(normalized_tag):
        manifest["contract"].update(
            {
                "requires_comprehensive_ui_inventory_per_profile": True,
                "required_app_sections": list(evidence.required_app_sections),
                "required_nested_settings_pages": list(evidence.required_settings_pages),
                "required_non_overview_device_pages": list(evidence.required_device_pages),
                "required_recommended_model_ids": list(evidence.required_recommended_model_ids),
                "requires_six_language_recommended_model_and_framework_ui_inventory": True,
                "requires_persisted_palette_bound_launcher_and_deep_link_capture_per_profile": True,
                "requires_human_frame_by_frame_launch_theme_review": True,
                "launch_theme_capture_does_not_self_certify_pixels": True,
                "requires_historical_issue8_e4b_cpu_speculation_off_completion": True,
                "requires_issue8_exact_direct_tool_and_metadata_only_12b_preflight": True,
                "requires_issue16_fresh_debian_guest_https_and_clean_stopped_disposition": True,
            }
        )
        manifest["historical_model_evidence"] = {
            "scope": "issue-8-historical-experimental-text-only",
            "artifact": asdict(HISTORICAL_E4B_ARTIFACT),
            "evidence_path": HISTORICAL_E4B_EVIDENCE_PATH.as_posix(),
            "required_runtime_entrypoint": "on-device-backend-manager",
            "required_accelerator": "cpu",
            "required_speculative_decoding": "disabled",
            "excluded_from_release_matrix_and_quick_recommendations": True,
        }
        manifest["issue_evidence"] = {
            "issue_8": {
                "schema": ISSUE8_EVIDENCE_SCHEMA,
                "path": ISSUE8_EVIDENCE_PATH.as_posix(),
                "required_exact_prompts": [
                    "Run a command to tell me what time it is.",
                    "Check my device status",
                ],
                "required_model_and_provider_network_requests": 0,
                "twelve_b_artifact": {
                    "model_id": ISSUE8_TWELVE_B_MODEL_ID,
                    "repository": ISSUE8_TWELVE_B_REPOSITORY,
                    "revision": ISSUE8_TWELVE_B_REVISION,
                    "file_name": ISSUE8_TWELVE_B_FILE_NAME,
                    "catalog_declared_bytes": ISSUE8_TWELVE_B_BYTES,
                    "sha256": ISSUE8_TWELVE_B_SHA256,
                    "artifact_file_present": False,
                    "release_certified": False,
                    "quick_start_eligible": False,
                    "present_in_mobile_quick_catalog": False,
                    "automatically_selected": False,
                },
            },
            "issue_16": {
                "schema": ISSUE16_EVIDENCE_SCHEMA,
                "path": ISSUE16_EVIDENCE_PATH.as_posix(),
                "required_guest_execution_mode": "proot_distro_qemu",
                "required_guest_path": GUEST_ONLY_PATH,
                "required_success_disposition": "sandbox_removed_stopped",
                "failed_run_preservation_is_not_accepted_as_passing_evidence": True,
            },
        }
        manifest["summary"].update(
            {
                "comprehensive_ui_capture_count": evidence.comprehensive_ui_capture_count,
                "launch_theme_capture_count": evidence.launch_theme_capture_count,
                "launch_theme_review_count": evidence.launch_theme_review_count,
                "historical_issue8_model_count": evidence.historical_issue8_model_count,
                "issue8_tool_and_preflight_count": evidence.issue8_tool_and_preflight_count,
                "issue16_debian_sandbox_count": evidence.issue16_debian_sandbox_count,
            }
        )
    if requires_physical_nanbeige_repair_evidence(normalized_tag):
        manifest["contract"].update(
            {
                "requires_one_physical_arm64_nanbeige_repair_record": True,
                "physical_nanbeige_repair_schema": PHYSICAL_NANBEIGE_REPAIR_SCHEMA,
                "physical_nanbeige_repair_path": PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix(),
                "requires_source_bound_signed_release_candidate_installed_byte_for_byte": True,
                "required_release_candidate_signer_sha256": EXPECTED_RELEASE_SIGNER_SHA256,
                "requires_non_qemu_arm64_physical_device": True,
                "requires_exact_nanbeige_bytes_sha_and_app_scoped_path": True,
                "requires_extracted_stable_unknown_nanbeige_architecture_precondition": True,
                "required_stable_precondition_capture_route": (
                    "adb-shell-extracted-stable-runtime"
                ),
                "required_stable_runtime_apk_entries": list(
                    PHYSICAL_STABLE_RUNTIME_APK_ENTRIES
                ),
                "required_stable_runtime_file_count": len(
                    PHYSICAL_STABLE_RUNTIME_APK_ENTRIES
                ),
                "required_stable_runtime_system_library_allowlist": list(
                    PHYSICAL_STABLE_RUNTIME_SYSTEM_LIBRARIES
                ),
                "requires_source_candidate_runtime_closure_extraction_and_device_hash_match": True,
                "requires_final_signed_apk_runtime_closure_entry_hash_binding": True,
                "requires_stable_runtime_dependency_and_environment_binding": True,
                "requires_no_stable_runtime_linker_or_loader_error": True,
                "requires_automatic_persisted_turboquant_reconciliation_before_launch": True,
                "requires_visible_settings_to_match_reconciled_turboquant_lane": True,
                "requires_app_managed_turboquant_readiness_and_completion_canary": True,
                "requires_general_mode_ordinary_chat_without_unsolicited_tools": True,
                "required_general_mode_prompt": PHYSICAL_ORDINARY_CHAT_PROMPT,
                "required_general_mode_visible_reply": (
                    PHYSICAL_ORDINARY_CHAT_EXPECTED_REPLY
                ),
                "requires_nonblank_think_marker_free_visible_reply": True,
                "requires_visible_generation_progress": True,
                "requires_stop_to_replace_placeholder_with_terminal_message": True,
                "physical_device_serial_disclosure": "sha256-only",
            }
        )
        manifest["tested_binaries"].update(
            {
                "physical_candidate_apk_sha256": evidence.physical_candidate_apk_sha256,
                "physical_candidate_apk_bytes": evidence.physical_candidate_apk_bytes,
                "physical_candidate_apk_signer_sha256": evidence.physical_apk_signer_sha256,
            }
        )
        manifest["physical_device_evidence"] = {
            "schema": PHYSICAL_NANBEIGE_REPAIR_SCHEMA,
            "path": PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix(),
            "classification": "physical-arm64-functional-repair-gate",
            "adb_serial_sha256": evidence.physical_adb_serial_sha256,
            "device_models": list(evidence.physical_device_models),
            "device_manufacturers": list(evidence.physical_device_manufacturers),
            "model_device_path": evidence.physical_nanbeige_model_path,
            "artifact": _artifact_manifest_record(NANBEIGE_REPAIR_ARTIFACT),
        }
        manifest["summary"].update(
            {
                "physical_nanbeige_repair_count": evidence.physical_nanbeige_repair_count,
                "physical_device_models": list(evidence.physical_device_models),
            }
        )
    return manifest


def verify_physical_candidate_apk_binding(
    apk_path: Path,
    record_path: Path,
) -> tuple[int, str, int]:
    """Bind the committed physical closure hashes to entries in the exact signed APK."""

    context = "physical_candidate_apk_binding"
    if not apk_path.is_file():
        raise EvidenceError(f"{context}.apk is missing: {apk_path}")
    record = _json_object(record_path)
    release = _nested_object(record, "release_identity", context)
    stable = _nested_object(record, "stable_precondition", context)
    expected_apk_bytes = _integer(release, "candidate_apk_bytes", context, positive=True)
    expected_apk_sha = release.get("candidate_apk_sha256")
    if not isinstance(expected_apk_sha, str) or not HEX_64_RE.fullmatch(expected_apk_sha):
        raise EvidenceError(f"{context}.candidate_apk_sha256 must be lowercase SHA-256")
    actual_apk_bytes = apk_path.stat().st_size
    actual_apk_sha = _sha256_file(apk_path)
    if actual_apk_bytes != expected_apk_bytes or actual_apk_sha != expected_apk_sha:
        raise EvidenceError(
            f"{context}.apk bytes/SHA-256 do not match the physical candidate record"
        )

    closure = stable.get("runtime_closure")
    if not isinstance(closure, list):
        raise EvidenceError(f"{context}.runtime_closure must be an array")
    observed_entries = [
        item.get("apk_entry") if isinstance(item, dict) else None
        for item in closure
    ]
    if tuple(observed_entries) != PHYSICAL_STABLE_RUNTIME_APK_ENTRIES:
        raise EvidenceError(
            f"{context}.runtime_closure must contain the exact ordered Stable APK entries"
        )

    try:
        with zipfile.ZipFile(apk_path) as archive:
            entries_by_name: dict[str, list[zipfile.ZipInfo]] = {}
            for info in archive.infolist():
                entries_by_name.setdefault(info.filename, []).append(info)
            for index, closure_file in enumerate(closure):
                closure_context = f"{context}.runtime_closure[{index}]"
                apk_entry = PHYSICAL_STABLE_RUNTIME_APK_ENTRIES[index]
                matches = entries_by_name.get(apk_entry, [])
                if len(matches) != 1 or matches[0].is_dir():
                    raise EvidenceError(
                        f"{closure_context}.apk_entry must occur exactly once as a file"
                    )
                info = matches[0]
                expected_bytes = _integer(
                    closure_file,
                    "extracted_bytes",
                    closure_context,
                    positive=True,
                )
                expected_sha = closure_file.get("extracted_sha256")
                if not isinstance(expected_sha, str) or not HEX_64_RE.fullmatch(expected_sha):
                    raise EvidenceError(
                        f"{closure_context}.extracted_sha256 must be lowercase SHA-256"
                    )
                digest = hashlib.sha256()
                extracted_bytes = 0
                with archive.open(info, "r") as entry_stream:
                    while True:
                        chunk = entry_stream.read(1024 * 1024)
                        if not chunk:
                            break
                        extracted_bytes += len(chunk)
                        digest.update(chunk)
                if info.file_size != expected_bytes or extracted_bytes != expected_bytes:
                    raise EvidenceError(
                        f"{closure_context}.extracted_bytes do not match the signed APK entry"
                    )
                if digest.hexdigest() != expected_sha:
                    raise EvidenceError(
                        f"{closure_context}.extracted_sha256 does not match the signed APK entry"
                    )
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise EvidenceError(f"{context}.apk is not a readable signed APK archive: {exc}") from exc

    return actual_apk_bytes, actual_apk_sha, len(closure)


def write_manifest(path: Path, manifest: Mapping[str, Any]) -> None:
    encoded = (json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")
    temporary = path.with_name(f"{path.name}.tmp")
    temporary.write_bytes(encoded)
    temporary.replace(path)


def verify_manifest(path: Path, expected: Mapping[str, Any]) -> None:
    actual = _json_object(path)
    if actual != expected:
        raise EvidenceError(
            "Committed Android release evidence manifest does not match the current "
            "tag, source tree, model registry, or evidence bytes; regenerate it after real device runs"
        )


def _relative_evidence_dir(repo_root: Path, evidence_dir: Path, tag: str) -> str:
    try:
        relative = evidence_dir.resolve().relative_to(repo_root.resolve()).as_posix()
    except ValueError as exc:
        raise EvidenceError("Release evidence directory must be inside the repository") from exc
    expected = (EVIDENCE_PREFIX / tag).as_posix()
    if relative != expected:
        raise EvidenceError(f"Release evidence must use {expected}, got {relative}")
    return relative


def require_committed_evidence(repo_root: Path, evidence_dir: Path) -> None:
    relative = evidence_dir.resolve().relative_to(repo_root.resolve()).as_posix()
    tracked = {
        token.decode("utf-8")
        for token in _run_git(repo_root, "ls-files", "-z", "--", relative).stdout.split(b"\0")
        if token
    }
    present = {
        f"{relative}/{path.as_posix()}"
        for path in _walk_evidence_files(evidence_dir)
    }
    if tracked != present:
        missing = present - tracked
        unexpected = tracked - present
        raise EvidenceError(
            "Every repository-resident release evidence file, including manifest.json, "
            "must be committed; "
            f"untracked={[path for path in sorted(missing)]}, missing={[path for path in sorted(unexpected)]}"
        )


def _resolve_paths(args: argparse.Namespace) -> tuple[Path, Path, Path, str]:
    repo_root = args.repo_root.resolve()
    tag = validate_tag(args.tag)
    def relative_to_repo(candidate: Path) -> Path:
        return candidate.resolve() if candidate.is_absolute() else (repo_root / candidate).resolve()

    evidence_dir = (
        relative_to_repo(args.evidence_dir)
        if args.evidence_dir is not None
        else repo_root / Path((EVIDENCE_PREFIX / tag).as_posix())
    )
    registry = (
        relative_to_repo(args.model_registry)
        if args.model_registry is not None
        else repo_root
        / "android/app/src/main/java/com/mobilefork/hermesagent/models/VerifiedLocalModelArtifacts.kt"
    )
    _relative_evidence_dir(repo_root, evidence_dir, tag)
    return repo_root, evidence_dir, registry, tag


def _resolve_perfetto_root(args: argparse.Namespace, repo_root: Path) -> Path | None:
    candidate = getattr(args, "perfetto_root", None)
    if candidate is None:
        return None
    return candidate.resolve() if candidate.is_absolute() else (repo_root / candidate).resolve()


def _create(args: argparse.Namespace) -> int:
    repo_root, evidence_dir, registry, tag = _resolve_paths(args)
    perfetto_root = _resolve_perfetto_root(args, repo_root)
    require_source_clean_for_create(repo_root, evidence_dir)
    artifacts = load_registered_model_matrix(registry)
    source = git_source_tree_identity(repo_root)
    evidence = validate_evidence_directory(
        evidence_dir,
        artifacts,
        source.digest,
        tag,
        repo_root=repo_root,
        perfetto_root=perfetto_root,
    )
    manifest = build_manifest(tag=tag, source=source, artifacts=artifacts, evidence=evidence)
    manifest_path = evidence_dir / "manifest.json"
    write_manifest(manifest_path, manifest)
    print(f"wrote={manifest_path.relative_to(repo_root).as_posix()}")
    print(f"tag={tag}")
    print(f"sourceDigest={source.digest}")
    print(f"sourceFiles={source.file_count}")
    print(f"evidenceFiles={len(evidence.files)}")
    print(f"uiCaptures={evidence.ui_capture_count}")
    print(f"models={evidence.model_count}")
    print(
        "perfettoEvidence="
        + ("external-bytes-verified" if perfetto_root is not None else "repository-bytes-verified")
    )
    return 0


def _verify(args: argparse.Namespace) -> int:
    repo_root, evidence_dir, registry, tag = _resolve_paths(args)
    perfetto_root = _resolve_perfetto_root(args, repo_root)
    require_clean_worktree(repo_root)
    if args.require_tag_ref:
        require_tag_points_to_head(repo_root, tag)
    artifacts = load_registered_model_matrix(registry)
    source = git_source_tree_identity(repo_root)
    evidence = validate_evidence_directory(
        evidence_dir,
        artifacts,
        source.digest,
        tag,
        repo_root=repo_root,
        perfetto_root=perfetto_root,
    )
    expected = build_manifest(tag=tag, source=source, artifacts=artifacts, evidence=evidence)
    verify_manifest(evidence_dir / "manifest.json", expected)
    require_committed_evidence(repo_root, evidence_dir)
    print(f"verified={evidence_dir.relative_to(repo_root).as_posix()}")
    print(f"tag={tag}")
    print(f"sourceDigest={source.digest}")
    print(f"evidenceFiles={len(evidence.files)}")
    print("deviceCertification=committed-headed-avd-evidence")
    print(
        "perfettoEvidence="
        + ("external-bytes-verified" if perfetto_root is not None else "repository-bytes-verified")
    )
    return 0


def _source_identity(args: argparse.Namespace) -> int:
    repo_root = args.repo_root.resolve()
    if args.require_clean:
        require_clean_worktree(repo_root)
    source = git_source_tree_identity(repo_root)
    print(f"sourceDigest={source.digest}")
    print(f"sourceFiles={source.file_count}")
    print(f"sourceAlgorithm={source.algorithm}")
    return 0


def _verify_physical_candidate_apk(args: argparse.Namespace) -> int:
    repo_root = args.repo_root.resolve()
    tag = validate_tag(args.tag)
    apk_path = args.apk.resolve() if args.apk.is_absolute() else (repo_root / args.apk).resolve()
    record_path = (
        args.record.resolve()
        if args.record is not None and args.record.is_absolute()
        else (
            (repo_root / args.record).resolve()
            if args.record is not None
            else repo_root
            / Path((EVIDENCE_PREFIX / tag / PHYSICAL_NANBEIGE_REPAIR_PATH).as_posix())
        )
    )
    apk_bytes, apk_sha, closure_files = verify_physical_candidate_apk_binding(
        apk_path,
        record_path,
    )
    print(f"physicalCandidateApk={apk_path}")
    print(f"physicalCandidateApkBytes={apk_bytes}")
    print(f"physicalCandidateApkSha256={apk_sha}")
    print(f"physicalCandidateRuntimeClosureFiles={closure_files}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Create or verify committed Android headed-device release evidence"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    source_parser = subparsers.add_parser(
        "source-identity",
        help="Print the committed source identity to embed in the headed debug candidate",
    )
    source_parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    source_parser.add_argument(
        "--require-clean",
        action="store_true",
        help="Reject tracked or nonignored untracked changes before printing the identity",
    )
    source_parser.set_defaults(handler=_source_identity)
    apk_binding_parser = subparsers.add_parser(
        "verify-physical-candidate-apk",
        help="Bind the physical Stable runtime closure to the exact signed candidate APK",
    )
    apk_binding_parser.add_argument("--tag", required=True, help="Android v0 SemVer release tag")
    apk_binding_parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    apk_binding_parser.add_argument("--apk", required=True, type=Path)
    apk_binding_parser.add_argument("--record", type=Path)
    apk_binding_parser.set_defaults(handler=_verify_physical_candidate_apk)
    for command, handler in (("create", _create), ("verify", _verify)):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--tag", required=True, help="Android v0 SemVer release tag")
        subparser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
        subparser.add_argument("--evidence-dir", type=Path)
        subparser.add_argument("--model-registry", type=Path)
        subparser.add_argument(
            "--perfetto-root",
            type=Path,
            help=(
                "Closed external directory containing phone-compact.traces and "
                "tablet.traces; every trace byte is hashed while logical manifest paths stay unchanged"
            ),
        )
        if command == "verify":
            subparser.add_argument(
                "--require-tag-ref",
                action="store_true",
                help="Require refs/tags/<tag> to resolve to the checked-out evidence commit",
            )
        subparser.set_defaults(handler=handler)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except EvidenceError as exc:
        print(f"Android release evidence rejected: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
