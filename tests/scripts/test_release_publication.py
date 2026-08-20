"""Release publication must push only its own tag and stop on push failure."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock


def _release_module(monkeypatch, tmp_path):
    spec = importlib.util.spec_from_file_location(
        "_release_publication_under_test",
        Path(__file__).resolve().parents[2] / "scripts" / "release.py",
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(module, "REPO_ROOT", tmp_path)
    return module


def test_push_release_refs_scopes_authority_to_exact_tag(monkeypatch, tmp_path):
    module = _release_module(monkeypatch, tmp_path)
    git_result = Mock(return_value=SimpleNamespace(returncode=0, stderr=""))
    monkeypatch.setattr(module, "git_result", git_result)

    result = module.push_release_refs("v2026.8.14")

    assert result.returncode == 0
    git_result.assert_called_once_with("push", "origin", "HEAD", "v2026.8.14")


def test_publish_stops_before_github_release_when_push_fails(monkeypatch, tmp_path):
    module = _release_module(monkeypatch, tmp_path)
    monkeypatch.setattr(module, "next_available_tag", lambda _tag: ("v2026.8.14", "2026.8.14"))
    monkeypatch.setattr(module, "get_current_version", lambda: "0.13.146")
    monkeypatch.setattr(module, "get_last_tag", lambda: "v2026.8.13")
    monkeypatch.setattr(module, "get_commits", lambda since_tag: [{"github_author": "tester"}])
    monkeypatch.setattr(module, "generate_changelog", lambda *args, **kwargs: "notes")
    monkeypatch.setattr(module, "update_version_files", Mock())
    calls = []

    def git_result(*args, **kwargs):
        calls.append(args)
        return SimpleNamespace(returncode=1 if args[0] == "push" else 0, stderr="denied")

    monkeypatch.setattr(module, "git_result", git_result)
    gh_run = Mock()
    monkeypatch.setattr(module.subprocess, "run", gh_run)

    module.main(["--publish"])

    assert calls[-1] == ("push", "origin", "HEAD", "v2026.8.14")
    gh_run.assert_not_called()
    assert not (tmp_path / ".release_notes.md").exists()
