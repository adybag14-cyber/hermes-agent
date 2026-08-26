#!/usr/bin/env python3
"""Carry a clean committed Android source digest through F-Droid prebuild edits.

F-Droid intentionally applies two declared ``sed`` transformations to
``android/app/build.gradle.kts`` before invoking Gradle. The ``prepare`` phase
runs first, after validating only fdroidserver's deterministic signing scrub
and SDK locator files, and writes the committed source identity outside the
source tree. The ``verify`` phase runs from Gradle after the metadata edits and
buildserver cleanup, and accepts only that exact closed transformation set.

The ``verify-transformed`` phase supports the central F-Droid bot's historical
two-``sed`` recipe without trusting an unbound build. It requires the exact
post-scanner/post-prebuild checkout state and derives the identity directly
from committed ``HEAD`` blobs, so no prebuild handoff file is needed.

The ``render-autoupdate-preview`` phase is a separate, local-only transaction.
It preserves the autoupdater's resolved release commit and all unrelated live
metadata while replacing only the target build's ``sudo``, ``ndk``, ``gradle``,
``gradleprops``, and ``prebuild`` fields with the exact repository template
contract. The matching
``verify-autoupdate-preview`` phase fails closed before a pinned buildserver run
if that source-binding contract is missing, stale, or ambiguous.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import stat
import subprocess
import sys
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Mapping


sys.dont_write_bytecode = True


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

BINDING_SCHEMA = "hermes-android-fdroid-source-binding-v1"
BINDING_FILE_NAME = "hermes-android-fdroid-source-binding.properties"
AUTUPDATE_VERSION_NAME = "0.13.153"
AUTUPDATE_VERSION_CODE = "145390"
EXPECTED_REMOTE_REPOSITORY = "https://github.com/adybag14-cyber/hermes-agent.git"
GRADLE_PATH = PurePosixPath("android/app/build.gradle.kts")
SOURCE_DIGEST_EXCLUDED_PREFIX = PurePosixPath("android/release-evidence")
FDROID_LOCAL_PROPERTIES = (
    PurePosixPath("local.properties"),
    PurePosixPath("android/local.properties"),
    PurePosixPath("android/app/local.properties"),
)
FDROID_LOCAL_PROPERTIES_PAYLOAD = (
    b"sdk.dir=/opt/android-sdk\n"
    b"sdk-location=/opt/android-sdk\n"
    b"ndk.dir=/opt/android-sdk/ndk/29.0.14206865\n"
    b"ndk-location=/opt/android-sdk/ndk/29.0.14206865\n"
)
FDROID_CHAQUOPY_PROGUARD_PATH = PurePosixPath(
    "android/app/build/python/proguard-rules.pro"
)
FDROID_CHAQUOPY_PROGUARD_PAYLOAD = (
    b"# Ensure all classes and methods used by Cython code are left alone by minifyEnabled.\n"
    b"-keep class com.chaquo.python.** { * ; }\n"
    b"\n"
    b"# See get_sam in class.pxi.\n"
    b"-keep class kotlin.jvm.functions.** { * ; }\n"
    b"-keep class kotlin.jvm.internal.FunctionBase { * ; }\n"
    b"-keep class kotlin.reflect.KAnnotatedElement { *; }\n"
    b"\n"
    b"# TODO: https://github.com/chaquo/chaquopy/issues/842\n"
    b"-dontwarn org.jetbrains.annotations.NotNull\n"
)
RELEASE_TAG_EXPRESSION = 'System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()'
BUILD_PYTHON_EXPRESSION = (
    'return if (osName.contains("windows")) "python" else "python3"'
)
BUILD_PYTHON_REPLACEMENT = (
    'return if (osName.contains("windows")) "python" else "python3.13"'
)
VERSION_RE = re.compile(r"0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)(?:\.[0-9]+)?)?")
COMMIT_RE = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
GRADLE_COMMENT_RE = re.compile(r"[ ]*//")
GRADLE_SIGNING_CONFIGS_RE = re.compile(r"^[\t ]*signingConfigs[ \t]*{[ \t]*$")
GRADLE_SIGNING_LINE_RES = (
    re.compile(r"^[\t ]*signingConfig\s*[= ]\s*[^ ]*$"),
    re.compile(r".*android\.signingConfigs\.[^{]*$"),
    re.compile(r".*release\.signingConfig *= *"),
)
FDROID_SCANNER_REMOVED_GRADLE_NAMES = frozenset(
    {"gradle-wrapper.jar", "gradlew", "gradlew.bat", "gradle-daemon-jvm.properties"}
)
FDROID_ALLOWED_CONFIGURATION_PREFIXES = (
    PurePosixPath("android/.gradle"),
    PurePosixPath("android/.kotlin"),
)
EXPECTED_BINDING_KEYS = frozenset(
    {
        "schema",
        "commit",
        "versionName",
        "sourceAlgorithm",
        "sourceDigest",
        "sourceFiles",
        "gitObjectFormat",
        "excludedPrefix",
    }
)
EXPECTED_METADATA_SUDO = (
    "    sudo:\n"
    "      - apt-get update\n"
    "      - apt-get install -y python3-pip\n"
    '      - sdkmanager "cmake;3.31.6"\n'
)
EXPECTED_METADATA_NDK = "    ndk: 29.0.14206865\n"
EXPECTED_METADATA_GRADLE = (
    "    gradle:\n"
    "      - yes\n"
)
EXPECTED_METADATA_GRADLEPROPS = (
    "    gradleprops:\n"
    "      - hermesFdroidSourceBinding=true\n"
)
EXPECTED_METADATA_PREBUILD = (
    "    prebuild:\n"
    "      - python3.13 ../../scripts/android_fdroid_source_binding.py prepare --repo-root\n"
    "        ../.. --binding-file \"${GRADLE_USER_HOME:-$HOME/.gradle}/"
    "hermes-android-fdroid-source-binding.properties\"\n"
    "        --version \"$$VERSION$$\"\n"
    "      - sed -i -e 's/System.getenv(\"HERMES_RELEASE_TAG\").orEmpty().trim()/"
    "\"v$$VERSION$$\"/'\n"
    "        build.gradle.kts\n"
    "      - sed -i -e 's/return if (osName.contains(\"windows\")) \"python\" else "
    "\"python3\"/return\n"
    "        if (osName.contains(\"windows\")) \"python\" else \"python3.13\"/' "
    "build.gradle.kts\n"
)
METADATA_OVERLAY_FIELDS = ("sudo", "ndk", "gradle", "gradleprops", "prebuild")
YAML_BUILD_START_RE = re.compile(r"^  - (?P<key>[A-Za-z][A-Za-z0-9]*):(?:[ \t]*(?P<value>.*))?$")
YAML_BUILD_FIELD_RE = re.compile(
    r"^    (?P<key>[A-Za-z][A-Za-z0-9]*):(?:[ \t]*(?P<value>.*))?$"
)
YAML_TOP_LEVEL_FIELD_RE = re.compile(
    r"^(?P<key>[A-Za-z][A-Za-z0-9]*):(?:[ \t]*(?P<value>.*))?$"
)


class FdroidSourceBindingError(RuntimeError):
    """Raised when the F-Droid source-binding handoff is not authoritative."""


def _sanitized_git_subprocess_environment() -> dict[str, str]:
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.upper().startswith("GIT_")
    }
    environment.update(
        {
            "GIT_TERMINAL_PROMPT": "0",
            "LC_ALL": "C",
            "LANG": "C",
        }
    )
    return environment


@contextmanager
def _sanitized_git_process_environment():
    controlled_names = {
        key for key in os.environ if key.upper().startswith("GIT_")
    } | {"LC_ALL", "LANG"}
    previous = {key: os.environ[key] for key in controlled_names if key in os.environ}
    try:
        for key in controlled_names:
            os.environ.pop(key, None)
        os.environ.update(
            {
                "GIT_TERMINAL_PROMPT": "0",
                "LC_ALL": "C",
                "LANG": "C",
            }
        )
        yield
    finally:
        for key in list(os.environ):
            if key.upper().startswith("GIT_") or key in {"LC_ALL", "LANG"}:
                os.environ.pop(key, None)
        os.environ.update(previous)


@dataclass(frozen=True)
class VerifiedBinding:
    commit: str
    version_name: str
    source_digest: str
    source_files: int


@dataclass(frozen=True)
class VerifiedMetadataPreview:
    metadata_file: Path
    template_file: Path
    version_name: str
    version_code: str
    commit: str


@dataclass(frozen=True)
class _YamlBuildBlock:
    start: int
    end: int
    fields: Mapping[str, tuple[int, int, str]]


def git_source_tree_identity(repo_root: Path):
    """Load the release-evidence implementation only for source binding.

    Metadata preview rendering intentionally remains usable from the pinned
    buildserver when this single helper is mounted without the rest of the
    Hermes ``scripts`` directory.
    """

    try:
        from android_release_evidence import (
            EvidenceError,
            git_source_tree_identity as implementation,
        )
    except ImportError as exc:
        raise FdroidSourceBindingError(
            f"unable to load Android source-identity implementation: {exc}"
        ) from exc
    try:
        with _sanitized_git_process_environment():
            return implementation(repo_root)
    except (EvidenceError, OSError, ValueError) as exc:
        raise FdroidSourceBindingError(
            f"unable to resolve committed Android source identity: {exc}"
        ) from exc


def _git_blob_content_identities(repo_root: Path, object_ids: set[str]) -> dict[str, str]:
    try:
        from android_release_evidence import _git_blob_content_identities as implementation
    except ImportError as exc:
        raise FdroidSourceBindingError(
            f"unable to load committed blob-identity implementation: {exc}"
        ) from exc
    try:
        with _sanitized_git_process_environment():
            return implementation(repo_root, object_ids)
    except (OSError, ValueError) as exc:
        raise FdroidSourceBindingError(
            f"unable to hash committed source blobs: {exc}"
        ) from exc


def _run_git(repo_root: Path, *args: str) -> bytes:
    environment = _sanitized_git_subprocess_environment()
    git = shutil.which("git", path=environment.get("PATH"))
    if not git:
        raise FdroidSourceBindingError("git is required for F-Droid source binding")
    try:
        result = subprocess.run(
            [str(Path(git).resolve()), *args],
            cwd=repo_root,
            check=False,
            capture_output=True,
            env=environment,
        )
    except FileNotFoundError as exc:
        raise FdroidSourceBindingError("git is required for F-Droid source binding") from exc
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        raise FdroidSourceBindingError(
            f"git {' '.join(args)} failed with exit {result.returncode}: {stderr}"
        )
    return result.stdout


def _normalize_repo_root(repo_root: Path) -> Path:
    resolved = repo_root.resolve()
    if not (resolved / ".git").exists():
        raise FdroidSourceBindingError(f"repository is not a Git checkout: {resolved}")
    reported = Path(
        _run_git(resolved, "rev-parse", "--show-toplevel").decode("utf-8").strip()
    ).resolve()
    if reported != resolved:
        raise FdroidSourceBindingError(
            f"Git worktree authority {reported} does not match repository root {resolved}"
        )
    return resolved


def _normalize_version(version: str) -> str:
    normalized = version.strip().removeprefix("v")
    if VERSION_RE.fullmatch(normalized) is None:
        raise FdroidSourceBindingError(
            f"F-Droid source binding requires an exact v0 semantic version, got {version!r}"
        )
    return normalized


def _assert_binding_file_outside_repo(repo_root: Path, binding_file: Path) -> Path:
    resolved = binding_file.resolve()
    try:
        resolved.relative_to(repo_root)
    except ValueError:
        return resolved
    raise FdroidSourceBindingError(
        f"F-Droid source binding file must remain outside the source checkout: {resolved}"
    )


def _assert_release_identity(repo_root: Path, version_name: str) -> None:
    try:
        from check_android_release_identity import validate_release_identity

        with _sanitized_git_process_environment():
            identity = validate_release_identity(repo_root, f"v{version_name}")
    except (ImportError, OSError, ValueError) as exc:
        raise FdroidSourceBindingError(
            f"F-Droid version {version_name} does not match the committed release identity: {exc}"
        ) from exc
    if identity.version_name != version_name:
        raise FdroidSourceBindingError(
            f"committed release identity returned {identity.version_name}, expected {version_name}"
        )


def _head_commit(repo_root: Path) -> str:
    commit = _run_git(repo_root, "rev-parse", "HEAD").decode("ascii").strip().lower()
    if COMMIT_RE.fullmatch(commit) is None:
        raise FdroidSourceBindingError(f"Git returned an invalid HEAD commit: {commit!r}")
    return commit


def _decode_git_paths(payload: bytes) -> set[PurePosixPath]:
    paths: set[PurePosixPath] = set()
    for raw_path in payload.split(b"\0"):
        if not raw_path:
            continue
        try:
            decoded = raw_path.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise FdroidSourceBindingError("Git reported a non-UTF-8 changed path") from exc
        paths.add(PurePosixPath(decoded))
    return paths


def _tracked_changed_paths(repo_root: Path) -> set[PurePosixPath]:
    return _decode_git_paths(
        _run_git(repo_root, "diff", "--no-ext-diff", "--name-only", "-z", "HEAD", "--")
    )


def _untracked_paths(repo_root: Path) -> set[PurePosixPath]:
    return _decode_git_paths(
        _run_git(repo_root, "ls-files", "--others", "--exclude-standard", "-z")
    )


def _all_untracked_paths(repo_root: Path) -> set[PurePosixPath]:
    """Return untracked paths without honoring ignore or exclude rules."""

    return _decode_git_paths(_run_git(repo_root, "ls-files", "--others", "-z"))


def _assert_default_index_flags(repo_root: Path) -> None:
    flagged: list[str] = []
    for record in _run_git(repo_root, "ls-files", "-v", "-z").split(b"\0"):
        if not record:
            continue
        if len(record) < 3 or record[1:2] != b" ":
            raise FdroidSourceBindingError("Git returned malformed index-flag output")
        marker = chr(record[0])
        try:
            path = record[2:].decode("utf-8")
        except UnicodeDecodeError as exc:
            raise FdroidSourceBindingError("Git reported a non-UTF-8 indexed path") from exc
        if marker != "H":
            flagged.append(f"{marker}:{path}")
    if flagged:
        raise FdroidSourceBindingError(
            "F-Droid source binding rejects non-default Git index flags: "
            + ", ".join(sorted(flagged)[:10])
        )


def _assert_no_hidden_untracked_inputs(
    repo_root: Path,
    *,
    allowed_generated: frozenset[PurePosixPath] = frozenset(),
) -> None:
    allowed_exact = set(FDROID_LOCAL_PROPERTIES) | set(allowed_generated)
    unexpected = []
    for path in _all_untracked_paths(repo_root):
        if path in allowed_exact:
            continue
        if any(
            prefix == path or prefix in path.parents
            for prefix in FDROID_ALLOWED_CONFIGURATION_PREFIXES
        ):
            continue
        unexpected.append(path.as_posix())
    if unexpected:
        raise FdroidSourceBindingError(
            "F-Droid checkout contains untracked or ignored build input(s): "
            + ", ".join(sorted(unexpected)[:10])
        )


def _read_exact_regular_file_without_following(path: Path, label: str) -> bytes:
    try:
        path_stat = path.lstat()
    except OSError as exc:
        raise FdroidSourceBindingError(f"unable to inspect {label}: {path}") from exc
    if path.is_symlink() or not stat.S_ISREG(path_stat.st_mode):
        raise FdroidSourceBindingError(
            f"{label} must be an ordinary non-symlink file"
        )
    flags = (
        os.O_RDONLY
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    if nofollow:
        flags |= nofollow
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise FdroidSourceBindingError(
            f"unable to open {label} without following links"
        ) from exc
    try:
        descriptor_stat = os.fstat(descriptor)
        if not stat.S_ISREG(descriptor_stat.st_mode):
            raise FdroidSourceBindingError(
                f"{label} must be an ordinary non-symlink file"
            )
        if (
            descriptor_stat.st_dev != path_stat.st_dev
            or descriptor_stat.st_ino != path_stat.st_ino
        ):
            raise FdroidSourceBindingError(
                f"{label} changed while it was being opened"
            )
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            return handle.read()
    except OSError as exc:
        raise FdroidSourceBindingError(f"unable to read {label}") from exc
    finally:
        os.close(descriptor)


def _validate_fdroid_chaquopy_proguard(repo_root: Path) -> None:
    candidate = repo_root / FDROID_CHAQUOPY_PROGUARD_PATH
    payload = _read_exact_regular_file_without_following(
        candidate,
        "F-Droid Chaquopy ProGuard output",
    )
    if payload != FDROID_CHAQUOPY_PROGUARD_PAYLOAD:
        raise FdroidSourceBindingError(
            "F-Droid Chaquopy ProGuard output does not match the exact generated payload; "
            f"expectedBytes={len(FDROID_CHAQUOPY_PROGUARD_PAYLOAD)}, "
            f"expectedSha256={hashlib.sha256(FDROID_CHAQUOPY_PROGUARD_PAYLOAD).hexdigest()}, "
            f"actualBytes={len(payload)}, actualSha256={hashlib.sha256(payload).hexdigest()}"
        )


def _head_tracked_entries(
    repo_root: Path,
) -> dict[PurePosixPath, tuple[str, str, str]]:
    entries: dict[PurePosixPath, tuple[str, str, str]] = {}
    for record in _run_git(repo_root, "ls-tree", "-r", "-z", "HEAD").split(b"\0"):
        if not record:
            continue
        try:
            metadata, raw_path = record.split(b"\t", 1)
            mode, entry_type, object_id = metadata.decode("ascii").split(" ", 2)
            path = PurePosixPath(raw_path.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as exc:
            raise FdroidSourceBindingError("Git returned malformed HEAD tree data") from exc
        if path in entries:
            raise FdroidSourceBindingError(f"Git returned duplicate HEAD path: {path}")
        entries[path] = (mode, entry_type, object_id)
    return entries


def _head_tracked_paths(repo_root: Path) -> set[PurePosixPath]:
    return set(_head_tracked_entries(repo_root))


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _assert_unchanged_tracked_bytes(
    repo_root: Path,
    expected_changes: Mapping[PurePosixPath, bytes | None],
    phase: str,
) -> None:
    comparable = {
        path: entry
        for path, entry in _head_tracked_entries(repo_root).items()
        if entry[1] == "blob"
        and path not in expected_changes
        and not (
            path == SOURCE_DIGEST_EXCLUDED_PREFIX
            or SOURCE_DIGEST_EXCLUDED_PREFIX in path.parents
        )
    }
    identities = _git_blob_content_identities(
        repo_root,
        {entry[2] for entry in comparable.values()},
    )
    for path, (mode, _entry_type, object_id) in comparable.items():
        candidate = repo_root.joinpath(*path.parts)
        expected_identity = identities.get(object_id, "")
        if not expected_identity.startswith("sha256:"):
            raise FdroidSourceBindingError(
                f"F-Droid {phase} has no committed blob identity for {path.as_posix()}"
            )
        expected_sha256 = expected_identity.removeprefix("sha256:")
        if mode == "120000":
            if not candidate.is_symlink():
                raise FdroidSourceBindingError(
                    f"F-Droid {phase} tracked symlink changed: {path.as_posix()}"
                )
            actual_sha256 = hashlib.sha256(
                os.readlink(candidate).encode("utf-8")
            ).hexdigest()
        else:
            try:
                candidate_stat = candidate.lstat()
            except OSError as exc:
                raise FdroidSourceBindingError(
                    f"F-Droid {phase} tracked file is missing: {path.as_posix()}"
                ) from exc
            if candidate.is_symlink() or not stat.S_ISREG(candidate_stat.st_mode):
                raise FdroidSourceBindingError(
                    f"F-Droid {phase} tracked file type changed: {path.as_posix()}"
                )
            expected_executable = mode == "100755"
            actual_executable = bool(candidate_stat.st_mode & stat.S_IXUSR)
            if actual_executable != expected_executable:
                raise FdroidSourceBindingError(
                    f"F-Droid {phase} tracked executable mode changed: {path.as_posix()}"
                )
            actual_sha256 = _sha256_file(candidate)
        if actual_sha256 != expected_sha256:
            raise FdroidSourceBindingError(
                f"F-Droid {phase} tracked bytes differ from HEAD: {path.as_posix()}"
            )


def _replace_once(source: str, needle: str, replacement: str, label: str) -> str:
    occurrences = source.count(needle)
    if occurrences != 1:
        raise FdroidSourceBindingError(
            f"committed Gradle source must contain exactly one {label}, found {occurrences}"
        )
    return source.replace(needle, replacement, 1)


def expected_fdroid_signing_scrub_source(committed_source: bytes) -> bytes:
    """Reproduce fdroidserver's signing-key removal for one Gradle source."""

    try:
        lines = committed_source.decode("utf-8").splitlines(keepends=True)
    except UnicodeDecodeError as exc:
        raise FdroidSourceBindingError("committed Gradle source is not UTF-8") from exc
    output: list[str] = []
    opened = 0
    index = 0
    while index < len(lines):
        line = lines[index]
        index += 1
        while line.endswith("\\\n"):
            if index >= len(lines):
                raise FdroidSourceBindingError("Gradle source ends in an incomplete continuation")
            line = line.rstrip("\\\n") + lines[index]
            index += 1
        if GRADLE_COMMENT_RE.match(line):
            output.append(line)
            continue
        if opened > 0:
            opened += line.count("{")
            opened -= line.count("}")
            continue
        if GRADLE_SIGNING_CONFIGS_RE.match(line):
            opened += 1
            continue
        if any(pattern.match(line) for pattern in GRADLE_SIGNING_LINE_RES):
            continue
        output.append(line)
    if opened != 0:
        raise FdroidSourceBindingError("fdroidserver signing scrub left unbalanced Gradle braces")
    return "".join(output).encode("utf-8")


def expected_fdroid_gradle_source(committed_source: bytes, version_name: str) -> bytes:
    """Apply fdroidserver's scrub plus the two declared metadata transformations."""

    normalized_version = _normalize_version(version_name)
    try:
        source = expected_fdroid_signing_scrub_source(committed_source).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise FdroidSourceBindingError("committed Android Gradle source is not UTF-8") from exc
    source = _replace_once(
        source,
        RELEASE_TAG_EXPRESSION,
        f'"v{normalized_version}"',
        "HERMES_RELEASE_TAG expression",
    )
    source = _replace_once(
        source,
        BUILD_PYTHON_EXPRESSION,
        BUILD_PYTHON_REPLACEMENT,
        "non-Windows build-Python expression",
    )
    return source.encode("utf-8")


def _head_gradle_source(repo_root: Path) -> bytes:
    return _run_git(repo_root, "show", f"HEAD:{GRADLE_PATH.as_posix()}")


def _head_source(repo_root: Path, path: PurePosixPath) -> bytes:
    return _run_git(repo_root, "show", f"HEAD:{path.as_posix()}")


def _fdroid_signing_scrubbed_sources(repo_root: Path) -> dict[PurePosixPath, bytes]:
    scrubbed: dict[PurePosixPath, bytes] = {}
    for path in _head_tracked_paths(repo_root):
        if path.name not in {"build.gradle", "build.gradle.kts"}:
            continue
        committed = _head_source(repo_root, path)
        expected = expected_fdroid_signing_scrub_source(committed)
        if expected != committed:
            scrubbed[path] = expected
    return scrubbed


def _fdroid_scanner_deleted_gradle_files(repo_root: Path) -> set[PurePosixPath]:
    """Model the pinned fdroidserver scanner's unconditional Gradle cleanup.

    fdroidserver removes these four exact basenames before Gradle configuration,
    independent of scanignore/scandelete. Restricting the closed transformation
    to the matching tracked paths preserves fail-closed detection for every
    other deletion or edit while accepting the scanner's deterministic cleanup.
    """

    return {
        path
        for path in _head_tracked_paths(repo_root)
        if path.name in FDROID_SCANNER_REMOVED_GRADLE_NAMES
    }


def _validate_fdroid_local_properties(repo_root: Path) -> None:
    untracked = _untracked_paths(repo_root)
    expected_untracked = {FDROID_LOCAL_PROPERTIES[0], FDROID_LOCAL_PROPERTIES[2]}
    if untracked != expected_untracked:
        raise FdroidSourceBindingError(
            "F-Droid checkout must contain only the buildserver-generated untracked SDK locators; "
            f"expected={sorted(path.as_posix() for path in expected_untracked)}, "
            f"actual={sorted(path.as_posix() for path in untracked)}"
        )
    payloads: list[bytes] = []
    for path in FDROID_LOCAL_PROPERTIES:
        candidate = repo_root / path
        if not candidate.is_file() or candidate.is_symlink():
            raise FdroidSourceBindingError(
                "F-Droid buildserver-generated SDK/NDK locator must be an ordinary file: "
                f"{path.as_posix()}"
            )
        payloads.append(candidate.read_bytes())
    if len(set(payloads)) != 1:
        raise FdroidSourceBindingError(
            "F-Droid buildserver-generated local.properties files are not identical"
        )
    if payloads[0] != FDROID_LOCAL_PROPERTIES_PAYLOAD:
        raise FdroidSourceBindingError(
            "F-Droid SDK/NDK locator does not match the exact pinned buildserver payload"
        )


def _assert_tracked_state(
    repo_root: Path,
    expected_changes: Mapping[PurePosixPath, bytes | None],
    phase: str,
) -> None:
    _assert_default_index_flags(repo_root)
    actual_paths = _tracked_changed_paths(repo_root)
    expected_paths = set(expected_changes)
    if actual_paths != expected_paths:
        raise FdroidSourceBindingError(
            f"F-Droid {phase} tracked-source changes do not match the closed contract; "
            f"expected={sorted(path.as_posix() for path in expected_paths)}, "
            f"actual={sorted(path.as_posix() for path in actual_paths)}"
        )
    for path, expected in expected_changes.items():
        candidate = repo_root / path
        if expected is None:
            if candidate.exists():
                raise FdroidSourceBindingError(
                    f"F-Droid {phase} must remove the checked-in wrapper {path.as_posix()}"
                )
            continue
        if not candidate.is_file():
            raise FdroidSourceBindingError(
                f"F-Droid {phase} expected transformed source is missing: {path.as_posix()}"
            )
        current = candidate.read_bytes()
        if current != expected:
            raise FdroidSourceBindingError(
                f"F-Droid {phase} source {path.as_posix()} does not match the exact declared "
                f"transformation; expectedSha256={hashlib.sha256(expected).hexdigest()}, "
                f"currentSha256={hashlib.sha256(current).hexdigest()}"
            )
    _assert_unchanged_tracked_bytes(repo_root, expected_changes, phase)


def _assert_fdroid_prepare_state(repo_root: Path) -> None:
    _validate_fdroid_local_properties(repo_root)
    _assert_no_hidden_untracked_inputs(repo_root)
    _assert_tracked_state(
        repo_root,
        _fdroid_signing_scrubbed_sources(repo_root),
        "pre-metadata-prebuild",
    )


def _assert_fdroid_verify_state(repo_root: Path, version_name: str) -> None:
    _validate_fdroid_chaquopy_proguard(repo_root)
    _validate_fdroid_local_properties(repo_root)
    _assert_no_hidden_untracked_inputs(
        repo_root,
        allowed_generated=frozenset({FDROID_CHAQUOPY_PROGUARD_PATH}),
    )
    expected: dict[PurePosixPath, bytes | None] = dict(
        _fdroid_signing_scrubbed_sources(repo_root)
    )
    expected[GRADLE_PATH] = expected_fdroid_gradle_source(
        _head_gradle_source(repo_root), version_name
    )
    for scanner_deleted in _fdroid_scanner_deleted_gradle_files(repo_root):
        expected[scanner_deleted] = None
    _assert_tracked_state(repo_root, expected, "post-metadata-prebuild")


def _assert_remote_release_tag_authority(repo_root: Path, version_name: str) -> str:
    origin = _run_git(repo_root, "remote", "get-url", "origin").decode("utf-8").strip()
    if origin != EXPECTED_REMOTE_REPOSITORY:
        raise FdroidSourceBindingError(
            "F-Droid transformed source must use the canonical Hermes origin; "
            f"got {origin!r}"
        )
    tag = f"v{version_name}"
    tag_ref = f"refs/tags/{tag}"
    peeled_ref = f"{tag_ref}^{{}}"
    rows = _run_git(
        repo_root,
        "ls-remote",
        "--tags",
        "origin",
        tag_ref,
        peeled_ref,
    ).decode("ascii").splitlines()
    parsed: dict[str, str] = {}
    for row in rows:
        fields = row.split("\t")
        if len(fields) != 2 or COMMIT_RE.fullmatch(fields[0].lower()) is None:
            raise FdroidSourceBindingError("canonical origin returned malformed tag authority")
        if fields[1] in parsed:
            raise FdroidSourceBindingError("canonical origin returned duplicate tag authority")
        parsed[fields[1]] = fields[0].lower()
    if set(parsed) != {tag_ref, peeled_ref}:
        raise FdroidSourceBindingError(
            f"canonical origin must expose one annotated {tag} object and peeled commit"
        )
    head = _head_commit(repo_root)
    if parsed[peeled_ref] != head:
        raise FdroidSourceBindingError(
            f"canonical {tag} commit {parsed[peeled_ref]} does not match HEAD {head}"
        )
    return head


def _normalize_version_code(version_code: str | int) -> str:
    normalized = str(version_code).strip()
    if re.fullmatch(r"[1-9][0-9]*", normalized) is None:
        raise FdroidSourceBindingError(
            f"F-Droid metadata preview requires a positive version code, got {version_code!r}"
        )
    return normalized


def _read_utf8_regular_file(path: Path, label: str) -> tuple[Path, bytes, str]:
    candidate = path.expanduser()
    try:
        file_stat = candidate.lstat()
    except OSError as exc:
        raise FdroidSourceBindingError(f"unable to inspect {label}: {candidate}") from exc
    if candidate.is_symlink() or not stat.S_ISREG(file_stat.st_mode):
        raise FdroidSourceBindingError(f"{label} must be a regular non-symlink file: {candidate}")
    try:
        payload = candidate.read_bytes()
        text = payload.decode("utf-8")
    except (OSError, UnicodeError) as exc:
        raise FdroidSourceBindingError(f"unable to read UTF-8 {label}: {candidate}") from exc
    return candidate.resolve(), payload, text


def _yaml_scalar(value: str | None, label: str) -> str:
    if value is None:
        raise FdroidSourceBindingError(f"F-Droid metadata {label} must be a scalar")
    scalar = value.strip()
    if not scalar:
        raise FdroidSourceBindingError(f"F-Droid metadata {label} must not be empty")
    if scalar[0] in {"'", '"'}:
        if len(scalar) < 2 or scalar[-1] != scalar[0]:
            raise FdroidSourceBindingError(
                f"F-Droid metadata {label} has an unterminated quoted scalar"
            )
        quote = scalar[0]
        scalar = scalar[1:-1]
        if quote == "'":
            scalar = scalar.replace("''", "'")
        elif "\\" in scalar:
            raise FdroidSourceBindingError(
                f"F-Droid metadata {label} uses unsupported scalar escapes"
            )
    if " #" in scalar:
        raise FdroidSourceBindingError(
            f"F-Droid metadata {label} must not use an inline comment"
        )
    return scalar


def _build_fields(
    lines: list[str],
    start: int,
    end: int,
) -> Mapping[str, tuple[int, int, str]]:
    field_starts: list[tuple[str, int, str]] = []
    first = YAML_BUILD_START_RE.fullmatch(lines[start].rstrip("\r\n"))
    if first is None:
        raise FdroidSourceBindingError("internal F-Droid build-block parsing failure")
    field_starts.append((first.group("key"), start, first.group("value") or ""))
    for index in range(start + 1, end):
        match = YAML_BUILD_FIELD_RE.fullmatch(lines[index].rstrip("\r\n"))
        if match is not None:
            field_starts.append((match.group("key"), index, match.group("value") or ""))

    fields: dict[str, tuple[int, int, str]] = {}
    for position, (key, field_start, value) in enumerate(field_starts):
        if key in fields:
            raise FdroidSourceBindingError(
                f"F-Droid metadata build contains duplicate field {key!r}"
            )
        field_end = field_starts[position + 1][1] if position + 1 < len(field_starts) else end
        while field_end > field_start + 1 and not lines[field_end - 1].strip():
            field_end -= 1
        fields[key] = (field_start, field_end, value)
    return fields


def _yaml_build_blocks(text: str, label: str) -> tuple[list[str], list[_YamlBuildBlock]]:
    lines = text.splitlines(keepends=True)
    builds_headers = [
        index
        for index, line in enumerate(lines)
        if line.rstrip("\r\n") == "Builds:"
    ]
    if len(builds_headers) != 1:
        raise FdroidSourceBindingError(
            f"{label} must contain exactly one top-level Builds mapping, found {len(builds_headers)}"
        )
    builds_start = builds_headers[0] + 1
    builds_end = len(lines)
    for index in range(builds_start, len(lines)):
        match = YAML_TOP_LEVEL_FIELD_RE.fullmatch(lines[index].rstrip("\r\n"))
        if match is not None:
            builds_end = index
            break
    item_starts = [
        index
        for index in range(builds_start, builds_end)
        if YAML_BUILD_START_RE.fullmatch(lines[index].rstrip("\r\n")) is not None
    ]
    if not item_starts:
        raise FdroidSourceBindingError(f"{label} contains no F-Droid Builds entries")

    blocks: list[_YamlBuildBlock] = []
    for position, start in enumerate(item_starts):
        end = item_starts[position + 1] if position + 1 < len(item_starts) else builds_end
        blocks.append(_YamlBuildBlock(start, end, _build_fields(lines, start, end)))
    return lines, blocks


def _required_build_scalar(block: _YamlBuildBlock, key: str, label: str) -> str:
    field = block.fields.get(key)
    if field is None:
        raise FdroidSourceBindingError(f"{label} build is missing required field {key!r}")
    return _yaml_scalar(field[2], f"{label} build {key}")


def _target_build(
    text: str,
    label: str,
    version_name: str,
    version_code: str,
) -> tuple[list[str], _YamlBuildBlock]:
    lines, blocks = _yaml_build_blocks(text, label)
    matches: list[_YamlBuildBlock] = []
    for block in blocks:
        name_field = block.fields.get("versionName")
        code_field = block.fields.get("versionCode")
        if name_field is None or code_field is None:
            continue
        name = _yaml_scalar(name_field[2], f"{label} build versionName")
        code = _yaml_scalar(code_field[2], f"{label} build versionCode")
        if name == version_name and code == version_code:
            matches.append(block)
    if len(matches) != 1:
        raise FdroidSourceBindingError(
            f"{label} must contain exactly one {version_name}/{version_code} build, "
            f"found {len(matches)}"
        )
    return lines, matches[0]


def _top_level_scalar(text: str, key: str, label: str) -> str:
    values: list[str] = []
    for line in text.splitlines():
        match = YAML_TOP_LEVEL_FIELD_RE.fullmatch(line)
        if match is not None and match.group("key") == key:
            values.append(_yaml_scalar(match.group("value"), f"{label} {key}"))
    if len(values) != 1:
        raise FdroidSourceBindingError(
            f"{label} must contain exactly one top-level {key}, found {len(values)}"
        )
    return values[0]


def _canonical_field(lines: list[str], field: tuple[int, int, str]) -> str:
    start, end, _ = field
    return "".join(lines[start:end]).replace("\r\n", "\n").replace("\r", "\n")


def _assert_template_metadata_contract(
    template_text: str,
    version_name: str,
    version_code: str,
) -> tuple[list[str], _YamlBuildBlock]:
    if _top_level_scalar(template_text, "CurrentVersion", "F-Droid template") != version_name:
        raise FdroidSourceBindingError(
            f"F-Droid template CurrentVersion does not match {version_name}"
        )
    if (
        _top_level_scalar(template_text, "CurrentVersionCode", "F-Droid template")
        != version_code
    ):
        raise FdroidSourceBindingError(
            f"F-Droid template CurrentVersionCode does not match {version_code}"
        )
    lines, block = _target_build(
        template_text,
        "F-Droid template",
        version_name,
        version_code,
    )
    expected = {
        "sudo": EXPECTED_METADATA_SUDO,
        "ndk": EXPECTED_METADATA_NDK,
        "gradle": EXPECTED_METADATA_GRADLE,
        "gradleprops": EXPECTED_METADATA_GRADLEPROPS,
        "prebuild": EXPECTED_METADATA_PREBUILD,
    }
    for field_name, canonical in expected.items():
        field = block.fields.get(field_name)
        if field is None:
            raise FdroidSourceBindingError(
                f"F-Droid template build is missing source-binding field {field_name!r}"
            )
        actual = _canonical_field(lines, field)
        if actual != canonical:
            raise FdroidSourceBindingError(
                f"F-Droid template {field_name} does not match the exact source-binding contract"
            )
        if "unbound" in actual.lower():
            raise FdroidSourceBindingError(
                f"F-Droid template {field_name} may not emit an unbound source identity"
            )
    return lines, block


def _assert_autoupdate_metadata_identity(
    metadata_text: str,
    version_name: str,
    version_code: str,
) -> None:
    current_version = _top_level_scalar(
        metadata_text,
        "CurrentVersion",
        "autoupdater metadata",
    )
    current_code = _top_level_scalar(
        metadata_text,
        "CurrentVersionCode",
        "autoupdater metadata",
    )
    if current_version != version_name or current_code != version_code:
        raise FdroidSourceBindingError(
            "autoupdater metadata current release does not match the requested preview; "
            f"expected={version_name}/{version_code}, actual={current_version}/{current_code}"
        )


def _verify_metadata_preview_text(
    metadata_text: str,
    template_text: str,
    version_name: str,
    version_code: str,
) -> str:
    _assert_autoupdate_metadata_identity(metadata_text, version_name, version_code)
    metadata_lines, metadata_build = _target_build(
        metadata_text,
        "autoupdater metadata",
        version_name,
        version_code,
    )
    template_lines, template_build = _assert_template_metadata_contract(
        template_text,
        version_name,
        version_code,
    )
    commit = _required_build_scalar(metadata_build, "commit", "autoupdater metadata")
    if COMMIT_RE.fullmatch(commit) is None:
        raise FdroidSourceBindingError(
            "autoupdater metadata must resolve the release tag to a full lowercase Git commit; "
            f"got {commit!r}"
        )
    for field_name in METADATA_OVERLAY_FIELDS:
        metadata_field = metadata_build.fields.get(field_name)
        template_field = template_build.fields.get(field_name)
        if metadata_field is None:
            raise FdroidSourceBindingError(
                f"autoupdater metadata build is missing source-binding field {field_name!r}"
            )
        if template_field is None:
            raise FdroidSourceBindingError(
                f"F-Droid template build is missing source-binding field {field_name!r}"
            )
        actual = _canonical_field(metadata_lines, metadata_field)
        expected = _canonical_field(template_lines, template_field)
        if actual != expected:
            raise FdroidSourceBindingError(
                f"autoupdater metadata {field_name} does not match the v{version_name} "
                "source-binding template"
            )
        if "unbound" in actual.lower():
            raise FdroidSourceBindingError(
                f"autoupdater metadata {field_name} may not emit an unbound source identity"
            )
    return commit


def verify_autoupdate_metadata_preview(
    metadata_file: Path,
    template_file: Path,
    version: str = AUTUPDATE_VERSION_NAME,
    version_code: str | int = AUTUPDATE_VERSION_CODE,
) -> VerifiedMetadataPreview:
    version_name = _normalize_version(version)
    normalized_code = _normalize_version_code(version_code)
    metadata_path, _, metadata_text = _read_utf8_regular_file(
        metadata_file,
        "autoupdater metadata",
    )
    template_path, _, template_text = _read_utf8_regular_file(
        template_file,
        "F-Droid metadata template",
    )
    commit = _verify_metadata_preview_text(
        metadata_text,
        template_text,
        version_name,
        normalized_code,
    )
    return VerifiedMetadataPreview(
        metadata_path,
        template_path,
        version_name,
        normalized_code,
        commit,
    )


def _target_newline(payload: bytes) -> str:
    return "\r\n" if b"\r\n" in payload else "\n"


def _field_lines_for_newline(
    lines: list[str],
    field: tuple[int, int, str],
    newline: str,
) -> list[str]:
    canonical = _canonical_field(lines, field)
    return [f"{line}{newline}" for line in canonical.rstrip("\n").split("\n")]


def _atomic_replace_file(path: Path, payload: bytes, mode: int) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_bytes(payload)
        os.chmod(temporary, stat.S_IMODE(mode))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def render_autoupdate_metadata_preview(
    metadata_file: Path,
    template_file: Path,
    version: str = AUTUPDATE_VERSION_NAME,
    version_code: str | int = AUTUPDATE_VERSION_CODE,
) -> VerifiedMetadataPreview:
    version_name = _normalize_version(version)
    normalized_code = _normalize_version_code(version_code)
    metadata_path, metadata_payload, metadata_text = _read_utf8_regular_file(
        metadata_file,
        "autoupdater metadata",
    )
    template_path, _, template_text = _read_utf8_regular_file(
        template_file,
        "F-Droid metadata template",
    )
    _assert_autoupdate_metadata_identity(metadata_text, version_name, normalized_code)
    metadata_lines, metadata_build = _target_build(
        metadata_text,
        "autoupdater metadata",
        version_name,
        normalized_code,
    )
    template_lines, template_build = _assert_template_metadata_contract(
        template_text,
        version_name,
        normalized_code,
    )
    original_commit = _required_build_scalar(
        metadata_build,
        "commit",
        "autoupdater metadata",
    )
    if COMMIT_RE.fullmatch(original_commit) is None:
        raise FdroidSourceBindingError(
            "autoupdater metadata must resolve the release tag to a full lowercase Git commit; "
            f"got {original_commit!r}"
        )

    existing_fields = [
        metadata_build.fields[name]
        for name in METADATA_OVERLAY_FIELDS
        if name in metadata_build.fields
    ]
    if existing_fields:
        insertion = min(field[0] for field in existing_fields)
    else:
        gradle_field = metadata_build.fields.get("gradle")
        if gradle_field is None:
            raise FdroidSourceBindingError(
                "autoupdater metadata build has no gradle field before source-binding overlay"
            )
        insertion = gradle_field[1]
    removed_lines = {
        index
        for field in existing_fields
        for index in range(field[0], field[1])
    }
    newline = _target_newline(metadata_payload)
    overlay_lines: list[str] = []
    for field_name in METADATA_OVERLAY_FIELDS:
        overlay_lines.extend(
            _field_lines_for_newline(
                template_lines,
                template_build.fields[field_name],
                newline,
            )
        )
    rendered_lines: list[str] = []
    for index, line in enumerate(metadata_lines):
        if index == insertion:
            rendered_lines.extend(overlay_lines)
        if index not in removed_lines:
            rendered_lines.append(line)
    rendered_text = "".join(rendered_lines)
    rendered_commit = _verify_metadata_preview_text(
        rendered_text,
        template_text,
        version_name,
        normalized_code,
    )
    if rendered_commit != original_commit:
        raise FdroidSourceBindingError(
            "source-binding render changed the autoupdater-resolved release commit"
        )

    if rendered_text != metadata_text:
        _atomic_replace_file(
            metadata_path,
            rendered_text.encode("utf-8"),
            metadata_path.stat().st_mode,
        )
    verified = verify_autoupdate_metadata_preview(
        metadata_path,
        template_path,
        version_name,
        normalized_code,
    )
    if verified.commit != original_commit:
        raise FdroidSourceBindingError(
            "source-binding transaction did not preserve the autoupdater-resolved commit"
        )
    return verified


def _serialize_binding(values: Mapping[str, str]) -> bytes:
    if set(values) != EXPECTED_BINDING_KEYS:
        raise FdroidSourceBindingError("internal binding fields do not match the closed schema")
    return "".join(f"{key}={values[key]}\n" for key in sorted(values)).encode("ascii")


def _write_binding_file(binding_file: Path, values: Mapping[str, str]) -> None:
    binding_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = binding_file.with_name(f".{binding_file.name}.{os.getpid()}.tmp")
    try:
        temporary.write_bytes(_serialize_binding(values))
        os.chmod(temporary, 0o600)
        temporary.replace(binding_file)
    finally:
        temporary.unlink(missing_ok=True)


def _load_binding_file(binding_file: Path) -> dict[str, str]:
    if not binding_file.is_file():
        raise FdroidSourceBindingError(f"F-Droid source binding file is missing: {binding_file}")
    try:
        lines = binding_file.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError) as exc:
        raise FdroidSourceBindingError(
            f"unable to read F-Droid source binding file: {binding_file}"
        ) from exc
    values: dict[str, str] = {}
    for line in lines:
        key, separator, value = line.partition("=")
        if not separator or not key or not value:
            raise FdroidSourceBindingError(f"invalid F-Droid source binding line: {line!r}")
        if key in values:
            raise FdroidSourceBindingError(f"duplicate F-Droid source binding field: {key}")
        values[key] = value
    unknown = set(values) - EXPECTED_BINDING_KEYS
    missing = EXPECTED_BINDING_KEYS - set(values)
    if unknown or missing:
        raise FdroidSourceBindingError(
            f"F-Droid source binding schema mismatch; missing={sorted(missing)}, unknown={sorted(unknown)}"
        )
    return values


def prepare_binding(repo_root: Path, binding_file: Path, version: str) -> VerifiedBinding:
    repo_root = _normalize_repo_root(repo_root)
    binding_file = _assert_binding_file_outside_repo(repo_root, binding_file)
    version_name = _normalize_version(version)
    _assert_fdroid_prepare_state(repo_root)
    _assert_release_identity(repo_root, version_name)
    identity = git_source_tree_identity(repo_root)
    commit = _head_commit(repo_root)
    values = {
        "schema": BINDING_SCHEMA,
        "commit": commit,
        "versionName": version_name,
        "sourceAlgorithm": identity.algorithm,
        "sourceDigest": identity.digest,
        "sourceFiles": str(identity.file_count),
        "gitObjectFormat": identity.git_object_format,
        "excludedPrefix": identity.excluded_prefix,
    }
    _write_binding_file(binding_file, values)
    return VerifiedBinding(commit, version_name, identity.digest, identity.file_count)


def verify_binding(repo_root: Path, binding_file: Path, version: str) -> VerifiedBinding:
    repo_root = _normalize_repo_root(repo_root)
    binding_file = _assert_binding_file_outside_repo(repo_root, binding_file)
    version_name = _normalize_version(version)
    _assert_release_identity(repo_root, version_name)
    values = _load_binding_file(binding_file)
    if values["schema"] != BINDING_SCHEMA:
        raise FdroidSourceBindingError(
            f"unsupported F-Droid source binding schema: {values['schema']!r}"
        )
    if values["versionName"] != version_name:
        raise FdroidSourceBindingError(
            f"F-Droid source binding version {values['versionName']} does not match {version_name}"
        )

    commit = _head_commit(repo_root)
    if values["commit"] != commit:
        raise FdroidSourceBindingError(
            f"F-Droid source binding commit {values['commit']} does not match HEAD {commit}"
        )
    identity = git_source_tree_identity(repo_root)
    expected_identity = {
        "sourceAlgorithm": identity.algorithm,
        "sourceDigest": identity.digest,
        "sourceFiles": str(identity.file_count),
        "gitObjectFormat": identity.git_object_format,
        "excludedPrefix": identity.excluded_prefix,
    }
    for key, expected in expected_identity.items():
        if values[key] != expected:
            raise FdroidSourceBindingError(
                f"F-Droid source binding {key}={values[key]!r} does not match committed {expected!r}"
            )
    if SHA256_RE.fullmatch(values["sourceDigest"]) is None:
        raise FdroidSourceBindingError("F-Droid source binding digest is not lowercase SHA-256")

    _assert_fdroid_verify_state(repo_root, version_name)
    return VerifiedBinding(commit, version_name, identity.digest, identity.file_count)


def verify_transformed_binding(repo_root: Path, version: str) -> VerifiedBinding:
    """Bind an exact bot-transformed checkout directly to committed ``HEAD``.

    This is deliberately narrower than a generic dirty-tree fallback. The
    checkout must match the same closed F-Droid signing scrub, SDK locators,
    two declared metadata edits, and scanner deletions accepted by
    :func:`verify_binding`. Source identity is calculated from Git objects, not
    from transformed working-tree bytes.
    """

    repo_root = _normalize_repo_root(repo_root)
    version_name = _normalize_version(version)
    commit = _assert_remote_release_tag_authority(repo_root, version_name)
    _assert_release_identity(repo_root, version_name)
    _assert_fdroid_verify_state(repo_root, version_name)
    identity = git_source_tree_identity(repo_root)
    return VerifiedBinding(commit, version_name, identity.digest, identity.file_count)


def _print_binding(binding: VerifiedBinding, binding_file: Path) -> None:
    print(f"sourceDigest={binding.source_digest}")
    print(f"sourceFiles={binding.source_files}")
    print(f"sourceCommit={binding.commit}")
    print(f"versionName={binding.version_name}")
    print(f"bindingFile={binding_file.resolve()}")


def _print_metadata_preview(preview: VerifiedMetadataPreview) -> None:
    print(f"metadataFile={preview.metadata_file}")
    print(f"templateFile={preview.template_file}")
    print(f"versionName={preview.version_name}")
    print(f"versionCode={preview.version_code}")
    print(f"sourceCommit={preview.commit}")
    print("sourceBindingGradleProperty=hermesFdroidSourceBinding=true")
    print("sourceBindingPrepare=true")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("prepare", "verify"):
        command_parser = subparsers.add_parser(command)
        command_parser.add_argument("--repo-root", type=Path, required=True)
        command_parser.add_argument("--binding-file", type=Path, required=True)
        command_parser.add_argument("--version", required=True)
    transformed_parser = subparsers.add_parser("verify-transformed")
    transformed_parser.add_argument("--repo-root", type=Path, required=True)
    transformed_parser.add_argument("--version", required=True)
    for command in ("render-autoupdate-preview", "verify-autoupdate-preview"):
        command_parser = subparsers.add_parser(command)
        command_parser.add_argument("--metadata", type=Path, required=True)
        command_parser.add_argument("--template", type=Path, required=True)
        command_parser.add_argument("--version", default=AUTUPDATE_VERSION_NAME)
        command_parser.add_argument("--version-code", default=AUTUPDATE_VERSION_CODE)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            binding = prepare_binding(args.repo_root, args.binding_file, args.version)
            _print_binding(binding, args.binding_file)
        elif args.command == "verify":
            binding = verify_binding(args.repo_root, args.binding_file, args.version)
            _print_binding(binding, args.binding_file)
        elif args.command == "verify-transformed":
            binding = verify_transformed_binding(args.repo_root, args.version)
            print("bindingMode=verified-transformed-checkout")
            print(f"sourceDigest={binding.source_digest}")
            print(f"sourceFiles={binding.source_files}")
            print(f"sourceCommit={binding.commit}")
            print(f"versionName={binding.version_name}")
        elif args.command == "render-autoupdate-preview":
            preview = render_autoupdate_metadata_preview(
                args.metadata,
                args.template,
                args.version,
                args.version_code,
            )
            _print_metadata_preview(preview)
        else:
            preview = verify_autoupdate_metadata_preview(
                args.metadata,
                args.template,
                args.version,
                args.version_code,
            )
            _print_metadata_preview(preview)
    except (FdroidSourceBindingError, OSError, ValueError) as exc:
        print(f"F-Droid source binding failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
