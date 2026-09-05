from unittest.mock import patch



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


def test_embedded_android_mcp_sync_never_persists_or_registers_external_servers(
    tmp_path,
    monkeypatch,
):
    from hermes_android.mcp_bridge import sync_android_mcp_config

    monkeypatch.delenv("HERMES_ANDROID_BOOTSTRAP", raising=False)
    config_dir = tmp_path / "mcp"
    config_dir.mkdir()
    (config_dir / "mcp_config.json").write_text(
        '{"mcpServers":{"unsafe":{"command":"unowned-child"}}}',
        encoding="utf-8",
    )
    with (
        patch("hermes_android.mcp_bridge.load_config", create=True) as load_config,
        patch("hermes_android.mcp_bridge.save_config", create=True) as save_config,
        patch("tools.mcp_tool_discovery.register_mcp_servers") as register,
    ):
        result = sync_android_mcp_config(tmp_path, force=True)

    assert result == {
        "synced": False,
        "reason": "embedded_runtime_external_mcp_disabled",
        "server_count": 0,
        "registered_tools": [],
    }
    load_config.assert_not_called()
    save_config.assert_not_called()
    register.assert_not_called()
