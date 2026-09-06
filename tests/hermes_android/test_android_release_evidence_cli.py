"""The release CLI must resolve its own siblings, not an installed scripts namespace."""

import os
from pathlib import Path
import subprocess
import sys


def test_release_cli_runs_with_an_unrelated_scripts_package_on_pythonpath(tmp_path):
    shadow = tmp_path / "scripts"
    shadow.mkdir()
    (shadow / "__init__.py").write_text("unrelated_package = True\n", encoding="utf-8")
    script = Path(__file__).resolve().parents[2] / "scripts/android_release_evidence.py"
    result = subprocess.run(
        [sys.executable, str(script), "--help"],
        cwd=tmp_path,
        env={**os.environ, "PYTHONPATH": str(tmp_path)},
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "source-identity" in result.stdout
