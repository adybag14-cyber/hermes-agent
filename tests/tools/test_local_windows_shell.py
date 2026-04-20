from unittest.mock import patch

import pytest
import sys
from pathlib import Path

import tools.environments.local as local_env


@pytest.fixture(autouse=True)
def _restore_windows_flag():
    original = local_env._IS_WINDOWS
    yield
    local_env._IS_WINDOWS = original


def test_find_bash_prefers_git_bash_over_wsl_launcher(monkeypatch):
    local_env._IS_WINDOWS = True
    git_bash = r"C:\Program Files\Git\bin\bash.exe"

    monkeypatch.delenv("HERMES_GIT_BASH_PATH", raising=False)
    monkeypatch.setenv("ProgramFiles", r"C:\Program Files")
    monkeypatch.setenv("ProgramFiles(x86)", r"C:\Program Files (x86)")
    monkeypatch.setenv("LOCALAPPDATA", r"C:\Users\Alice\AppData\Local")
    monkeypatch.setattr(local_env.shutil, "which", lambda _name: r"C:\Windows\System32\bash.exe")
    monkeypatch.setattr(local_env.os.path, "isfile", lambda path: path == git_bash)

    assert local_env._find_bash() == git_bash


def test_find_bash_rejects_wsl_launcher_without_git_bash(monkeypatch):
    local_env._IS_WINDOWS = True

    monkeypatch.delenv("HERMES_GIT_BASH_PATH", raising=False)
    monkeypatch.setenv("ProgramFiles", r"C:\Program Files")
    monkeypatch.setenv("ProgramFiles(x86)", r"C:\Program Files (x86)")
    monkeypatch.setenv("LOCALAPPDATA", r"C:\Users\Alice\AppData\Local")
    monkeypatch.setattr(local_env.shutil, "which", lambda _name: r"C:\Windows\System32\bash.exe")
    monkeypatch.setattr(local_env.os.path, "isfile", lambda _path: False)

    with pytest.raises(RuntimeError, match="Git Bash not found"):
        local_env._find_bash()


@pytest.mark.skipif(sys.platform != "win32", reason="Windows-only live regression")
def test_local_environment_captures_stdout_on_windows():
    try:
        local_env._find_bash()
    except RuntimeError:
        pytest.skip("Git Bash not installed")

    env = local_env.LocalEnvironment(cwd=str(Path.cwd()), timeout=10)
    try:
        result = env.execute("echo WINDOWS_STDOUT_OK")
    finally:
        env.cleanup()

    assert result["returncode"] == 0
    assert "WINDOWS_STDOUT_OK" in result["output"]
