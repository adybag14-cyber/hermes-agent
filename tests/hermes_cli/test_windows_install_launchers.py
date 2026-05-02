from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
INSTALL_PS1 = REPO_ROOT / "scripts" / "install.ps1"
INSTALL_CMD = REPO_ROOT / "scripts" / "install.cmd"


def test_install_cmd_prefers_powershell_7_before_windows_powershell():
    content = INSTALL_CMD.read_text(encoding="utf-8")

    assert "where pwsh.exe" in content
    assert "pwsh.exe -ExecutionPolicy ByPass -NoProfile" in content
    assert "powershell.exe -ExecutionPolicy ByPass -NoProfile" in content
    assert content.index("pwsh.exe -ExecutionPolicy") < content.index(
        "powershell.exe -ExecutionPolicy"
    )


def test_windows_installer_writes_modern_terminal_launchers():
    content = INSTALL_PS1.read_text(encoding="utf-8")

    assert "function Write-HermesWindowsLaunchers" in content
    assert "hermes-start.ps1" in content
    assert "hermes-start.cmd" in content
    assert "hermes-native-start.cmd" in content
    assert "hermes-native-window.ps1" in content
    assert "Resolve-HermesWindowsTerminalPath" in content
    assert "HERMES_START_DRY_RUN" in content
    assert "wt.exe" in content
    assert "Resolve-HermesPowerShellPath" in content
    assert "pwsh.exe" in content
    assert "powershell.exe" in content


def test_windows_installer_keeps_powershell_5_as_fallback_only():
    content = INSTALL_PS1.read_text(encoding="utf-8")

    pwsh_lookup = content.index("Get-Command pwsh.exe")
    fallback_lookup = content.index("Get-Command powershell.exe")
    assert pwsh_lookup < fallback_lookup
    assert "System32\\WindowsPowerShell\\v1.0\\powershell.exe" in content


def test_windows_installer_supports_user_and_elevated_launchers():
    content = INSTALL_PS1.read_text(encoding="utf-8")

    assert "Join-Path $HermesHome \"bin\"" in content
    assert "Join-Path $env:ProgramData \"Hermes\\bin\"" in content
    assert "Test-IsAdministrator" in content
    assert '"Path",' in content
    assert '"Machine"' in content
