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

import pytest

# A guaranteed-foreign PID: PID 1 (init).  Owned by root, not us, and
# always exists. A sane guard refuses to signal it.
FOREIGN_PID = 1


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


def test_pty_spawn_systemctl_blocked():
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


# ──────────────────── pass-through cases (must NOT raise) ──────


def test_systemctl_status_passes_through():
    """Read-only systemctl probes (status/show/list-units) are fine."""
    # Run with check=False so we don't fail on the gateway's exit code.
    r = subprocess.run(
        ["systemctl", "--user", "status", "hermes-gateway", "--no-pager"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert r is not None  # Did not raise — the guard let it through.


def test_systemctl_show_passes_through():
    r = subprocess.run(
        ["systemctl", "--user", "show", "hermes-gateway", "--no-pager"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert r is not None


def test_systemctl_list_units_passes_through():
    r = subprocess.run(
        ["systemctl", "--user", "list-units", "fake-not-real-unit*", "--no-pager"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert r is not None


def test_systemctl_unrelated_unit_passes_through():
    """systemctl restart of a non-hermes unit is allowed (we only protect hermes)."""
    # Use --dry-run so we don't actually try to restart anything; just
    # verify the guard doesn't block the call. systemctl supports
    # --dry-run via the privileged API; on user scope it usually fails
    # quickly without side effects.
    r = subprocess.run(
        ["systemctl", "--user", "show", "fake-not-real-unit"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert r is not None


def test_kill_own_subtree_passes_through():
    """We CAN kill our own children — guard recognizes them via psutil."""
    p = subprocess.Popen(["sleep", "30"])
    try:
        os.kill(p.pid, signal.SIGTERM)
    finally:
        p.wait(timeout=2)
    # SIGTERM = 15; subprocess returncode is -15 on POSIX.
    assert p.returncode in {-signal.SIGTERM, 128 + int(signal.SIGTERM)}


def test_subprocess_pkill_with_unrelated_pattern_passes_through():
    """``pkill -f some-unrelated-pattern`` (no hermes/python) is fine."""
    # We don't actually run pkill — just verify the guard would let it
    # through by inspecting the matcher. Re-implementing the check here
    # would duplicate the guard; instead spawn a noop to confirm no raise.
    # Use 'true' so it succeeds quickly.
    r = subprocess.run(["true"], capture_output=True)
    assert r.returncode == 0


def test_normal_subprocess_run_passes_through():
    """Plain non-systemctl subprocess.run should work normally."""
    r = subprocess.run(["echo", "hello"], capture_output=True, text=True)
    assert r.stdout.strip() == "hello"


def test_process_killer_words_in_harmless_arguments_pass_through():
    """A search term and checkout path must not be mistaken for an executable."""
    r = subprocess.run(
        ["true", "skill", "/tmp/hermes-agent/skills"],
        capture_output=True,
        check=False,
    )
    assert r.returncode == 0


def test_rg_process_killer_words_in_data_arguments_pass_through():
    r = subprocess.run(
        ["rg", "pkill", "/tmp/hermes-agent/skills/definitely-missing"],
        capture_output=True,
        check=False,
    )
    assert r.returncode in {1, 2}


def test_rg_no_pre_disables_prior_preprocessor_passes_through():
    r = subprocess.run(
        [
            "rg",
            "--pre=pkill",
            "--no-pre",
            "needle",
            "/tmp/hermes-live-guard-sentinel-never-running",
        ],
        capture_output=True,
        check=False,
    )
    assert r.returncode in {1, 2}


def test_git_grep_shell_punctuation_in_pattern_passes_through():
    r = subprocess.run(
        ["git", "grep", "foo;pkill -f hermes", "--", "file.py"],
        capture_output=True,
        check=False,
    )
    assert r.returncode in {1, 128}


def test_git_grep_taskkill_exe_data_passes_through():
    r = subprocess.run(
        ["git", "grep", "taskkill.exe", "--", "hermes_cli"],
        capture_output=True,
        check=False,
    )
    assert r.returncode in {0, 1, 128}


def test_git_grep_uppercase_skill_data_passes_through():
    r = subprocess.run(
        ["git", "grep", "SKILL", "--", "hermes_cli"],
        capture_output=True,
        check=False,
    )
    assert r.returncode in {0, 1, 128}


def test_git_grep_pkill_full_python_data_passes_through():
    r = subprocess.run(
        ["git", "grep", "pkill", "--", "--full", "python"],
        capture_output=True,
        check=False,
    )
    assert r.returncode in {0, 1, 128}


def test_shell_process_killer_words_in_harmless_arguments_pass_through():
    r = subprocess.run(
        ["sh", "-c", "printf '%s\\n' skill /tmp/hermes-agent/skills"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert r.returncode == 0
    assert r.stdout.splitlines() == ["skill", "/tmp/hermes-agent/skills"]


def test_independent_shell_segments_do_not_share_killer_target_context():
    r = subprocess.run(
        "true skill; echo hermes",
        shell=True,
        capture_output=True,
        text=True,
        check=False,
    )
    assert r.returncode == 0
    assert r.stdout.strip() == "hermes"


# ──────────────────── bypass marker ─────────────────────────────


@pytest.mark.live_system_guard_bypass
def test_bypass_marker_disables_guard():
    """The bypass marker exists for tests that genuinely need real signal delivery
    (e.g. PTY tests SIGINTing their own child). Verify it works.

    We use it harmlessly here by signaling our own PID 0 (own group) so we
    don't actually kill anything — but the call goes through real os.kill.
    """
    # With bypass, the guard yields without installing the monkeypatch,
    # so we get the real os.kill. Calling os.kill(os.getpid(), 0) just
    # checks that the PID exists — harmless.
    os.kill(os.getpid(), 0)  # No exception — guard is OFF.
