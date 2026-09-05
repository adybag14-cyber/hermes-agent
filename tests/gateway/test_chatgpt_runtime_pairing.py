"""ChatGPT gateway routes carry one grant/browser pair, never pool metadata."""

from copy import deepcopy
import json
from threading import Lock
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from agent.chatgpt_credentials import (
    CHATGPT_WEB_METADATA_FIELDS,
    chatgpt_web_agent_kwargs,
)
from gateway.config import ChannelOverride, GatewayConfig, Platform, PlatformConfig
from gateway.platforms.api_server import (
    APIServerAdapter,
    _apply_runtime_agent_overrides,
    _resolve_request_runtime_agent_kwargs,
)
from gateway.run import (
    GatewayRunner,
    _resolve_runtime_agent_kwargs,
    _resolve_runtime_agent_kwargs_for_provider,
    _try_resolve_fallback_provider,
)
from gateway.session import SessionSource, SessionStore


def _runtime(label="A", *, empty=False, provider="chatgpt-web"):
    return {
        "provider": provider,
        "requested_provider": provider,
        "api_mode": "chatgpt_web" if provider == "chatgpt-web" else "chat_completions",
        "api_key": f"synthetic-grant-{label}",
        "base_url": "https://chatgpt.example.test",
        "session_token": "" if empty else f"synthetic-session-{label}",
        "cookie_header": "" if empty else f"synthetic-cookie={label}",
        "browser_cookies": [] if empty else [{"name": "synthetic-browser", "value": label}],
        "user_agent": "" if empty else f"synthetic-browser-{label}",
        "device_id": "" if empty else f"synthetic-device-{label}",
        "max_output_tokens": 1234,
        "capabilities": {"synthetic_capability": True},
    }


def _paired(runtime):
    return {
        key: value for key, value in runtime.items()
        if key not in CHATGPT_WEB_METADATA_FIELDS
    } | chatgpt_web_agent_kwargs(runtime)


def _assert_pair(runtime, expected):
    pair = runtime["chatgpt_web_credentials"]
    assert runtime["api_key"] == pair.api_key == expected["api_key"]
    for field in CHATGPT_WEB_METADATA_FIELDS:
        assert getattr(pair, field) == expected[field]


@pytest.fixture
def resolver(monkeypatch):
    def install(default, selected=None):
        def resolve(*, requested=None, **kwargs):
            return default if requested is None else (selected or default)

        monkeypatch.setattr("hermes_cli.runtime_provider.resolve_runtime_provider", resolve)
        monkeypatch.setattr("hermes_cli.runtime_provider._get_model_config", lambda: {})
        return resolve

    return install


@pytest.mark.parametrize("route", ["default", "provider", "fallback", "api-request"])
@pytest.mark.parametrize("empty", [False, True])
def test_resolver_projections_freeze_the_selected_pair(route, empty, resolver, monkeypatch):
    selected = _runtime(empty=empty)
    expected = deepcopy(selected)
    pool = MagicMock()
    pool.pick.return_value = _runtime("B")
    selected["credential_pool"] = pool
    resolver(selected)
    monkeypatch.setattr(
        "gateway.run._load_gateway_runtime_config",
        lambda: {"fallback_model": {"provider": "chatgpt-web", "model": "synthetic-model"}},
    )
    projected = {
        "default": _resolve_runtime_agent_kwargs,
        "provider": lambda: _resolve_runtime_agent_kwargs_for_provider("chatgpt-web"),
        "fallback": _try_resolve_fallback_provider,
        "api-request": lambda: _resolve_request_runtime_agent_kwargs("chatgpt-web", "synthetic-model"),
    }[route]()
    assert projected is not None
    selected["session_token"] = "changed-after-resolution"
    selected["browser_cookies"].append({"name": "late", "value": "B"})
    _assert_pair(projected, expected)
    assert projected["credential_pool"] is pool
    pool.pick.assert_not_called()
    assert not set(CHATGPT_WEB_METADATA_FIELDS).intersection(projected)


@pytest.mark.parametrize("apply_route", ["gateway", "api"])
@pytest.mark.parametrize("override_kind", ["snapshot", "raw", "key-only", "model-only", "ordinary"])
def test_runtime_override_replaces_pairs_atomically(apply_route, override_kind, monkeypatch):
    original = _runtime()
    replacement = _runtime("B", empty=True)
    overrides = {
        "snapshot": _paired(replacement),
        "raw": replacement,
        "key-only": {"api_key": replacement["api_key"]},
        "model-only": {"model": "next-model"},
        "ordinary": _runtime("B", provider="openrouter"),
    }[override_kind]
    runtime = _paired(original)
    if apply_route == "gateway":
        runner = object.__new__(GatewayRunner)
        runner._session_model_overrides = {"session": overrides}
        monkeypatch.setattr("gateway.run._credential_pool_for_provider", lambda *_: None)
        _, runtime = runner._apply_session_model_override("session", "model", runtime)
    else:
        _apply_runtime_agent_overrides(runtime, overrides)
    if override_kind == "ordinary":
        assert "chatgpt_web_credentials" not in runtime
        assert runtime["api_key"] == replacement["api_key"]
    elif override_kind == "model-only":
        _assert_pair(runtime, original)
    elif override_kind == "key-only":
        assert runtime["chatgpt_web_credentials"].api_key == replacement["api_key"]
        assert runtime["chatgpt_web_credentials"].browser_cookies is None
        for field in ("session_token", "cookie_header", "user_agent", "device_id"):
            assert getattr(runtime["chatgpt_web_credentials"], field) == ""
    else:
        _assert_pair(runtime, replacement)


def test_same_key_partial_override_preserves_freshly_resolved_metadata():
    fresh = _runtime("B")
    runtime = _paired(fresh)
    _apply_runtime_agent_overrides(runtime, {"provider": "chatgpt-web", "api_key": fresh["api_key"]})
    _assert_pair(runtime, fresh)


@pytest.mark.parametrize("field", CHATGPT_WEB_METADATA_FIELDS)
def test_gateway_cache_invalidates_for_same_key_browser_refresh(field):
    before = _runtime()
    after = deepcopy(before)
    after[field] = [] if field == "browser_cookies" else ""
    first = GatewayRunner._agent_config_signature("model", _paired(before), ["messaging"], "")
    second = GatewayRunner._agent_config_signature("model", _paired(after), ["messaging"], "")
    assert first != second
    assert first == GatewayRunner._agent_config_signature("model", _paired(deepcopy(before)), ["messaging"], "")


def test_ordinary_signature_and_projection_ignore_chatgpt_metadata(resolver):
    runtime = _runtime(provider="openrouter")
    resolver(runtime)
    projected = _resolve_runtime_agent_kwargs()
    assert "chatgpt_web_credentials" not in projected
    signature = GatewayRunner._agent_config_signature("model", projected, [], "")
    runtime["session_token"] = "different"
    assert signature == GatewayRunner._agent_config_signature("model", _resolve_runtime_agent_kwargs(), [], "")


def test_channel_override_and_turn_projection_keep_selected_pair(resolver, monkeypatch):
    selected = _runtime("B")
    resolver(_runtime(provider="openrouter"), selected)
    monkeypatch.setattr("gateway.run._resolve_gateway_model", lambda *_: "global-model")
    runner = object.__new__(GatewayRunner)
    runner._session_model_overrides = {}
    runner.config = GatewayConfig(platforms={
        Platform.DISCORD: PlatformConfig(enabled=True, channel_overrides={
            "channel": ChannelOverride(model="selected-model", provider="chatgpt-web"),
        }),
    })
    source = SessionSource(platform=Platform.DISCORD, user_id="user", chat_id="channel")
    model, runtime = runner._resolve_session_agent_runtime(source=source)
    assert model == "selected-model"
    _assert_pair(runtime, selected)
    _assert_pair(runner._resolve_turn_agent_config("hello", model, runtime)["runtime"], selected)


def test_session_pair_is_nonsecret_on_disk_and_fresh_on_restart(tmp_path, resolver, monkeypatch):
    import hermes_state

    monkeypatch.setattr(hermes_state, "SessionDB", lambda: (_ for _ in ()).throw(RuntimeError("no sqlite")))
    store = SessionStore(sessions_dir=tmp_path, config=GatewayConfig())
    source = SessionSource(platform=Platform.TELEGRAM, user_id="user", chat_id="channel")
    entry = store.get_or_create_session(source)
    first = _runtime()
    store.set_model_override(entry.session_key, {"model": "selected-model", **_paired(first)})
    encoded = (tmp_path / "sessions.json").read_text(encoding="utf-8")
    for field in ("api_key", *CHATGPT_WEB_METADATA_FIELDS):
        assert field not in json.dumps(store.get_model_override(entry.session_key))
    assert "synthetic-grant" not in encoded
    assert "synthetic-session" not in encoded
    assert "chatgpt_web_credentials" not in encoded

    selected = _runtime("B", empty=True)
    resolver(selected)
    runner = object.__new__(GatewayRunner)
    runner._session_model_overrides = {}
    runner.session_store = SessionStore(sessions_dir=tmp_path, config=GatewayConfig())
    monkeypatch.setattr("gateway.run._resolve_gateway_model", lambda *_: "global-model")
    model, runtime = runner._resolve_session_agent_runtime(session_key=entry.session_key)
    assert model == "selected-model"
    _assert_pair(runtime, selected)
    _assert_pair(runner._resolve_turn_agent_config("hello", model, runtime)["runtime"], selected)


def _api_adapter(monkeypatch, captured):
    class CapturingAgent:
        def __init__(self, **kwargs):
            captured.update(kwargs)

    monkeypatch.setattr("run_agent.AIAgent", CapturingAgent)
    monkeypatch.setattr("gateway.run._resolve_gateway_model", lambda *_: "global-model")
    monkeypatch.setattr("gateway.run._load_gateway_config", lambda *a, **kw: {})
    monkeypatch.setattr("gateway.run._current_max_iterations", lambda: 12)
    monkeypatch.setattr(GatewayRunner, "_load_reasoning_config", staticmethod(lambda *_: {}))
    monkeypatch.setattr(GatewayRunner, "_load_fallback_model", staticmethod(lambda: None))
    monkeypatch.setattr("hermes_cli.tools_config._get_platform_tools", lambda *_: set())
    adapter = APIServerAdapter(PlatformConfig(enabled=True))
    monkeypatch.setattr(adapter, "_ensure_session_db", lambda: None)
    return adapter


@pytest.mark.parametrize("selection", ["default", "request", "route", "locked", "session", "session-model"])
def test_api_constructor_receives_selected_pair(selection, resolver, monkeypatch):
    primary, selected = _runtime(), _runtime("B", empty=True)
    pool = MagicMock()
    pool.pick.return_value = _runtime("C")
    primary["credential_pool"] = selected["credential_pool"] = pool
    resolver(primary, selected)
    captured = {}
    adapter = _api_adapter(monkeypatch, captured)
    kwargs = {"session_id": "session"}
    expected = selected
    if selection == "default":
        expected = primary
    elif selection in {"request", "locked"}:
        kwargs.update(requested_provider="chatgpt-web", requested_model="selected-model")
        if selection == "locked":
            kwargs["confirmed_runtime_lock"] = True
    elif selection == "route":
        kwargs["route"] = {"provider": "chatgpt-web", "model": "selected-model"}
    elif selection == "session":
        override = {"model": "selected-model", **_paired(selected)}
        monkeypatch.setattr(adapter, "_session_model_override_for", lambda *_: override)
    else:
        kwargs["session_model"] = "selected-model"
    adapter._create_agent(**kwargs)
    _assert_pair(captured, expected)
    pool.pick.assert_not_called()


def test_api_explicit_route_key_drops_previous_browser_pair(resolver, monkeypatch):
    resolver(_runtime())
    captured = {}
    adapter = _api_adapter(monkeypatch, captured)
    adapter._create_agent(session_id="session", route={"api_key": "synthetic-grant-B"})
    pair = captured["chatgpt_web_credentials"]
    assert pair.api_key == captured["api_key"] == "synthetic-grant-B"
    assert pair.session_token == pair.cookie_header == pair.user_agent == pair.device_id == ""
    assert pair.browser_cookies is None


def test_api_auth_fallback_passes_the_fallback_pair(monkeypatch):
    from hermes_cli.auth import AuthError

    selected = _runtime("B")

    def resolve(*, requested=None, **kwargs):
        if requested is None:
            raise AuthError("synthetic primary authentication failure")
        return selected

    monkeypatch.setattr("hermes_cli.runtime_provider.resolve_runtime_provider", resolve)
    monkeypatch.setattr("gateway.run._load_gateway_runtime_config", lambda: {
        "fallback_model": {"provider": "chatgpt-web", "model": "fallback-model"},
    })
    captured = {}
    adapter = _api_adapter(monkeypatch, captured)
    adapter._create_agent(session_id="session")
    assert captured["model"] == "fallback-model"
    _assert_pair(captured, selected)


@pytest.mark.parametrize("explicit_provider", ["", "chatgpt-web"])
def test_model_switch_result_freezes_resolver_pair(explicit_provider, resolver, monkeypatch):
    from hermes_cli.model_switch import switch_model

    selected = _runtime()
    expected = deepcopy(selected)
    resolver(selected)
    monkeypatch.setattr("hermes_cli.model_switch.resolve_alias", lambda *_a, **_kw: None)
    monkeypatch.setattr("hermes_cli.model_switch.list_provider_models", lambda *_a, **_kw: [])
    monkeypatch.setattr("hermes_cli.model_switch.get_model_info", lambda *_a, **_kw: None)
    monkeypatch.setattr("hermes_cli.model_switch.get_model_capabilities", lambda *_a, **_kw: None)
    monkeypatch.setattr("hermes_cli.models.detect_provider_for_model", lambda *_a, **_kw: None)
    monkeypatch.setattr("hermes_cli.models_validate.validate_requested_model", lambda *_a, **_kw: {
        "accepted": True, "persist": True, "recognized": True,
    })
    result = switch_model(
        raw_input="synthetic-model",
        current_provider="chatgpt-web" if not explicit_provider else "openrouter",
        current_model="old-model", explicit_provider=explicit_provider,
    )
    assert result.success, result.error_message
    selected["browser_cookies"][0]["value"] = "B"
    _assert_pair({"api_key": result.api_key, "chatgpt_web_credentials": result.chatgpt_web_credentials}, expected)
    assert "synthetic-session" not in repr(result)


@pytest.mark.asyncio
@pytest.mark.parametrize("picker", [False, True])
async def test_slash_switch_carries_pair_to_cached_agent_and_session(picker, monkeypatch):
    from gateway.platforms.base import MessageEvent, MessageType
    from hermes_cli.model_switch import ModelSwitchResult

    selected = _runtime("B", empty=True)
    result = ModelSwitchResult(
        success=True, new_model="selected-model", target_provider="chatgpt-web",
        api_key=selected["api_key"], base_url=selected["base_url"],
        api_mode="chatgpt_web", **chatgpt_web_agent_kwargs(selected),
    )
    monkeypatch.setattr("gateway.run._load_gateway_config", lambda *_a, **_kw: {
        "model": {"default": "old-model", "provider": "openrouter"},
    })
    monkeypatch.setattr("gateway.slash_commands_model._model_switch_skew_guard", lambda: None)
    monkeypatch.setattr("hermes_cli.model_switch.switch_model", lambda **_kw: result)
    monkeypatch.setattr("hermes_cli.model_switch_providers.list_picker_providers", lambda **_kw: [{"slug": "chatgpt-web"}])
    monkeypatch.setattr("hermes_cli.model_switch.resolve_display_context_length_async", AsyncMock(return_value=0))
    monkeypatch.setattr("hermes_cli.context_switch_guard.enrich_model_switch_warnings_for_gateway", lambda *_a, **_kw: None)
    monkeypatch.setattr("hermes_cli.model_selection_guards.combined_selection_warning", lambda *_a, **_kw: None)
    runner = object.__new__(GatewayRunner)
    runner._session_model_overrides = {}
    runner._running_agents = {}
    runner._voice_mode = {}
    runner.session_store = None
    runner._session_db = None
    runner._async_session_store = MagicMock()
    runner._async_session_store._store = None
    runner._async_session_store.set_model_override = AsyncMock()
    cached_agent = MagicMock()
    runner._agent_cache = {"session": (cached_agent, "old-signature")}
    runner._agent_cache_lock = Lock()
    monkeypatch.setattr(runner, "_session_key_for_source", lambda *_: "session")
    monkeypatch.setattr(runner, "_normalize_source_for_session_key", lambda source: source)
    monkeypatch.setattr(runner, "_evict_cached_agent", MagicMock())
    monkeypatch.setattr(runner, "_thread_metadata_for_source", lambda *_: {})
    monkeypatch.setattr(runner, "_reply_anchor_for_event", lambda *_: None)

    class PickerAdapter:
        async def send_model_picker(self, **kwargs):
            self.callback = kwargs["on_model_selected"]
            return SimpleNamespace(success=True)

    adapter = PickerAdapter()
    monkeypatch.setattr(runner, "_adapter_for_source", lambda *_: adapter)
    event = MessageEvent(
        text="/model --session" if picker else "/model selected-model --provider chatgpt-web --session",
        message_type=MessageType.TEXT,
        source=SessionSource(platform=Platform.DISCORD, user_id="user", chat_id="channel"),
    )
    response = await runner._handle_model_command(event)
    if picker:
        assert response is None
        response = await adapter.callback("channel", "selected-model", "chatgpt-web")
    assert "selected-model" in response
    _assert_pair(cached_agent.switch_model.call_args.kwargs, selected)
    _assert_pair(runner._session_model_overrides["session"], selected)
