import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_mcp_bridge_maps_android_json_to_runtime_servers():
    from hermes_android.mcp_bridge import android_server_to_runtime_config, build_runtime_mcp_servers

    android_config = {
        "mcpServers": {
            "hermes-native-tools": {
                "transport": "native",
                "enabled": True,
                "autoStart": True,
                "description": "Native tools",
            },
            "remote-sse": {
                "transport": "sse",
                "url": "http://127.0.0.1:8787/sse",
                "enabled": True,
                "autoStart": True,
            },
            "disabled-server": {
                "transport": "stdio",
                "command": "echo",
                "enabled": False,
            },
        }
    }

    runtime_servers = build_runtime_mcp_servers(android_config)

    assert set(runtime_servers) == {"hermes-native-tools", "remote-sse"}
    assert runtime_servers["hermes-native-tools"]["transport"] == "native"
    assert runtime_servers["remote-sse"]["url"] == "http://127.0.0.1:8787/sse"

    stdio = android_server_to_runtime_config(
        "demo",
        {"transport": "stdio", "command": "npx", "args": ["-y", "demo-mcp"], "enabled": True},
    )
    assert stdio["command"] == "npx"
    assert stdio["args"] == ["-y", "demo-mcp"]


def test_runtime_env_exposes_loopback_and_optional_lan_urls():
    from hermes_android.runtime_env import lan_base_url, loopback_base_url

    assert loopback_base_url("0.0.0.0", 8642) == "http://127.0.0.1:8642"
    lan = lan_base_url("0.0.0.0", 8642)
    assert lan is None or lan.startswith("http://")


def test_server_bridge_status_includes_loopback_base_url():
    bridge = (REPO_ROOT / "hermes_android" / "server_bridge.py").read_text(encoding="utf-8")
    assert "loopback_base_url" in bridge
    assert "lan_base_url" in bridge
    assert "sync_android_mcp_config" in bridge