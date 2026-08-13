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
import zlib
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence


MANIFEST_SCHEMA = "hermes-android-release-evidence-manifest-v2"
MODEL_EVIDENCE_SCHEMA = "hermes-model-evidence-v1"
PERFORMANCE_SCHEMA = "hermes-android-performance-evidence-v2"
RAW_PERFORMANCE_SCHEMA = "hermes-android-performance-host-raw-v2"
SOURCE_DIGEST_ALGORITHM = "sha256-git-tree-contents-v1"
EVIDENCE_PREFIX = PurePosixPath("android/release-evidence")
LANGUAGES = ("en", "zh", "es", "de", "pt", "fr")
PROFILES = ("phone-compact", "tablet")
TAG_RE = re.compile(
    r"^v0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"(?:-(?:alpha|beta|rc)(?:\.[0-9]+)?)?$"
)
HEX_40_RE = re.compile(r"^[0-9a-f]{40}$")
HEX_64_RE = re.compile(r"^[0-9a-f]{64}$")
SOFTWARE_RENDERER_MARKERS = (
    "swiftshader",
    "llvmpipe",
    "software rasterizer",
    "microsoft basic render driver",
)
PACKAGE_ID = "com.mobilefork.hermesagent"
TEST_PACKAGE_ID = f"{PACKAGE_ID}.test"
BENCHMARK_TEST_PACKAGE_ID = f"{PACKAGE_ID}.macrobenchmark"
MAIN_ACTIVITY = f"{PACKAGE_ID}/.MainActivity"
PHONE_UI_DRAWER_TAG = "HermesShellDrawerButton"
BUILD_VARIANT = "debug"
PERFORMANCE_BUILD_VARIANT = "benchmark"
LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
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

    @property
    def backend(self) -> str:
        return {"litert-lm": "litert-lm", "llama.cpp": "llama.cpp"}[self.runtime]

    @property
    def evidence_path(self) -> PurePosixPath:
        return PurePosixPath("models") / f"{self.model_id}.json"


@dataclass(frozen=True)
class SourceTreeIdentity:
    algorithm: str
    digest: str
    file_count: int
    git_object_format: str
    excluded_prefix: str


@dataclass(frozen=True)
class EvidenceFile:
    path: str
    bytes: int
    sha256: str


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


@dataclass(frozen=True)
class DecodedPng:
    width: int
    height: int
    content_pixel_sha256: str
    sampled_unique_colors: int


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
        if set(raw_fields) != required_fields:
            raise EvidenceError(
                "Model registry Artifact fields do not match the canonical contract; "
                f"missing={sorted(required_fields - set(raw_fields))}, "
                f"unexpected={sorted(set(raw_fields) - required_fields)}"
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
        artifact = ArtifactSpec(
            model_id=string_field("modelId"),
            repository=string_field("repoId"),
            revision=string_field("revision").lower(),
            file_name=string_field("fileName"),
            runtime=string_field("runtime"),
            expected_bytes=int(bytes_match.group(1).replace("_", "")),
            sha256=string_field("sha256").lower(),
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
        "litertlm_coordinate": LITERTLM_COORDINATE,
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
        f"-PhermesBenchmarkExpectedLiteRtLmCoordinate={LITERTLM_COORDINATE}",
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
        if dropped_count != 0 or unknown_tag_count != 0 or overlapping_jank_tag_count != 0:
            raise EvidenceError(
                f"{context} iteration {index + 1} contains dropped, unknown-tag, or overlapping-tag frames"
            )
        expected_self_tagged_percent = self_jank_tagged_count * 100.0 / total
        app_deadline_missed_percent = deadline_count * 100.0 / total
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
    if app_deadline_missed_percent > 10.0:
        raise EvidenceError(
            f"{context} App Deadline Missed surface tokens exceed the 10% "
            "controlled-AVD budget"
        )
    if (
        deadline_frames + non_deadline_self_jank_tagged_frames
        != self_jank_tagged_frames
    ):
        raise EvidenceError(f"{context} pooled jank categories do not reconcile")
    if self_jank_tagged_frames + other_jank_tagged_frames > total_frames:
        raise EvidenceError(f"{context} pooled Self/Other Jank tags exceed surface tokens")
    if (
        dropped_frames != 0
        or unknown_tag_frames != 0
        or overlapping_jank_tag_frames != 0
    ):
        raise EvidenceError(
            f"{context} contains dropped, unknown-tag, or overlapping-tag Perfetto frames"
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
    artifact_path_overrides: Mapping[str, Path] | None = None,
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
        "litertlm_coordinate": LITERTLM_COORDINATE,
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
    if overrides and set(overrides) != expected_references:
        raise EvidenceError(f"{context} temporary artifact override set is incomplete or unexpected")

    def validate_reference(reference: Any, expected_path: str, *, nonempty: bool = True) -> Path:
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
        trace_path = validate_reference(reference, expected_path)
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
        host_payload, payload, profile, source_digest, version_name, version_code
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
        "litertlm_coordinate": LITERTLM_COORDINATE,
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


def expected_evidence_paths(
    artifacts: Sequence[ArtifactSpec],
    performance_records: Sequence[Mapping[str, Any]] = (),
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


def validate_evidence_directory(
    evidence_dir: Path,
    artifacts: Sequence[ArtifactSpec],
    source_digest: str,
    tag: str,
) -> ValidatedEvidence:
    if not evidence_dir.is_dir():
        raise EvidenceError(f"Release evidence directory does not exist: {evidence_dir}")
    actual_paths = _walk_evidence_files(evidence_dir)
    actual_without_manifest = actual_paths - {PurePosixPath("manifest.json")}
    base_expected_paths = expected_evidence_paths(artifacts)
    missing_base = base_expected_paths - actual_without_manifest
    if missing_base:
        raise EvidenceError(
            "Release evidence is missing required fixed paths: "
            f"{[path.as_posix() for path in sorted(missing_base)]}"
        )

    if not HEX_64_RE.fullmatch(source_digest):
        raise EvidenceError("Current source digest must be one lowercase SHA-256")
    version_name, version_code = android_identity_for_tag(tag)
    performance_records = [
        _validate_performance(
            evidence_dir / "performance" / f"{profile}.json",
            profile,
            source_digest,
            version_name,
            version_code,
        )
        for profile in PROFILES
    ]
    expected_paths = expected_evidence_paths(artifacts, performance_records)
    missing = expected_paths - actual_without_manifest
    unexpected = actual_without_manifest - expected_paths
    if missing or unexpected:
        raise EvidenceError(
            "Release evidence layout mismatch; "
            f"missing={[path.as_posix() for path in sorted(missing)]}, "
            f"unexpected={[path.as_posix() for path in sorted(unexpected)]}"
        )

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
                "litertlm_coordinate": LITERTLM_COORDINATE,
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
        )

    file_records = tuple(
        EvidenceFile(
            path=relative.as_posix(),
            bytes=(evidence_dir / Path(relative.as_posix())).stat().st_size,
            sha256=_sha256_file(evidence_dir / Path(relative.as_posix())),
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
    )


def build_manifest(
    *,
    tag: str,
    source: SourceTreeIdentity,
    artifacts: Sequence[ArtifactSpec],
    evidence: ValidatedEvidence,
) -> dict[str, Any]:
    return {
        "schema": MANIFEST_SCHEMA,
        "tag": validate_tag(tag),
        "source_tree": asdict(source),
        "contract": {
            "languages": list(LANGUAGES),
            "profiles": list(PROFILES),
            "ui_screenshot_and_semantics_per_language_and_profile": True,
            "minimum_frame_timing_samples_per_profile": 100,
            "minimum_perfetto_surface_frame_timeline_tokens_per_profile": 100,
            "minimum_macrobenchmark_iterations_per_profile": 5,
            "maximum_perfetto_app_deadline_missed_percent": 10.0,
            "maximum_frame_duration_cpu_p95_ms": MAX_FRAME_DURATION_CPU_P95_MS,
            "maximum_frame_duration_cpu_p99_ms": MAX_FRAME_DURATION_CPU_P99_MS,
            "frame_timing_positive_overrun_is_nongating_avd_buffer_queue_diagnostic": True,
            "requires_zero_perfetto_dropped_unknown_or_overlapping_jank_tags": True,
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
        "registered_model_matrix": [asdict(artifact) for artifact in artifacts],
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
            "Every release evidence file, including manifest.json, must be committed; "
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


def _create(args: argparse.Namespace) -> int:
    repo_root, evidence_dir, registry, tag = _resolve_paths(args)
    require_source_clean_for_create(repo_root, evidence_dir)
    artifacts = load_registered_model_matrix(registry)
    source = git_source_tree_identity(repo_root)
    evidence = validate_evidence_directory(evidence_dir, artifacts, source.digest, tag)
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
    return 0


def _verify(args: argparse.Namespace) -> int:
    repo_root, evidence_dir, registry, tag = _resolve_paths(args)
    require_clean_worktree(repo_root)
    if args.require_tag_ref:
        require_tag_points_to_head(repo_root, tag)
    artifacts = load_registered_model_matrix(registry)
    source = git_source_tree_identity(repo_root)
    evidence = validate_evidence_directory(evidence_dir, artifacts, source.digest, tag)
    expected = build_manifest(tag=tag, source=source, artifacts=artifacts, evidence=evidence)
    verify_manifest(evidence_dir / "manifest.json", expected)
    require_committed_evidence(repo_root, evidence_dir)
    print(f"verified={evidence_dir.relative_to(repo_root).as_posix()}")
    print(f"tag={tag}")
    print(f"sourceDigest={source.digest}")
    print(f"evidenceFiles={len(evidence.files)}")
    print("deviceCertification=committed-headed-avd-evidence")
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
    for command, handler in (("create", _create), ("verify", _verify)):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--tag", required=True, help="Android v0 SemVer release tag")
        subparser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
        subparser.add_argument("--evidence-dir", type=Path)
        subparser.add_argument("--model-registry", type=Path)
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
