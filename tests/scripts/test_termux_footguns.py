"""Regression tests for scripts/check-termux-footguns.py."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
LINTER_PATH = REPO_ROOT / "scripts" / "check-termux-footguns.py"


def _load_linter_module():
    spec = importlib.util.spec_from_file_location("check_termux_footguns", LINTER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["check_termux_footguns"] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


@pytest.fixture()
def linter():
    return _load_linter_module()


def _write(root: Path, rel: str, content: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def test_flags_host_package_manager_in_native_installer(linter, tmp_path, monkeypatch):
    monkeypatch.setattr(linter, "REPO_ROOT", tmp_path)
    path = _write(
        tmp_path,
        "scripts/install-termux.sh",
        "#!/data/data/com.termux/files/usr/bin/bash\nsudo apt-get install python\n",
    )
    findings = linter.scan_file(path)
    assert {item.name for item in findings} == {"host Linux package manager in native Termux installer"}


def test_unset_python_host_platform_is_allowed_but_assignment_is_not(linter, tmp_path, monkeypatch):
    monkeypatch.setattr(linter, "REPO_ROOT", tmp_path)
    path = _write(
        tmp_path,
        "scripts/install-termux.sh",
        "#!/data/data/com.termux/files/usr/bin/bash\nunset UV_INDEX \\\n    _PYTHON_HOST_PLATFORM\n",
    )
    assert not linter.scan_file(path)

    path.write_text(
        "#!/data/data/com.termux/files/usr/bin/bash\nexport _PYTHON_HOST_PLATFORM=linux_aarch64\n",
        encoding="utf-8",
    )
    assert any(item.name == "fake _PYTHON_HOST_PLATFORM assignment" for item in linter.scan_file(path))


def test_termux_regression_rejects_fragile_inline_bash_lc(linter, tmp_path, monkeypatch):
    monkeypatch.setattr(linter, "REPO_ROOT", tmp_path)
    path = _write(
        tmp_path,
        ".github/workflows/termux-regression.yml",
        "run: docker run image bash -lc 'echo quoted'\n",
    )
    findings = linter.scan_file(path)
    assert {item.name for item in findings} == {"fragile inline quoted Termux regression shell"}


def test_termux_regression_rejects_privileged_or_seccomp_disabled_container(linter, tmp_path, monkeypatch):
    monkeypatch.setattr(linter, "REPO_ROOT", tmp_path)
    path = _write(
        tmp_path,
        ".github/workflows/termux-regression.yml",
        "run: docker run --rm --privileged --security-opt seccomp=unconfined image\n",
    )
    findings = linter.scan_file(path)
    assert {item.name for item in findings} == {"privileged Termux PR regression container"}


def test_repository_termux_policy_is_clean(linter):
    files = list(linter.iter_files([REPO_ROOT]))
    selected = {linter._repo_rel(path) for path in files}
    findings = [finding for path in files for finding in linter.scan_file(path)]
    findings.extend(linter.invariant_findings(selected))
    assert findings == []
