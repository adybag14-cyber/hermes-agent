from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_module():
    script = REPO_ROOT / "scripts/android_perfetto_artifacts.py"
    spec = importlib.util.spec_from_file_location("android_perfetto_artifacts", script)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def perfetto_module():
    return _load_module()


def _git(root: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def _write_profile(root: Path, tag: str, profile: str, contents: bytes) -> None:
    performance = root / "android" / "release-evidence" / tag / "performance"
    trace_directory = performance / f"{profile}.traces"
    trace_directory.mkdir(parents=True, exist_ok=True)
    trace = trace_directory / "iteration-001.perfetto-trace"
    trace.write_bytes(contents)
    payload = {
        "traces": [
            {
                "iteration": 1,
                "path": f"performance/{profile}.traces/iteration-001.perfetto-trace",
                "source_name": f"{profile}-source.perfetto-trace",
                "bytes": len(contents),
                "sha256": hashlib.sha256(contents).hexdigest(),
            }
        ]
    }
    (performance / f"{profile}.json").write_text(json.dumps(payload), encoding="utf-8")


def _write_release_manifest(root: Path, tag: str) -> None:
    evidence_files = []
    performance = root / "android" / "release-evidence" / tag / "performance"
    for normalized in sorted(performance.glob("*.json")):
        payload = json.loads(normalized.read_text(encoding="utf-8"))
        for trace in payload["traces"]:
            evidence_files.append(
                {
                    "path": trace["path"],
                    "bytes": trace["bytes"],
                    "sha256": trace["sha256"],
                }
            )
    (performance.parent / "manifest.json").write_text(
        json.dumps({"evidence": {"files": evidence_files}}), encoding="utf-8"
    )


def test_create_source_manifest_covers_every_tracked_trace(perfetto_module, tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    _git(root, "init")
    _git(root, "config", "user.name", "Perfetto Test")
    _git(root, "config", "user.email", "perfetto@example.invalid")
    _write_profile(root, "v0.13.147", "phone-compact", b"phone trace")
    _write_profile(root, "v0.13.147", "tablet", b"tablet trace")
    _write_release_manifest(root, "v0.13.147")
    _git(root, "add", ".")
    _git(root, "commit", "-m", "fixture")

    manifest = perfetto_module.create_source_manifest(root)
    records = perfetto_module.validate_source_manifest(manifest)

    assert manifest["source_commit"] == _git(root, "rev-parse", "HEAD")
    assert manifest["trace_file_count"] == 2
    assert manifest["trace_bytes"] == len(b"phone trace") + len(b"tablet trace")
    assert len(records) == 2
    assert manifest["versions"][0]["artifact_name"].endswith(manifest["source_commit"])


def test_committed_source_manifest_is_the_complete_historical_inventory(perfetto_module):
    path = (
        REPO_ROOT
        / "android"
        / "release-evidence"
        / "perfetto-artifacts"
        / "source-manifest.json"
    )
    payload = json.loads(path.read_text(encoding="utf-8"))
    records = perfetto_module.validate_source_manifest(payload)

    assert payload["trace_file_count"] == 70
    assert payload["trace_bytes"] == 3_250_743_669
    assert {version["tag"]: version["trace_bytes"] for version in payload["versions"]} == {
        "v0.13.147": 418_599_397,
        "v0.13.148": 451_716_589,
        "v0.13.149": 419_533_763,
        "v0.13.150": 493_802_147,
        "v0.13.151": 504_405_243,
        "v0.13.152": 527_170_123,
        "v0.13.153": 435_516_407,
    }
    inventory = "\n".join(
        f"{record['path']}|{record['bytes']}|{record['sha256']}"
        for record in (records[path] for path in sorted(records))
    )
    assert hashlib.sha256(inventory.encode("utf-8")).hexdigest() == (
        "35d603ca2f3af4b102ed1c0d63bd005bcb7381bef03a66e489b25f472b721338"
    )


def _source_manifest(perfetto_module, files_by_tag: dict[str, list[tuple[str, bytes]]]):
    source_commit = "1" * 40
    versions = []
    for tag in sorted(files_by_tag):
        files = [
            {
                "path": f"android/release-evidence/{tag}/performance/{relative}",
                "bytes": len(contents),
                "sha256": hashlib.sha256(contents).hexdigest(),
            }
            for relative, contents in files_by_tag[tag]
        ]
        versions.append(
            {
                "tag": tag,
                "artifact_name": perfetto_module.artifact_name(tag, source_commit),
                "trace_file_count": len(files),
                "trace_bytes": sum(record["bytes"] for record in files),
                "files": files,
            }
        )
    return {
        "schema": perfetto_module.SOURCE_MANIFEST_SCHEMA,
        "repository": perfetto_module.DEFAULT_REPOSITORY,
        "source_commit": source_commit,
        "trace_file_count": sum(version["trace_file_count"] for version in versions),
        "trace_bytes": sum(version["trace_bytes"] for version in versions),
        "versions": versions,
    }


def test_download_verifier_hashes_a_closed_artifact_layout(perfetto_module, tmp_path):
    files_by_tag = {
        "v0.13.147": [
            ("phone-compact.traces/iteration-001.perfetto-trace", b"phone"),
            ("tablet.traces/iteration-001.perfetto-trace", b"tablet"),
        ],
        "v0.13.148": [
            ("phone-compact.traces/iteration-001.perfetto-trace", b"new phone"),
            ("tablet.traces/iteration-001.perfetto-trace", b"new tablet"),
        ],
    }
    manifest = _source_manifest(perfetto_module, files_by_tag)
    manifest_path = tmp_path / "source-manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    downloads = tmp_path / "downloads"
    downloads.mkdir()
    for version in manifest["versions"]:
        artifact = downloads / version["artifact_name"]
        for relative, contents in files_by_tag[version["tag"]]:
            path = artifact / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(contents)

    assert perfetto_module.verify_downloads(downloads, manifest_path) == (
        manifest["trace_file_count"],
        manifest["trace_bytes"],
    )
    tampered = (
        downloads
        / manifest["versions"][0]["artifact_name"]
        / "phone-compact.traces"
        / "iteration-001.perfetto-trace"
    )
    tampered.write_bytes(b"tampered")
    with pytest.raises(perfetto_module.PerfettoArtifactError, match="byte count differs"):
        perfetto_module.verify_downloads(downloads, manifest_path)


def test_registry_is_cryptographically_bound_to_the_source_manifest(
    perfetto_module, tmp_path
):
    manifest = _source_manifest(
        perfetto_module,
        {
            "v0.13.147": [
                ("phone-compact.traces/iteration-001.perfetto-trace", b"trace")
            ]
        },
    )
    manifest_path = tmp_path / "source-manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    run_id = 123456
    artifact_id = 987654
    version = manifest["versions"][0]
    registry = {
        "schema": perfetto_module.REGISTRY_SCHEMA,
        "repository": manifest["repository"],
        "source_commit": manifest["source_commit"],
        "source_manifest": {
            "path": perfetto_module.SOURCE_MANIFEST_RELATIVE.as_posix(),
            "bytes": manifest_path.stat().st_size,
            "sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        },
        "workflow": {
            "path": perfetto_module.WORKFLOW_PATH,
            "run_id": run_id,
            "run_attempt": 1,
            "url": f"https://github.com/{manifest['repository']}/actions/runs/{run_id}",
            "workflow_head_sha": "2" * 40,
            "event": "workflow_dispatch",
            "status": "completed",
            "conclusion": "success",
            "created_at": "2026-09-02T10:00:00Z",
            "updated_at": "2026-09-02T10:30:00Z",
        },
        "retention_days": 90,
        "trace_file_count": manifest["trace_file_count"],
        "trace_bytes": manifest["trace_bytes"],
        "archive_bytes": 1234,
        "artifacts": [
            {
                "tag": version["tag"],
                "name": version["artifact_name"],
                "id": artifact_id,
                "url": (
                    f"https://github.com/{manifest['repository']}/actions/runs/{run_id}/"
                    f"artifacts/{artifact_id}"
                ),
                "archive_download_url": (
                    f"https://api.github.com/repos/{manifest['repository']}/actions/artifacts/"
                    f"{artifact_id}/zip"
                ),
                "archive_bytes": 1234,
                "digest": "sha256:" + "3" * 64,
                "created_at": "2026-09-02T10:05:00Z",
                "expires_at": "2026-12-01T10:05:00Z",
                "trace_file_count": version["trace_file_count"],
                "trace_bytes": version["trace_bytes"],
            }
        ],
    }
    registry_path = tmp_path / "registry.json"
    registry_path.write_text(json.dumps(registry), encoding="utf-8")

    perfetto_module.validate_registry(registry_path, manifest_path)
    registry["artifacts"][0]["digest"] = "sha256:" + "4" * 63
    registry_path.write_text(json.dumps(registry), encoding="utf-8")
    with pytest.raises(perfetto_module.PerfettoArtifactError, match="identity is invalid"):
        perfetto_module.validate_registry(registry_path, manifest_path)


def test_archive_workflow_is_read_only_pinned_and_round_trip_gated():
    workflow = (
        REPO_ROOT / ".github" / "workflows" / "android-perfetto-artifacts.yml"
    ).read_text(encoding="utf-8")
    action_refs = re.findall(r"^\s*-?\s*uses:\s*([^\s#]+)", workflow, flags=re.MULTILINE)
    assert action_refs
    assert all(re.fullmatch(r"[^@]+@[0-9a-f]{40}", ref) for ref in action_refs)
    assert workflow.count("actions/upload-artifact@") == 7
    assert workflow.count("actions/download-artifact@") == 7
    assert workflow.count("retention-days: 90") == 7
    assert workflow.count("compression-level: 6") == 7
    assert workflow.count("if-no-files-found: error") == 7
    assert "actions: read" in workflow
    assert "contents: read" in workflow
    assert "contents: write" not in workflow
    assert "persist-credentials: false" in workflow
    assert "^[0-9a-f]{40}$" in workflow
    assert "--expected-source-commit \"$SOURCE_COMMIT\"" in workflow
    assert workflow.index("verify-source") < workflow.index("actions/upload-artifact@")
    assert workflow.rindex("actions/download-artifact@") < workflow.index("verify-downloads")
    assert "artifact-ids:" in workflow
    assert "digest-mismatch: error" in workflow
    assert "gh release" not in workflow
    assert "git push" not in workflow
    assert "secrets." not in workflow
