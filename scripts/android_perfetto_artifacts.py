#!/usr/bin/env python3
"""Create and verify the external GitHub Actions archive for Android Perfetto traces.

Perfetto traces are required release evidence, but they are too large to keep in
the normal repository checkout.  This helper binds every trace to the existing
normalized performance record, verifies an Actions download byte-for-byte, and
records the successful workflow run without weakening the release-evidence
contract.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence


SOURCE_MANIFEST_SCHEMA = "hermes-android-perfetto-source-manifest-v1"
REGISTRY_SCHEMA = "hermes-android-perfetto-actions-registry-v1"
DEFAULT_REPOSITORY = "adybag14-cyber/hermes-agent"
EVIDENCE_PREFIX = PurePosixPath("android/release-evidence")
SOURCE_MANIFEST_RELATIVE = EVIDENCE_PREFIX / "perfetto-artifacts/source-manifest.json"
REGISTRY_RELATIVE = EVIDENCE_PREFIX / "perfetto-artifacts/registry.json"
WORKFLOW_PATH = ".github/workflows/android-perfetto-artifacts.yml"
ARTIFACT_PREFIX = "hermes-android-perfetto-"
RETENTION_DAYS = 90
HEX_40_RE = re.compile(r"^[0-9a-f]{40}$")
HEX_64_RE = re.compile(r"^[0-9a-f]{64}$")
TAG_RE = re.compile(r"^v0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$")
TRACE_PATH_RE = re.compile(
    r"^android/release-evidence/(?P<tag>v0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))/"
    r"performance/(?P<profile>phone-compact|tablet)\.traces/"
    r"iteration-(?P<iteration>[0-9]{3})\.perfetto-trace$"
)


class PerfettoArtifactError(RuntimeError):
    """Raised when a trace archive or its provenance is not exact."""


def _json_object(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        raise PerfettoArtifactError(f"JSON input is missing or unsafe: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PerfettoArtifactError(f"Unable to read JSON object {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise PerfettoArtifactError(f"JSON input must contain one object: {path}")
    return payload


def _write_json(path: Path, payload: Mapping[str, Any]) -> None:
    encoded = (json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode(
        "utf-8"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(encoded)
    temporary.replace(path)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _run(
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    text: bool = True,
) -> subprocess.CompletedProcess[Any]:
    try:
        return subprocess.run(
            list(command),
            cwd=cwd,
            check=True,
            capture_output=True,
            text=text,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        stderr = getattr(exc, "stderr", "")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        detail = stderr.strip() if isinstance(stderr, str) else ""
        raise PerfettoArtifactError(
            f"Command failed: {' '.join(command)}" + (f": {detail}" if detail else "")
        ) from exc


def _repo_path(repo_root: Path, relative: str | PurePosixPath) -> Path:
    posix = PurePosixPath(relative)
    if posix.is_absolute() or ".." in posix.parts:
        raise PerfettoArtifactError(f"Unsafe repository-relative path: {relative!r}")
    candidate = repo_root.joinpath(*posix.parts)
    try:
        candidate.resolve().relative_to(repo_root.resolve())
    except ValueError as exc:
        raise PerfettoArtifactError(f"Path escapes repository root: {relative!r}") from exc
    return candidate


def _require_regular_file(path: Path, *, nonempty: bool = True) -> None:
    if not path.is_file() or path.is_symlink():
        raise PerfettoArtifactError(f"Required file is missing or unsafe: {path}")
    if nonempty and path.stat().st_size <= 0:
        raise PerfettoArtifactError(f"Required file is empty: {path}")


def _git_head(repo_root: Path) -> str:
    head = _run(("git", "rev-parse", "HEAD"), cwd=repo_root).stdout.strip()
    if not HEX_40_RE.fullmatch(head):
        raise PerfettoArtifactError("Git HEAD is not one exact SHA-1 commit")
    return head


def _tracked_trace_paths(repo_root: Path) -> tuple[str, ...]:
    result = _run(
        (
            "git",
            "ls-files",
            "-z",
            "--",
            ":(glob)android/release-evidence/**/*.perfetto-trace",
        ),
        cwd=repo_root,
        text=False,
    )
    try:
        decoded = [part.decode("utf-8") for part in result.stdout.split(b"\0") if part]
    except UnicodeDecodeError as exc:
        raise PerfettoArtifactError("Tracked trace path is not UTF-8") from exc
    return tuple(sorted(decoded))


def _working_trace_paths(repo_root: Path) -> tuple[str, ...]:
    evidence_root = _repo_path(repo_root, EVIDENCE_PREFIX)
    if not evidence_root.is_dir():
        raise PerfettoArtifactError(f"Release evidence directory is missing: {evidence_root}")
    records: list[str] = []
    for path in evidence_root.rglob("*.perfetto-trace"):
        if path.is_symlink() or not path.is_file():
            raise PerfettoArtifactError(f"Trace path is not a regular file: {path}")
        records.append(path.relative_to(repo_root).as_posix())
    return tuple(sorted(records))


def _require_clean_repo(repo_root: Path) -> None:
    status = _run(
        ("git", "status", "--porcelain=v1", "--untracked-files=all"), cwd=repo_root
    ).stdout
    if status:
        raise PerfettoArtifactError("Repository must be clean before creating the source manifest")


def _trace_bindings_from_performance(repo_root: Path, tag: str) -> dict[str, dict[str, Any]]:
    bindings: dict[str, dict[str, Any]] = {}
    for profile in ("phone-compact", "tablet"):
        performance_path = _repo_path(
            repo_root, EVIDENCE_PREFIX / tag / "performance" / f"{profile}.json"
        )
        payload = _json_object(performance_path)
        traces = payload.get("traces")
        if not isinstance(traces, list) or not traces:
            raise PerfettoArtifactError(f"{performance_path} has no trace bindings")
        for index, record in enumerate(traces, start=1):
            context = f"{tag}/{profile}/trace[{index}]"
            if not isinstance(record, dict) or set(record) != {
                "iteration",
                "path",
                "source_name",
                "bytes",
                "sha256",
            }:
                raise PerfettoArtifactError(f"{context} has an invalid key set")
            expected_relative = (
                EVIDENCE_PREFIX
                / tag
                / "performance"
                / f"{profile}.traces"
                / f"iteration-{index:03d}.perfetto-trace"
            ).as_posix()
            expected_evidence_path = (
                PurePosixPath("performance")
                / f"{profile}.traces"
                / f"iteration-{index:03d}.perfetto-trace"
            ).as_posix()
            size = record.get("bytes")
            digest = record.get("sha256")
            if record.get("iteration") != index or record.get("path") != expected_evidence_path:
                raise PerfettoArtifactError(f"{context} path or iteration is not canonical")
            if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
                raise PerfettoArtifactError(f"{context} byte count is invalid")
            if not isinstance(digest, str) or not HEX_64_RE.fullmatch(digest):
                raise PerfettoArtifactError(f"{context} SHA-256 is invalid")
            if expected_relative in bindings:
                raise PerfettoArtifactError(f"Duplicate trace binding: {expected_relative}")
            bindings[expected_relative] = {
                "path": expected_relative,
                "bytes": size,
                "sha256": digest,
            }
    return bindings


def _trace_bindings_from_release_manifest(
    repo_root: Path, tag: str
) -> dict[str, dict[str, Any]]:
    manifest_path = _repo_path(repo_root, EVIDENCE_PREFIX / tag / "manifest.json")
    payload = _json_object(manifest_path)
    evidence = payload.get("evidence")
    files = evidence.get("files") if isinstance(evidence, dict) else None
    if not isinstance(files, list):
        raise PerfettoArtifactError(f"{manifest_path} has no evidence file index")
    bindings: dict[str, dict[str, Any]] = {}
    for record in files:
        if not isinstance(record, dict) or not str(record.get("path", "")).endswith(
            ".perfetto-trace"
        ):
            continue
        if set(record) != {"path", "bytes", "sha256"}:
            raise PerfettoArtifactError(f"{manifest_path} has an invalid trace file record")
        evidence_relative = record["path"]
        repository_relative = (EVIDENCE_PREFIX / tag / evidence_relative).as_posix()
        if TRACE_PATH_RE.fullmatch(repository_relative) is None:
            raise PerfettoArtifactError(
                f"{manifest_path} has a non-canonical trace path: {evidence_relative!r}"
            )
        if repository_relative in bindings:
            raise PerfettoArtifactError(
                f"{manifest_path} repeats trace path {evidence_relative!r}"
            )
        bindings[repository_relative] = {
            "path": repository_relative,
            "bytes": record.get("bytes"),
            "sha256": record.get("sha256"),
        }
    if not bindings:
        raise PerfettoArtifactError(f"{manifest_path} has no trace file records")
    return bindings


def _validate_trace_file(path: Path, record: Mapping[str, Any]) -> None:
    _require_regular_file(path)
    expected_bytes = record["bytes"]
    actual_bytes = path.stat().st_size
    if actual_bytes != expected_bytes:
        raise PerfettoArtifactError(
            f"Trace byte count differs for {record['path']}: {actual_bytes} != {expected_bytes}"
        )
    actual_sha = _sha256_file(path)
    if actual_sha != record["sha256"]:
        raise PerfettoArtifactError(
            f"Trace SHA-256 differs for {record['path']}: {actual_sha} != {record['sha256']}"
        )


def create_source_manifest(repo_root: Path) -> dict[str, Any]:
    root = repo_root.resolve()
    _require_clean_repo(root)
    source_commit = _git_head(root)
    tracked = _tracked_trace_paths(root)
    working = _working_trace_paths(root)
    if not tracked:
        raise PerfettoArtifactError("No tracked Perfetto traces were found")
    if tracked != working:
        raise PerfettoArtifactError(
            "Tracked and working-tree Perfetto sets differ; "
            f"tracked_only={sorted(set(tracked) - set(working))}, "
            f"working_only={sorted(set(working) - set(tracked))}"
        )

    tags: dict[str, list[str]] = {}
    for relative in tracked:
        match = TRACE_PATH_RE.fullmatch(relative)
        if match is None:
            raise PerfettoArtifactError(f"Tracked trace path is not canonical: {relative}")
        tags.setdefault(match.group("tag"), []).append(relative)

    versions: list[dict[str, Any]] = []
    seen: set[str] = set()
    for tag in sorted(tags, key=_version_key):
        bindings = _trace_bindings_from_performance(root, tag)
        release_bindings = _trace_bindings_from_release_manifest(root, tag)
        if release_bindings != bindings:
            raise PerfettoArtifactError(
                f"{tag} release manifest trace index differs from normalized performance bindings"
            )
        trace_paths = tuple(sorted(tags[tag]))
        if set(trace_paths) != set(bindings):
            raise PerfettoArtifactError(
                f"{tag} performance bindings and tracked trace paths differ; "
                f"bindings_only={sorted(set(bindings) - set(trace_paths))}, "
                f"tracked_only={sorted(set(trace_paths) - set(bindings))}"
            )
        files = []
        for relative in trace_paths:
            record = bindings[relative]
            _validate_trace_file(_repo_path(root, relative), record)
            files.append(record)
            seen.add(relative)
        versions.append(
            {
                "tag": tag,
                "artifact_name": artifact_name(tag, source_commit),
                "trace_file_count": len(files),
                "trace_bytes": sum(record["bytes"] for record in files),
                "files": files,
            }
        )
    if seen != set(tracked):
        raise PerfettoArtifactError("Not every tracked trace was added to the source manifest")
    return {
        "schema": SOURCE_MANIFEST_SCHEMA,
        "repository": DEFAULT_REPOSITORY,
        "source_commit": source_commit,
        "trace_file_count": sum(version["trace_file_count"] for version in versions),
        "trace_bytes": sum(version["trace_bytes"] for version in versions),
        "versions": versions,
    }


def _version_key(tag: str) -> tuple[int, int, int]:
    if not TAG_RE.fullmatch(tag):
        raise PerfettoArtifactError(f"Invalid Android release tag: {tag!r}")
    return tuple(int(part) for part in tag[1:].split("."))  # type: ignore[return-value]


def artifact_name(tag: str, source_commit: str) -> str:
    _version_key(tag)
    if not HEX_40_RE.fullmatch(source_commit):
        raise PerfettoArtifactError("Perfetto artifact source commit is invalid")
    return f"{ARTIFACT_PREFIX}{tag}-{source_commit}"


def validate_source_manifest(payload: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    if set(payload) != {
        "schema",
        "repository",
        "source_commit",
        "trace_file_count",
        "trace_bytes",
        "versions",
    }:
        raise PerfettoArtifactError("Perfetto source manifest key set is invalid")
    if payload.get("schema") != SOURCE_MANIFEST_SCHEMA:
        raise PerfettoArtifactError("Perfetto source manifest schema is unsupported")
    repository = payload.get("repository")
    if not isinstance(repository, str) or repository.count("/") != 1:
        raise PerfettoArtifactError("Perfetto source manifest repository is invalid")
    source_commit = payload.get("source_commit")
    if not isinstance(source_commit, str) or not HEX_40_RE.fullmatch(source_commit):
        raise PerfettoArtifactError("Perfetto source manifest commit is invalid")
    versions = payload.get("versions")
    if not isinstance(versions, list) or not versions:
        raise PerfettoArtifactError("Perfetto source manifest must contain versions")

    all_records: dict[str, dict[str, Any]] = {}
    tags: list[str] = []
    total_files = 0
    total_bytes = 0
    for version in versions:
        if not isinstance(version, dict) or set(version) != {
            "tag",
            "artifact_name",
            "trace_file_count",
            "trace_bytes",
            "files",
        }:
            raise PerfettoArtifactError("Perfetto source manifest version key set is invalid")
        tag = version.get("tag")
        if not isinstance(tag, str):
            raise PerfettoArtifactError("Perfetto source manifest version tag is invalid")
        _version_key(tag)
        if tag in tags:
            raise PerfettoArtifactError(f"Duplicate Perfetto source version: {tag}")
        tags.append(tag)
        if version.get("artifact_name") != artifact_name(tag, source_commit):
            raise PerfettoArtifactError(f"Perfetto artifact name is not canonical for {tag}")
        files = version.get("files")
        if not isinstance(files, list) or not files:
            raise PerfettoArtifactError(f"Perfetto source version {tag} has no files")
        version_bytes = 0
        paths: list[str] = []
        for record in files:
            if not isinstance(record, dict) or set(record) != {"path", "bytes", "sha256"}:
                raise PerfettoArtifactError(f"Perfetto source file record is invalid for {tag}")
            path = record.get("path")
            size = record.get("bytes")
            digest = record.get("sha256")
            if not isinstance(path, str):
                raise PerfettoArtifactError(f"Perfetto source path is invalid for {tag}")
            match = TRACE_PATH_RE.fullmatch(path)
            if match is None or match.group("tag") != tag:
                raise PerfettoArtifactError(f"Perfetto source path is not canonical for {tag}: {path}")
            if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
                raise PerfettoArtifactError(f"Perfetto source byte count is invalid: {path}")
            if not isinstance(digest, str) or not HEX_64_RE.fullmatch(digest):
                raise PerfettoArtifactError(f"Perfetto source SHA-256 is invalid: {path}")
            if path in all_records:
                raise PerfettoArtifactError(f"Duplicate Perfetto source path: {path}")
            paths.append(path)
            all_records[path] = dict(record)
            version_bytes += size
        if paths != sorted(paths):
            raise PerfettoArtifactError(f"Perfetto source files are not sorted for {tag}")
        if version.get("trace_file_count") != len(files) or version.get("trace_bytes") != version_bytes:
            raise PerfettoArtifactError(f"Perfetto source totals do not match files for {tag}")
        total_files += len(files)
        total_bytes += version_bytes
    if tags != sorted(tags, key=_version_key):
        raise PerfettoArtifactError("Perfetto source versions are not sorted")
    if payload.get("trace_file_count") != total_files or payload.get("trace_bytes") != total_bytes:
        raise PerfettoArtifactError("Perfetto source aggregate totals do not match files")
    return all_records


def verify_source(repo_root: Path, manifest_path: Path, expected_commit: str | None = None) -> None:
    root = repo_root.resolve()
    payload = _json_object(manifest_path.resolve())
    records = validate_source_manifest(payload)
    manifest_commit = payload["source_commit"]
    if expected_commit is not None and expected_commit != manifest_commit:
        raise PerfettoArtifactError(
            f"Requested source commit {expected_commit} does not match manifest {manifest_commit}"
        )
    if _git_head(root) != manifest_commit:
        raise PerfettoArtifactError("Checked-out trace source does not match the source manifest commit")
    tracked = _tracked_trace_paths(root)
    working = _working_trace_paths(root)
    if tracked != working or set(tracked) != set(records):
        raise PerfettoArtifactError("Checked-out tracked trace set does not exactly match the manifest")
    for relative in tracked:
        _validate_trace_file(_repo_path(root, relative), records[relative])
    for version in payload["versions"]:
        bindings = _trace_bindings_from_performance(root, version["tag"])
        release_bindings = _trace_bindings_from_release_manifest(root, version["tag"])
        version_records = {
            record["path"]: record for record in version["files"]
        }
        if bindings != version_records or release_bindings != version_records:
            raise PerfettoArtifactError(
                f"{version['tag']} committed trace bindings differ from the source manifest"
            )


def _download_relative_path(tag: str, repository_path: str) -> str:
    prefix = f"android/release-evidence/{tag}/performance/"
    if not repository_path.startswith(prefix):
        raise PerfettoArtifactError(f"Trace does not belong to {tag}: {repository_path}")
    return repository_path[len(prefix) :]


def verify_downloads(download_root: Path, manifest_path: Path) -> tuple[int, int]:
    root = download_root.resolve()
    if not root.is_dir():
        raise PerfettoArtifactError(f"Artifact download directory is missing: {root}")
    payload = _json_object(manifest_path.resolve())
    validate_source_manifest(payload)
    verified_files = 0
    verified_bytes = 0
    expected_artifact_directories = {version["artifact_name"] for version in payload["versions"]}
    actual_artifact_directories = {
        path.name for path in root.iterdir() if path.is_dir() and not path.is_symlink()
    }
    if actual_artifact_directories != expected_artifact_directories:
        raise PerfettoArtifactError(
            "Downloaded artifact directory set differs from the manifest; "
            f"missing={sorted(expected_artifact_directories - actual_artifact_directories)}, "
            f"unexpected={sorted(actual_artifact_directories - expected_artifact_directories)}"
        )
    for version in payload["versions"]:
        artifact_dir = root / version["artifact_name"]
        expected = {
            _download_relative_path(version["tag"], record["path"]): record
            for record in version["files"]
        }
        actual: set[str] = set()
        for path in artifact_dir.rglob("*"):
            if path.is_symlink():
                raise PerfettoArtifactError(f"Downloaded artifact contains a symlink: {path}")
            if path.is_file():
                actual.add(path.relative_to(artifact_dir).as_posix())
        if actual != set(expected):
            raise PerfettoArtifactError(
                f"Downloaded {version['tag']} file set differs; "
                f"missing={sorted(set(expected) - actual)}, unexpected={sorted(actual - set(expected))}"
            )
        for relative in sorted(actual):
            _validate_trace_file(artifact_dir / Path(relative), expected[relative])
            verified_files += 1
            verified_bytes += expected[relative]["bytes"]
    if verified_files != payload["trace_file_count"] or verified_bytes != payload["trace_bytes"]:
        raise PerfettoArtifactError("Downloaded aggregate trace totals differ from the manifest")
    return verified_files, verified_bytes


def _parse_utc(value: Any, context: str) -> datetime:
    if not isinstance(value, str):
        raise PerfettoArtifactError(f"{context} timestamp is invalid")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PerfettoArtifactError(f"{context} timestamp is invalid") from exc
    if parsed.tzinfo is None:
        raise PerfettoArtifactError(f"{context} timestamp has no timezone")
    return parsed.astimezone(timezone.utc)


def _gh_json(arguments: Sequence[str], *, cwd: Path) -> dict[str, Any]:
    result = _run(("gh", "api", *arguments), cwd=cwd)
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise PerfettoArtifactError("GitHub CLI returned invalid JSON") from exc
    if not isinstance(payload, dict):
        raise PerfettoArtifactError("GitHub CLI response is not an object")
    return payload


def create_registry(
    repo_root: Path,
    manifest_path: Path,
    repository: str,
    run_id: int,
) -> dict[str, Any]:
    root = repo_root.resolve()
    source_payload = _json_object(manifest_path.resolve())
    validate_source_manifest(source_payload)
    if source_payload["repository"] != repository:
        raise PerfettoArtifactError("Requested repository differs from the source manifest")
    run = _gh_json((f"repos/{repository}/actions/runs/{run_id}",), cwd=root)
    if (
        run.get("id") != run_id
        or run.get("event") != "workflow_dispatch"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("path") != WORKFLOW_PATH
    ):
        raise PerfettoArtifactError("GitHub Actions run is not a successful Perfetto archive dispatch")
    workflow_head_sha = run.get("head_sha")
    if not isinstance(workflow_head_sha, str) or not HEX_40_RE.fullmatch(workflow_head_sha):
        raise PerfettoArtifactError("GitHub Actions workflow head SHA is invalid")
    response = _gh_json(
        (f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100",), cwd=root
    )
    api_artifacts = response.get("artifacts")
    if not isinstance(api_artifacts, list):
        raise PerfettoArtifactError("GitHub Actions artifacts response is invalid")
    by_name: dict[str, dict[str, Any]] = {}
    for item in api_artifacts:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise PerfettoArtifactError("GitHub Actions artifact record is invalid")
        if item["name"] in by_name:
            raise PerfettoArtifactError(f"GitHub Actions run has duplicate artifact {item['name']}")
        by_name[item["name"]] = item
    expected_names = {version["artifact_name"] for version in source_payload["versions"]}
    if set(by_name) != expected_names:
        raise PerfettoArtifactError(
            "GitHub Actions run artifact set differs from the source manifest; "
            f"missing={sorted(expected_names - set(by_name))}, "
            f"unexpected={sorted(set(by_name) - expected_names)}"
        )

    artifacts: list[dict[str, Any]] = []
    for version in source_payload["versions"]:
        item = by_name[version["artifact_name"]]
        artifact_id = item.get("id")
        archive_bytes = item.get("size_in_bytes")
        digest = item.get("digest")
        created_at = item.get("created_at")
        expires_at = item.get("expires_at")
        if isinstance(artifact_id, bool) or not isinstance(artifact_id, int) or artifact_id <= 0:
            raise PerfettoArtifactError(f"GitHub Actions artifact ID is invalid for {version['tag']}")
        if isinstance(archive_bytes, bool) or not isinstance(archive_bytes, int) or archive_bytes <= 0:
            raise PerfettoArtifactError(f"GitHub Actions artifact size is invalid for {version['tag']}")
        if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise PerfettoArtifactError(f"GitHub Actions artifact digest is invalid for {version['tag']}")
        created = _parse_utc(created_at, f"{version['tag']}.created_at")
        expires = _parse_utc(expires_at, f"{version['tag']}.expires_at")
        if expires <= created or (expires - created).total_seconds() > (RETENTION_DAYS + 1) * 86400:
            raise PerfettoArtifactError(f"GitHub Actions retention is invalid for {version['tag']}")
        if item.get("expired") is not False:
            raise PerfettoArtifactError(f"GitHub Actions artifact is already expired for {version['tag']}")
        artifacts.append(
            {
                "tag": version["tag"],
                "name": version["artifact_name"],
                "id": artifact_id,
                "url": f"https://github.com/{repository}/actions/runs/{run_id}/artifacts/{artifact_id}",
                "archive_download_url": item.get("archive_download_url"),
                "archive_bytes": archive_bytes,
                "digest": digest,
                "created_at": created_at,
                "expires_at": expires_at,
                "trace_file_count": version["trace_file_count"],
                "trace_bytes": version["trace_bytes"],
            }
        )

    manifest_bytes = manifest_path.stat().st_size
    manifest_sha = _sha256_file(manifest_path)
    return {
        "schema": REGISTRY_SCHEMA,
        "repository": repository,
        "source_commit": source_payload["source_commit"],
        "source_manifest": {
            "path": SOURCE_MANIFEST_RELATIVE.as_posix(),
            "bytes": manifest_bytes,
            "sha256": manifest_sha,
        },
        "workflow": {
            "path": WORKFLOW_PATH,
            "run_id": run_id,
            "run_attempt": run.get("run_attempt"),
            "url": run.get("html_url"),
            "workflow_head_sha": workflow_head_sha,
            "event": run.get("event"),
            "status": run.get("status"),
            "conclusion": run.get("conclusion"),
            "created_at": run.get("created_at"),
            "updated_at": run.get("updated_at"),
        },
        "retention_days": RETENTION_DAYS,
        "trace_file_count": source_payload["trace_file_count"],
        "trace_bytes": source_payload["trace_bytes"],
        "archive_bytes": sum(item["archive_bytes"] for item in artifacts),
        "artifacts": artifacts,
    }


def validate_registry(
    registry_path: Path,
    source_manifest_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_payload = _json_object(source_manifest_path.resolve())
    validate_source_manifest(source_payload)
    registry = _json_object(registry_path.resolve())
    if set(registry) != {
        "schema",
        "repository",
        "source_commit",
        "source_manifest",
        "workflow",
        "retention_days",
        "trace_file_count",
        "trace_bytes",
        "archive_bytes",
        "artifacts",
    }:
        raise PerfettoArtifactError("Perfetto Actions registry key set is invalid")
    if registry.get("schema") != REGISTRY_SCHEMA:
        raise PerfettoArtifactError("Perfetto Actions registry schema is unsupported")
    if (
        registry.get("repository") != source_payload["repository"]
        or registry.get("source_commit") != source_payload["source_commit"]
        or registry.get("retention_days") != RETENTION_DAYS
        or registry.get("trace_file_count") != source_payload["trace_file_count"]
        or registry.get("trace_bytes") != source_payload["trace_bytes"]
    ):
        raise PerfettoArtifactError("Perfetto Actions registry identity differs from its source manifest")
    source_record = registry.get("source_manifest")
    if not isinstance(source_record, dict) or set(source_record) != {"path", "bytes", "sha256"}:
        raise PerfettoArtifactError("Perfetto Actions source-manifest binding is invalid")
    if (
        source_record.get("path") != SOURCE_MANIFEST_RELATIVE.as_posix()
        or source_record.get("bytes") != source_manifest_path.stat().st_size
        or source_record.get("sha256") != _sha256_file(source_manifest_path)
    ):
        raise PerfettoArtifactError("Perfetto Actions source-manifest bytes or SHA-256 differ")
    workflow = registry.get("workflow")
    if not isinstance(workflow, dict) or set(workflow) != {
        "path",
        "run_id",
        "run_attempt",
        "url",
        "workflow_head_sha",
        "event",
        "status",
        "conclusion",
        "created_at",
        "updated_at",
    }:
        raise PerfettoArtifactError("Perfetto Actions workflow record is invalid")
    if (
        workflow.get("path") != WORKFLOW_PATH
        or workflow.get("event") != "workflow_dispatch"
        or workflow.get("status") != "completed"
        or workflow.get("conclusion") != "success"
        or isinstance(workflow.get("run_id"), bool)
        or not isinstance(workflow.get("run_id"), int)
        or workflow["run_id"] <= 0
        or isinstance(workflow.get("run_attempt"), bool)
        or not isinstance(workflow.get("run_attempt"), int)
        or workflow["run_attempt"] <= 0
        or not isinstance(workflow.get("workflow_head_sha"), str)
        or not HEX_40_RE.fullmatch(workflow["workflow_head_sha"])
    ):
        raise PerfettoArtifactError("Perfetto Actions workflow result is not successful or exact")
    for field in ("created_at", "updated_at"):
        _parse_utc(workflow.get(field), f"workflow.{field}")
    run_id = workflow["run_id"]
    if workflow.get("url") != f"https://github.com/{registry['repository']}/actions/runs/{run_id}":
        raise PerfettoArtifactError("Perfetto Actions workflow URL is not canonical")

    artifacts = registry.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != len(source_payload["versions"]):
        raise PerfettoArtifactError("Perfetto Actions artifact count differs from source versions")
    expected_by_tag = {version["tag"]: version for version in source_payload["versions"]}
    seen_tags: list[str] = []
    archive_total = 0
    for artifact in artifacts:
        if not isinstance(artifact, dict) or set(artifact) != {
            "tag",
            "name",
            "id",
            "url",
            "archive_download_url",
            "archive_bytes",
            "digest",
            "created_at",
            "expires_at",
            "trace_file_count",
            "trace_bytes",
        }:
            raise PerfettoArtifactError("Perfetto Actions artifact record key set is invalid")
        tag = artifact.get("tag")
        if not isinstance(tag, str) or tag not in expected_by_tag or tag in seen_tags:
            raise PerfettoArtifactError("Perfetto Actions artifact tag is invalid or duplicated")
        seen_tags.append(tag)
        expected = expected_by_tag[tag]
        artifact_id = artifact.get("id")
        archive_bytes = artifact.get("archive_bytes")
        if (
            artifact.get("name") != expected["artifact_name"]
            or artifact.get("trace_file_count") != expected["trace_file_count"]
            or artifact.get("trace_bytes") != expected["trace_bytes"]
            or isinstance(artifact_id, bool)
            or not isinstance(artifact_id, int)
            or artifact_id <= 0
            or isinstance(archive_bytes, bool)
            or not isinstance(archive_bytes, int)
            or archive_bytes <= 0
            or not isinstance(artifact.get("digest"), str)
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", artifact["digest"])
        ):
            raise PerfettoArtifactError(f"Perfetto Actions artifact identity is invalid for {tag}")
        expected_url = (
            f"https://github.com/{registry['repository']}/actions/runs/{run_id}/artifacts/{artifact_id}"
        )
        expected_download = (
            f"https://api.github.com/repos/{registry['repository']}/actions/artifacts/"
            f"{artifact_id}/zip"
        )
        if artifact.get("url") != expected_url or artifact.get("archive_download_url") != expected_download:
            raise PerfettoArtifactError(f"Perfetto Actions artifact URLs are not canonical for {tag}")
        created = _parse_utc(artifact.get("created_at"), f"{tag}.created_at")
        expires = _parse_utc(artifact.get("expires_at"), f"{tag}.expires_at")
        if expires <= created or (expires - created).total_seconds() > (RETENTION_DAYS + 1) * 86400:
            raise PerfettoArtifactError(f"Perfetto Actions artifact retention is invalid for {tag}")
        archive_total += archive_bytes
    if seen_tags != [version["tag"] for version in source_payload["versions"]]:
        raise PerfettoArtifactError("Perfetto Actions artifacts are not in source-manifest order")
    if registry.get("archive_bytes") != archive_total:
        raise PerfettoArtifactError("Perfetto Actions aggregate archive bytes do not match artifacts")
    return registry, source_payload


def archived_trace_records(
    registry_path: Path,
    source_manifest_path: Path,
    tag: str,
) -> dict[str, dict[str, Any]]:
    """Return evidence-relative trace records for one externally archived tag."""

    _, source_payload = validate_registry(registry_path, source_manifest_path)
    for version in source_payload["versions"]:
        if version["tag"] != tag:
            continue
        prefix = f"android/release-evidence/{tag}/"
        return {
            record["path"][len(prefix) :]: {
                "bytes": record["bytes"],
                "sha256": record["sha256"],
            }
            for record in version["files"]
        }
    return {}


def active_artifact_for_tag(
    registry_path: Path,
    source_manifest_path: Path,
    tag: str,
    *,
    now: datetime | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    registry, source_payload = validate_registry(registry_path, source_manifest_path)
    _version_key(tag)
    artifact = next(
        (record for record in registry["artifacts"] if record["tag"] == tag),
        None,
    )
    if artifact is None:
        raise PerfettoArtifactError(f"No registered Perfetto Actions artifact exists for {tag}")
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if _parse_utc(artifact["expires_at"], f"{tag}.expires_at") <= current:
        raise PerfettoArtifactError(f"The registered Perfetto Actions artifact for {tag} has expired")
    return registry, artifact


def _resolve(repo_root: Path, candidate: Path | None, default: PurePosixPath) -> Path:
    if candidate is None:
        return _repo_path(repo_root.resolve(), default)
    return candidate.resolve() if candidate.is_absolute() else (repo_root / candidate).resolve()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create-source-manifest")
    create.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    create.add_argument("--output", type=Path)

    verify = subparsers.add_parser("verify-source")
    verify.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    verify.add_argument("--manifest", type=Path)
    verify.add_argument("--expected-source-commit")

    downloads = subparsers.add_parser("verify-downloads")
    downloads.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    downloads.add_argument("--manifest", type=Path)
    downloads.add_argument("--download-root", type=Path, required=True)

    registry = subparsers.add_parser("create-registry")
    registry.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    registry.add_argument("--manifest", type=Path)
    registry.add_argument("--repository", default=DEFAULT_REPOSITORY)
    registry.add_argument("--run-id", type=int, required=True)
    registry.add_argument("--output", type=Path)

    verify_registry_parser = subparsers.add_parser("verify-registry")
    verify_registry_parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    verify_registry_parser.add_argument("--manifest", type=Path)
    verify_registry_parser.add_argument("--registry", type=Path)

    resolve_artifact_parser = subparsers.add_parser("resolve-active-artifact")
    resolve_artifact_parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    resolve_artifact_parser.add_argument("--manifest", type=Path)
    resolve_artifact_parser.add_argument("--registry", type=Path)
    resolve_artifact_parser.add_argument("--tag", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    repo_root = args.repo_root.resolve()
    manifest_path = _resolve(repo_root, getattr(args, "manifest", None), SOURCE_MANIFEST_RELATIVE)
    try:
        if args.command == "create-source-manifest":
            output = _resolve(repo_root, args.output, SOURCE_MANIFEST_RELATIVE)
            payload = create_source_manifest(repo_root)
            _write_json(output, payload)
            print(f"wrote={output}")
            print(f"sourceCommit={payload['source_commit']}")
            print(f"traceFiles={payload['trace_file_count']}")
            print(f"traceBytes={payload['trace_bytes']}")
        elif args.command == "verify-source":
            verify_source(repo_root, manifest_path, args.expected_source_commit)
            payload = _json_object(manifest_path)
            print(f"verifiedSource={payload['source_commit']}")
            print(f"traceFiles={payload['trace_file_count']}")
            print(f"traceBytes={payload['trace_bytes']}")
        elif args.command == "verify-downloads":
            files, size = verify_downloads(args.download_root, manifest_path)
            print(f"verifiedDownloadedFiles={files}")
            print(f"verifiedDownloadedBytes={size}")
        elif args.command == "create-registry":
            output = _resolve(repo_root, args.output, REGISTRY_RELATIVE)
            payload = create_registry(
                repo_root, manifest_path, args.repository, args.run_id
            )
            _write_json(output, payload)
            print(f"wrote={output}")
            print(f"workflowRun={payload['workflow']['url']}")
            print(f"artifacts={len(payload['artifacts'])}")
            print(f"archiveBytes={payload['archive_bytes']}")
        elif args.command == "verify-registry":
            registry_path = _resolve(repo_root, args.registry, REGISTRY_RELATIVE)
            registry, _ = validate_registry(registry_path, manifest_path)
            print(f"verifiedRegistry={registry_path}")
            print(f"workflowRun={registry['workflow']['url']}")
            print(f"artifacts={len(registry['artifacts'])}")
        elif args.command == "resolve-active-artifact":
            registry_path = _resolve(repo_root, args.registry, REGISTRY_RELATIVE)
            registry, artifact = active_artifact_for_tag(
                registry_path, manifest_path, args.tag
            )
            print(f"artifactTag={artifact['tag']}")
            print(f"workflowRunId={registry['workflow']['run_id']}")
            print(f"artifactId={artifact['id']}")
            print(f"artifactName={artifact['name']}")
            print(f"artifactDigest={artifact['digest']}")
            print(f"artifactExpiresAt={artifact['expires_at']}")
        else:  # pragma: no cover - argparse owns command selection
            raise PerfettoArtifactError(f"Unsupported command: {args.command}")
        return 0
    except PerfettoArtifactError as exc:
        print(f"Perfetto artifact evidence rejected: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
