"""Sync Android MCP JSON config into Hermes runtime config.yaml."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from hermes_android.python_path import prefer_hermes_package_root

prefer_hermes_package_root()

NATIVE_TRANSPORT = "native"


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
    _ = hermes_home, force

    # This module is reached from the Android app before the embedded Python
    # server has necessarily installed its bootstrap environment.  External
    # MCP transports create global loops and subprocesses whose lifetime is not
    # owned by the Android runtime, so the Android bridge must reject them by
    # identity rather than by a late environment-variable check.
    return {
        "synced": False,
        "reason": "embedded_runtime_external_mcp_disabled",
        "server_count": 0,
        "registered_tools": [],
    }


def reload_android_mcp_config(hermes_home: str | Path) -> str:
    return json.dumps(sync_android_mcp_config(hermes_home, force=True), sort_keys=True)
