from __future__ import annotations

import json
from typing import Any

from hermes_android.mcp_bridge import sync_android_mcp_config
from hermes_android.runtime_env import lan_base_url, loopback_base_url
from hermes_android.server import AndroidServerHandle, start_local_api_server

_ACTIVE_HANDLE: AndroidServerHandle | None = None


def _status_payload(handle: AndroidServerHandle | None) -> dict[str, Any]:
    if handle is None:
        return {"started": False}
    host = handle.runtime.api_server_host
    port = handle.runtime.api_server_port
    loopback_url = loopback_base_url(host, port)
    lan_url = lan_base_url(host, port)
    payload: dict[str, Any] = {
        "started": True,
        "base_url": loopback_url,
        "loopback_base_url": loopback_url,
        "api_server_host": host,
        "api_server_port": port,
        "api_server_key": handle.runtime.api_server_key,
        "api_server_model_name": handle.runtime.api_server_model_name,
        "hermes_home": str(handle.runtime.hermes_home),
    }
    if lan_url:
        payload["lan_base_url"] = lan_url
    return payload


def ensure_server(files_dir: str) -> str:
    global _ACTIVE_HANDLE
    if _ACTIVE_HANDLE is None:
        _ACTIVE_HANDLE = start_local_api_server(files_dir)
        sync_android_mcp_config(_ACTIVE_HANDLE.runtime.hermes_home)
    return json.dumps(_status_payload(_ACTIVE_HANDLE), sort_keys=True)


def current_server_status() -> str:
    return json.dumps(_status_payload(_ACTIVE_HANDLE), sort_keys=True)


def stop_server() -> str:
    global _ACTIVE_HANDLE
    if _ACTIVE_HANDLE is not None:
        _ACTIVE_HANDLE.stop()
        _ACTIVE_HANDLE = None
    return json.dumps({"started": False}, sort_keys=True)
