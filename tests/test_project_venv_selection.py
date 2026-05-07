"""Updates and runtime probes must agree on the environment in this checkout."""
import sys

from hermes_constants import project_venv_dir


def test_active_legacy_environment_wins_over_inactive_dot_venv(tmp_path, monkeypatch):
    (tmp_path / ".venv").mkdir()
    active = tmp_path / "venv"
    active.mkdir()
    monkeypatch.setattr(sys, "prefix", str(active))
    assert project_venv_dir(tmp_path) == active


def test_active_dot_venv_survives_interpreter_symlink_resolution(tmp_path, monkeypatch):
    active = tmp_path / ".venv"
    active.mkdir()
    (tmp_path / "venv").mkdir()
    monkeypatch.setattr(sys, "prefix", str(active))
    monkeypatch.setattr(sys, "executable", str(tmp_path.parent / "external-python"))
    assert project_venv_dir(tmp_path) == active


def test_foreign_active_environment_is_not_selected(tmp_path, monkeypatch):
    expected = tmp_path / ".venv"
    expected.mkdir()
    (tmp_path / "venv").mkdir()
    monkeypatch.setattr(sys, "prefix", str(tmp_path.parent / "another-project" / "venv"))
    assert project_venv_dir(tmp_path) == expected


def test_missing_project_environment_has_no_invented_path(tmp_path):
    assert project_venv_dir(tmp_path) is None
