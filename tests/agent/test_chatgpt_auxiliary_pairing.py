"""The selected provider pair survives pool and auxiliary-client boundaries."""

from types import SimpleNamespace

from agent.chatgpt_credentials import chatgpt_web_agent_kwargs


def _runtime():
    return {
        "provider": "chatgpt-web", "api_mode": "chatgpt_web", "model": "test-model",
        "api_key": "selected-grant", "base_url": "https://chatgpt.com/backend-api/f",
        "session_token": "selected-session", "cookie_header": "selected=cookie",
        "browser_cookies": [{"name": "selected", "value": "original"}],
        "user_agent": "selected-agent", "device_id": "selected-device",
    }


def test_pool_runtime_freezes_metadata_at_the_selected_entry():
    from hermes_cli.runtime_provider import _resolve_runtime_from_pool_entry

    data = _runtime()
    entry = SimpleNamespace(**data, runtime_api_key=data["api_key"], runtime_base_url=data["base_url"])
    runtime = _resolve_runtime_from_pool_entry(
        provider="chatgpt-web", entry=entry, requested_provider="chatgpt-web",
        model_cfg={"default": "test-model"},
    )
    entry.browser_cookies[0]["value"] = "later mutation"
    snapshot = runtime["chatgpt_web_credentials"]
    assert snapshot.api_key == data["api_key"]
    assert snapshot.session_token == data["session_token"]
    assert snapshot.browser_cookies[0]["value"] == "original"


def test_auxiliary_auto_route_keeps_main_pair_without_reselecting(monkeypatch):
    from agent import auxiliary_chatgpt_web as web, auxiliary_client as aux

    runtime = _runtime()
    runtime.update(chatgpt_web_agent_kwargs(runtime))
    captured = []

    def forbidden(*args, **kwargs):
        raise AssertionError("a resolved main grant must not reselect browser credentials")

    monkeypatch.setattr(web, "resolve_chatgpt_web_runtime_credentials", forbidden)
    monkeypatch.setattr(web, "matching_chatgpt_web_credentials", forbidden)
    monkeypatch.setattr(web, "stream_chatgpt_web_completion", lambda **kwargs: captured.append(kwargs) or {"content": "ok"})
    client, model = aux.resolve_provider_client("auto", main_runtime=runtime)
    response = client.chat.completions.create(messages=[{"role": "user", "content": "test"}])
    assert model == runtime["model"]
    assert response.choices[0].message.content == "ok"
    assert captured[0]["credential_snapshot"] == runtime["chatgpt_web_credentials"]
    assert captured[0]["access_token"] == runtime["api_key"]
