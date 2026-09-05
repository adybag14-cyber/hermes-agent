"""Native Windows smoke through the real terminal tool, not a raw shell."""
import json
import shlex
import shutil
import uuid

import pytest

pytestmark = pytest.mark.windows_only


@pytest.mark.parametrize("command,background,expected", [
    ("Get-ChildItem | Select-Object -First 1 -ExpandProperty Name", False, "native-probe.txt"),
    ("dir /b", False, "native-probe.txt"),
    ("Write-Output BG_PS_OK", True, "BG_PS_OK"),
])
def test_real_windows_terminal_explicit_shells_capture_output(tmp_path, monkeypatch, command, background, expected):
    from tools.terminal_tool import terminal_tool
    from tools.terminal_tool_lifecycle import cleanup_vm
    from tools.process_registry import process_registry

    task_id = "windows-native-probe-" + uuid.uuid4().hex
    workdir = tmp_path / "terminal-work"
    workdir.mkdir()
    (workdir / "native-probe.txt").write_text("fixture", encoding="utf-8")
    monkeypatch.setenv("TERMINAL_ENV", "local")
    monkeypatch.setenv("TERMINAL_CWD", str(workdir))
    monkeypatch.setenv("TERMINAL_TIMEOUT", "30")
    # This branch's local terminal is Git Bash, not an automatic shell router.
    # The backend disables MSYS argv conversion, so cmd takes normal /switches.
    # Leave the installed launchers unchanged.
    if command == "dir /b":
        executable = shutil.which("cmd.exe")
        assert executable
        command = f'{shlex.quote(executable)} /d /s /c {shlex.quote(command)}'
    else:
        executable = shutil.which("pwsh.exe") or shutil.which("powershell.exe")
        assert executable
        command = f'{shlex.quote(executable)} -NoProfile -NonInteractive -Command {shlex.quote(command)}'
    try:
        result = json.loads(terminal_tool(
            command=command, background=background, timeout=30, force=True,
            task_id=task_id, workdir=str(workdir),
        ))
        assert not result.get("error"), result
        if background:
            assert result.get("session_id"), result
            result = process_registry.wait(result["session_id"], timeout=30)
            assert result["status"] == "exited", result
        assert result["exit_code"] == 0, result
        assert expected in result["output"], result
        assert "#< CLIXML" not in result["output"]
    finally:
        cleanup_vm(task_id, raise_on_error=True)
