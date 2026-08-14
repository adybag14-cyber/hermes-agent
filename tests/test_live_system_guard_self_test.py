"""Self-test for the live-system guard fixture in tests/conftest.py.

This file is the canary. If anyone removes a guard or weakens it, these
tests fail. If anyone adds a NEW kill primitive to the codebase without
adding it to the guard, the corresponding test added here will fail too.

The guard exists to protect the developer's live ``hermes-gateway`` process
from being SIGTERMed by tests. See PR #23397 for the original incident
(5+ live gateway kills in 3 days). Per Teknium 2026-05-10:

  > "You better do such a deep scan and scrub of the tests that this
  >  never is possible ever again for all eternity."

Every primitive that can deliver a signal to a foreign process or mutate
the live systemd unit MUST be exercised below. Adding a new primitive to
the guard? Add a test here too.
"""
from __future__ import annotations

import os
import signal
import subprocess
import types

import pytest

# The parent is an existing foreign process on every host, including Windows
# where PID 1 need not exist. No real signal backend is reachable in this file.
FOREIGN_PID = os.getppid()


@pytest.fixture
def _live_system_guard_primitives(monkeypatch):
    """Record primitives BEFORE the guard wraps them: a guard regression is inert.

    The dependency in conftest guarantees ordering. The local safety fixture
    also depends on this fixture, so collection without that conftest remains
    harmless and fails the guard-identity check.
    """
    import asyncio
    from types import SimpleNamespace

    tape = SimpleNamespace(calls=[], result=object())

    def record(name):
        def invoke(*args, **kwargs):
            tape.calls.append((name, args, kwargs))
            return tape.result
        return invoke

    class RecordingPopen:
        def __init__(self, *args, **kwargs):
            tape.calls.append(("Popen", args, kwargs))

        @classmethod
        def __class_getitem__(cls, _item):
            return cls

    for name in ("run", "call", "check_call", "check_output", "getoutput", "getstatusoutput"):
        monkeypatch.setattr(subprocess, name, record(name))
    monkeypatch.setattr(subprocess, "Popen", RecordingPopen)
    for name in ("kill", "killpg", "system", "popen"):
        if hasattr(os, name):
            monkeypatch.setattr(os, name, record("os." + name))
    try:
        import pty
    except ImportError:
        pass
    else:
        monkeypatch.setattr(pty, "spawn", record("pty.spawn"))

    def async_record(name):
        async def invoke(*args, **kwargs):
            return record(name)(*args, **kwargs)
        return invoke

    for name in ("create_subprocess_exec", "create_subprocess_shell"):
        monkeypatch.setattr(asyncio, name, async_record(name))
    return tape


def _live_system_guard_is_active() -> bool:
    # A recording Python function alone is not proof the guard loaded.
    return getattr(os.kill, "__name__", "") == "_guarded_kill"


@pytest.fixture(autouse=True)
def _refuse_to_fire_live_weapons(request, _live_system_guard_primitives):
    if not request.node.get_closest_marker("live_system_guard_bypass"):
        if not _live_system_guard_is_active():
            pytest.fail("REFUSING TO RUN: live-system guard was not installed", pytrace=False)
    yield


def test_fail_closed_probe_reports_guard_active():
    """In the real suite the guard is loaded, so the probe reports active and
    ``_refuse_to_fire_live_weapons`` stays out of the way (no false positives
    that would wedge CI)."""
    assert _live_system_guard_is_active() is True


def test_fail_closed_probe_classifies_raw_builtin_as_unguarded():
    """The probe's discriminator, exercised against real objects: a raw C
    builtin the guard never touches (``os.getpid``) is exactly what an
    unguarded ``os.kill`` looks like and must read as 'guard not active', while
    the loaded guard's ``os.kill`` is a plain Python function."""
    assert isinstance(os.getpid, types.BuiltinFunctionType)
    assert not isinstance(os.kill, types.BuiltinFunctionType)


# ──────────────────── kill primitives ─────────────────────────


def test_os_kill_blocks_foreign_pid():
    with pytest.raises(RuntimeError, match="live-system guard"):
        os.kill(FOREIGN_PID, signal.SIGTERM)


def test_os_kill_blocks_negative_one():
    """``os.kill(-1, sig)`` signals every process we can reach. Must be blocked."""
    with pytest.raises(RuntimeError, match="live-system guard"):
        os.kill(-1, signal.SIGTERM)


@pytest.mark.skipif(not hasattr(os, "killpg"), reason="killpg POSIX-only")
def test_os_killpg_blocks_foreign_pgid():
    with pytest.raises(RuntimeError, match="live-system guard"):
        os.killpg(FOREIGN_PID, signal.SIGTERM)


# ──────────────────── subprocess regex bypasses ────────────────


def test_subprocess_run_systemctl_restart_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["systemctl", "--user", "restart", "hermes-gateway"])


def test_subprocess_run_full_path_systemctl_blocked():
    """``/usr/bin/systemctl`` (full path) must be blocked too."""
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["/usr/bin/systemctl", "--user", "stop", "hermes-gateway"])


def test_subprocess_run_sudo_systemctl_blocked():
    """``sudo systemctl ...`` defeated the old head==systemctl check."""
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["sudo", "systemctl", "restart", "hermes-gateway"])


def test_subprocess_run_env_systemctl_blocked():
    """``env systemctl ...`` similarly defeated the old head check."""
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["env", "systemctl", "--user", "restart", "hermes-gateway"])


def test_subprocess_run_bash_c_systemctl_blocked():
    """``bash -c "systemctl ..."`` must also be caught."""
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["bash", "-c", "systemctl --user restart hermes-gateway"])


def test_subprocess_run_sh_c_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["sh", "-c", "systemctl --user stop hermes-gateway"])


def test_subprocess_run_setsid_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["setsid", "systemctl", "kill", "hermes-gateway"])


def test_subprocess_run_string_shell_true_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            "systemctl --user restart hermes-gateway",
            shell=True,
        )


def test_subprocess_popen_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.Popen(["systemctl", "--user", "stop", "hermes-gateway"])


def test_subprocess_call_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.call(["systemctl", "--user", "restart", "hermes-gateway"])


def test_subprocess_check_call_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.check_call(["systemctl", "--user", "restart", "hermes-gateway"])


def test_subprocess_check_output_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.check_output(["systemctl", "--user", "restart", "hermes-gateway"])


def test_subprocess_getoutput_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.getoutput("systemctl --user restart hermes-gateway")


def test_subprocess_getstatusoutput_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.getstatusoutput("systemctl --user restart hermes-gateway")


# ──────────────────── os.system / os.popen ────────────────────


def test_os_system_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        os.system("systemctl --user restart hermes-gateway")


def test_os_popen_systemctl_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        os.popen("systemctl --user restart hermes-gateway")


# ──────────────────── pty.spawn ────────────────────────────────


@pytest.mark.linux_only
def test_pty_spawn_systemctl_blocked():
    _assert_pty_spawn_systemctl_blocked()


@pytest.mark.macos_only
def test_pty_spawn_systemctl_blocked_on_macos():
    _assert_pty_spawn_systemctl_blocked()


def _assert_pty_spawn_systemctl_blocked():
    import pty
    with pytest.raises(RuntimeError, match="live-system guard"):
        pty.spawn(["systemctl", "--user", "restart", "hermes-gateway"])


# ──────────────────── asyncio.create_subprocess_* ──────────────


def test_asyncio_create_subprocess_exec_systemctl_blocked():
    import asyncio

    async def _attempt():
        await asyncio.create_subprocess_exec(
            "systemctl", "--user", "restart", "hermes-gateway"
        )

    with pytest.raises(RuntimeError, match="live-system guard"):
        asyncio.run(_attempt())


def test_asyncio_create_subprocess_shell_systemctl_blocked():
    import asyncio

    async def _attempt():
        await asyncio.create_subprocess_shell(
            "systemctl --user restart hermes-gateway"
        )

    with pytest.raises(RuntimeError, match="live-system guard"):
        asyncio.run(_attempt())


# ──────────────────── pkill / killall / taskkill ───────────────


def test_subprocess_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["pkill", "-f", "hermes"])


def test_subprocess_pkill_hermes_gateway_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["pkill", "-f", "hermes-gateway"])


def test_subprocess_pkill_python_dash_f_blocked():
    """``pkill -f python`` matches the gateway's "python -m hermes_cli.main"."""
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["pkill", "-f", "python"])


def test_subprocess_killall_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["killall", "hermes"])


def test_subprocess_env_wrapped_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "env",
                "EXAMPLE=1",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_env_inline_option_wrapped_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "env",
                "--unset=EXAMPLE",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_env_split_string_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "env",
                "-S",
                "pkill -f hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_shell_wrapped_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            ["sh", "-c", "pkill -f hermes-live-guard-sentinel-never-running"]
        )


def test_subprocess_chained_shell_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "sh",
                "-c",
                "true; pkill -f hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_combined_shell_options_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "bash",
                "-lc",
                "pkill -f hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_shell_positional_pkill_target_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "sh",
                "-c",
                'pkill -f "$1"',
                "guard",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


@pytest.mark.parametrize(
    "script",
    [
        'target=hermes; pkill -f "$target"',
        'target=hermes && pkill -f "$target"',
        'set -- hermes; pkill -f "$1"',
    ],
)
def test_subprocess_shell_stateful_pkill_target_blocked(script):
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(["sh", "-c", script])


def test_subprocess_raw_shell_list_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            "true; pkill -f hermes-live-guard-sentinel-never-running",
            shell=True,
        )


def test_subprocess_pipeline_supplied_pkill_target_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            "echo hermes | xargs pkill -f",
            shell=True,
        )


def test_subprocess_getoutput_raw_shell_list_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.getoutput(
            "true; pkill -f hermes-live-guard-sentinel-never-running"
        )


def test_os_system_raw_shell_list_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        os.system("true; pkill -f hermes-live-guard-sentinel-never-running")


def test_asyncio_raw_shell_list_pkill_hermes_blocked():
    import asyncio

    async def _attempt():
        await asyncio.create_subprocess_shell(
            "true; pkill -f hermes-live-guard-sentinel-never-running"
        )

    with pytest.raises(RuntimeError, match="live-system guard"):
        asyncio.run(_attempt())


def test_subprocess_cmd_wrapped_taskkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "cmd.exe",
                "/c",
                "taskkill",
                "/IM",
                "hermes-live-guard-sentinel-never-running.exe",
            ]
        )


def test_subprocess_time_wrapped_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "/usr/bin/time",
                "-p",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_timeout_wrapped_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "timeout",
                "5s",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_unknown_posix_wrapper_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "nice",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_command_producer_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "xargs",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_busybox_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "busybox",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_powershell_taskkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "powershell",
                "-Command",
                "taskkill /IM "
                "hermes-live-guard-sentinel-never-running.exe",
            ]
        )


def test_subprocess_rg_pre_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "rg",
                "--pre",
                "pkill -f hermes-live-guard-sentinel-never-running",
                "needle",
                "fixture.txt",
            ]
        )


@pytest.mark.parametrize("pre_option", ["--pre", "--pre=pkill -f"])
def test_subprocess_rg_pre_receives_protected_path_blocked(pre_option):
    command = ["rg", pre_option]
    if pre_option == "--pre":
        command.append("pkill -f")
    command.extend(
        ["needle", "hermes-live-guard-sentinel-never-running"]
    )
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(command)


def test_subprocess_rg_pre_after_path_receives_protected_path_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "rg",
                "needle",
                "hermes-live-guard-sentinel-never-running",
                "--pre",
                "pkill",
            ]
        )


def test_subprocess_rg_last_pre_wins_and_is_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "rg",
                "--pre",
                "true",
                "--pre",
                "pkill",
                "needle",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_env_argv0_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "env",
                "-a",
                "worker",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_env_clustered_options_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "env",
                "-iS",
                "pkill -f hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_env_clustered_argv0_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "env",
                "-ia",
                "true",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_sudo_clustered_prompt_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "sudo",
                "-np",
                "true",
                "pkill",
                "-f",
                "hermes-live-guard-sentinel-never-running",
            ]
        )


def test_subprocess_shell_exec_named_pkill_hermes_blocked():
    with pytest.raises(RuntimeError, match="live-system guard"):
        subprocess.run(
            [
                "bash",
                "-c",
                "exec -a worker pkill -f "
                "hermes-live-guard-sentinel-never-running",
            ]
        )


# Pass-through contract: the guard must forward data arguments unchanged,
# but no shell or process is needed to establish that relationship.
@pytest.mark.parametrize("command,options", [
    (["true", "skill", "/tmp/hermes-agent/skills"], {}),
    (["rg", "pkill", "/tmp/hermes-agent/skills/definitely-missing"], {}),
    (["rg", "--pre=pkill", "--no-pre", "needle", "/tmp/hermes-sentinel"], {}),
    (["git", "grep", "foo;pkill -f hermes", "--", "file.py"], {}),
    (["git", "grep", "taskkill.exe", "--", "hermes_cli"], {}),
    (["git", "grep", "SKILL", "--", "hermes_cli"], {}),
    (["git", "grep", "pkill", "--", "--full", "python"], {}),
    (["sh", "-c", "printf '%s\\\\n' skill /tmp/hermes-agent/skills"], {}),
    ("true skill; echo hermes", {"shell": True}),
])
def test_harmless_commands_reach_backend_unchanged(_live_system_guard_primitives, command, options):
    tape = _live_system_guard_primitives
    result = subprocess.run(command, **options)
    assert result is tape.result
    assert tape.calls == [("run", (command,), options)]


@pytest.mark.live_system_guard_bypass
def test_bypass_marker_disables_guard(_live_system_guard_primitives):
    tape = _live_system_guard_primitives
    assert not _live_system_guard_is_active()
    os.kill(os.getpid(), 0)
    assert tape.calls == [("os.kill", (os.getpid(), 0), {})]
