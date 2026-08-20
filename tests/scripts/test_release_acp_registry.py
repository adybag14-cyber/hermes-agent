"""Tests for the ACP Registry version-lockstep bump in scripts/release.py.

The official ACP Registry manifest must match ``pyproject.toml`` exactly —
``tests/acp/test_registry_manifest.py`` enforces this at lint time, and the
upstream registry CI rejects ``@latest`` / floating pins. The release script
is the single place that bumps the manifest in lockstep with pyproject; if
that bump ever silently breaks, weekly releases fail the manifest test
until someone hand-edits the JSON.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock


def _load_release_module(monkeypatch, tmp_root: Path):
    """Import scripts/release.py with REPO_ROOT pinned to a temp tree."""
    spec = importlib.util.spec_from_file_location(
        "_release_under_test",
        Path(__file__).resolve().parents[2] / "scripts" / "release.py",
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    monkeypatch.setattr(module, "REPO_ROOT", tmp_root)
    monkeypatch.setattr(
        module, "ACP_REGISTRY_MANIFEST", tmp_root / "acp_registry" / "agent.json"
    )
    return module


def _write_manifest(root: Path, version: str) -> None:
    manifest_dir = root / "acp_registry"
    manifest_dir.mkdir(parents=True)
    (manifest_dir / "agent.json").write_text(
        json.dumps(
            {
                "id": "hermes-agent",
                "name": "Hermes Agent",
                "version": version,
                "description": "test",
                "distribution": {
                    "uvx": {
                        "package": f"hermes-agent[acp]=={version}",
                        "args": ["hermes-acp"],
                    }
                },
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def test_update_acp_registry_versions_bumps_manifest_and_pin(monkeypatch, tmp_path):
    _write_manifest(tmp_path, "0.13.0")
    module = _load_release_module(monkeypatch, tmp_path)

    module._update_acp_registry_versions("0.14.0")

    manifest = json.loads(
        (tmp_path / "acp_registry" / "agent.json").read_text(encoding="utf-8")
    )
    assert manifest["version"] == "0.14.0"
    assert manifest["distribution"]["uvx"]["package"] == "hermes-agent[acp]==0.14.0"
    # args stay untouched so we don't accidentally rewrite them.
    assert manifest["distribution"]["uvx"]["args"] == ["hermes-acp"]


def test_update_acp_registry_versions_is_silent_when_manifest_missing(
    monkeypatch, tmp_path
):
    """Older release branches predate the ACP Registry asset — must no-op."""
    module = _load_release_module(monkeypatch, tmp_path)

    # No fixture written; function should not raise.
    module._update_acp_registry_versions("0.14.0")


def test_update_version_files_bumps_manifest_alongside_pyproject(
    monkeypatch, tmp_path
):
    """End-to-end: update_version_files() is the function release.py actually
    calls, so it must drive the manifest bump too."""
    _write_manifest(tmp_path, "0.13.0")
    (tmp_path / "pyproject.toml").write_text(
        '[project]\nname = "hermes-agent"\nversion = "0.13.0"\n', encoding="utf-8"
    )
    version_dir = tmp_path / "hermes_cli"
    version_dir.mkdir()
    (version_dir / "__init__.py").write_text(
        '__version__ = "0.13.0"\n__release_date__ = "2026-05-14"\n',
        encoding="utf-8",
    )

    module = _load_release_module(monkeypatch, tmp_path)
    monkeypatch.setattr(module, "VERSION_FILE", version_dir / "__init__.py")
    monkeypatch.setattr(module, "PYPROJECT_FILE", tmp_path / "pyproject.toml")

    module.update_version_files("0.14.0", "2026-05-21")

    pyproject_text = (tmp_path / "pyproject.toml").read_text(encoding="utf-8")
    assert 'version = "0.14.0"' in pyproject_text

    manifest = json.loads(
        (tmp_path / "acp_registry" / "agent.json").read_text(encoding="utf-8")
    )
    assert manifest["version"] == "0.14.0"
    assert manifest["distribution"]["uvx"]["package"] == "hermes-agent[acp]==0.14.0"


def test_push_release_refs_scopes_authority_to_exact_tag(monkeypatch, tmp_path):
    module = _load_release_module(monkeypatch, tmp_path)
    git_result = Mock(return_value=SimpleNamespace(returncode=0, stderr=""))
    monkeypatch.setattr(module, "git_result", git_result)

    result = module.push_release_refs("v2026.8.14")

    assert result.returncode == 0
    git_result.assert_called_once_with("push", "origin", "HEAD", "v2026.8.14")


def test_publish_stops_before_artifacts_or_github_release_when_push_fails(
    monkeypatch,
    tmp_path,
):
    module = _load_release_module(monkeypatch, tmp_path)
    monkeypatch.setattr(
        module,
        "next_available_tag",
        lambda _tag: ("v2026.8.14", "2026.8.14"),
    )
    monkeypatch.setattr(module, "get_current_version", lambda: "0.13.146")
    monkeypatch.setattr(module, "get_last_tag", lambda: "v2026.8.13")
    monkeypatch.setattr(
        module,
        "get_commits",
        lambda since_tag: [{"github_author": "tester"}],
    )
    monkeypatch.setattr(module, "generate_changelog", lambda *args, **kwargs: "notes")
    build_artifacts = Mock(return_value=[])
    monkeypatch.setattr(module, "build_release_artifacts", build_artifacts)

    calls = []

    def git_result(*args, **kwargs):
        calls.append(args)
        if args[0] == "push":
            return SimpleNamespace(returncode=1, stderr="denied")
        return SimpleNamespace(returncode=0, stderr="")

    monkeypatch.setattr(module, "git_result", git_result)
    gh_run = Mock()
    monkeypatch.setattr(module.subprocess, "run", gh_run)

    module.main(["--publish"])

    assert calls[-1] == ("push", "origin", "HEAD", "v2026.8.14")
    build_artifacts.assert_not_called()
    gh_run.assert_not_called()
    assert not (tmp_path / ".release_notes.md").exists()
