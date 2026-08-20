import os
import shutil
import threading
import time
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from tools.environments import android_linux
from tools.environments.android_linux import AndroidLinuxEnvironment
from tools.environments.base import BaseEnvironment
from tools.environments.base_output import _pipe_stdin
from tools import terminal_tool


@pytest.fixture(autouse=True)
def _reset_android_process_owners(monkeypatch):
    if not android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False):
        android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
        assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
    monkeypatch.delenv("HERMES_ANDROID_BOOTSTRAP", raising=False)
    android_linux._ANDROID_PROCESS_OWNERS.clear()
    android_linux._ANDROID_PROCESS_HANDLES.clear()
    android_linux._ANDROID_UNSAFE_PROCESS_DETAIL = ""
    android_linux._ANDROID_UNRECOVERABLE_PROCESS_POISON = False
    yield
    if not android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False):
        android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
        assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
    android_linux._ANDROID_PROCESS_OWNERS.clear()
    android_linux._ANDROID_PROCESS_HANDLES.clear()
    android_linux._ANDROID_UNSAFE_PROCESS_DETAIL = ""
    android_linux._ANDROID_UNRECOVERABLE_PROCESS_POISON = False


def _owner(*process_ids, token="owner-token"):
    return android_linux._AndroidProcessOwner(
        baseline_process_ids=frozenset(process_ids),
        owner_token=token,
    )


def test_shared_pipe_stdin_returns_the_started_writer_thread():
    target = Mock()
    target.write.return_value = len(b"payload")
    proc = SimpleNamespace(stdin=SimpleNamespace(buffer=target))

    writer = _pipe_stdin(proc, "payload")
    writer.join(timeout=2.0)

    assert isinstance(writer, threading.Thread)
    assert not writer.is_alive()
    target.write.assert_called_once_with(b"payload")
    target.close.assert_called_once_with()


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
    assert child_env["PATH"].startswith("/system/bin:/system/xbin:/vendor/bin:/odm/bin")


def test_android_linux_snapshot_excludes_first_party_terminal_secrets(monkeypatch):
    environment = AndroidLinuxEnvironment.__new__(AndroidLinuxEnvironment)
    environment.env = {"BUZZ_PRIVATE_KEY": "profile-private-value"}
    assert "BUZZ_PRIVATE_KEY" in environment._additional_profile_scoped_passthrough_names()
    assert environment._profile_scoped_passthrough is True


def test_android_linux_environment_builds_system_first_runtime_env(tmp_path, monkeypatch):
    prefix = tmp_path / "prefix"
    bin_dir = prefix / "bin"
    lib_dir = prefix / "lib"
    home_dir = prefix / "home"
    tmp_dir = prefix / "tmp"
    for directory in [bin_dir, lib_dir, home_dir, tmp_dir]:
        directory.mkdir(parents=True, exist_ok=True)

    monkeypatch.setenv("HERMES_ANDROID_LINUX_PREFIX", str(prefix))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/system/bin/sh")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_BIN", str(bin_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_LIB", str(lib_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(home_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_TMP", str(tmp_dir))
    monkeypatch.setenv("PATH", "/test-host/bin")
    for name in ("TERMUX_PREFIX", "LD_LIBRARY_PATH", "TERMINFO", "GIT_EXEC_PATH", "HERMES_ANDROID_ALLOW_PREFIX_BIN"):
        monkeypatch.delenv(name, raising=False)

    env = AndroidLinuxEnvironment(cwd=str(home_dir), timeout=30)
    run_env = env._build_run_env()

    assert env.process_shell_path == "/system/bin/sh"
    assert run_env["PREFIX"] == str(prefix)
    assert run_env["HOME"] == str(home_dir)
    assert run_env["TMPDIR"] == str(tmp_dir)
    assert run_env["PATH"].startswith("/system/bin:/system/xbin:/vendor/bin:/odm/bin:")
    assert run_env["PATH"].endswith("/test-host/bin")
    assert str(bin_dir) not in run_env["PATH"]
    assert run_env["HERMES_ANDROID_SHELL"] == "/system/bin/sh"
    assert run_env["HERMES_ANDROID_EXECUTION_MODE"] == "android_system_shell"
    assert run_env["LD_LIBRARY_PATH"] == str(lib_dir)
    for name in ("TERMUX_PREFIX", "TERMINFO", "GIT_EXEC_PATH"):
        assert name not in run_env
    monkeypatch.setenv("HERMES_ANDROID_ALLOW_PREFIX_BIN", "1")
    assert env._build_run_env()["PATH"].endswith(":" + str(bin_dir))

    env.cleanup()


def test_android_linux_environment_preserves_selected_native_runtime(tmp_path, monkeypatch):
    native_dir = tmp_path / "native-libs"
    prefix_lib = tmp_path / "prefix-libs"
    monkeypatch.setenv("HERMES_ANDROID_EXECUTION_MODE", "embedded_termux")
    monkeypatch.setenv("HERMES_ANDROID_SHELL", str(native_dir / "libhermes_android_bash.so"))
    monkeypatch.setenv("HERMES_ANDROID_NATIVE_LIB", str(native_dir))
    monkeypatch.setenv("HERMES_ANDROID_LINUX_LIB", str(prefix_lib))
    monkeypatch.setenv("LD_LIBRARY_PATH", "/vendor/lib64")
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-reach-native-shell")
    monkeypatch.setattr(AndroidLinuxEnvironment, "init_session", lambda self: None)

    environment = AndroidLinuxEnvironment(cwd=str(tmp_path))
    child_env = environment._build_run_env()

    assert child_env["HERMES_ANDROID_EXECUTION_MODE"] == "embedded_termux"
    assert child_env["HERMES_ANDROID_SHELL"] == str(native_dir / "libhermes_android_bash.so")
    assert environment.process_shell_path == "/system/bin/sh"
    assert child_env["LD_LIBRARY_PATH"].startswith(str(native_dir) + ":")
    assert child_env["LD_LIBRARY_PATH"].endswith(f"{prefix_lib}:/vendor/lib64")
    assert "OPENAI_API_KEY" not in child_env


def test_android_linux_environment_derives_native_library_dir_from_shell_path(tmp_path, monkeypatch):
    native_dir = tmp_path / "apk-lib"
    shell_path = native_dir / "libhermes_android_bash.so"
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/system/bin/sh")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_NATIVE_BASH", str(shell_path))
    monkeypatch.delenv("HERMES_ANDROID_NATIVE_SHELL", raising=False)
    monkeypatch.delenv("HERMES_ANDROID_NATIVE_LIB", raising=False)
    monkeypatch.setattr(AndroidLinuxEnvironment, "init_session", lambda self: None)

    environment = AndroidLinuxEnvironment(cwd=str(tmp_path))
    child_env = environment._build_run_env()

    assert environment.process_shell_path == "/system/bin/sh"
    assert child_env["HERMES_ANDROID_SHELL"] == "/system/bin/sh"
    assert child_env["HERMES_ANDROID_NATIVE_SHELL"] == str(shell_path)
    assert child_env["LD_LIBRARY_PATH"].startswith(str(native_dir))


def test_android_shell_spawn_uses_platform_safe_process_options(monkeypatch):
    import os
    import subprocess

    environment = AndroidLinuxEnvironment.__new__(AndroidLinuxEnvironment)
    environment.process_shell_path = "test-only-shell"
    monkeypatch.setattr(environment, "_build_run_env", lambda: {"ANDROID_TEST_VALUE": "kept"})
    captured = {}
    process = object()

    def spawn(argv, **kwargs):
        captured.update(argv=argv, **kwargs)
        return process

    monkeypatch.setattr("tools.environments.android_linux.subprocess.Popen", spawn)
    assert environment._run_bash("printf ready") is process
    assert captured["argv"] == ["test-only-shell", "-c", "printf ready"]
    assert captured["env"] == {"ANDROID_TEST_VALUE": "kept"}
    assert captured["stdin"] == subprocess.DEVNULL
    if os.name == "nt":
        assert "preexec_fn" not in captured
        assert captured["creationflags"] == subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        assert captured["start_new_session"] is True
        assert "preexec_fn" not in captured
        assert "creationflags" not in captured

@pytest.mark.parametrize("returncode", [124, 130])
def test_android_wait_consumes_verified_timeout_or_interrupt_cleanup(
    monkeypatch,
    returncode,
):
    """Base wait may call the override before returning its 124/130 result."""
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(pid=321)
    owner = _owner(10, 11)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    cleanup_calls = []

    def terminate(process_group_id, process_owner, *, timeout):
        cleanup_calls.append((process_group_id, process_owner, timeout))
        return 1, ""

    def base_wait(self, owned_proc, *, timeout):
        self._kill_process(owned_proc)
        return {"output": "stopped", "returncode": returncode}

    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", terminate)
    monkeypatch.setattr(BaseEnvironment, "_wait_for_process", base_wait)

    result = env._wait_for_process(proc, timeout=7)

    assert result == {"output": "stopped", "returncode": returncode}
    assert cleanup_calls == [(321, owner, 2.0)]
    assert proc._hermes_android_cleanup_verified is True
    assert proc._hermes_android_detached_count == 1
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS


def test_android_wait_rejects_natural_detached_descendant_after_verified_cleanup(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(pid=654)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = _owner(20)

    monkeypatch.setattr(
        BaseEnvironment,
        "_wait_for_process",
        lambda self, owned_proc, *, timeout: {"output": "shell finished", "returncode": 0},
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        lambda process_group_id, process_owner, *, timeout: (2, ""),
    )

    result = env._wait_for_process(proc, timeout=7)

    assert result["returncode"] == 125
    assert "stopped detached Android command descendants" in result["output"]
    assert "background commands are disabled" in result["output"]
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS


def test_android_wait_retains_failed_cleanup_for_server_finalizer(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(pid=777)
    owner = _owner(30)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner

    monkeypatch.setattr(
        BaseEnvironment,
        "_wait_for_process",
        lambda self, owned_proc, *, timeout: {"output": "", "returncode": 0},
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        lambda process_group_id, process_owner, *, timeout: (
            1,
            "Android command descendants remained live: 778",
        ),
    )

    with pytest.raises(RuntimeError, match="descendants remained live"):
        env._wait_for_process(proc, timeout=7)

    assert android_linux._ANDROID_PROCESS_OWNERS[proc.pid] == owner
    assert "descendants remained live" in android_linux._ANDROID_UNSAFE_PROCESS_DETAIL


def test_android_failed_cleanup_blocks_retry_before_second_popen(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}
    proc = SimpleNamespace(pid=777)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = _owner(30)

    monkeypatch.setattr(
        BaseEnvironment,
        "_wait_for_process",
        lambda self, owned_proc, *, timeout: {"output": "", "returncode": 0},
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        lambda process_group_id, process_owner, *, timeout: (
            1,
            "Android command descendants remained live: 778",
        ),
    )

    with pytest.raises(RuntimeError, match="descendants remained live"):
        env._wait_for_process(proc, timeout=7)

    popen_mock = Mock()
    monkeypatch.setattr(android_linux.subprocess, "Popen", popen_mock)
    with pytest.raises(RuntimeError, match="force-stop and reopen"):
        env._run_bash("date")
    popen_mock.assert_not_called()


def test_android_wait_requires_registered_process_ownership_before_waiting(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(pid=888, _hermes_android_execution_lock_held=True)
    base_called = False
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()

    def base_wait(self, owned_proc, *, timeout):
        nonlocal base_called
        base_called = True
        return {"output": "", "returncode": 0}

    monkeypatch.setattr(BaseEnvironment, "_wait_for_process", base_wait)

    with pytest.raises(RuntimeError, match="ownership was not registered"):
        env._wait_for_process(proc, timeout=7)

    assert base_called is False
    assert proc._hermes_android_execution_lock_held is False
    assert android_linux.android_command_execution_requires_restart()

    with pytest.raises(RuntimeError, match="ownership was not registered"):
        android_linux.terminate_owned_android_command_processes_verified(timeout=0.0)
    assert android_linux.android_command_execution_requires_restart()


def test_android_finalizer_clears_poison_only_after_all_owners_are_verified(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    first_owner = _owner(1, token="first")
    second_owner = _owner(1, token="second")
    android_linux._ANDROID_PROCESS_OWNERS.update(
        {901: first_owner, 902: second_owner}
    )
    android_linux._ANDROID_UNSAFE_PROCESS_DETAIL = "prior cleanup failed"
    calls = []

    def terminate(process_group_id, process_owner, *, timeout):
        calls.append((process_group_id, process_owner))
        return 0, ""

    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", terminate)

    android_linux.terminate_owned_android_command_processes_verified(timeout=1.0)

    assert calls == [(901, first_owner), (902, second_owner)]
    assert android_linux._ANDROID_PROCESS_OWNERS == {}
    assert android_linux._ANDROID_UNSAFE_PROCESS_DETAIL == ""


@pytest.mark.linux_only
def test_android_cleanup_never_signals_ambiguous_unmarked_same_uid_process(monkeypatch):
    owner = _owner(1, token="exact-token")
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1, 200})
    monkeypatch.setattr(android_linux, "_process_owner_token", lambda pid: None)
    monkeypatch.setattr(android_linux, "_process_group_is_alive", lambda pgid: False)
    signals = []
    monkeypatch.setattr(
        android_linux,
        "_signal_owned_processes",
        lambda process_ids, token, sig: signals.append(
            (set(process_ids), token, sig)
        ),
    )

    observed, error = android_linux._terminate_android_process_owner(
        199,
        owner,
        timeout=0.0,
    )

    assert observed == 0
    assert "ambiguous-unmarked=200" in error
    assert signals
    assert all(process_ids == set() for process_ids, _, _ in signals)
    assert all(token == "exact-token" for _, token, _ in signals)


@pytest.mark.linux_only
def test_android_cleanup_signals_only_exact_inherited_token_processes(monkeypatch):
    owner = _owner(1, token="exact-token")
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1, 200})
    monkeypatch.setattr(
        android_linux,
        "_process_owner_token",
        lambda pid: "exact-token" if pid == 200 else None,
    )
    monkeypatch.setattr(android_linux, "_process_group_is_alive", lambda pgid: False)
    signals = []
    monkeypatch.setattr(
        android_linux,
        "_signal_owned_processes",
        lambda process_ids, token, sig: signals.append(
            (set(process_ids), token, sig)
        ),
    )

    observed, error = android_linux._terminate_android_process_owner(
        199,
        owner,
        timeout=0.0,
    )

    assert observed == 1
    assert "owned=200" in error
    assert signals
    assert all(process_ids == {200} for process_ids, _, _ in signals)
    assert all(token == "exact-token" for _, token, _ in signals)


@pytest.mark.linux_only
def test_android_cleanup_detects_token_owner_after_baseline_pid_reuse(monkeypatch):
    owner = _owner(1, 200, token="exact-token")
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1, 200})
    monkeypatch.setattr(
        android_linux,
        "_process_owner_token",
        lambda pid: "exact-token" if pid == 200 else None,
    )
    monkeypatch.setattr(android_linux, "_process_group_is_alive", lambda pgid: False)
    signals = []
    monkeypatch.setattr(
        android_linux,
        "_signal_owned_processes",
        lambda process_ids, token, sig: signals.append(
            (set(process_ids), token, sig)
        ),
    )

    observed, error = android_linux._terminate_android_process_owner(
        199,
        owner,
        timeout=0.0,
    )

    assert observed == 1
    assert "owned=200" in error
    assert "ambiguous-unmarked" not in error
    assert signals
    assert all(process_ids == {200} for process_ids, _, _ in signals)
    assert all(token == "exact-token" for _, token, _ in signals)


def test_android_cleanup_waits_for_stable_quiescence_and_catches_late_fork(monkeypatch):
    owner = _owner(1, token="exact-token")
    inventory_calls = 0
    child_alive = False
    child_spawned = False

    def inventory():
        nonlocal child_alive, child_spawned, inventory_calls
        inventory_calls += 1
        if inventory_calls >= 3 and not child_spawned:
            child_alive = True
            child_spawned = True
        return {1, 200} if child_alive else {1}

    def signal_owned(process_ids, token, sig):
        nonlocal child_alive
        assert token == "exact-token"
        if 200 in process_ids:
            child_alive = False

    monkeypatch.setattr(android_linux, "_same_uid_process_ids", inventory)
    monkeypatch.setattr(
        android_linux,
        "_process_owner_token",
        lambda pid: "exact-token" if pid == 200 else None,
    )
    monkeypatch.setattr(android_linux, "_process_group_is_alive", lambda pgid: False)
    monkeypatch.setattr(android_linux, "_signal_owned_processes", signal_owned)

    observed, error = android_linux._terminate_android_process_owner(
        199,
        owner,
        timeout=1.0,
    )

    assert error == ""
    assert observed == 1
    assert inventory_calls >= 3


def test_android_cleanup_polls_retained_direct_child_until_it_is_reaped(monkeypatch):
    owner = _owner(1, token="exact-token")
    proc = Mock()
    poll_calls = 0

    def poll():
        nonlocal poll_calls
        poll_calls += 1
        return None if poll_calls == 1 else 0

    proc.poll.side_effect = poll
    android_linux._ANDROID_PROCESS_HANDLES[199] = proc
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1})
    monkeypatch.setattr(android_linux, "_process_group_is_alive", lambda pgid: False)

    observed, error = android_linux._terminate_android_process_owner(
        199,
        owner,
        timeout=1.0,
    )

    assert observed == 0
    assert error == ""
    assert proc.poll.call_count >= 2


def test_android_runtime_work_guard_serializes_embedded_requests(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    first_entered = threading.Event()
    release_first = threading.Event()
    second_entered = threading.Event()

    def first():
        with android_linux.android_embedded_runtime_work_guard():
            first_entered.set()
            assert release_first.wait(timeout=2.0)

    def second():
        assert first_entered.wait(timeout=2.0)
        with android_linux.android_embedded_runtime_work_guard():
            second_entered.set()

    first_thread = threading.Thread(target=first)
    second_thread = threading.Thread(target=second)
    first_thread.start()
    second_thread.start()
    try:
        assert first_entered.wait(timeout=2.0)
        time.sleep(0.05)
        assert not second_entered.is_set()
        release_first.set()
        second_thread.join(timeout=2.0)
        assert second_entered.is_set()
    finally:
        release_first.set()
        first_thread.join(timeout=2.0)
        second_thread.join(timeout=2.0)

    assert not first_thread.is_alive()
    assert not second_thread.is_alive()


def test_embedded_android_does_not_start_global_terminal_cleanup_daemon(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setattr(terminal_tool, "_cleanup_thread", None)
    monkeypatch.setattr(terminal_tool, "_cleanup_running", False)

    terminal_tool._start_cleanup_thread()

    assert terminal_tool._cleanup_thread is None
    assert terminal_tool._cleanup_running is False


def test_terminal_cleanup_worker_must_be_proven_dead(monkeypatch):
    worker = Mock()
    worker.is_alive.return_value = True
    monkeypatch.setattr(terminal_tool, "_cleanup_thread", worker)
    monkeypatch.setattr(terminal_tool, "_cleanup_running", True)

    with pytest.raises(RuntimeError, match="did not stop"):
        terminal_tool.stop_cleanup_thread_verified(timeout=0.01)

    worker.join.assert_called_once_with(timeout=0.01)
    assert terminal_tool._cleanup_running is False


@pytest.mark.linux_only
def test_android_signal_revalidates_token_immediately_before_pid_signal(monkeypatch):
    kill_mock = Mock()
    monkeypatch.setattr(android_linux.os, "kill", kill_mock)
    monkeypatch.setattr(
        android_linux,
        "_process_owner_token",
        lambda pid: "replacement-token",
    )

    android_linux._signal_owned_processes({200}, "exact-token", android_linux.signal.SIGTERM)

    kill_mock.assert_not_called()

    monkeypatch.setattr(
        android_linux,
        "_process_owner_token",
        lambda pid: "exact-token",
    )
    android_linux._signal_owned_processes({200}, "exact-token", android_linux.signal.SIGKILL)
    kill_mock.assert_called_once_with(200, android_linux.signal.SIGKILL)


def test_android_spawn_injects_unique_owner_token_and_records_baseline(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {"EXISTING": "yes"}
    proc = SimpleNamespace(pid=444)
    popen_mock = Mock(return_value=proc)
    monkeypatch.setattr(android_linux.subprocess, "Popen", popen_mock)
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1, 2})
    monkeypatch.setattr(
        android_linux.uuid,
        "uuid4",
        lambda: SimpleNamespace(hex="fixed-owner-token"),
    )

    try:
        spawned = env._run_bash("date")

        assert spawned is proc
        run_env = popen_mock.call_args.kwargs["env"]
        assert run_env["EXISTING"] == "yes"
        assert (
            run_env[android_linux._ANDROID_COMMAND_OWNER_ENV]
            == "fixed-owner-token"
        )
        assert android_linux._ANDROID_PROCESS_OWNERS[444] == _owner(
            1,
            2,
            token="fixed-owner-token",
        )
    finally:
        android_linux._ANDROID_PROCESS_OWNERS.pop(444, None)
        if getattr(proc, "_hermes_android_execution_lock_held", False):
            proc._hermes_android_execution_lock_held = False
            android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_stdin_writer_start_failure_unwinds_process_and_releases_lock(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}
    proc = SimpleNamespace(pid=445, stdin=Mock())
    monkeypatch.setattr(android_linux.subprocess, "Popen", Mock(return_value=proc))
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1})
    cleanup = Mock(return_value=(0, ""))
    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", cleanup)
    monkeypatch.setattr(
        android_linux,
        "_pipe_stdin",
        Mock(side_effect=RuntimeError("writer thread failed to start")),
    )

    with pytest.raises(RuntimeError, match="writer thread failed to start"):
        env._run_bash("cat", stdin_data="payload")

    cleanup.assert_called_once()
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
    assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_stdin_writer_is_registered_before_thread_start(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}

    class RegistrationFails:
        pid = 445
        stdin = Mock()

        def __setattr__(self, name, value):
            if name == "_hermes_android_stdin_writer":
                raise MemoryError("writer registration failed")
            object.__setattr__(self, name, value)

    proc = RegistrationFails()
    monkeypatch.setattr(android_linux.subprocess, "Popen", Mock(return_value=proc))
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1})
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        Mock(return_value=(0, "")),
    )
    thread_start = Mock()
    monkeypatch.setattr(threading.Thread, "start", thread_start)

    with pytest.raises(MemoryError, match="writer registration failed"):
        env._run_bash("cat", stdin_data="payload")

    thread_start.assert_not_called()
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
    assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_execution_lock_marker_failure_unwinds_owned_process(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}

    class MarkerPublicationFails:
        pid = 447
        stdin = Mock()

        def __setattr__(self, name, value):
            if name == "_hermes_android_execution_lock_held":
                raise KeyboardInterrupt("execution lock marker publication interrupted")
            object.__setattr__(self, name, value)

    proc = MarkerPublicationFails()
    cleanup = Mock(return_value=(0, ""))
    monkeypatch.setattr(android_linux.subprocess, "Popen", Mock(return_value=proc))
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1})
    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", cleanup)

    with pytest.raises(
        KeyboardInterrupt,
        match="execution lock marker publication interrupted",
    ):
        env._run_bash("date")

    cleanup.assert_called_once()
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
    assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_spawn_cleanup_base_exception_poisons_and_releases_lock(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}
    proc = SimpleNamespace(pid=445, stdin=Mock())
    monkeypatch.setattr(android_linux.subprocess, "Popen", Mock(return_value=proc))
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1})
    monkeypatch.setattr(
        android_linux,
        "_pipe_stdin",
        Mock(side_effect=RuntimeError("writer thread failed to start")),
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        Mock(side_effect=KeyboardInterrupt("cleanup interrupted")),
    )

    with pytest.raises(KeyboardInterrupt, match="cleanup interrupted"):
        env._run_bash("cat", stdin_data="payload")

    assert "spawn cleanup raised KeyboardInterrupt" in (
        android_linux._ANDROID_UNSAFE_PROCESS_DETAIL
    )
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_failed_cleanup_before_handle_registration_is_unrecoverable(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}
    proc = SimpleNamespace(pid=446, stdin=Mock())
    monkeypatch.setattr(android_linux.subprocess, "Popen", Mock(return_value=proc))
    monkeypatch.setattr(android_linux, "_same_uid_process_ids", lambda: {1})

    class HandleRegistrationFails(dict):
        def __setitem__(self, key, value):
            raise MemoryError("handle registration failed")

    monkeypatch.setattr(
        android_linux,
        "_ANDROID_PROCESS_HANDLES",
        HandleRegistrationFails(),
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        Mock(return_value=(0, "direct process cleanup was not verified")),
    )

    with pytest.raises(MemoryError, match="handle registration failed"):
        env._run_bash("date")

    assert android_linux._ANDROID_UNRECOVERABLE_PROCESS_POISON is True
    assert "cleanup was not verified" in android_linux._ANDROID_UNSAFE_PROCESS_DETAIL
    with pytest.raises(RuntimeError, match="cleanup was not verified"):
        android_linux.terminate_owned_android_command_processes_verified(timeout=0.0)
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_blocked_stdin_writer_retains_owner_and_poisons_retry(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    writer = Mock()
    writer.is_alive.return_value = True
    proc = SimpleNamespace(pid=446, stdin=Mock(), _hermes_android_stdin_writer=writer)
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    monkeypatch.setattr(
        BaseEnvironment,
        "_wait_for_process",
        lambda self, owned_proc, *, timeout: {"output": "", "returncode": 0},
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        lambda process_group_id, process_owner, *, timeout: (0, ""),
    )

    with pytest.raises(RuntimeError, match="stdin writer did not stop"):
        env._wait_for_process(proc, timeout=7)

    writer.join.assert_called_once()
    proc.stdin.close.assert_not_called()
    assert android_linux._ANDROID_PROCESS_OWNERS[proc.pid] is owner
    assert android_linux.android_command_execution_requires_restart()


def test_android_live_stdout_drain_retains_owner_poisons_and_blocks_retry(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    env.process_shell_path = "/system/bin/sh"
    env._build_run_env = lambda: {}
    drain = Mock()
    drain.is_alive.return_value = True
    proc = SimpleNamespace(
        pid=447,
        stdout=Mock(),
        stdin=None,
        _hermes_stdout_drain_thread=drain,
    )
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    monkeypatch.setattr(
        BaseEnvironment,
        "_wait_for_process",
        lambda self, owned_proc, *, timeout: {"output": "", "returncode": 0},
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        lambda process_group_id, process_owner, *, timeout: (0, ""),
    )

    with pytest.raises(RuntimeError, match="stdout drain did not stop"):
        env._wait_for_process(proc, timeout=7)

    drain.join.assert_called_once()
    proc.stdout.close.assert_not_called()
    assert android_linux._ANDROID_PROCESS_OWNERS[proc.pid] is owner
    assert "stdout drain did not stop" in android_linux.android_command_execution_requires_restart()

    popen_mock = Mock()
    monkeypatch.setattr(android_linux.subprocess, "Popen", popen_mock)
    with pytest.raises(RuntimeError, match="force-stop and reopen"):
        env._run_bash("date")
    popen_mock.assert_not_called()


def test_android_stdout_drain_cleanup_interrupt_poisons_all_runtime_work(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    drain = Mock()
    drain.ident = 1
    drain.join.side_effect = KeyboardInterrupt("stdout cleanup interrupted")
    proc = SimpleNamespace(
        pid=451,
        stdout=Mock(),
        stdin=None,
        _hermes_stdout_drain_thread=drain,
        _hermes_android_execution_lock_held=True,
    )
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()
    monkeypatch.setattr(
        BaseEnvironment,
        "_wait_for_process",
        lambda self, owned_proc, *, timeout: {"output": "", "returncode": 0},
    )
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        lambda process_group_id, process_owner, *, timeout: (0, ""),
    )

    with pytest.raises(KeyboardInterrupt, match="stdout cleanup interrupted"):
        env._wait_for_process(proc, timeout=7)

    assert android_linux._ANDROID_PROCESS_OWNERS[proc.pid] is owner
    assert android_linux._ANDROID_PROCESS_HANDLES[proc.pid] is proc
    assert "post-exit cleanup raised KeyboardInterrupt" in (
        android_linux.android_command_execution_requires_restart()
    )
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
    with pytest.raises(android_linux.AndroidRuntimeWorkRejected, match="force-stop"):
        with android_linux.android_embedded_runtime_work_guard():
            pytest.fail("unsafe Android runtime work was admitted")


def test_android_stdout_drain_is_owned_when_thread_start_launches_then_interrupts(
    monkeypatch,
):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    read_fd, write_fd = os.pipe()
    stdout = os.fdopen(read_fd, "r", encoding="utf-8")
    proc = SimpleNamespace(
        pid=448,
        stdout=stdout,
        stdin=None,
        returncode=0,
        _hermes_android_execution_lock_held=True,
        poll=lambda: 0,
    )
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()
    original_start = threading.Thread.start
    writer_open = True

    def launch_then_interrupt(drain_thread):
        original_start(drain_thread)
        raise KeyboardInterrupt("stdout drain start interrupted")

    def terminate(process_group_id, process_owner, *, timeout):
        nonlocal writer_open
        if writer_open:
            os.close(write_fd)
            writer_open = False
        return 0, ""

    monkeypatch.setattr(threading.Thread, "start", launch_then_interrupt)
    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", terminate)
    try:
        with pytest.raises(KeyboardInterrupt, match="stdout drain start interrupted"):
            env._wait_for_process(proc, timeout=7)

        drain = proc._hermes_stdout_drain_thread
        assert drain.ident is not None
        assert not drain.is_alive()
        assert proc._hermes_android_cleanup_verified is True
        assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
        assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
        assert android_linux.android_command_execution_requires_restart() == ""
        assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
        android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
    finally:
        if writer_open:
            os.close(write_fd)
        drain = getattr(proc, "_hermes_stdout_drain_thread", None)
        if drain is not None and drain.ident is not None:
            drain.join(timeout=2.0)
        if not stdout.closed and (drain is None or not drain.is_alive()):
            stdout.close()


def test_android_base_wait_setup_failure_unwinds_registered_process(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(
        pid=447,
        stdout=Mock(),
        stdin=None,
        _hermes_android_execution_lock_held=True,
    )
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()
    cleanup = Mock(return_value=(0, ""))
    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", cleanup)
    monkeypatch.setattr(
        threading.Thread,
        "start",
        Mock(side_effect=RuntimeError("stdout drain failed to start")),
    )

    with pytest.raises(RuntimeError, match="stdout drain failed to start"):
        env._wait_for_process(proc, timeout=7)

    cleanup.assert_called_once_with(proc.pid, owner, timeout=2.0)
    assert proc._hermes_android_cleanup_verified is True
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
    assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_atomic_spawn_wait_abandonment_releases_verified_owner_lock(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(pid=448, _hermes_android_execution_lock_held=True)
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()
    monkeypatch.setattr(env, "_run_bash", Mock(return_value=proc))
    monkeypatch.setattr(
        env,
        "_wait_for_process",
        Mock(side_effect=KeyboardInterrupt("between spawn and wait")),
    )
    cleanup = Mock(return_value=(0, ""))
    monkeypatch.setattr(android_linux, "_terminate_android_process_owner", cleanup)

    with pytest.raises(KeyboardInterrupt, match="between spawn and wait"):
        env._run_bash_and_wait("date")

    cleanup.assert_called_once_with(proc.pid, owner, timeout=2.0)
    assert proc._hermes_android_cleanup_verified is True
    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
    assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_finalizer_releases_abandoned_command_lock_after_verified_cleanup(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    proc = SimpleNamespace(pid=449, _hermes_android_execution_lock_held=True)
    owner = _owner(1)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = owner
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        Mock(return_value=(0, "")),
    )

    android_linux.terminate_owned_android_command_processes_verified(timeout=1.0)

    assert proc.pid not in android_linux._ANDROID_PROCESS_OWNERS
    assert proc.pid not in android_linux._ANDROID_PROCESS_HANDLES
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()


def test_android_abandonment_cleanup_base_exception_poisons_and_releases_lock(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    env = object.__new__(AndroidLinuxEnvironment)
    proc = SimpleNamespace(pid=450, _hermes_android_execution_lock_held=True)
    android_linux._ANDROID_PROCESS_OWNERS[proc.pid] = _owner(1)
    android_linux._ANDROID_PROCESS_HANDLES[proc.pid] = proc
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire()
    monkeypatch.setattr(
        android_linux,
        "_terminate_android_process_owner",
        Mock(side_effect=KeyboardInterrupt("cleanup interrupted")),
    )

    with pytest.raises(KeyboardInterrupt, match="cleanup interrupted"):
        env._cleanup_abandoned_process(proc)

    assert "cleanup raised KeyboardInterrupt" in android_linux._ANDROID_UNSAFE_PROCESS_DETAIL
    assert android_linux._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False)
    android_linux._ANDROID_COMMAND_EXECUTION_LOCK.release()
