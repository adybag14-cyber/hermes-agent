from pathlib import Path
import subprocess
import os
import shutil

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SETUP_SCRIPT = REPO_ROOT / "setup-hermes.sh"


def _bash_syntax_command(script: Path) -> list[str]:
    if os.name == "nt":
        git_bash_candidates = [
            Path(os.environ.get("GIT_BASH", "")),
            Path(os.environ.get("ProgramFiles", "")) / "Git" / "bin" / "bash.exe",
            Path(os.environ.get("ProgramFiles", "")) / "Git" / "usr" / "bin" / "bash.exe",
        ]
        for bash_path in git_bash_candidates:
            if bash_path.is_file():
                return [str(bash_path), "-n", str(script)]

        wsl = shutil.which("wsl.exe") or shutil.which("wsl")
        if wsl:
            converted = subprocess.run(
                [wsl, "wslpath", "-a", str(script)],
                capture_output=True,
                text=True,
            )
            if converted.returncode == 0 and converted.stdout.strip():
                return [wsl, "bash", "-n", converted.stdout.strip()]

    bash = shutil.which("bash")
    if bash:
        return [bash, "-n", str(script)]

    pytest.skip("bash is required to validate setup-hermes.sh syntax")


def test_setup_hermes_script_is_valid_shell():
    result = subprocess.run(_bash_syntax_command(SETUP_SCRIPT), capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_setup_hermes_script_has_termux_path():
    content = SETUP_SCRIPT.read_text(encoding="utf-8")

    assert "is_termux()" in content
    assert ".[termux]" in content
    assert "constraints-termux.txt" in content
    assert "$PREFIX/bin" in content
    assert "Skipping tinker-atropos on Termux" in content
