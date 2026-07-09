"""Sync Android MCP JSON config into Hermes runtime config.yaml."""

from __future__ import annotations

import hashlib
import json
import logging
from copy import deepcopy
from pathlib import Path
from typing import Any

from hermes_android.python_path import prefer_hermes_package_root

prefer_hermes_package_root()

from hermes_cli.config import load_config, save_config

logger = logging.getLogger(__name__)

_LAST_SYNCED_HASH: str | None = None

ANDROID_MCP_RELATIVE_PATH = Path("mcp") / "mcp_config.json"
NATIVE_TRANSPORT = "native"


def _config_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _android_mcp_config_path(hermes_home: str | Path) -> Path:
    return Path(hermes_home).expanduser().resolve() / ANDROID_MCP_RELATIVE_PATH


def _enabled_servers(android_config: dict[str, Any]) -> dict[str, dict[str, Any]]:
    servers = android_config.get("mcpServers") or android_config.get("mcp_servers") or {}
    if not isinstance(servers, dict):
        return {}
    enabled: dict[str, dict[str, Any]] = {}
    for name, raw in servers.items():
        if not isinstance(raw, dict):
            continue
        if raw.get("enabled", True) is False:
            continue
        enabled[str(name)] = raw
    return enabled


def android_server_to_runtime_config(name: str, server: dict[str, Any]) -> dict[str, Any]:
    transport = str(server.get("transport") or "stdio").strip().lower()
    if transport == NATIVE_TRANSPORT:
        return {
            "transport": NATIVE_TRANSPORT,
            "description": server.get("description") or "Hermes Android native tools",
            "auto_start": bool(server.get("autoStart", True)),
        }

    runtime_entry: dict[str, Any] = {
        "description": server.get("description") or f"Android MCP server {name}",
        "auto_start": bool(server.get("autoStart", False)),
    }
    if transport in {"sse", "http", "streamable_http"}:
        url = str(server.get("url") or "").strip()
        if url:
            runtime_entry["url"] = url
        headers = server.get("headers")
        if isinstance(headers, dict) and headers:
            runtime_entry["headers"] = headers
        return runtime_entry

    command = str(server.get("command") or "").strip()
    if command:
        runtime_entry["command"] = command
    args = server.get("args")
    if isinstance(args, list):
        runtime_entry["args"] = [str(item) for item in args]
    env = server.get("env")
    if isinstance(env, dict) and env:
        runtime_entry["env"] = {str(key): str(value) for key, value in env.items()}
    return runtime_entry


def build_runtime_mcp_servers(android_config: dict[str, Any]) -> dict[str, dict[str, Any]]:
    runtime_servers: dict[str, dict[str, Any]] = {}
    for name, server in _enabled_servers(android_config).items():
        runtime_servers[name] = android_server_to_runtime_config(name, server)
    return runtime_servers


def sync_android_mcp_config(
    hermes_home: str | Path,
    *,
    force: bool = False,
) -> dict[str, Any]:
    global _LAST_SYNCED_HASH

    config_path = _android_mcp_config_path(hermes_home)
    if not config_path.is_file():
        return {"synced": False, "reason": "missing_config", "server_count": 0}

    try:
        android_config = json.loads(config_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return {"synced": False, "reason": f"invalid_json:{error}", "server_count": 0}

    if not isinstance(android_config, dict):
        return {"synced": False, "reason": "invalid_root", "server_count": 0}

    runtime_servers = build_runtime_mcp_servers(android_config)
    payload_hash = _config_hash(runtime_servers)
    if not force and payload_hash == _LAST_SYNCED_HASH:
        return {
            "synced": False,
            "reason": "unchanged",
            "server_count": len(runtime_servers),
        }

    config = load_config()
    config["mcp_servers"] = runtime_servers
    save_config(config)
    _LAST_SYNCED_HASH = payload_hash

    registered: list[str] = []
    try:
        from tools.mcp_tool import register_mcp_servers

        registered = register_mcp_servers(deepcopy(runtime_servers))
    except Exception as error:  # noqa: BLE001 - runtime may not be ready yet
        logger.debug("Deferred MCP registration after config sync: %s", error)

    return {
        "synced": True,
        "reason": "updated",
        "server_count": len(runtime_servers),
        "registered_tools": registered,
    }


def reload_android_mcp_config(hermes_home: str | Path) -> str:
    return json.dumps(sync_android_mcp_config(hermes_home, force=True), sort_keys=True)