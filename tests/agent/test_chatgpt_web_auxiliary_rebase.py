"""Behavioral integration guards for ChatGPT Web auxiliary routing."""

import asyncio
from contextlib import contextmanager

import pytest

from agent import auxiliary_client as aux
from agent import auxiliary_chatgpt_web as web_aux
from agent import secret_scope


@contextmanager
def _scope(values):
    token = secret_scope.set_secret_scope(values)
    try:
        yield
    finally:
        secret_scope.reset_secret_scope(token)


@pytest.fixture(autouse=True)
def isolated_auxiliary_state(monkeypatch, tmp_path):
    from hermes_cli import auth

    aux.shutdown_cached_clients()
    aux.clear_runtime_main()
    monkeypatch.setattr(aux, "get_hermes_home", lambda: tmp_path)
    monkeypatch.setattr(aux, "_peek_pool_entry", lambda _provider: None)
    monkeypatch.setattr(auth, "_read_codex_tokens", lambda: {"tokens": {}})
    monkeypatch.setattr(secret_scope, "_MULTIPLEX_ACTIVE", True)
    with _scope({}):
        yield
    aux.shutdown_cached_clients()
    aux.clear_runtime_main()


def _must_not_resolve(**_kwargs):
    pytest.fail("This route must not exchange or resolve runtime credentials")


@pytest.mark.parametrize("scope_key,expected_session", [
    ("caller-token", "scoped-session"), ("another-token", ""), ("", ""),
])
def test_explicit_key_only_uses_metadata_paired_with_that_grant(monkeypatch, scope_key, expected_session):
    monkeypatch.setattr(web_aux, "resolve_chatgpt_web_runtime_credentials", _must_not_resolve)
    with _scope({"CHATGPT_WEB_ACCESS_TOKEN": scope_key, "CHATGPT_WEB_SESSION_TOKEN": "scoped-session"}):
        client, model = aux.resolve_provider_client(
            "chatgpt-web", "gpt-test", explicit_api_key="caller-token",
        )
    assert isinstance(client, web_aux.ChatGptWebAuxiliaryClient)
    assert client.api_key == "caller-token"
    assert client.chat.completions._session_token == expected_session
    assert model == "gpt-test"


def test_probe_does_not_resolve_credentials_or_cache_a_stub(monkeypatch):
    monkeypatch.setattr(web_aux, "resolve_chatgpt_web_runtime_credentials", _must_not_resolve)
    with _scope({"CHATGPT_WEB_SESSION_TOKEN": "probe-session"}), aux.aux_probe_mode():
        client, model = aux._get_cached_client("chatgpt-web", "gpt-test")
    assert isinstance(client, aux._AuxProbeClientStub)
    assert model == "gpt-test"
    assert not aux._client_cache


def test_probe_without_existing_credentials_is_unavailable(monkeypatch):
    monkeypatch.setattr(web_aux, "resolve_chatgpt_web_runtime_credentials", _must_not_resolve)
    with aux.aux_probe_mode():
        assert aux.resolve_provider_client("chatgpt-web", "gpt-test") == (None, None)


@pytest.mark.parametrize("probe", [False, True])
def test_unscoped_multiplex_calls_fail_closed(monkeypatch, probe):
    def fail_unscoped():
        raise secret_scope.UnscopedSecretError("missing profile scope")

    monkeypatch.setattr(web_aux, "resolve_chatgpt_web_runtime_credentials", fail_unscoped)
    with _scope(None), pytest.raises(secret_scope.UnscopedSecretError):
        if probe:
            with aux.aux_probe_mode():
                aux.resolve_provider_client("chatgpt-web", "gpt-test")
        else:
            aux.resolve_provider_client("chatgpt-web", "gpt-test")


def test_sync_response_tool_protocol_and_progress(monkeypatch):
    captured = {}
    progress = []
    monkeypatch.setattr(
        web_aux, "resolve_chatgpt_web_runtime_credentials",
        lambda: {"api_key": "synthetic-token", "session_token": "synthetic-session"},
    )

    def transport(**kwargs):
        captured.update(kwargs)
        kwargs["on_delta"]("Working")
        return {
            "content": 'Ready. <tool_call>{"name":"lookup","arguments":{"q":"hello"}}</tool_call>',
            "model": "gpt-test",
        }

    monkeypatch.setattr(web_aux, "stream_chatgpt_web_completion", transport)
    client, model = aux.resolve_provider_client("chatgpt-web", "gpt-test")
    with aux.aux_progress_hook(lambda: progress.append(True)):
        result = aux._create_with_progress_once(client, {
            "model": model,
            "messages": [
                {"role": "system", "content": "Preserve the instructions."},
                {"role": "user", "content": "Find hello"},
            ],
            "tools": [{"type": "function", "function": {
                "name": "lookup", "parameters": {"type": "object"},
            }}],
            "timeout": 2,
        })
    assert captured["access_token"] == "synthetic-token"
    assert captured["session_token"] == "synthetic-session"
    assert captured["history_and_training_disabled"] is True
    assert "Preserve the instructions." in captured["instructions"]
    assert '"name": "lookup"' in captured["instructions"]
    assert captured["messages"] == [{"role": "user", "content": "Find hello"}]
    assert progress
    assert result.choices[0].message.content == "Ready."
    assert result.choices[0].message.tool_calls[0].function.name == "lookup"
    assert result.choices[0].finish_reason == "tool_calls"


@pytest.mark.asyncio
async def test_async_bridge_preserves_secret_context(monkeypatch):
    monkeypatch.setattr(
        web_aux, "resolve_chatgpt_web_runtime_credentials",
        lambda: {"api_key": "synthetic-token"},
    )
    observed = []

    def transport(**kwargs):
        observed.append(secret_scope.get_secret("CHATGPT_WEB_DEVICE_ID"))
        return {"content": "async answer", "model": kwargs["model"]}

    monkeypatch.setattr(web_aux, "stream_chatgpt_web_completion", transport)
    with _scope({"CHATGPT_WEB_DEVICE_ID": "scoped-device"}):
        client, _ = aux.resolve_provider_client("chatgpt-web", "gpt-test", async_mode=True)
        result = await asyncio.wait_for(
            client.chat.completions.create(messages=[{"role": "user", "content": "Hello"}]),
            timeout=5,
        )
    assert result.choices[0].message.content == "async answer"
    assert observed == ["scoped-device"]


def test_scoped_cache_reuses_own_client_but_never_another_profiles(monkeypatch, tmp_path):
    home = [tmp_path / "a"]
    monkeypatch.setattr(aux, "get_hermes_home", lambda: home[0])
    monkeypatch.setattr(
        web_aux, "resolve_chatgpt_web_runtime_credentials",
        lambda: {"api_key": secret_scope.get_secret("CHATGPT_WEB_ACCESS_TOKEN")},
    )
    with _scope({"CHATGPT_WEB_ACCESS_TOKEN": "profile-a-token"}):
        client_a, _ = aux._get_cached_client("chatgpt-web", "gpt-test")
        assert aux._get_cached_client("chatgpt-web", "gpt-test")[0] is client_a
    home[0] = tmp_path / "b"
    with _scope({"CHATGPT_WEB_ACCESS_TOKEN": "profile-b-token"}):
        client_b, _ = aux._get_cached_client("chatgpt-web", "gpt-test")
    assert client_b is not client_a
    assert client_a.api_key == "profile-a-token"
    assert client_b.api_key == "profile-b-token"
    assert "profile-a-token" not in repr(aux._client_cache.keys())
    assert "profile-b-token" not in repr(aux._client_cache.keys())


@pytest.mark.parametrize("field", [
    "CHATGPT_WEB_ACCESS_TOKEN", "CHATGPT_WEB_SESSION_TOKEN", "CHATGPT_WEB_COOKIE_HEADER",
    "CHATGPT_WEB_USER_AGENT", "CHATGPT_WEB_DEVICE_ID",
])
def test_changed_scoped_metadata_invalidates_cache_without_plaintext_keys(field):
    with _scope({field: "first-sensitive-value"}):
        first = aux._client_cache_key("chatgpt-web", async_mode=False, model="gpt-test")
    with _scope({field: "second-sensitive-value"}):
        second = aux._client_cache_key("chatgpt-web", async_mode=False, model="gpt-test")
    assert first != second
    assert "first-sensitive-value" not in repr(first)
    assert "second-sensitive-value" not in repr(second)
