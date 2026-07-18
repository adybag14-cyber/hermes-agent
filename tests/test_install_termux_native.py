from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
INSTALL = REPO_ROOT / "scripts" / "install-termux.sh"
INSTALL_SH = REPO_ROOT / "scripts" / "install.sh"


def test_termux_installer_is_uv_only_and_serializes_native_builds() -> None:
    text = INSTALL.read_text(encoding="utf-8")
    assert "uv venv --python" in text
    assert "uv pip install --python" in text
    assert "python -m pip" not in text
    assert "UV_LINK_MODE=copy" in text
    assert "UV_CONCURRENT_BUILDS=1" in text
    assert "CARGO_BUILD_JOBS=1" in text
    assert "UV_DEFAULT_INDEX" in text
    assert "unset UV_INDEX UV_EXTRA_INDEX_URL PIP_INDEX_URL PIP_EXTRA_INDEX_URL" in text


def test_termux_installer_rejects_python_314_and_preserves_system_aliases() -> None:
    text = INSTALL.read_text(encoding="utf-8")
    assert "(3, 11) <= sys.version_info < (3, 14)" in text
    assert "apt download python3.11" in text
    assert '"$staged_prefix/bin/python"' in text
    assert '"$staged_prefix/bin/python3"' in text
    assert 'cp -a "$staged_prefix/." "$PREFIX/"' in text


def test_termux_installer_has_launcher_recovery_and_version_smoke() -> None:
    text = INSTALL.read_text(encoding="utf-8")
    assert "venv.pre-native-termux" in text
    assert "unset PYTHONPATH" in text
    assert 'exec "$venv_hermes"' in text
    assert "--version|-V|version)" in text
    assert '"$PREFIX/bin/hermes" --version' in text
    assert "uv pip check --python" in text


def test_dispatch_keeps_established_installer_contracts_in_place() -> None:
    text = INSTALL_SH.read_text(encoding="utf-8")
    dispatch = "# Native Termux uses a dedicated uv-based installer."
    assert dispatch in text
    assert text.index(dispatch) < text.index("# Parse arguments")
    for signature in (
        "resolve_install_layout() {",
        "install_system_packages() {",
        "setup_path() {",
        "run_setup_wizard() {",
        "maybe_start_gateway() {",
    ):
        assert signature in text
    assert not (INSTALL_SH.parent / "install-legacy.sh").exists()


def test_termux_dispatches_to_local_installer_without_network(tmp_path: Path) -> None:
    scripts = tmp_path / "scripts"
    scripts.mkdir()
    shutil.copy2(INSTALL_SH, scripts / "install.sh")
    child = scripts / "install-termux.sh"
    child.write_text("#!/bin/bash\nprintf 'termux:%s\\n' \"$*\"\n", encoding="utf-8")
    child.chmod(0o755)

    env = os.environ.copy()
    env.update({"TERMUX_VERSION": "0.118", "PREFIX": "/data/data/com.termux/files/usr"})
    result = subprocess.run(
        ["bash", str(scripts / "install.sh"), "--skip-setup"],
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    assert result.stdout.strip() == "termux:--skip-setup"


def test_streamed_termux_dispatch_honors_requested_ref(tmp_path: Path) -> None:
    scripts = tmp_path / "scripts"
    scripts.mkdir()
    shutil.copy2(INSTALL_SH, scripts / "install.sh")

    stub = tmp_path / "install-termux.stub.sh"
    stub.write_text("#!/bin/bash\nprintf 'remote:%s\\n' \"$*\"\n", encoding="utf-8")
    stub.chmod(0o755)

    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    fake_curl = fake_bin / "curl"
    fake_curl.write_text(
        "#!/bin/bash\n"
        "url=''\nout=''\n"
        "while [ \"$#\" -gt 0 ]; do\n"
        "  case \"$1\" in\n"
        "    -o) out=\"$2\"; shift 2 ;;\n"
        "    -*) shift ;;\n"
        "    *) url=\"$1\"; shift ;;\n"
        "  esac\n"
        "done\n"
        "printf '%s\\n' \"$url\" > \"$CURL_LOG\"\n"
        "cp \"$TERMUX_STUB_SOURCE\" \"$out\"\n",
        encoding="utf-8",
    )
    fake_curl.chmod(0o755)

    env = os.environ.copy()
    env.update(
        {
            "TERMUX_VERSION": "0.118",
            "PREFIX": "/data/data/com.termux/files/usr",
            "PATH": f"{fake_bin}:{env['PATH']}",
            "TERMUX_STUB_SOURCE": str(stub),
            "CURL_LOG": str(tmp_path / "curl.log"),
        }
    )
    result = subprocess.run(
        [
            "bash",
            str(scripts / "install.sh"),
            "--branch",
            "feature/native",
            "--skip-setup",
        ],
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    assert result.stdout.strip() == "remote:--branch feature/native --skip-setup"
    assert (tmp_path / "curl.log").read_text(encoding="utf-8").strip().endswith(
        "/feature/native/scripts/install-termux.sh"
    )
