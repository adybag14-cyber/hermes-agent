import shutil
import sys
from pathlib import Path

from hermes_android.runtime_capabilities import android_command_path, parse_proc_memory_kib, read_runtime_capabilities


def test_capabilities_measure_app_storage_and_do_not_confuse_embedded_and_shell_python(tmp_path):
    (tmp_path / "skills").mkdir()
    env = {"PATH": str(tmp_path / "no-executables"), "HERMES_BUNDLED_SKILLS": str(tmp_path / "bundle")}
    payload = read_runtime_capabilities(tmp_path, environment=env)
    assert payload["storage"]["path"] == str(tmp_path)
    assert payload["storage"]["total_bytes"] == shutil.disk_usage(tmp_path).total
    assert 0 <= payload["storage"]["available_bytes"] <= payload["storage"]["total_bytes"]
    assert payload["embedded_python"]["version"] == ".".join(map(str, sys.version_info[:3]))
    # Derive the expectation from this host's actual command search, without faking the host OS.
    path = android_command_path(env["PATH"], "", False)
    assert payload["shell"]["python3_executable"] == shutil.which("python3", path=path)
    assert payload["skills"]["installed_directory"] == str(tmp_path / "skills")
    assert payload["skills"]["installed_directory_exists"] is True
    assert not (tmp_path / "workspace").exists()  # The diagnostic is read-only.


def test_memory_parser_uses_available_not_free_and_command_path_preserves_opt_in_boundary():
    fields = parse_proc_memory_kib("MemTotal: 10000 kB\nMemFree: 12 kB\nMemAvailable: 4000 kB\nBogus: -1 kB\n")
    assert fields["MemAvailable"] > fields["MemFree"]
    assert fields["MemTotal"] == 10000 * 1024
    assert "Bogus" not in fields
    prefix = str(Path.cwd() / "explicit-prefix")
    assert prefix not in android_command_path("", prefix, False)
    assert android_command_path("", prefix, True).endswith(prefix)
