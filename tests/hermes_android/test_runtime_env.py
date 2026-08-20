import os
import sys

from hermes_android.runtime_env import prepare_runtime_env
from hermes_android.runtime_identity import is_embedded_android_runtime


_RUNTIME_ENV_OUTPUTS = (
    "HERMES_ANDROID_BOOTSTRAP",
    "API_SERVER_HOST",
    "API_SERVER_PORT",
    "API_SERVER_KEY",
    "API_SERVER_MODEL_NAME",
)


def _register_runtime_env_for_test_cleanup(monkeypatch):
    # prepare_runtime_env intentionally mutates os.environ directly in
    # production. Register each output with pytest's MonkeyPatch first so its
    # teardown owns and removes those writes even when an assertion fails.
    for name in _RUNTIME_ENV_OUTPUTS:
        monkeypatch.setenv(name, "")


def test_prepare_runtime_env_sets_android_env_and_dirs(tmp_path, monkeypatch):
    _register_runtime_env_for_test_cleanup(monkeypatch)
    files_dir = tmp_path / "files"
    runtime = prepare_runtime_env(
        files_dir,
        api_server_port=8765,
        api_server_key="secret-key",
    )

    assert runtime.files_dir == files_dir.resolve()
    assert runtime.hermes_home == files_dir.resolve() / "hermes-home"
    # Default bind is all interfaces so LAN clients can reach the agent API;
    # status URLs still advertise loopback separately for on-device clients.
    assert runtime.api_server_host == "0.0.0.0"
    assert runtime.api_server_port == 8765
    assert runtime.api_server_key == "secret-key"

    for child in ("logs", "sessions", "skills", "downloads", "workspace"):
        assert (runtime.hermes_home / child).is_dir()

    assert os.environ["HERMES_HOME"] == str(runtime.hermes_home)
    assert os.environ["HERMES_ANDROID_BOOTSTRAP"] == "1"
    assert os.environ["API_SERVER_HOST"] == "0.0.0.0"
    assert os.environ["API_SERVER_PORT"] == "8765"
    assert os.environ["API_SERVER_KEY"] == "secret-key"
    assert os.environ["API_SERVER_MODEL_NAME"] == "hermes-agent-android"


def test_prepare_runtime_env_generates_port_and_key(tmp_path, monkeypatch):
    _register_runtime_env_for_test_cleanup(monkeypatch)
    runtime = prepare_runtime_env(tmp_path / "files")

    assert runtime.api_server_port > 0
    assert runtime.api_server_key


def test_runtime_identity_recognizes_android_platform_before_env_setup(monkeypatch):
    monkeypatch.delenv("HERMES_ANDROID_BOOTSTRAP", raising=False)
    monkeypatch.setattr(sys, "platform", "android")

    assert is_embedded_android_runtime() is True
