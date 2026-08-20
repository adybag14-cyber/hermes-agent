"""Exercise the Android API constructor through the actual model tool schemas."""
import copy

import pytest


@pytest.fixture
def android_schema_adapter(monkeypatch):
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from gateway.run import GatewayRunner
    from hermes_cli.config_defaults import DEFAULT_CONFIG
    from agent.model_metadata import MINIMUM_CONTEXT_LENGTH

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    config = copy.deepcopy(DEFAULT_CONFIG)
    config["model"] = {
        "default": "schema-test", "provider": "custom",
        "base_url": "http://127.0.0.1:1/v1", "context_length": MINIMUM_CONTEXT_LENGTH,
    }
    config["memory"]["provider"] = ""
    config["platform_toolsets"] = {"api_server": ["hermes-android-app"]}
    config["tools"]["tool_search"] = {"enabled": "on", "defer": ["__android_unowned_probe__"]}
    monkeypatch.setattr("hermes_cli.config.load_config", lambda: config)
    monkeypatch.setattr("hermes_cli.config.load_config_readonly", lambda: config)
    monkeypatch.setattr("gateway.run._load_gateway_config", lambda: config)
    monkeypatch.setattr("gateway.run._resolve_gateway_model", lambda: "schema-test")
    monkeypatch.setattr("gateway.run._current_max_iterations", lambda: 1)
    monkeypatch.setattr(GatewayRunner, "_load_fallback_model", lambda: None)
    monkeypatch.setattr("gateway.run._resolve_runtime_agent_kwargs", lambda: {
        "api_key": "test-only-schema-key", "base_url": "http://127.0.0.1:1/v1",
        "provider": "custom", "api_mode": "chat_completions",
    })
    monkeypatch.setattr("agent.agent_init.query_ollama_num_ctx", lambda *args, **kwargs: None)
    monkeypatch.setattr("agent.model_metadata.get_model_context_length", lambda *args, **kwargs: MINIMUM_CONTEXT_LENGTH)
    adapter = APIServerAdapter(PlatformConfig())
    try:
        yield adapter, config
    finally:
        adapter.finalize_owned_runtime_resources()


def _wire_tool_names(agent):
    kwargs = agent._build_api_kwargs([{"role": "user", "content": "Hello"}])
    return {tool["function"]["name"] for tool in kwargs["tools"]}


def test_registry_additions_cannot_expand_android_model_schemas(android_schema_adapter):
    from model_tools import get_tool_definitions
    from tools.mcp_tool_agent import refresh_agent_mcp_tools
    from tools.registry import discover_builtin_tools, registry
    from toolsets import resolve_toolset

    adapter, _ = android_schema_adapter
    discover_builtin_tools()
    probe = "__android_unowned_probe__"
    registry.register(
        name=probe, toolset="hermes-android-app",
        schema={"name": probe, "description": "Unowned registry injection", "parameters": {"type": "object", "properties": {}}},
        handler=lambda *_args, **_kwargs: "unowned",
    )
    try:
        raw = get_tool_definitions(enabled_toolsets=["hermes-android-app"], quiet_mode=True, skip_tool_search_assembly=True)
        assert probe in {tool["function"]["name"] for tool in raw}
        assembled = get_tool_definitions(enabled_toolsets=["hermes-android-app"], quiet_mode=True)
        assert "tool_search" in {tool["function"]["name"] for tool in assembled}

        agent = adapter._create_agent(session_id="android-owned-schema")
        names = _wire_tool_names(agent)
        assert {"todo_list", "process_manage"} <= names
        assert names <= set(resolve_toolset("hermes-android-app", include_registry=False))
        assert not names & {probe, "tool_search", "tool_describe", "tool_call", "delegate_task", "cronjob_manage", "web_search", "web_extract", "vision_analyze", "image_generate"}
        snapshot = copy.deepcopy(agent.tools)
        assert refresh_agent_mcp_tools(agent, enabled_override=["hermes-cli"]) == set()
        assert agent.tools == snapshot
    finally:
        registry.deregister(probe)


def test_android_disabled_toolsets_reach_the_actual_model_request(android_schema_adapter):
    adapter, config = android_schema_adapter
    config["agent"]["disabled_toolsets"] = '["terminal", "memory"]'

    agent = adapter._create_agent(session_id="android-disabled-schema")
    names = _wire_tool_names(agent)

    assert "todo_list" in names
    assert not names & {"terminal", "process_manage", "memory"}
