from unittest.mock import patch

from tools.environments.local import LocalEnvironment
import tools.environments.local as local_env


class TestLocalTempDir:
    def test_uses_os_tmpdir_for_session_artifacts(self, monkeypatch):
        monkeypatch.setattr(local_env, "_IS_WINDOWS", False)
        monkeypatch.setenv("TMPDIR", "/data/data/com.termux/files/usr/tmp")
        monkeypatch.delenv("TMP", raising=False)
        monkeypatch.delenv("TEMP", raising=False)

        with patch.object(LocalEnvironment, "init_session", autospec=True, return_value=None):
            env = LocalEnvironment(cwd=".", timeout=10)

        assert env.get_temp_dir() == "/data/data/com.termux/files/usr/tmp"
        assert env._snapshot_path == f"/data/data/com.termux/files/usr/tmp/hermes-snap-{env._session_id}.sh"
        assert env._cwd_file == f"/data/data/com.termux/files/usr/tmp/hermes-cwd-{env._session_id}.txt"

    def test_prefers_backend_env_tmpdir_override(self, monkeypatch):
        monkeypatch.setattr(local_env, "_IS_WINDOWS", False)
        monkeypatch.delenv("TMPDIR", raising=False)
        monkeypatch.delenv("TMP", raising=False)
        monkeypatch.delenv("TEMP", raising=False)

        with patch.object(LocalEnvironment, "init_session", autospec=True, return_value=None):
            env = LocalEnvironment(
                cwd=".",
                timeout=10,
                env={"TMPDIR": "/data/data/com.termux/files/home/.cache/hermes-tmp/"},
            )

        assert env.get_temp_dir() == "/data/data/com.termux/files/home/.cache/hermes-tmp"
        assert env._snapshot_path == (
            f"/data/data/com.termux/files/home/.cache/hermes-tmp/hermes-snap-{env._session_id}.sh"
        )
        assert env._cwd_file == (
            f"/data/data/com.termux/files/home/.cache/hermes-tmp/hermes-cwd-{env._session_id}.txt"
        )

    def test_falls_back_to_tempfile_when_tmp_missing(self, monkeypatch):
        monkeypatch.setattr(local_env, "_IS_WINDOWS", False)
        monkeypatch.delenv("TMPDIR", raising=False)
        monkeypatch.delenv("TMP", raising=False)
        monkeypatch.delenv("TEMP", raising=False)

        with patch("tools.environments.local.os.path.isdir", return_value=False), \
             patch("tools.environments.local.os.access", return_value=False), \
             patch("tools.environments.local.tempfile.gettempdir", return_value="/cache/tmp"), \
             patch.object(LocalEnvironment, "init_session", autospec=True, return_value=None):
            env = LocalEnvironment(cwd=".", timeout=10)
            assert env.get_temp_dir() == "/cache/tmp"
            assert env._snapshot_path == f"/cache/tmp/hermes-snap-{env._session_id}.sh"
            assert env._cwd_file == f"/cache/tmp/hermes-cwd-{env._session_id}.txt"

    def test_windows_tempdir_uses_host_accessible_forward_slash_path(self, monkeypatch):
        monkeypatch.setattr(local_env, "_IS_WINDOWS", True)
        with patch.dict("tools.environments.local.os.environ", {}, clear=True), \
             patch("tools.environments.local.tempfile.gettempdir", return_value=r"C:\Users\Alice\AppData\Local\Temp"), \
             patch.object(LocalEnvironment, "init_session", autospec=True, return_value=None):
            env = LocalEnvironment(cwd=r"C:\Users\Alice\project", timeout=10)
            assert env.cwd == "C:/Users/Alice/project"
            assert env.get_temp_dir() == "C:/Users/Alice/AppData/Local/Temp"
            assert env._snapshot_path == f"C:/Users/Alice/AppData/Local/Temp/hermes-snap-{env._session_id}.sh"
            assert env._cwd_file == f"C:/Users/Alice/AppData/Local/Temp/hermes-cwd-{env._session_id}.txt"
