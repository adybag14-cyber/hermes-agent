"""Resolved ChatGPT credentials remain paired across CLI agent factories."""

from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from agent.chatgpt_credentials import chatgpt_web_agent_kwargs


def _chatgpt_runtime(identity, **changes):
    runtime = {
        "provider": "chatgpt-web",
        "requested_provider": "chatgpt-web",
        "api_mode": "chatgpt_web",
        "base_url": "https://chatgpt.com/backend-api",
        "api_key": f"access-{identity}",
        "session_token": f"session-{identity}",
        "cookie_header": f"session=session-{identity}",
        "browser_cookies": [
            {"name": "session", "value": f"session-{identity}", "domain": ".chatgpt.com"}
        ],
        "user_agent": f"browser-{identity}",
        "device_id": f"device-{identity}",
    }
    runtime.update(changes)
    return runtime


def _ordinary_runtime():
    return {
        "provider": "openrouter",
        "requested_provider": "openrouter",
        "api_mode": "chat_completions",
        "base_url": "https://openrouter.ai/api/v1",
        "api_key": "ordinary-key",
    }


class _RotatingPool:
    """Rotate immediately after resolution, before the factory can run."""

    def __init__(self, first, second):
        self.active = first
        self.second = second
        self.selections = 0

    def resolve(self, **_kwargs):
        self.selections += 1
        runtime = deepcopy(self.active)
        self.active = self.second
        runtime["credential_pool"] = self
        return runtime


@pytest.fixture
def cli_shell(monkeypatch):
    import cli as cli_mod

    monkeypatch.setattr(cli_mod, "_prepare_deferred_agent_startup", lambda: None)
    monkeypatch.setattr(cli_mod, "_active_agent_ref", None)
    monkeypatch.setattr(
        "hermes_cli.mcp_startup.ensure_mcp_discovery_before_agent_build",
        lambda **_kwargs: None,
    )
    monkeypatch.setattr(
        "agent.credits_tracker.seed_credits_at_session_start", lambda _agent: None
    )
    shell = cli_mod.HermesCLI(model="gpt-test", provider="chatgpt-web", compact=True)
    shell._install_tool_callbacks = lambda: None
    shell._ensure_tirith_security = lambda: None
    shell._normalize_model_for_provider = lambda _provider: False
    constructed = []

    def capture_agent(**kwargs):
        agent = SimpleNamespace(kwargs=kwargs)
        constructed.append(agent)
        return agent

    monkeypatch.setattr("run_agent.AIAgent", capture_agent)
    try:
        yield shell, constructed
    finally:
        if shell._session_db is not None:
            shell._session_db.close()


def test_cli_default_factory_keeps_resolved_pair_after_pool_rotation(cli_shell, monkeypatch):
    shell, constructed = cli_shell
    first, second = _chatgpt_runtime("a"), _chatgpt_runtime("b")
    expected = chatgpt_web_agent_kwargs(first)["chatgpt_web_credentials"]
    pool = _RotatingPool(first, second)
    monkeypatch.setattr("hermes_cli.runtime_provider.resolve_runtime_provider", pool.resolve)

    assert shell._init_agent() is True

    kwargs = constructed[0].kwargs
    assert kwargs["api_key"] == first["api_key"]
    assert kwargs["chatgpt_web_credentials"] == expected
    assert kwargs["chatgpt_web_credentials"].session_token == first["session_token"]
    assert kwargs["credential_pool"] is pool
    assert pool.active == second
    assert pool.selections == 1
    assert shell._resolve_turn_agent_config("next")["signature"] == shell._active_agent_route_signature


def test_cli_turn_runtime_keeps_its_pair_when_primary_is_reresolved(cli_shell, monkeypatch):
    shell, constructed = cli_shell
    first, second = _chatgpt_runtime("a"), _chatgpt_runtime("b")
    expected = chatgpt_web_agent_kwargs(first)["chatgpt_web_credentials"]
    pool = _RotatingPool(first, second)
    monkeypatch.setattr("hermes_cli.runtime_provider.resolve_runtime_provider", pool.resolve)

    assert shell._ensure_runtime_credentials() is True
    route = shell._resolve_turn_agent_config("hello")
    assert route["runtime"]["chatgpt_web_credentials"] == expected
    assert shell._init_agent(runtime_override=route["runtime"]) is True

    assert shell.api_key == second["api_key"]
    assert constructed[0].kwargs["api_key"] == first["api_key"]
    assert constructed[0].kwargs["chatgpt_web_credentials"] == expected
    assert pool.selections == 2
    assert shell._active_agent_route_signature == route["signature"]
    # The caller compares this signature before reusing the current agent.
    # Primary B must not look reusable merely because _init_agent's second
    # resolution already stored B in the CLI's main runtime fields.
    assert shell._resolve_turn_agent_config("next")["signature"] != shell._active_agent_route_signature


@pytest.mark.parametrize("snapshot_override", [False, True])
@pytest.mark.parametrize("empty_metadata", [False, True])
def test_cli_explicit_override_uses_its_own_pair(
    cli_shell, monkeypatch, snapshot_override, empty_metadata
):
    shell, constructed = cli_shell
    primary, override = _chatgpt_runtime("a"), _chatgpt_runtime("b")
    if empty_metadata:
        override.update(
            session_token="", cookie_header="", browser_cookies=[], user_agent="", device_id=""
        )
    expected = chatgpt_web_agent_kwargs(override)["chatgpt_web_credentials"]
    monkeypatch.setattr(
        "hermes_cli.runtime_provider.resolve_runtime_provider", lambda **_kwargs: primary
    )
    if snapshot_override:
        override = {
            key: override[key]
            for key in ("provider", "api_key", "api_mode", "base_url")
        }
        override["chatgpt_web_credentials"] = expected

    assert shell._init_agent(runtime_override=override) is True

    assert shell._chatgpt_web_credentials.api_key == primary["api_key"]
    assert constructed[0].kwargs["api_key"] == expected.api_key
    assert constructed[0].kwargs["chatgpt_web_credentials"] == expected
    assert shell._active_agent_route_signature[-1] == expected


def test_cli_same_key_metadata_refresh_invalidates_agent_and_clears_on_provider_change(
    cli_shell, monkeypatch
):
    shell, _constructed = cli_shell
    first = _chatgpt_runtime("a")
    second = _chatgpt_runtime("b", api_key=first["api_key"])
    responses = iter((first, first, second, _ordinary_runtime()))
    monkeypatch.setattr(
        "hermes_cli.runtime_provider.resolve_runtime_provider", lambda **_kwargs: next(responses)
    )

    assert shell._ensure_runtime_credentials() is True
    sentinel_agent = SimpleNamespace()
    shell.agent = sentinel_agent
    shell._active_agent_route_signature = ("old-route",)
    assert shell._ensure_runtime_credentials() is True
    assert shell.agent is sentinel_agent
    assert shell._ensure_runtime_credentials() is True
    assert shell.agent is None
    assert shell._active_agent_route_signature is None
    assert shell._chatgpt_web_credentials.session_token == second["session_token"]

    assert shell._ensure_runtime_credentials() is True
    assert shell._chatgpt_web_credentials is None
    assert "chatgpt_web_credentials" not in shell._resolve_turn_agent_config("hello")["runtime"]


@pytest.mark.parametrize("explicit_override", [False, True])
def test_cli_ordinary_factory_does_not_receive_chatgpt_credentials(
    cli_shell, monkeypatch, explicit_override
):
    shell, constructed = cli_shell
    ordinary = _ordinary_runtime()
    primary = _chatgpt_runtime("a") if explicit_override else ordinary
    shell.requested_provider = primary["requested_provider"]
    monkeypatch.setattr(
        "hermes_cli.runtime_provider.resolve_runtime_provider", lambda **_kwargs: primary
    )

    assert shell._init_agent(runtime_override=ordinary if explicit_override else None) is True

    kwargs = constructed[0].kwargs
    assert kwargs["provider"] == ordinary["provider"]
    assert kwargs["api_key"] == ordinary["api_key"]
    assert "chatgpt_web_credentials" not in kwargs
    assert shell._active_agent_route_signature == (
        shell.model,
        ordinary["provider"],
        ordinary["requested_provider"],
        ordinary["base_url"],
        ordinary["api_mode"],
        None,
        (),
    )


@pytest.mark.parametrize("chatgpt", [False, True])
def test_oneshot_factory_preserves_resolved_pair_and_cleanup(monkeypatch, chatgpt):
    from hermes_cli import oneshot

    first = _chatgpt_runtime("a") if chatgpt else _ordinary_runtime()
    pool = _RotatingPool(first, _chatgpt_runtime("b"))
    expected = chatgpt_web_agent_kwargs(first)
    agent = MagicMock()
    agent.run_conversation.return_value = {"final_response": "finished", "messages": []}
    factory = MagicMock(return_value=agent)
    session_db = MagicMock()
    monkeypatch.setattr("run_agent.AIAgent", factory)
    monkeypatch.setattr("hermes_cli.config.load_config", lambda: {})
    monkeypatch.setattr("hermes_cli.runtime_provider.resolve_runtime_provider", pool.resolve)
    monkeypatch.setattr(
        "hermes_cli.mcp_startup.ensure_mcp_discovery_before_agent_build",
        lambda **_kwargs: None,
    )
    monkeypatch.setattr(oneshot, "_create_session_db_for_oneshot", lambda: session_db)
    monkeypatch.setattr(
        "tools.process_registry.process_registry.wait_for_pending_completions",
        lambda _task: None,
    )

    response, _result = oneshot._run_agent(
        "hello", model="gpt-test", provider=first["provider"], use_config_toolsets=False
    )

    kwargs = factory.call_args.kwargs
    assert kwargs["api_key"] == first["api_key"]
    assert kwargs["credential_pool"] is pool
    if chatgpt:
        assert kwargs["chatgpt_web_credentials"] == expected["chatgpt_web_credentials"]
        assert kwargs["chatgpt_web_credentials"].session_token == first["session_token"]
    else:
        assert "chatgpt_web_credentials" not in kwargs
    assert pool.selections == 1
    assert response == "finished"
    agent.run_conversation.assert_called_once_with("hello")
    agent.shutdown_memory_provider.assert_called_once()
    agent.close.assert_called_once()
    session_db.close.assert_called_once()
