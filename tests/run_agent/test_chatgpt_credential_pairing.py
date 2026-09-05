"""A selected ChatGPT grant stays paired through construction and delegation."""

import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import httpx
import pytest

import run_agent
from agent import credential_pool, secret_scope
from agent.chatgpt_credentials import (
    CHATGPT_WEB_METADATA_FIELDS,
    chatgpt_web_agent_kwargs,
    chatgpt_web_credential_fingerprint,
)
from hermes_cli import chatgpt_web
from tools import delegate_tool


BASE = "https://chatgpt.com/backend-api/f"


def runtime(label, *, empty=False):
    return {
        "provider": "chatgpt-web", "api_mode": "chatgpt_web", "base_url": BASE,
        "api_key": f"access-{label}",
        "session_token": "" if empty else f"session-{label}",
        "cookie_header": "" if empty else f"profile=cookie-{label}",
        "browser_cookies": None if empty else [{"name": "browser", "value": label}],
        "user_agent": "" if empty else f"agent-{label}",
        "device_id": "" if empty else f"device-{label}",
    }


@pytest.fixture(autouse=True)
def isolated_state(monkeypatch):
    old = secret_scope.is_multiplex_active()
    secret_scope.set_multiplex_active(True)
    token = secret_scope.set_secret_scope({})
    monkeypatch.setattr("model_tools.get_tool_definitions", lambda **_kwargs: [])
    monkeypatch.setattr("model_tools.check_toolset_requirements", lambda: {})
    try:
        yield
    finally:
        secret_scope.reset_secret_scope(token)
        secret_scope.set_multiplex_active(old)


def make_agent(resolved, *, paired=True, **kwargs):
    return run_agent.AIAgent(
        model="gpt-5-thinking", provider="chatgpt-web", api_mode="chatgpt_web",
        base_url=BASE, api_key=resolved["api_key"], quiet_mode=True,
        skip_context_files=True, skip_memory=True, max_iterations=4,
        **(chatgpt_web_agent_kwargs(resolved) if paired else {}), **kwargs,
    )


@pytest.mark.parametrize("empty", [False, True])
def test_constructor_uses_resolved_pair_without_second_pool_selection(monkeypatch, empty):
    selected = runtime("A", empty=empty)
    pool = MagicMock()
    pool.has_credentials.return_value = True
    pool.select.return_value = SimpleNamespace(**runtime("B"))
    monkeypatch.setattr(credential_pool, "load_pool", lambda _provider: pool)
    token = secret_scope.set_secret_scope({
        "CHATGPT_WEB_" + name.upper(): str(value)
        for name, value in runtime("B").items()
        if name in CHATGPT_WEB_METADATA_FIELDS and name != "browser_cookies"
    })
    try:
        agent = make_agent(selected)
        try:
            assert agent.api_key == "access-A"
            for name in CHATGPT_WEB_METADATA_FIELDS:
                assert getattr(agent, "_chatgpt_web_" + name) == selected[name]
            pool.select.assert_not_called()
            pool.peek.assert_not_called()
        finally:
            agent.close()
    finally:
        secret_scope.reset_secret_scope(token)


def test_legacy_constructor_only_uses_metadata_for_its_bound_key(monkeypatch):
    entries = [SimpleNamespace(access_token=row["api_key"], **row) for row in (runtime("B"), runtime("A"))]
    pool = MagicMock()
    pool.has_credentials.return_value = True
    pool.entries.return_value = entries
    monkeypatch.setattr(credential_pool, "load_pool", lambda _provider: pool)
    agent = make_agent(runtime("A"), paired=False)
    try:
        assert agent._chatgpt_web_session_token == "session-A"
        assert agent._chatgpt_web_browser_cookies == runtime("A")["browser_cookies"]
        pool.select.assert_not_called()
        pool.peek.assert_not_called()
    finally:
        agent.close()


def test_legacy_constructor_does_not_borrow_a_different_pool_identity(monkeypatch):
    pool = MagicMock()
    pool.has_credentials.return_value = True
    pool.entries.return_value = [SimpleNamespace(access_token="access-B", **runtime("B"))]
    monkeypatch.setattr(credential_pool, "load_pool", lambda _provider: pool)
    agent = make_agent(runtime("A"), paired=False)
    try:
        assert agent._chatgpt_web_session_token == ""
        assert agent._chatgpt_web_cookie_header == ""
        assert agent._chatgpt_web_browser_cookies is None
        pool.select.assert_not_called()
    finally:
        agent.close()


def test_snapshot_is_detached_and_secret_safe():
    source = runtime("A")
    snapshot = chatgpt_web_agent_kwargs(source)["chatgpt_web_credentials"]
    source["browser_cookies"][0]["value"] = "B"
    assert snapshot.browser_cookies[0]["value"] == "A"
    assert "access-A" not in repr(snapshot)
    assert "session-A" not in repr(snapshot)
    assert isinstance(hash(snapshot), int)
    changed = runtime("A")
    changed["cookie_header"] = "refreshed"
    assert chatgpt_web_credential_fingerprint(runtime("A")) != chatgpt_web_credential_fingerprint(changed)


def test_mismatched_snapshot_fails_before_client_creation(monkeypatch):
    snapshot = chatgpt_web_agent_kwargs(runtime("B"))["chatgpt_web_credentials"]
    constructor = MagicMock(side_effect=AssertionError("client must not be built"))
    monkeypatch.setattr(chatgpt_web.httpx, "Client", constructor)
    with pytest.raises(ValueError, match="does not match"):
        make_agent(runtime("A"), paired=False, chatgpt_web_credentials=snapshot)
    constructor.assert_not_called()


def test_authoritative_empty_snapshot_stays_empty_in_transport_headers(monkeypatch):
    headers = []
    event = {"message": {"author": {"role": "assistant"}, "content": {"content_type": "text", "parts": ["done"]}}}

    def handler(request):
        headers.append(request.headers)
        if request.url.path.endswith("/prepare"):
            return httpx.Response(200, json={"conduit_token": "conduit"})
        if request.url.path.endswith("/chat-requirements"):
            return httpx.Response(200, json={"token": "requirements"})
        return httpx.Response(200, text="data: " + json.dumps(event) + "\n\ndata: [DONE]\n\n")

    token = secret_scope.set_secret_scope({
        "CHATGPT_WEB_SESSION_TOKEN": "session-B", "CHATGPT_WEB_COOKIE_HEADER": "profile=B",
        "CHATGPT_WEB_USER_AGENT": "agent-B", "CHATGPT_WEB_DEVICE_ID": "device-B",
    })
    try:
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            response = chatgpt_web.stream_chatgpt_web_completion(
                access_token="access-A", model="test", messages=[{"role": "user", "content": "hello"}],
                credential_snapshot=chatgpt_web_agent_kwargs(runtime("A", empty=True))["chatgpt_web_credentials"],
                client=client,
            )
        assert response["content"] == "done"
        for item in headers:
            assert item["Authorization"] == "Bearer access-A"
            assert item["User-Agent"] == chatgpt_web.DEFAULT_CHATGPT_WEB_USER_AGENT
            assert item["Oai-Device-Id"] != "device-B"
            assert "session-B" not in item.get("Cookie", "")
            assert "profile=B" not in item.get("Cookie", "")
    finally:
        secret_scope.reset_secret_scope(token)


def test_pool_rotation_updates_grant_and_all_metadata_together():
    agent = SimpleNamespace(
        api_key="access-A", base_url=BASE, api_mode="chatgpt_web", _client_kwargs={},
        _reapply_route_client_config=lambda **_kwargs: None,
        _replace_primary_openai_client=lambda **_kwargs: True,
    )
    run_agent.AIAgent._swap_credential(agent, SimpleNamespace(access_token="access-B", **runtime("B")))
    assert agent.api_key == agent._chatgpt_web_credentials.api_key == "access-B"
    assert agent._chatgpt_web_session_token == "session-B"
    assert agent._chatgpt_web_cookie_header == "profile=cookie-B"


def test_live_model_switch_keeps_snapshot_and_rolls_back_mismatched_pair(monkeypatch):
    monkeypatch.setattr("agent.model_metadata.get_model_context_length", lambda *_a, **_k: 8192)
    monkeypatch.setattr(credential_pool, "load_pool", lambda _provider: None)
    agent = make_agent(runtime("A"))
    paired_b = chatgpt_web_agent_kwargs(runtime("B"))["chatgpt_web_credentials"]
    try:
        agent.switch_model(
            "gpt-5-instant", "chatgpt-web", api_key="access-B", base_url=BASE,
            api_mode="chatgpt_web", chatgpt_web_credentials=paired_b,
        )
        assert agent.api_key == "access-B"
        assert agent._chatgpt_web_credentials == paired_b
        assert agent._primary_runtime["chatgpt_web_credentials"] == paired_b
        with pytest.raises(ValueError, match="does not match"):
            agent.switch_model(
                "gpt-5-thinking", "chatgpt-web", api_key="access-C", base_url=BASE,
                api_mode="chatgpt_web", chatgpt_web_credentials=paired_b,
            )
        assert agent.api_key == "access-B"
        assert agent._chatgpt_web_session_token == "session-B"
    finally:
        agent.close()


@pytest.fixture
def child_factory(monkeypatch):
    parent = make_agent(runtime("A"))
    captured = []

    def construct(**kwargs):
        captured.append(kwargs)
        return SimpleNamespace(session_id="child", _session_init_model_config={})

    monkeypatch.setattr(run_agent, "AIAgent", construct)
    monkeypatch.setattr(delegate_tool, "_load_config", lambda: {})
    monkeypatch.setattr(delegate_tool, "_build_child_system_prompt", lambda *_a, **_kw: "child prompt")
    monkeypatch.setattr(delegate_tool, "_resolve_child_credential_pool", lambda *_a: None)
    try:
        yield parent, captured
    finally:
        parent.close()


def _child(parent, **kwargs):
    return delegate_tool._build_child_agent(0, "test", None, None, None, 4, 1, parent, **kwargs)


def test_same_provider_child_inherits_the_parents_exact_pair(child_factory):
    parent, captured = child_factory
    _child(parent)
    assert captured[0]["api_key"] == "access-A"
    assert captured[0]["chatgpt_web_credentials"] == parent._chatgpt_web_credentials


def test_named_child_override_keeps_its_own_resolved_pair(child_factory, monkeypatch):
    parent, captured = child_factory
    monkeypatch.setattr("hermes_cli.runtime_provider.resolve_runtime_provider", lambda **_kwargs: runtime("B"))
    credentials = delegate_tool._resolve_delegation_credentials({"provider": "chatgpt-web"}, parent)
    _child(parent, override_provider=credentials["provider"], override_api_key=credentials["api_key"],
           override_base_url=credentials["base_url"], override_api_mode=credentials["api_mode"],
           override_chatgpt_web_credentials=credentials["chatgpt_web_credentials"])
    assert captured[0]["api_key"] == "access-B"
    assert captured[0]["chatgpt_web_credentials"].session_token == "session-B"


def test_unpaired_explicit_child_grant_does_not_inherit_parent_cookies(child_factory):
    parent, captured = child_factory
    _child(parent, override_provider="chatgpt-web", override_api_key="access-C")
    snapshot = captured[0]["chatgpt_web_credentials"]
    assert snapshot.api_key == "access-C"
    assert snapshot.session_token == snapshot.cookie_header == ""
    assert snapshot.browser_cookies is None


def test_child_switching_to_ordinary_provider_has_no_chatgpt_snapshot(child_factory):
    parent, captured = child_factory
    _child(parent, override_provider="openrouter", override_api_key="ordinary", override_base_url="https://openrouter.ai/api/v1")
    assert "chatgpt_web_credentials" not in captured[0]
