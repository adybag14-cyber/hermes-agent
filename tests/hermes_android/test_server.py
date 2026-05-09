import asyncio
from unittest.mock import patch

import pytest

from hermes_android.server import start_local_api_server


class FakeAdapter:
    def __init__(self, config):
        self.config = config
        self.connected = False
        self.disconnected = False

    async def connect(self):
        self.connected = True
        return True

    async def disconnect(self):
        self.disconnected = True


class SlowAdapter:
    def __init__(self, config):
        self.config = config

    async def connect(self):
        await asyncio.sleep(5)
        return True


def test_start_local_api_server_bootstraps_and_starts(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8765,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }

    with patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload), \
         patch("hermes_android.server.APIServerAdapter", FakeAdapter):
        handle = start_local_api_server(str(tmp_path / "files"), api_server_port=8765, api_server_key="android-key")

    try:
        assert handle.base_url == "http://127.0.0.1:8765"
        assert handle.adapter.connected is True
        assert handle.adapter.config.extra["host"] == "127.0.0.1"
        assert handle.adapter.config.extra["port"] == 8765
        assert handle.adapter.config.extra["key"] == "android-key"
    finally:
        handle.stop()
        assert handle.adapter.disconnected is True


def test_start_local_api_server_timeout_is_actionable(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8766,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }

    with patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload), \
         patch("hermes_android.server.APIServerAdapter", SlowAdapter), \
         pytest.raises(TimeoutError) as error:
        start_local_api_server(
            str(tmp_path / "files"),
            api_server_port=8766,
            api_server_key="android-key",
            connect_timeout=0.01,
        )

    message = str(error.value)
    assert "Timed out starting the Android local API server" in message
    assert "Free phone storage" in message
