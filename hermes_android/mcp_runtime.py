"""App-consent gate and attachment to the existing Android API-server owner."""
from __future__ import annotations

import json
from pathlib import Path


def external_mcp_allowed() -> bool:
    # The installed build and app-private affirmative opt-in are authoritative.
    # A retained JSON file or an environment variable cannot enable this feature.
    try:
        from java import jclass
    except ImportError:
        return False
    return bool(jclass("com.mobilefork.hermesagent.data.McpRuntimeBridge").externalMcpAllowed())


def read_mcp_configuration(hermes_home, *, auto_start_only=False):
    path = Path(hermes_home) / "mcp/mcp_config.json"
    if not path.exists():
        return {}
    if path.is_symlink() or path.stat().st_size > 200000:
        raise ValueError("MCP configuration file is linked or too large")
    config = json.loads(path.read_text(encoding="utf-8"))
    if auto_start_only and isinstance(config, dict):
        servers = config.get("mcpServers", config.get("mcp_servers", {}))
        if isinstance(servers, dict):
            config = {"mcpServers": {name: value for name, value in servers.items()
                                    if isinstance(value, dict) and value.get("autoStart", False)}}
    return config


async def initialize_owned_mcp(adapter, hermes_home):
    from hermes_android.mcp_supervisor import McpSupervisor
    from hermes_android.mcp_tools import AndroidMcpTools

    supervisor = McpSupervisor(hermes_home)
    adapter._android_mcp = AndroidMcpTools(supervisor)
    if external_mcp_allowed():
        await supervisor.reload(read_mcp_configuration(hermes_home, auto_start_only=True), enabled=True)


async def reload_owned_mcp(adapter, hermes_home):
    owner = getattr(adapter, "_android_mcp", None)
    if owner is None or owner.supervisor.hermes_home.resolve() != Path(hermes_home).resolve():
        raise RuntimeError("MCP does not have this Android runtime's owner")
    allowed = external_mcp_allowed()
    try:
        config = read_mcp_configuration(hermes_home) if allowed else {}
    except (OSError, ValueError):
        await owner.supervisor.shutdown()
        raise
    status = await owner.supervisor.reload(config, enabled=allowed)
    return {"synced": True, "reason": "owned_runtime" if allowed else "external_mcp_not_enabled", **status}
