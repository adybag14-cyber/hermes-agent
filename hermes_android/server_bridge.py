from __future__ import annotations

import json
from typing import Any

from hermes_android.runtime_env import lan_base_url, loopback_base_url
from hermes_android.server import (
    AndroidServerHandle,
    AndroidServerStartupError,
    start_local_api_server,
)

_ACTIVE_HANDLE: AndroidServerHandle | None = None
_UNSAFE_STARTUP_HANDLE: AndroidServerHandle | None = None
_UNSAFE_STARTUP_ERROR = ""
_UNSAFE_STOP_ERROR = ""


def _status_payload(handle: AndroidServerHandle | None) -> dict[str, Any]:
    if handle is None:
        return {"started": False}
    if not handle.thread.is_alive():
        if handle.shutdown_complete.is_set():
            return {"started": False}
        return {
            "started": False,
            "requires_app_restart": True,
            "error": handle._incomplete_shutdown_detail(),
        }
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
    global _ACTIVE_HANDLE, _UNSAFE_STARTUP_HANDLE, _UNSAFE_STARTUP_ERROR, _UNSAFE_STOP_ERROR
    if _UNSAFE_STOP_ERROR:
        raise RuntimeError(_UNSAFE_STOP_ERROR)
    if _UNSAFE_STARTUP_HANDLE is not None:
        if not _UNSAFE_STARTUP_HANDLE.shutdown_complete.is_set():
            raise RuntimeError(
                _UNSAFE_STARTUP_ERROR
                or "A previous Android API server startup did not prove clean shutdown. "
                "Force stop and reopen Hermes before retrying."
            )
        _UNSAFE_STARTUP_HANDLE = None
        _UNSAFE_STARTUP_ERROR = ""
    if _ACTIVE_HANDLE is not None and not _ACTIVE_HANDLE.thread.is_alive():
        if _ACTIVE_HANDLE.shutdown_complete.is_set():
            _ACTIVE_HANDLE = None
        else:
            _UNSAFE_STOP_ERROR = _ACTIVE_HANDLE._incomplete_shutdown_detail()
            raise RuntimeError(_UNSAFE_STOP_ERROR)
    if _ACTIVE_HANDLE is None:
        try:
            _ACTIVE_HANDLE = start_local_api_server(files_dir)
        except AndroidServerStartupError as exc:
            if exc.unsafe_handle is not None:
                _UNSAFE_STARTUP_HANDLE = exc.unsafe_handle
                _UNSAFE_STARTUP_ERROR = str(exc)
            raise
    return json.dumps(_status_payload(_ACTIVE_HANDLE), sort_keys=True)


def current_server_status() -> str:
    if _UNSAFE_STOP_ERROR:
        return json.dumps(
            {
                "started": False,
                "requires_app_restart": True,
                "error": _UNSAFE_STOP_ERROR,
            },
            sort_keys=True,
        )
    if _UNSAFE_STARTUP_HANDLE is not None:
        if not _UNSAFE_STARTUP_HANDLE.shutdown_complete.is_set():
            return json.dumps(
                {
                    "started": False,
                    "requires_app_restart": True,
                    "error": _UNSAFE_STARTUP_ERROR
                    or _UNSAFE_STARTUP_HANDLE._incomplete_shutdown_detail(),
                },
                sort_keys=True,
            )
        return json.dumps({"started": False}, sort_keys=True)
    return json.dumps(_status_payload(_ACTIVE_HANDLE), sort_keys=True)


def stop_server() -> str:
    global _ACTIVE_HANDLE, _UNSAFE_STARTUP_HANDLE, _UNSAFE_STARTUP_ERROR, _UNSAFE_STOP_ERROR
    if _UNSAFE_STOP_ERROR:
        raise RuntimeError(_UNSAFE_STOP_ERROR)
    if _UNSAFE_STARTUP_HANDLE is not None:
        try:
            if not _UNSAFE_STARTUP_HANDLE.shutdown_complete.is_set():
                _UNSAFE_STARTUP_HANDLE.stop()
        except Exception as exc:
            _UNSAFE_STOP_ERROR = (
                "The prior Android API server could not prove that its agent/tool "
                f"workers stopped ({exc}). Force stop and reopen Hermes before retrying."
            )
            raise RuntimeError(_UNSAFE_STOP_ERROR) from exc
        _UNSAFE_STARTUP_HANDLE = None
        _UNSAFE_STARTUP_ERROR = ""
    if _ACTIVE_HANDLE is not None:
        try:
            _ACTIVE_HANDLE.stop()
        except Exception as exc:
            _UNSAFE_STOP_ERROR = (
                "The Android API server could not prove that its agent/tool workers "
                f"stopped ({exc}). Force stop and reopen Hermes before starting "
                "another runtime."
            )
            raise RuntimeError(_UNSAFE_STOP_ERROR) from exc
        _ACTIVE_HANDLE = None
    return json.dumps({"started": False}, sort_keys=True)
