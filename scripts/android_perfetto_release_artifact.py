#!/usr/bin/env python3
"""Bind new, never-tracked release traces to one verified Actions artifact.

The historical archive helper owns old Git-backed traces. This producer keeps
new trace bytes external, and requires a successful independent cloud round trip
before issuing a receipt. The normal release validator still checks trace data.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

if __package__:
    from . import android_perfetto_artifacts as archive
else:
    import android_perfetto_artifacts as archive


SOURCE_SCHEMA = "hermes-android-perfetto-release-source-v1"
RECEIPT_SCHEMA = "hermes-android-perfetto-release-receipt-v1"
WORKFLOW_PATH = ".github/workflows/android-perfetto-release.yml"
PROFILES = ("phone-compact", "tablet")
Error = archive.PerfettoArtifactError


def metadata_path(root: Path, tag: str, name: str) -> Path:
    if not archive.TAG_RE.fullmatch(tag):
        raise Error("Release tag is not canonical")
    return root / "android/release-evidence/perfetto-artifacts" / tag / name


def _performance_source(root: Path, tag: str) -> dict[str, Any]:
    metadata_path(root, tag, "source.json")
    bindings = archive._trace_bindings_from_performance(root, tag)
    performance = root / "android/release-evidence" / tag / "performance"
    identities = []
    records = []
    for profile in PROFILES:
        payload = archive._json_object(performance / f"{profile}.json")
        identities.append((payload.get("release_source_digest"), payload.get("evidence_run_id")))
        if (
            payload.get("version_name") != tag.removeprefix("v")
            or payload.get("profile") != profile
            or payload.get("schema") != "hermes-android-performance-evidence-v2"
        ):
            raise Error(f"{profile} performance evidence has another release/profile identity")
        if not 5 <= len(payload["traces"]) <= 20:
            raise Error(f"{profile} requires 5 to 20 measured iterations")
    if identities[0] != identities[1]:
        raise Error("Phone and tablet evidence have different source/run identities")
    digest, run_id = identities[0]
    if not isinstance(digest, str) or not archive.HEX_64_RE.fullmatch(digest):
        raise Error("Performance source digest is invalid")
    if not isinstance(run_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{15,79}", run_id):
        raise Error("Performance evidence run ID is invalid")
    prefix = PurePosixPath("android/release-evidence") / tag / "performance"
    for path, record in sorted(bindings.items()):
        records.append({**record, "path": PurePosixPath(path).relative_to(prefix).as_posix()})
    return {
        "schema": SOURCE_SCHEMA,
        "repository": archive.DEFAULT_REPOSITORY,
        "tag": tag,
        "source_digest": digest,
        "evidence_run_id": run_id,
        "artifact_name": f"hermes-android-perfetto-{tag}-{digest}",
        "trace_file_count": len(records),
        "trace_bytes": sum(record["bytes"] for record in records),
        "traces": records,
    }


def verify_source(root: Path, tag: str, expected_digest: str | None = None) -> dict[str, Any]:
    source = archive._json_object(metadata_path(root, tag, "source.json"))
    if source != _performance_source(root, tag):
        raise Error("Source manifest differs from the measured performance bindings")
    if expected_digest is not None and source["source_digest"] != expected_digest:
        raise Error("Trace source differs from the release source digest")
    return source


def verify_traces(source: dict[str, Any], trace_root: Path) -> None:
    # Refuse links before resolving anything: the upload must contain only the
    # closed, public trace inventory, never a linked workspace or hidden file.
    if trace_root.is_symlink() or not trace_root.is_dir():
        raise Error("Trace root is missing or linked")
    expected = {record["path"] for record in source["traces"]}
    allowed_directories = {f"{profile}.traces" for profile in PROFILES}
    observed = set()
    for path in trace_root.rglob("*"):
        relative = path.relative_to(trace_root).as_posix()
        if path.is_symlink():
            raise Error(f"Trace archive contains a link: {relative}")
        if path.is_dir() and relative in allowed_directories:
            continue
        if not path.is_file() or relative not in expected:
            raise Error(f"Trace archive contains an unexpected path: {relative}")
        observed.add(relative)
    if observed != expected:
        raise Error(f"Trace archive is missing files: {sorted(expected - observed)}")
    for record in source["traces"]:
        archive._validate_trace_file(trace_root / record["path"], record)


def create_source(root: Path, tag: str, trace_root: Path) -> dict[str, Any]:
    source = _performance_source(root, tag)
    verify_traces(source, trace_root)
    tracked = archive._tracked_trace_paths(root)
    if any(path.startswith(f"android/release-evidence/{tag}/") for path in tracked):
        raise Error("New release traces must not be tracked in Git")
    archive._write_json(metadata_path(root, tag, "source.json"), source)
    return source


def _positive_id(value: Any, context: str) -> int:
    if type(value) is not int or value <= 0:
        raise Error(f"{context} must be a positive integer")
    return value


def receipt_from_api(
    source: dict[str, Any], manifest_sha: str, run: dict[str, Any],
    artifact: dict[str, Any], *, now: datetime,
) -> dict[str, Any]:
    """Validate authority before persisting any API data as release metadata."""
    repository = source["repository"]
    run_id = _positive_id(run.get("id"), "Workflow run ID")
    attempt = _positive_id(run.get("run_attempt"), "Workflow run attempt")
    head = run.get("head_sha")
    if (
        run.get("path") != WORKFLOW_PATH
        or run.get("event") != "workflow_dispatch"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("repository", {}).get("full_name") != repository
        or run.get("head_repository", {}).get("full_name") != repository
        or not isinstance(head, str) or not archive.HEX_40_RE.fullmatch(head)
    ):
        raise Error("Upload and independent round-trip workflow is not a successful trusted dispatch")
    artifact_id = _positive_id(artifact.get("id"), "Artifact ID")
    size = _positive_id(artifact.get("size_in_bytes"), "Archive bytes")
    digest = artifact.get("digest")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise Error("Artifact archive digest is invalid")
    parent = artifact.get("workflow_run", {})
    if (
        artifact.get("name") != source["artifact_name"]
        or parent.get("id") != run_id or parent.get("head_sha") != head
        or parent.get("repository_id") != run["repository"].get("id")
        or parent.get("head_repository_id") != run["repository"].get("id")
    ):
        raise Error("Artifact does not belong to the exact source and workflow run")
    created = archive._parse_utc(artifact.get("created_at"), "Artifact created_at")
    expires = archive._parse_utc(artifact.get("expires_at"), "Artifact expires_at")
    archive._require_ninety_day_retention(created, expires, "Release traces")
    if artifact.get("expired") is not False or not created <= now < expires:
        raise Error("Artifact is expired or has invalid creation time")
    return {
        "schema": RECEIPT_SCHEMA,
        "repository": repository,
        "tag": source["tag"],
        "source_manifest_sha256": manifest_sha,
        "workflow_head_sha": head,
        "workflow_run_id": run_id,
        "workflow_run_attempt": attempt,
        "workflow_url": f"https://github.com/{repository}/actions/runs/{run_id}",
        "artifact_id": artifact_id,
        "artifact_name": artifact["name"],
        "artifact_digest": digest,
        "artifact_archive_bytes": size,
        "artifact_created_at": artifact["created_at"],
        "artifact_expires_at": artifact["expires_at"],
        "retention_days": archive.RETENTION_DAYS,
    }


def live_receipt(root: Path, tag: str, run_id: int, artifact_id: int) -> dict[str, Any]:
    source = verify_source(root, tag)
    _positive_id(run_id, "Workflow run ID")
    _positive_id(artifact_id, "Artifact ID")
    repository = source["repository"]
    run = archive._gh_json((f"repos/{repository}/actions/runs/{run_id}",), cwd=root)
    artifact = archive._gh_json((f"repos/{repository}/actions/artifacts/{artifact_id}",), cwd=root)
    manifest = metadata_path(root, tag, "source.json")
    receipt = receipt_from_api(
        source, archive._sha256_file(manifest), run, artifact, now=datetime.now(timezone.utc)
    )
    if receipt["workflow_run_id"] != run_id or receipt["artifact_id"] != artifact_id:
        raise Error("GitHub returned a different run or artifact ID")
    # Bind the upload's checked-out manifest and verifier to the release tree.
    # Evidence-only commits may follow without changing these source bytes.
    for path in (
        manifest.relative_to(root).as_posix(), WORKFLOW_PATH,
        "scripts/android_perfetto_release_artifact.py", "scripts/android_perfetto_artifacts.py",
    ):
        response = archive._gh_json(
            (f"repos/{repository}/contents/{path}?ref={receipt['workflow_head_sha']}",), cwd=root
        )
        if response.get("encoding") != "base64" or response.get("type") != "file":
            raise Error(f"Upload source is not an ordinary GitHub file: {path}")
        try:
            remote_bytes = base64.b64decode(response["content"].replace("\n", ""), validate=True)
        except (KeyError, ValueError, TypeError) as exc:
            raise Error(f"Upload source content is invalid: {path}") from exc
        if hashlib.sha256(remote_bytes).hexdigest() != archive._sha256_file(root / path):
            raise Error(f"Upload source differs from release source: {path}")
    return receipt


def verify_receipt(root: Path, tag: str) -> dict[str, Any]:
    stored = archive._json_object(metadata_path(root, tag, "receipt.json"))
    live = live_receipt(root, tag, stored.get("workflow_run_id"), stored.get("artifact_id"))
    if stored != live:
        raise Error("Committed receipt differs from current GitHub Actions authority")
    return live


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create-source", "verify-source", "verify-traces", "create-receipt", "verify-receipt"))
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--trace-root", type=Path)
    parser.add_argument("--expected-source-digest")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--artifact-id", type=int)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    try:
        if args.command == "create-source":
            if args.trace_root is None:
                parser.error("create-source requires --trace-root")
            result = create_source(root, args.tag, args.trace_root)
        elif args.command == "create-receipt":
            result = live_receipt(root, args.tag, args.run_id, args.artifact_id)
            archive._write_json(metadata_path(root, args.tag, "receipt.json"), result)
        else:
            result = verify_source(root, args.tag, args.expected_source_digest)
            if args.command == "verify-traces":
                if args.trace_root is None:
                    parser.error("verify-traces requires --trace-root")
                verify_traces(result, args.trace_root)
            elif args.command == "verify-receipt":
                result = verify_receipt(root, args.tag)
        print(json.dumps(result, sort_keys=True))
    except (Error, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
