from __future__ import annotations

import base64
import copy
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from scripts import android_perfetto_release_artifact as release


TAG = "v0.13.154"
ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def captured(tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    subprocess.run(["git", "init", str(root)], check=True, capture_output=True)
    external = tmp_path / "trace-input"
    performance = root / "android/release-evidence" / TAG / "performance"
    performance.mkdir(parents=True)
    for profile in release.PROFILES:
        records = []
        for iteration in range(1, 6):
            name = f"{profile}.traces/iteration-{iteration:03d}.perfetto-trace"
            data = f"synthetic test bytes {profile} {iteration}".encode()
            path = external / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            records.append({
                "iteration": iteration, "path": f"performance/{name}",
                "source_name": f"raw-{profile}-{iteration}.perfetto-trace",
                "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            })
        (performance / f"{profile}.json").write_text(json.dumps({
            "schema": "hermes-android-performance-evidence-v2", "profile": profile,
            "version_name": TAG[1:], "release_source_digest": "a" * 64,
            "evidence_run_id": "v0.13.154-synthetic-test-capture", "traces": records,
        }), encoding="utf-8")
    return root, external


@pytest.mark.parametrize("mutation", ["none", "bytes", "missing", "extra", "hidden", "directory", "binding", "profile", "run", "digest", "traversal", "tracked", pytest.param("link", marks=pytest.mark.linux_only)])
def test_real_cli_round_trip_rejects_nonexact_trace_inputs(captured, mutation):
    root, external = captured
    command = [sys.executable, str(ROOT / "scripts/android_perfetto_release_artifact.py")]
    created = subprocess.run(command + ["create-source", "--repo-root", str(root), "--tag", TAG,
                                      "--trace-root", str(external)], capture_output=True, text=True)
    assert created.returncode == 0, created.stderr
    source = json.loads(created.stdout)
    assert source["trace_bytes"] == sum(p.stat().st_size for p in external.rglob("*.perfetto-trace"))
    first = external / source["traces"][0]["path"]
    def replace_with_link():
        first.unlink()
        first.symlink_to(external / source["traces"][1]["path"])

    file_mutations = {
        "bytes": lambda: first.write_bytes(b"x" * first.stat().st_size),
        "missing": first.unlink,
        "extra": lambda: (external / "unexpected.txt").write_bytes(b"not a trace"),
        "hidden": lambda: (external / ".secret").write_bytes(b"not a trace"),
        "directory": lambda: (external / "unlisted-empty-directory").mkdir(),
        "link": replace_with_link,
    }
    if mutation in file_mutations:
        file_mutations[mutation]()
    if mutation in {"binding", "traversal"}:
        source["traces"][0]["path"] = "../../escape" if mutation == "traversal" else "tablet.traces/iteration-001.perfetto-trace"
        release.archive._write_json(release.metadata_path(root, TAG, "source.json"), source)
    elif mutation in {"profile", "run", "digest"}:
        record = root / "android/release-evidence" / TAG / "performance/tablet.json"
        payload = json.loads(record.read_text(encoding="utf-8"))
        key = {"profile": "profile", "run": "evidence_run_id", "digest": "release_source_digest"}[mutation]
        payload[key] = "b" * 64
        release.archive._write_json(record, payload)
    elif mutation == "tracked":
        tracked = root / "android/release-evidence" / TAG / "performance" / source["traces"][0]["path"]
        tracked.parent.mkdir(parents=True, exist_ok=True)
        tracked.write_bytes(first.read_bytes())
        subprocess.run(["git", "add", "."], cwd=root, check=True)
    operation = "create-source" if mutation == "tracked" else "verify-traces"
    checked = subprocess.run(command + [operation, "--repo-root", str(root), "--tag", TAG,
                                      "--trace-root", str(external)], capture_output=True, text=True)
    assert (checked.returncode == 0) == (mutation == "none"), checked.stdout + checked.stderr


@pytest.mark.parametrize("mutation", ["none", "in_progress", "failed", "foreign_repo", "wrong_workflow", "foreign_run", "wrong_head", "wrong_name", "expired", "short_retention", "bad_digest", "wrong_remote_manifest", "wrong_remote_tool"])
def test_receipt_binds_live_successful_workflow_and_immutable_download(captured, monkeypatch, mutation):
    root, external = captured
    source = release.create_source(root, TAG, external)
    now = datetime.now(timezone.utc)
    repository = {"id": 7, "full_name": source["repository"]}
    run = {
        "id": 11, "run_attempt": 1, "path": release.WORKFLOW_PATH,
        "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
        "head_sha": "b" * 40, "repository": repository, "head_repository": repository,
    }
    artifact = {
        "id": 13, "size_in_bytes": 1234, "name": source["artifact_name"],
        "digest": "sha256:" + "c" * 64, "expired": False,
        "created_at": (now - timedelta(hours=1)).isoformat(),
        "expires_at": (now + timedelta(days=90, hours=-1)).isoformat(),
        "workflow_run": {"id": 11, "head_sha": run["head_sha"], "repository_id": 7, "head_repository_id": 7},
    }
    for path in (release.WORKFLOW_PATH, "scripts/android_perfetto_release_artifact.py", "scripts/android_perfetto_artifacts.py"):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("synthetic trusted tooling fixture\n", encoding="utf-8")

    def api(arguments, *, cwd):
        endpoint = arguments[0]
        assert cwd == root
        if endpoint.endswith("/actions/runs/11"):
            return copy.deepcopy(run)
        if endpoint.endswith("/actions/artifacts/13"):
            return copy.deepcopy(artifact)
        path = endpoint.split("/contents/", 1)[1].split("?ref=", 1)[0]
        data = (root / path).read_bytes()
        if mutation == "wrong_remote_manifest" and path.endswith("source.json"):
            data += b"\n"
        if mutation == "wrong_remote_tool" and path == release.WORKFLOW_PATH:
            data += b"altered workflow"
        return {"type": "file", "encoding": "base64", "content": base64.b64encode(data).decode()}

    monkeypatch.setattr(release.archive, "_gh_json", api)
    changes = {
        "in_progress": (run, "status", "in_progress"),
        "failed": (run, "conclusion", "failure"),
        "foreign_repo": (run, "head_repository", {"id": 99, "full_name": "foreign/fork"}),
        "wrong_workflow": (run, "path", ".github/workflows/untrusted.yml"),
        "foreign_run": (artifact["workflow_run"], "id", 12),
        "wrong_head": (artifact["workflow_run"], "head_sha", "d" * 40),
        "wrong_name": (artifact, "name", "another capture"),
        "expired": (artifact, "expired", True),
        "short_retention": (artifact, "expires_at", (now + timedelta(days=1)).isoformat()),
        "bad_digest": (artifact, "digest", "unverified"),
    }
    if mutation in changes:
        target, key, value = changes[mutation]
        target[key] = value
    if mutation != "none":
        with pytest.raises(release.Error):
            release.live_receipt(root, TAG, 11, 13)
        return
    receipt = release.live_receipt(root, TAG, 11, 13)
    release.archive._write_json(release.metadata_path(root, TAG, "receipt.json"), receipt)
    assert release.verify_receipt(root, TAG) == receipt
    artifact["digest"] = "sha256:" + "d" * 64
    with pytest.raises(release.Error, match="differs from current"):
        release.verify_receipt(root, TAG)
