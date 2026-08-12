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
import xml.etree.ElementTree as ET
import zlib
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence


MANIFEST_SCHEMA = "hermes-android-release-evidence-manifest-v1"
MODEL_EVIDENCE_SCHEMA = "hermes-model-evidence-v1"
PERFORMANCE_SCHEMA = "hermes-android-performance-evidence-v1"
RAW_PERFORMANCE_SCHEMA = "hermes-android-performance-raw-v1"
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
MAIN_ACTIVITY = f"{PACKAGE_ID}/.MainActivity"
UI_DUMP_PATH_PREFIX = "/data/local/tmp/hermes-performance-ui-"
PHONE_DRAWER_TAG = "HermesChatDrawerButton"
PHONE_UI_DRAWER_TAG = "HermesShellDrawerButton"
PHONE_SETTINGS_TAG = "HermesNavSettings"
TABLET_SETTINGS_TAG = "HermesRailSettings"
SETTINGS_CONTENT_TAG = "HermesSettingsContentList"
BUILD_VARIANT = "debug"
LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{15,79}$")
BOOT_ID_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
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
    candidate_apk_sha256: str
    instrumentation_apk_sha256: str
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
        reconstructed = bytearray(row_bytes)
        for index, value in enumerate(filtered):
            left = reconstructed[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            above = previous[index]
            upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
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
                visible_row = b"".join(
                    reconstructed[index : index + 3]
                    for index in range(0, row_bytes, 4)
                )
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


def _raw_ui_nodes(xml_text: str, context: str) -> tuple[Mapping[str, str], ...]:
    encoded_size = len(xml_text.encode("utf-8"))
    if encoded_size <= 0 or encoded_size > 4 * 1024 * 1024:
        raise EvidenceError(f"{context} UI hierarchy has an unsafe byte size: {encoded_size}")
    if "<!DOCTYPE" in xml_text.upper() or "<!ENTITY" in xml_text.upper():
        raise EvidenceError(f"{context} UI hierarchy contains a forbidden XML declaration")
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise EvidenceError(f"{context} UI hierarchy is invalid XML: {exc}") from exc
    if root.tag != "hierarchy":
        raise EvidenceError(f"{context} UI hierarchy has unexpected root {root.tag!r}")
    nodes = tuple(dict(node.attrib) for node in root.iter("node"))
    if not nodes:
        raise EvidenceError(f"{context} UI hierarchy contains no accessibility nodes")
    return nodes


def _raw_matching_ui_nodes(
    xml_text: str, resource_id: str, context: str
) -> tuple[Mapping[str, str], ...]:
    return tuple(
        node
        for node in _raw_ui_nodes(xml_text, context)
        if node.get("resource-id", "") == resource_id
    )


def _raw_reject_ui_resource(xml_text: str, resource_id: str, context: str) -> None:
    if _raw_matching_ui_nodes(xml_text, resource_id, context):
        raise EvidenceError(f"{context} exposes wrong-profile resource ID {resource_id}")


def _raw_ui_target(
    xml_text: str,
    resource_id: str,
    width_px: int,
    height_px: int,
    context: str,
    *,
    clickable: bool = False,
    scrollable: bool = False,
) -> tuple[int, int, int, int]:
    matches = _raw_matching_ui_nodes(xml_text, resource_id, context)
    if len(matches) != 1:
        raise EvidenceError(
            f"{context} resource ID {resource_id} must appear exactly once; observed {len(matches)}"
        )
    node = matches[0]
    if node.get("package") != PACKAGE_ID:
        raise EvidenceError(f"{context} resource ID {resource_id} belongs to the wrong package")
    if node.get("enabled") != "true":
        raise EvidenceError(f"{context} resource ID {resource_id} is not enabled")
    if clickable and node.get("clickable") != "true":
        raise EvidenceError(f"{context} resource ID {resource_id} is not clickable")
    if scrollable and node.get("scrollable") != "true":
        raise EvidenceError(f"{context} resource ID {resource_id} is not scrollable")
    match = re.fullmatch(
        r"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]", node.get("bounds", "")
    )
    if match is None:
        raise EvidenceError(f"{context} resource ID {resource_id} has invalid bounds")
    bounds = tuple(int(value) for value in match.groups())
    left, top, right, bottom = bounds
    if not (0 <= left < right <= width_px and 0 <= top < bottom <= height_px):
        raise EvidenceError(f"{context} resource ID {resource_id} has unsafe display bounds")
    return bounds


def _raw_safe_swipe_coordinates(bounds: tuple[int, int, int, int]) -> tuple[int, int, int]:
    left, top, right, bottom = bounds
    width, height = right - left, bottom - top
    if width < 48 or height < 160:
        raise EvidenceError("settings scroll bounds are too small for a safe swipe")
    x = (left + right) // 2
    inset = max(16, height // 5)
    top_y, bottom_y = top + inset, bottom - inset
    if not (
        left < x < right
        and top < top_y < bottom_y < bottom
        and bottom_y - top_y >= 48
    ):
        raise EvidenceError("settings scroll bounds cannot yield safe interior swipe coordinates")
    return x, top_y, bottom_y


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


def _raw_gfx_number(output: str, label: str, context: str, *, integer: bool) -> int | float:
    values = re.findall(
        rf"(?mi)^\s*{re.escape(label)}:\s*([0-9]+(?:\.[0-9]+)?)\s*(?:ms)?\s*$",
        output,
    )
    if len(values) != 1:
        raise EvidenceError(f"{context} does not expose one {label}")
    raw = values[0]
    if integer and not raw.isdigit():
        raise EvidenceError(f"{context} {label} is not an integer")
    return int(raw) if integer else float(raw)


def _raw_parse_gfxinfo(output: str, context: str) -> dict[str, int | float]:
    total = int(_raw_gfx_number(output, "Total frames rendered", context, integer=True))
    matches = re.findall(
        r"(?mi)^\s*Janky frames:\s*([0-9]+)(?:\s*\(([0-9]+(?:\.[0-9]+)?)%\))?\s*$",
        output,
    )
    unique = [(int(count), printed) for count, printed in matches]
    if len(unique) != 1:
        raise EvidenceError(f"{context} does not expose one janky-frame summary")
    janky, printed = unique[0]
    if total <= 0 or not 0 <= janky <= total:
        raise EvidenceError(f"{context} contains invalid frame counts")
    percent = janky * 100.0 / total
    if printed and abs(float(printed) - percent) > 0.25:
        raise EvidenceError(f"{context} printed jank percentage disagrees with counts")
    result: dict[str, int | float] = {
        "total_rendered": total,
        "janky": janky,
        "janky_percent": round(percent, 4),
        "p50_ms": float(_raw_gfx_number(output, "50th percentile", context, integer=False)),
        "p90_ms": float(_raw_gfx_number(output, "90th percentile", context, integer=False)),
        "p95_ms": float(_raw_gfx_number(output, "95th percentile", context, integer=False)),
        "p99_ms": float(_raw_gfx_number(output, "99th percentile", context, integer=False)),
    }
    percentiles = [result[field] for field in ("p50_ms", "p90_ms", "p95_ms", "p99_ms")]
    if any(value <= 0 for value in percentiles) or percentiles != sorted(percentiles):
        raise EvidenceError(f"{context} contains invalid percentile timings")
    return result


def _raw_qemu_inventory(output: str, context: str) -> tuple[Mapping[str, Any], ...]:
    try:
        decoded = json.loads(output)
    except json.JSONDecodeError as exc:
        raise EvidenceError(f"{context} QEMU inventory is invalid JSON: {exc}") from exc
    items = decoded if isinstance(decoded, list) else [decoded]
    processes: list[Mapping[str, Any]] = []
    for index, item in enumerate(items):
        if not isinstance(item, Mapping) or set(item) != {"pid", "name", "command_line"}:
            raise EvidenceError(f"{context} QEMU inventory entry {index} has invalid fields")
        if (
            isinstance(item["pid"], bool)
            or not isinstance(item["pid"], int)
            or item["pid"] <= 0
            or not isinstance(item["name"], str)
            or not item["name"]
            or not item["name"].casefold().startswith("qemu-system-")
            or not isinstance(item["command_line"], str)
            or not item["command_line"]
        ):
            raise EvidenceError(f"{context} QEMU inventory entry {index} has invalid identity")
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
    matches: list[Mapping[str, Any]] = []
    for process in _raw_qemu_inventory(_raw_stdout(record, context), context):
        try:
            tokens = shlex.split(str(process["command_line"]), posix=False)
        except ValueError as exc:
            raise EvidenceError(f"{context} contains an untokenizable QEMU command: {exc}") from exc
        flags: dict[str, list[str]] = {}
        for index, token in enumerate(tokens):
            folded = token.strip('"\'').casefold()
            if folded in {"-avd", "-port", "-ports"}:
                if index + 1 >= len(tokens):
                    raise EvidenceError(f"{context} contains incomplete QEMU flag {token}")
                flags.setdefault(folded, []).append(tokens[index + 1].strip('"\''))
        exact_port = flags.get("-port") == [str(console_port)] and "-ports" not in flags
        exact_ports = flags.get("-ports") == [f"{console_port},{console_port + 1}"] and "-port" not in flags
        if flags.get("-avd") == [normalized_device["avd_name"]] and (exact_port or exact_ports):
            matches.append(process)
    if len(matches) != 1:
        raise EvidenceError(f"{context} does not prove exactly one serial/AVD QEMU process")
    process = matches[0]
    exact = {
        "pid": normalized_device.get("emulator_pid"),
        "name": normalized_device.get("emulator_process_name"),
        "command_line": normalized_device.get("emulator_command"),
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
    context = f"performance[{profile}].raw"
    exact_header: dict[str, Any] = {
        "schema": RAW_PERFORMANCE_SCHEMA,
        "profile": profile,
        "release_source_digest": source_digest,
        "candidate_apk_sha256": normalized["candidate_apk_sha256"],
        "instrumentation_apk_sha256": normalized["instrumentation_apk_sha256"],
        "evidence_run_id": normalized["evidence_run_id"],
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "build_variant": BUILD_VARIANT,
        "litertlm_coordinate": LITERTLM_COORDINATE,
    }
    if set(raw_payload) != set(exact_header) | {"records"}:
        raise EvidenceError(f"{context} top-level fields do not match the raw transcript contract")
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
        "package.candidate.path",
        "package.candidate.sha256",
        "package.instrumentation.path",
        "package.instrumentation.sha256",
        "package.version",
        "host.qemu_processes",
    ]
    initial_ids = [f"initial.{suffix}" for suffix in identity_suffix]
    measure_before_retry = [
        "measure.emulator.accel-check",
        "measure.screen.wm_size",
        "measure.screen.wm_density",
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
    navigation_ids = (
        [
            "measure.ui.initial.remove",
            "measure.ui.initial.dump",
            "measure.ui.initial.cat",
            "measure.ui.phone.drawer.tap",
            "measure.ui.drawer.remove",
            "measure.ui.drawer.dump",
            "measure.ui.drawer.cat",
            "measure.ui.phone.settings.tap",
            "measure.ui.settings.remove",
            "measure.ui.settings.dump",
            "measure.ui.settings.cat",
        ]
        if profile == "phone-compact"
        else [
            "measure.ui.initial.remove",
            "measure.ui.initial.dump",
            "measure.ui.initial.cat",
            "measure.ui.tablet.settings.tap",
            "measure.ui.settings.remove",
            "measure.ui.settings.dump",
            "measure.ui.settings.cat",
        ]
    )
    measure_after_retry = [
        *navigation_ids,
        "measure.screen.am_config",
        "measure.gpu.surfaceflinger",
        "measure.activity.before_gfx",
        "measure.gfx.reset",
    ]
    final_ids = [f"final.{suffix}" for suffix in identity_suffix]
    fixed_before = initial_ids + measure_before_retry
    if order[: len(fixed_before)] != fixed_before:
        raise EvidenceError(f"{context} initial/measurement command order is incomplete or reordered")
    prefix_cursor = len(fixed_before)
    has_warm_retry = order[prefix_cursor : prefix_cursor + len(retry_ids)] == retry_ids
    if has_warm_retry:
        prefix_cursor += len(retry_ids)
    if order[prefix_cursor : prefix_cursor + len(measure_after_retry)] != measure_after_retry:
        raise EvidenceError(f"{context} UI navigation/measurement command order is incomplete")
    prefix_cursor += len(measure_after_retry)
    measurement_suffix = [
        "measure.activity.after_gfx",
        "measure.memory.meminfo",
        "measure.process.pid_after_measurement",
        *final_ids,
    ]
    if order[-len(measurement_suffix) :] != measurement_suffix:
        raise EvidenceError(f"{context} memory/final identity command order is incomplete or reordered")
    dynamic = order[prefix_cursor : -len(measurement_suffix)]
    if not dynamic:
        raise EvidenceError(f"{context} contains no gfxinfo exercise records")
    swipe_index = 1
    round_index = 1
    framestats_ids: list[str] = []
    swipe_ordinal_in_round: dict[str, int] = {}
    cursor = 0
    while cursor < len(dynamic):
        swipes_this_round = 0
        while cursor < len(dynamic) and dynamic[cursor].startswith("measure.gfx.swipe."):
            expected = f"measure.gfx.swipe.{swipe_index:04d}"
            if dynamic[cursor] != expected:
                raise EvidenceError(f"{context} swipe transcript IDs are not contiguous")
            swipe_ordinal_in_round[expected] = swipes_this_round
            swipe_index += 1
            swipes_this_round += 1
            cursor += 1
        if swipes_this_round == 0:
            raise EvidenceError(f"{context} each gfxinfo round must exercise the UI")
        expected_framestats = f"measure.gfx.framestats.{round_index:02d}"
        if cursor >= len(dynamic) or dynamic[cursor] != expected_framestats:
            raise EvidenceError(f"{context} each exercise round must end in one framestats dump")
        framestats_ids.append(expected_framestats)
        round_index += 1
        cursor += 1

    device = _nested_object(normalized, "device", f"performance[{profile}]")
    collector = _nested_object(normalized, "collector", f"performance[{profile}]")
    serial = _required_string(device, "serial", f"performance[{profile}].device")
    adb_record = _raw_record(records, "initial.adb.devices", context)
    adb = str(adb_record["argv"][0])
    if _portable_executable_name(adb) not in {"adb", "adb.exe"}:
        raise EvidenceError(f"{context} uses an unexpected adb executable")

    def adb_command(record_id: str, *tail: str, targeted: bool = True) -> Mapping[str, Any]:
        record = _raw_record(records, record_id, context)
        expected = [adb, "-s", serial, *tail] if targeted else [adb, *tail]
        _raw_expect_argv(record, expected, f"{context}.{record_id}")
        return record

    def validate_identity(phase: str) -> None:
        inventory = adb_command(f"{phase}.adb.devices", "devices", "-l", targeted=False)
        states = []
        for line in str(inventory["stdout"]).splitlines():
            fields = line.strip().split()
            if fields and fields[0] == serial:
                states.append(fields[1] if len(fields) > 1 else "")
        if states != ["device"]:
            raise EvidenceError(f"{context}.{phase} adb inventory does not bind one device target")
        if _raw_stdout(adb_command(f"{phase}.adb.get-serialno", "get-serialno"), context) != serial:
            raise EvidenceError(f"{context}.{phase} adb get-serialno does not exactly match serial")
        if _raw_stdout(adb_command(f"{phase}.adb.get-state", "get-state"), context) != "device":
            raise EvidenceError(f"{context}.{phase} adb get-state is not device")

        properties: tuple[tuple[str, str, Any], ...] = (
            ("avd_name", "ro.boot.qemu.avd_name", device["avd_name"]),
            ("build_fingerprint", "ro.build.fingerprint", device["build_fingerprint"]),
            ("model", "ro.product.model", device["model"]),
            ("android_sdk", "ro.build.version.sdk", str(device["android_sdk"])),
        )
        for label, prop, expected in properties:
            record_id = f"{phase}.device.getprop.{label}"
            observed = _raw_stdout(adb_command(record_id, "shell", "getprop", prop), context)
            if observed != expected:
                raise EvidenceError(f"{context}.{record_id} disagrees with normalized device identity")
        abi_record = adb_command(
            f"{phase}.device.getprop.supported_abis",
            "shell",
            "getprop",
            "ro.product.cpu.abilist",
        )
        observed_abis = tuple(
            part.strip() for part in _raw_stdout(abi_record, context).split(",") if part.strip()
        )
        if observed_abis != tuple(device["supported_abis"]):
            raise EvidenceError(f"{context}.{phase} ABI getprop disagrees with normalized identity")
        boot = _raw_stdout(
            adb_command(
                f"{phase}.device.boot_id",
                "shell",
                "cat",
                "/proc/sys/kernel/random/boot_id",
            ),
            context,
        ).lower()
        if boot != str(device["boot_id"]).lower():
            raise EvidenceError(f"{context}.{phase} boot_id disagrees with normalized identity")
        font_scale_record = adb_command(
            f"{phase}.device.settings.font_scale",
            "shell",
            "settings",
            "get",
            "system",
            "font_scale",
        )
        font_scale_text = _raw_stdout(font_scale_record, context)
        try:
            observed_font_scale = float(font_scale_text)
        except ValueError as exc:
            raise EvidenceError(f"{context}.{phase} font_scale is invalid") from exc
        normalized_screen = _nested_object(
            normalized, "screen", f"performance[{profile}]"
        )
        if (
            not math.isfinite(observed_font_scale)
            or observed_font_scale != 1.0
            or normalized_screen.get("font_scale") != observed_font_scale
        ):
            raise EvidenceError(
                f"{context}.{phase} font_scale must equal normalized release value 1.0"
            )

        package_contract = (
            (
                "candidate",
                PACKAGE_ID,
                collector.get("candidate_apk_device_path"),
                normalized["candidate_apk_sha256"],
            ),
            (
                "instrumentation",
                TEST_PACKAGE_ID,
                collector.get("instrumentation_apk_device_path"),
                normalized["instrumentation_apk_sha256"],
            ),
        )
        for label, package_id, expected_path, expected_sha in package_contract:
            if not isinstance(expected_path, str) or not expected_path.startswith("/"):
                raise EvidenceError(f"performance[{profile}].collector {label} APK path is invalid")
            path_record = adb_command(
                f"{phase}.package.{label}.path", "shell", "pm", "path", package_id
            )
            paths = [
                line.removeprefix("package:").strip()
                for line in str(path_record["stdout"]).splitlines()
                if line.strip().startswith("package:")
            ]
            if paths != [expected_path]:
                raise EvidenceError(f"{context}.{phase} {label} package path disagrees")
            sha_record = adb_command(
                f"{phase}.package.{label}.sha256", "shell", "sha256sum", expected_path
            )
            sha_match = re.fullmatch(
                r"([0-9A-Fa-f]{64})\s+(.+)", _raw_stdout(sha_record, context)
            )
            if (
                sha_match is None
                or sha_match.group(1).lower() != expected_sha
                or sha_match.group(2).strip() != expected_path
            ):
                raise EvidenceError(f"{context}.{phase} {label} APK SHA/path disagrees")

        package_dump = _raw_stdout(
            adb_command(
                f"{phase}.package.version", "shell", "dumpsys", "package", PACKAGE_ID
            ),
            context,
        )
        names = re.findall(r"(?m)^\s*versionName=([^\s]+)\s*$", package_dump)
        codes = [
            int(value)
            for value in re.findall(r"(?m)^\s*versionCode=([0-9]+)(?:\s|$)", package_dump)
        ]
        if names != [version_name] or codes != [version_code]:
            raise EvidenceError(f"{context}.{phase} package version disagrees")
        _raw_qemu_match(
            _raw_record(records, f"{phase}.host.qemu_processes", context),
            device,
            serial,
            f"{context}.{phase}.host.qemu_processes",
        )

    validate_identity("initial")

    accel = _raw_record(records, "measure.emulator.accel-check", context)
    emulator = str(accel["argv"][0])
    if _portable_executable_name(emulator) not in {"emulator", "emulator.exe"}:
        raise EvidenceError(f"{context} uses an unexpected emulator executable")
    _raw_expect_argv(accel, [emulator, "-accel-check"], f"{context}.accel-check")
    acceleration_output = "\n".join(
        value.strip() for value in (str(accel["stdout"]), str(accel["stderr"])) if value.strip()
    )
    if (
        device.get("acceleration_check_exit_code") != accel["exit_code"]
        or device["acceleration_check"] != acceleration_output
    ):
        raise EvidenceError(f"{context} accel-check raw output disagrees with normalized evidence")

    screen = _nested_object(normalized, "screen", f"performance[{profile}]")
    wm_size = adb_command("measure.screen.wm_size", "shell", "wm", "size")
    if _raw_parse_wm_size(_raw_stdout(wm_size, context), context) != (
        screen["width_px"],
        screen["height_px"],
    ):
        raise EvidenceError(f"{context} wm size disagrees with normalized screen")
    wm_density = adb_command("measure.screen.wm_density", "shell", "wm", "density")
    if _raw_parse_wm_density(_raw_stdout(wm_density, context), context) != screen["density_dpi"]:
        raise EvidenceError(f"{context} wm density disagrees with normalized screen")
    config_record = adb_command("measure.screen.am_config", "shell", "am", "get-config")
    dp_pairs = {
        (int(width), int(height))
        for width, height in re.findall(
            r"(?:^|[-\s])w([0-9]+)dp-h([0-9]+)dp(?:[-\s]|$)",
            _raw_stdout(config_record, context),
        )
    }
    if dp_pairs != {(screen["width_dp"], screen["height_dp"])}:
        raise EvidenceError(f"{context} am get-config disagrees with normalized dp dimensions")

    adb_command("measure.launch.force_stop", "shell", "am", "force-stop", PACKAGE_ID)
    cold_record = adb_command(
        "measure.launch.cold", "shell", "am", "start", "-W", "-S", "-n", MAIN_ACTIVITY
    )
    cold_total, cold_wait = _raw_parse_start(
        _raw_stdout(cold_record, context), {"COLD"}, f"{context}.cold"
    )
    process_before = _raw_parse_pidof(
        _raw_stdout(
            adb_command(
                "measure.launch.pid_before_back",
                "shell",
                "pidof",
                PACKAGE_ID,
            ),
            context,
            allow_blank=True,
        ),
        f"{context}.pid_before_back",
    )
    adb_command("measure.launch.back", "shell", "input", "keyevent", "KEYCODE_BACK")
    process_after = _raw_parse_pidof(
        _raw_stdout(
            adb_command(
                "measure.launch.pid_after_back",
                "shell",
                "pidof",
                PACKAGE_ID,
            ),
            context,
            allow_blank=True,
        ),
        f"{context}.pid_after_back",
    )
    if process_after != process_before:
        raise EvidenceError(f"{context} Hermes process PID changed across KEYCODE_BACK")
    warm_record = adb_command(
        "measure.launch.warm", "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY
    )
    warm_output = _raw_stdout(warm_record, context)
    if has_warm_retry:
        if not _raw_retryable_unknown_start(warm_output):
            raise EvidenceError(
                f"{context}.warm retry is only permitted for one UNKNOWN/zero launch result"
            )
        retry_before = _raw_parse_pidof(
            _raw_stdout(
                adb_command(
                    "measure.launch.retry.pid_before_back",
                    "shell",
                    "pidof",
                    PACKAGE_ID,
                ),
                context,
            ),
            f"{context}.retry.pid_before_back",
        )
        if retry_before != process_after:
            raise EvidenceError(f"{context} Hermes PID changed before bounded warm retry")
        adb_command(
            "measure.launch.retry.back", "shell", "input", "keyevent", "KEYCODE_BACK"
        )
        retry_after = _raw_parse_pidof(
            _raw_stdout(
                adb_command(
                    "measure.launch.retry.pid_after_back",
                    "shell",
                    "pidof",
                    PACKAGE_ID,
                ),
                context,
            ),
            f"{context}.retry.pid_after_back",
        )
        if retry_after != retry_before:
            raise EvidenceError(f"{context} Hermes PID changed across bounded warm retry BACK")
        retry_record = adb_command(
            "measure.launch.retry.warm",
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            MAIN_ACTIVITY,
        )
        warm_total, _ = _raw_parse_start(
            _raw_stdout(retry_record, context),
            {"WARM", "HOT"},
            f"{context}.retry.warm",
        )
    else:
        warm_total, _ = _raw_parse_start(warm_output, {"WARM", "HOT"}, f"{context}.warm")
    launch = _nested_object(normalized, "launch", f"performance[{profile}]")
    if (cold_total, cold_wait, warm_total, process_after) != (
        launch["cold_total_ms"],
        launch["cold_wait_ms"],
        launch["warm_total_ms"],
        launch["warm_process_pid"],
    ):
        raise EvidenceError(
            f"{context} raw launch timings disagree with normalized evidence, or process PID differs"
        )

    def ui_hierarchy(phase: str) -> str:
        if phase not in {"initial", "drawer", "settings"}:
            raise EvidenceError(f"{context} contains unsupported UI phase {phase!r}")
        dump_path = f"{UI_DUMP_PATH_PREFIX}{phase}.xml"
        remove_record = adb_command(
            f"measure.ui.{phase}.remove",
            "shell",
            "rm",
            "-f",
            dump_path,
        )
        if str(remove_record["stdout"]).strip() or str(remove_record["stderr"]).strip():
            raise EvidenceError(f"{context}.ui.{phase} fresh-path removal produced output")
        dump_record = adb_command(
            f"measure.ui.{phase}.dump",
            "shell",
            "uiautomator",
            "dump",
            dump_path,
        )
        expected_success = f"UI hierchary dumped to: {dump_path}"
        if (
            str(dump_record["stdout"]).strip() != expected_success
            or str(dump_record["stderr"]).strip()
        ):
            raise EvidenceError(f"{context}.ui.{phase} dump lacks exact fresh success marker")
        return _raw_stdout(
            adb_command(f"measure.ui.{phase}.cat", "shell", "cat", dump_path),
            f"{context}.ui.{phase}",
        )

    def expect_tap(record_id: str, bounds: tuple[int, int, int, int]) -> None:
        left, top, right, bottom = bounds
        x, y = (left + right) // 2, (top + bottom) // 2
        if not (left < x < right and top < y < bottom):
            raise EvidenceError(f"{context}.{record_id} target has no safe interior tap")
        adb_command(record_id, "shell", "input", "tap", str(x), str(y))

    width_px, height_px = screen["width_px"], screen["height_px"]
    initial_xml = ui_hierarchy("initial")
    if profile == "phone-compact":
        _raw_reject_ui_resource(initial_xml, TABLET_SETTINGS_TAG, f"{context}.ui.initial")
        drawer_bounds = _raw_ui_target(
            initial_xml,
            PHONE_DRAWER_TAG,
            width_px,
            height_px,
            f"{context}.ui.initial",
            clickable=True,
        )
        expect_tap("measure.ui.phone.drawer.tap", drawer_bounds)
        drawer_xml = ui_hierarchy("drawer")
        settings_bounds = _raw_ui_target(
            drawer_xml,
            PHONE_SETTINGS_TAG,
            width_px,
            height_px,
            f"{context}.ui.drawer",
            clickable=True,
        )
        expect_tap("measure.ui.phone.settings.tap", settings_bounds)
        expected_route = "phone-drawer-settings"
    else:
        _raw_reject_ui_resource(initial_xml, PHONE_DRAWER_TAG, f"{context}.ui.initial")
        settings_bounds = _raw_ui_target(
            initial_xml,
            TABLET_SETTINGS_TAG,
            width_px,
            height_px,
            f"{context}.ui.initial",
            clickable=True,
        )
        expect_tap("measure.ui.tablet.settings.tap", settings_bounds)
        expected_route = "tablet-rail-settings"

    settings_xml = ui_hierarchy("settings")
    content_bounds = _raw_ui_target(
        settings_xml,
        SETTINGS_CONTENT_TAG,
        width_px,
        height_px,
        f"{context}.ui.settings",
        scrollable=True,
    )
    swipe_x, swipe_top_y, swipe_bottom_y = _raw_safe_swipe_coordinates(content_bounds)
    if collector.get("ui_navigation_route") != expected_route:
        raise EvidenceError(f"{context} normalized UI navigation route disagrees with profile")
    if collector.get("settings_scroll_bounds_px") != list(content_bounds):
        raise EvidenceError(f"{context} normalized settings bounds disagree with raw UI hierarchy")
    expected_swipe = [swipe_x, swipe_bottom_y, swipe_x, swipe_top_y]
    if collector.get("gfx_swipe_coordinates") != expected_swipe:
        raise EvidenceError(f"{context} normalized swipe coordinates disagree with raw UI hierarchy")

    gpu_record = adb_command(
        "measure.gpu.surfaceflinger", "shell", "dumpsys", "SurfaceFlinger"
    )
    if _raw_parse_gpu_renderer(_raw_stdout(gpu_record, context), context) != device["gpu_renderer"]:
        raise EvidenceError(f"{context} SurfaceFlinger renderer disagrees with normalized evidence")
    foreground_before = adb_command(
        "measure.activity.before_gfx",
        "shell",
        "dumpsys",
        "activity",
        "activities",
    )
    _raw_require_resumed_activity(
        _raw_stdout(foreground_before, context), f"{context}.measure.activity.before_gfx"
    )
    adb_command("measure.gfx.reset", "shell", "dumpsys", "gfxinfo", PACKAGE_ID, "reset")

    for index in range(1, swipe_index):
        record_id = f"measure.gfx.swipe.{index:04d}"
        record = _raw_record(records, record_id, context)
        argv = list(record["argv"])
        expected_prefix = [adb, "-s", serial, "shell", "input", "swipe", str(swipe_x)]
        if len(argv) != 11 or argv[:7] != expected_prefix or argv[8] != str(swipe_x):
            raise EvidenceError(f"{context}.{record_id} is not the expected display exercise command")
        coordinates = (argv[7], argv[9])
        expected_coordinates = (
            (str(swipe_bottom_y), str(swipe_top_y))
            if swipe_ordinal_in_round[record_id] % 2 == 0
            else (str(swipe_top_y), str(swipe_bottom_y))
        )
        if coordinates != expected_coordinates:
            raise EvidenceError(f"{context}.{record_id} uses unexpected swipe coordinates")
        if not argv[10].isdigit() or not 50 <= int(argv[10]) <= 2_000:
            raise EvidenceError(f"{context}.{record_id} has an invalid swipe duration")

    observed_frames: list[dict[str, int | float]] = []
    for record_id in framestats_ids:
        record = adb_command(
            record_id, "shell", "dumpsys", "gfxinfo", PACKAGE_ID, "framestats"
        )
        raw_gfxinfo = _raw_stdout(record, context)
        _raw_require_process_header(
            raw_gfxinfo,
            "Graphics info for pid",
            launch["warm_process_pid"],
            f"{context}.{record_id}",
        )
        observed_frames.append(_raw_parse_gfxinfo(raw_gfxinfo, f"{context}.{record_id}"))
    totals = [record["total_rendered"] for record in observed_frames]
    if totals != sorted(totals) or any(total >= 100 for total in totals[:-1]) or totals[-1] < 100:
        raise EvidenceError(f"{context} framestats rounds do not stop at the first >=100-frame dump")
    if collector.get("gfxinfo_exercise_rounds") != len(framestats_ids):
        raise EvidenceError(f"{context} gfxinfo round count disagrees with normalized collector data")
    normalized_frames = _nested_object(normalized, "frames", f"performance[{profile}]")
    if any(normalized_frames.get(field) != value for field, value in observed_frames[-1].items()):
        raise EvidenceError(f"{context} raw framestats disagree with normalized frame metrics")

    foreground_after = adb_command(
        "measure.activity.after_gfx",
        "shell",
        "dumpsys",
        "activity",
        "activities",
    )
    _raw_require_resumed_activity(
        _raw_stdout(foreground_after, context), f"{context}.measure.activity.after_gfx"
    )

    memory_record = adb_command(
        "measure.memory.meminfo", "shell", "dumpsys", "meminfo", PACKAGE_ID
    )
    raw_meminfo = _raw_stdout(memory_record, context)
    _raw_require_process_header(
        raw_meminfo,
        "MEMINFO in pid",
        launch["warm_process_pid"],
        f"{context}.measure.memory.meminfo",
    )
    memory_pairs = [
        (int(pss), int(rss))
        for pss, rss in re.findall(
            r"(?mi)^\s*TOTAL\s+PSS:\s*([0-9]+)\s+TOTAL\s+RSS:\s*([0-9]+)(?:\s|$)",
            raw_meminfo,
        )
    ]
    memory = _nested_object(normalized, "memory", f"performance[{profile}]")
    if memory_pairs != [(memory["total_pss_kb"], memory["total_rss_kb"])]:
        raise EvidenceError(f"{context} raw meminfo disagrees with normalized memory metrics")

    final_process_pid = _raw_parse_pidof(
        _raw_stdout(
            adb_command(
                "measure.process.pid_after_measurement",
                "shell",
                "pidof",
                PACKAGE_ID,
            ),
            context,
            allow_blank=True,
        ),
        f"{context}.pid_after_measurement",
    )
    if final_process_pid != launch["warm_process_pid"]:
        raise EvidenceError(f"{context} Hermes process PID changed during measurement")

    validate_identity("final")


def _validate_performance(
    path: Path,
    profile: str,
    source_digest: str,
    version_name: str,
    version_code: int,
    *,
    raw_path_override: Path | None = None,
) -> dict[str, Any]:
    payload = _json_object(path)
    context = f"performance[{profile}]"
    if payload.get("schema") != PERFORMANCE_SCHEMA or payload.get("profile") != profile:
        raise EvidenceError(f"{context} has the wrong schema/profile")
    if payload.get("release_source_digest") != source_digest:
        raise EvidenceError(f"{context}.release_source_digest does not match the tested source")
    expected_identity: dict[str, Any] = {
        "package_id": PACKAGE_ID,
        "version_name": version_name,
        "version_code": version_code,
        "build_variant": BUILD_VARIANT,
        "litertlm_coordinate": LITERTLM_COORDINATE,
    }
    for field, expected in expected_identity.items():
        if payload.get(field) != expected:
            raise EvidenceError(f"{context}.{field} must equal {expected!r}")
    run_id = payload.get("evidence_run_id")
    if not isinstance(run_id, str) or not RUN_ID_RE.fullmatch(run_id):
        raise EvidenceError(f"{context}.evidence_run_id is invalid")
    for field in ("candidate_apk_sha256", "instrumentation_apk_sha256"):
        value = payload.get(field)
        if not isinstance(value, str) or not HEX_64_RE.fullmatch(value):
            raise EvidenceError(f"{context}.{field} must be lowercase SHA-256")
    raw_reference = _nested_object(payload, "raw_evidence", context)
    if set(raw_reference) != {"path", "sha256"}:
        raise EvidenceError(f"{context}.raw_evidence must contain exactly path and sha256")
    expected_raw_relative = f"performance/{profile}.raw.json"
    if raw_reference.get("path") != expected_raw_relative:
        raise EvidenceError(
            f"{context}.raw_evidence.path must equal {expected_raw_relative!r}"
        )
    raw_sha256 = raw_reference.get("sha256")
    if not isinstance(raw_sha256, str) or not HEX_64_RE.fullmatch(raw_sha256):
        raise EvidenceError(f"{context}.raw_evidence.sha256 must be lowercase SHA-256")
    raw_path = (
        raw_path_override.resolve()
        if raw_path_override is not None
        else path.parent.parent / Path(expected_raw_relative)
    )
    if not raw_path.is_file():
        raise EvidenceError(f"{context} raw transcript is missing: {raw_path}")
    if _sha256_file(raw_path) != raw_sha256:
        raise EvidenceError(f"{context}.raw_evidence.sha256 does not match the raw transcript bytes")
    raw_payload = _json_object(raw_path)

    device = _nested_object(payload, "device", context)
    serial = _required_string(device, "serial", f"{context}.device")
    if not serial.startswith("emulator-"):
        raise EvidenceError(f"{context}.device.serial must identify an Android emulator")
    _required_string(device, "avd_name", f"{context}.device")
    boot_id = _required_string(device, "boot_id", f"{context}.device").lower()
    if not BOOT_ID_RE.fullmatch(boot_id):
        raise EvidenceError(f"{context}.device.boot_id must be a kernel boot UUID")
    _required_string(device, "model", f"{context}.device")
    _required_string(device, "build_fingerprint", f"{context}.device")
    android_sdk = _integer(device, "android_sdk", f"{context}.device", positive=True)
    if android_sdk < 24:
        raise EvidenceError(f"{context}.device.android_sdk is below the supported API 24 floor")
    supported_abis = device.get("supported_abis")
    if not isinstance(supported_abis, list) or not supported_abis or not all(
        isinstance(abi, str) and abi for abi in supported_abis
    ):
        raise EvidenceError(f"{context}.device.supported_abis must be a nonempty string list")
    if "x86_64" not in supported_abis:
        raise EvidenceError(f"{context}.device.supported_abis must prove the x86_64 AVD lane")
    emulator_pid = _integer(device, "emulator_pid", f"{context}.device", positive=True)
    if emulator_pid <= 0:
        raise EvidenceError(f"{context}.device.emulator_pid must be positive")
    emulator_process_name = _required_string(
        device, "emulator_process_name", f"{context}.device"
    )
    if not emulator_process_name.casefold().startswith("qemu-system-"):
        raise EvidenceError(f"{context}.device.emulator_process_name must identify qemu-system")
    if not _required_bool(device, "hardware_acceleration", f"{context}.device"):
        raise EvidenceError(f"{context}.device must record hardware_acceleration=true")
    acceleration_check = _required_string(device, "acceleration_check", f"{context}.device")
    if _integer(device, "acceleration_check_exit_code", f"{context}.device") != 0:
        raise EvidenceError(f"{context}.device.acceleration_check_exit_code must be zero")
    normalized_acceleration = acceleration_check.casefold()
    if "usable" not in normalized_acceleration or re.search(
        r"\b(?:not|isn't|isnt|unusable|failed|unavailable)\b", normalized_acceleration
    ):
        raise EvidenceError(f"{context}.device.acceleration_check does not report a usable accelerator")
    renderer = _required_string(device, "gpu_renderer", f"{context}.device")
    if any(marker in renderer.casefold() for marker in SOFTWARE_RENDERER_MARKERS):
        raise EvidenceError(f"{context}.device.gpu_renderer reports a software renderer: {renderer}")
    emulator_command = _required_string(device, "emulator_command", f"{context}.device")
    try:
        import shlex

        command_tokens = shlex.split(emulator_command, posix=False)
    except ValueError as exc:
        raise EvidenceError(f"{context}.device.emulator_command cannot be tokenized: {exc}") from exc
    flag_values: dict[str, list[str]] = {}
    for index, token in enumerate(command_tokens):
        normalized_token = token.casefold()
        if normalized_token in {"-avd", "-gpu", "-accel"}:
            if index + 1 >= len(command_tokens):
                raise EvidenceError(f"{context}.device.emulator_command has an incomplete {token} flag")
            flag_values.setdefault(normalized_token, []).append(command_tokens[index + 1].strip('"\'').casefold())
    if flag_values.get("-gpu") != ["host"] or flag_values.get("-accel") != ["on"]:
        raise EvidenceError(f"{context}.device.emulator_command must use -gpu host and -accel on")
    if any(token.casefold() == "-no-window" for token in command_tokens):
        raise EvidenceError(f"{context}.device.emulator_command is not a headed emulator launch")
    avd_name = _required_string(device, "avd_name", f"{context}.device")
    if flag_values.get("-avd") != [avd_name.casefold()]:
        raise EvidenceError(f"{context}.device.emulator_command does not identify its avd_name")
    command_sha = _required_string(device, "emulator_command_sha256", f"{context}.device").lower()
    if not HEX_64_RE.fullmatch(command_sha):
        raise EvidenceError(f"{context}.device.emulator_command_sha256 must be lowercase SHA-256")
    calculated_command_sha = hashlib.sha256(emulator_command.encode("utf-8")).hexdigest()
    if command_sha != calculated_command_sha:
        raise EvidenceError(f"{context}.device.emulator_command_sha256 does not match emulator_command")

    screen = _nested_object(payload, "screen", context)
    width_px = _integer(screen, "width_px", f"{context}.screen", positive=True)
    height_px = _integer(screen, "height_px", f"{context}.screen", positive=True)
    width_dp = _integer(screen, "width_dp", f"{context}.screen", positive=True)
    height_dp = _integer(screen, "height_dp", f"{context}.screen", positive=True)
    density_dpi = _integer(screen, "density_dpi", f"{context}.screen", positive=True)
    font_scale = _number(screen, "font_scale", f"{context}.screen", positive=True)
    if font_scale != 1.0:
        raise EvidenceError(f"{context}.screen.font_scale must equal 1.0")
    _validate_profile_dimensions(profile, width_dp, height_dp, f"{context}.screen")
    physical_width_dp = width_px * 160 / density_dpi
    physical_height_dp = height_px * 160 / density_dpi
    # Configuration screen dp can exclude status/navigation bars while a
    # UiAutomation screenshot covers the full display. Preserve that real
    # relationship without accepting unrelated dimensions.
    if (
        width_dp > physical_width_dp + 3
        or height_dp > physical_height_dp + 3
        or physical_width_dp - width_dp > 160
        or physical_height_dp - height_dp > 160
    ):
        raise EvidenceError(f"{context}.screen pixel/dp/density values disagree")

    launch = _nested_object(payload, "launch", context)
    for field in ("cold_total_ms", "cold_wait_ms", "warm_total_ms"):
        _number(launch, field, f"{context}.launch", positive=True)
    _integer(launch, "warm_process_pid", f"{context}.launch", positive=True)
    if launch["cold_total_ms"] > 15_000 or launch["warm_total_ms"] > 5_000:
        raise EvidenceError(f"{context}.launch exceeds the release performance budget")
    if launch["cold_wait_ms"] > launch["cold_total_ms"] + 1_000:
        raise EvidenceError(f"{context}.launch cold wait/total timings disagree")

    frames = _nested_object(payload, "frames", context)
    total_frames = _integer(frames, "total_rendered", f"{context}.frames", positive=True)
    if total_frames < 100:
        raise EvidenceError(f"{context}.frames.total_rendered must be at least 100")
    janky_frames = _integer(frames, "janky", f"{context}.frames")
    if not 0 <= janky_frames <= total_frames:
        raise EvidenceError(f"{context}.frames.janky is outside the rendered-frame range")
    janky_percent = _number(frames, "janky_percent", f"{context}.frames")
    expected_percent = janky_frames * 100 / total_frames
    if not 0 <= janky_percent <= 100 or abs(janky_percent - expected_percent) > 0.25:
        raise EvidenceError(f"{context}.frames.janky_percent disagrees with frame counts")
    if janky_percent > 10.0:
        raise EvidenceError(f"{context}.frames.janky_percent exceeds the 10% release budget")
    percentiles = [
        _number(frames, field, f"{context}.frames", positive=True)
        for field in ("p50_ms", "p90_ms", "p95_ms", "p99_ms")
    ]
    if percentiles != sorted(percentiles):
        raise EvidenceError(f"{context}.frames percentile timings are not monotonic")
    if percentiles[2] > 250 or percentiles[3] > 1_000:
        raise EvidenceError(f"{context}.frames percentile timings exceed the release budget")

    memory = _nested_object(payload, "memory", context)
    total_pss = _integer(memory, "total_pss_kb", f"{context}.memory", positive=True)
    total_rss = _integer(memory, "total_rss_kb", f"{context}.memory", positive=True)
    if total_pss > total_rss:
        raise EvidenceError(f"{context}.memory total_pss_kb cannot exceed total_rss_kb")
    memory_budget = MEMORY_BUDGET_KB[profile]
    if total_pss > memory_budget["total_pss_kb"] or total_rss > memory_budget["total_rss_kb"]:
        raise EvidenceError(
            f"{context}.memory exceeds the {profile} release ceiling: "
            f"PSS {total_pss}/{memory_budget['total_pss_kb']} KB, "
            f"RSS {total_rss}/{memory_budget['total_rss_kb']} KB"
        )
    collector = _nested_object(payload, "collector", context)
    _required_string(collector, "source_digest_algorithm", f"{context}.collector")
    _integer(collector, "source_file_count", f"{context}.collector", positive=True)
    _required_string(collector, "git_object_format", f"{context}.collector")
    for field in ("candidate_apk_device_path", "instrumentation_apk_device_path"):
        device_path = _required_string(collector, field, f"{context}.collector")
        if not device_path.startswith("/"):
            raise EvidenceError(f"{context}.collector.{field} must be an absolute device path")
    _integer(collector, "gfxinfo_exercise_rounds", f"{context}.collector", positive=True)
    _validate_raw_performance(
        raw_payload,
        payload,
        profile,
        source_digest,
        version_name,
        version_code,
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


def expected_evidence_paths(artifacts: Sequence[ArtifactSpec]) -> set[PurePosixPath]:
    paths = {
        PurePosixPath("performance") / f"{profile}.json"
        for profile in PROFILES
    }
    paths.update(
        PurePosixPath("performance") / f"{profile}.raw.json"
        for profile in PROFILES
    )
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
    expected_paths = expected_evidence_paths(artifacts)
    missing = expected_paths - actual_without_manifest
    unexpected = actual_without_manifest - expected_paths
    if missing or unexpected:
        raise EvidenceError(
            "Release evidence layout mismatch; "
            f"missing={[path.as_posix() for path in sorted(missing)]}, "
            f"unexpected={[path.as_posix() for path in sorted(unexpected)]}"
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
    candidate_digests = {record["candidate_apk_sha256"] for record in performance_records}
    instrumentation_digests = {
        record["instrumentation_apk_sha256"] for record in performance_records
    }
    if len(candidate_digests) != 1 or len(instrumentation_digests) != 1:
        raise EvidenceError("Performance profiles were not measured from the same app/test APK pair")
    candidate_apk_sha256 = candidate_digests.pop()
    instrumentation_apk_sha256 = instrumentation_digests.pop()
    evidence_run_ids = {record["evidence_run_id"] for record in performance_records}
    if len(evidence_run_ids) != 1:
        raise EvidenceError("Performance profiles do not share one evidence_run_id")
    evidence_run_id = evidence_run_ids.pop()
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
                "candidate_apk_sha256": candidate_apk_sha256,
                "instrumentation_apk_sha256": instrumentation_apk_sha256,
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
            candidate_apk_sha256,
            instrumentation_apk_sha256,
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
        candidate_apk_sha256=candidate_apk_sha256,
        instrumentation_apk_sha256=instrumentation_apk_sha256,
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
            "minimum_rendered_frames_per_profile": 100,
            "requires_hardware_accelerated_avd": True,
            "requires_raw_performance_transcript": True,
            "requires_runtime_health_and_nonempty_completion": True,
        },
        "registered_model_matrix": [asdict(artifact) for artifact in artifacts],
        "tested_binaries": {
            "candidate_apk_sha256": evidence.candidate_apk_sha256,
            "instrumentation_apk_sha256": evidence.instrumentation_apk_sha256,
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
