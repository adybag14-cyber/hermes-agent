import os
import shutil
from pathlib import Path

from tools.environments.android_linux import AndroidLinuxEnvironment


def test_android_linux_command_env_uses_shared_secret_filter(tmp_path, monkeypatch):
    prefix = tmp_path / "prefix"
    monkeypatch.setenv("HERMES_ANDROID_LINUX_PREFIX", str(prefix))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BASH", str(prefix / "bin" / "bash"))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BIN", str(prefix / "bin"))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(prefix / "home"))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_TMP", str(prefix / "tmp"))
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-reach-shell")
    monkeypatch.setattr(AndroidLinuxEnvironment, "init_session", lambda self: None)
    environment = AndroidLinuxEnvironment(env={"ANDROID_TEST_VALUE": "kept"})
    child_env = environment._build_run_env()
    assert "OPENAI_API_KEY" not in child_env
    assert child_env["ANDROID_TEST_VALUE"] == "kept"
    assert child_env["PREFIX"] == str(prefix)
    assert child_env["PATH"].startswith(str(prefix / "bin") + ":")


def test_android_linux_snapshot_excludes_first_party_terminal_secrets(monkeypatch):
    environment = AndroidLinuxEnvironment.__new__(AndroidLinuxEnvironment)
    environment.env = {"BUZZ_PRIVATE_KEY": "profile-private-value"}
    assert "BUZZ_PRIVATE_KEY" in environment._additional_profile_scoped_passthrough_names()
    assert environment._profile_scoped_passthrough is True


def test_android_linux_environment_builds_prefix_first_runtime_env(tmp_path, monkeypatch):
    prefix = tmp_path / "prefix"
    bin_dir = prefix / "bin"
    lib_dir = prefix / "lib"
    home_dir = prefix / "home"
    tmp_dir = prefix / "tmp"
    share_terminfo = prefix / "share" / "terminfo"
    git_exec = prefix / "libexec" / "git-core"
    for directory in [bin_dir, lib_dir, home_dir, tmp_dir, share_terminfo, git_exec]:
        directory.mkdir(parents=True, exist_ok=True)

    bash_path = shutil.which("bash")
    assert bash_path is not None

    monkeypatch.setenv("HERMES_ANDROID_LINUX_PREFIX", str(prefix))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BASH", bash_path)
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BIN", str(bin_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_LIB", str(lib_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(home_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_TMP", str(tmp_dir))

    env = AndroidLinuxEnvironment(cwd=str(home_dir), timeout=30)
    run_env = env._build_run_env()

    assert run_env["PREFIX"] == str(prefix)
    assert run_env["TERMUX_PREFIX"] == str(prefix)
    assert run_env["HOME"] == str(home_dir)
    assert run_env["TMPDIR"] == str(tmp_dir)
    assert run_env["PATH"].split(":")[0] == str(bin_dir)
    assert run_env["LD_LIBRARY_PATH"].split(":")[0] == str(lib_dir)
    assert run_env["TERMINFO"] == str(share_terminfo)
    assert run_env["GIT_EXEC_PATH"] == str(git_exec)

    env.cleanup()
