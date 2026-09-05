"""ChatGPT Web credentials and browser selectors respect profile secret scopes."""

from contextlib import contextmanager
import json
from types import SimpleNamespace

import httpx
import pytest

from agent import credential_pool, secret_scope
from hermes_cli import auth, auth_codex, chatgpt_web


def _credentials(label):
    return {
        "CHATGPT_WEB_ACCESS_TOKEN": f"access-{label}",
        "CHATGPT_WEB_SESSION_TOKEN": f"session-{label}",
        "CHATGPT_WEB_COOKIE_HEADER": f"profile_cookie=cookie-{label}",
        "CHATGPT_WEB_USER_AGENT": f"user-agent-{label}",
        "CHATGPT_WEB_DEVICE_ID": f"device-{label}",
        "CHATGPT_WEB_DEBUG_BASE": f"http://browser-{label.lower()}.invalid:9222",
        "CHATGPT_WEB_FORCE_BROWSER_FETCH": "true",
    }


@contextmanager
def _scope(values):
    token = secret_scope.set_secret_scope(values)
    try:
        yield
    finally:
        secret_scope.reset_secret_scope(token)


@pytest.fixture(autouse=True)
def isolated_credentials(monkeypatch):
    old_multiplex = secret_scope.is_multiplex_active()
    token = secret_scope.set_secret_scope(None)
    secret_scope.set_multiplex_active(True)
    for name, value in _credentials("AMBIENT-A").items():
        monkeypatch.setenv(name, value)
    # The global pool/shared Codex grant remains a supported fallback. Stub
    # that authority explicitly instead of allowing tests to inspect real auth.
    monkeypatch.setattr(credential_pool, "load_pool", lambda _provider: None)
    monkeypatch.setattr(auth, "resolve_codex_runtime_credentials", lambda **_kwargs: {"api_key": "shared-codex-grant"})
    monkeypatch.setattr(auth, "get_codex_auth_status", lambda: {"logged_in": True, "api_key": "shared-codex-grant"})
    monkeypatch.setattr(auth_codex, "_codex_access_token_is_expiring", lambda *_args: False)
    try:
        yield
    finally:
        secret_scope.reset_secret_scope(token)
        secret_scope.set_multiplex_active(old_multiplex)


def _catalog(monkeypatch):
    requests = []

    def get(url, **kwargs):
        requests.append(httpx.Request("GET", url, headers=kwargs["headers"]))
        return httpx.Response(200, request=requests[-1], json={"models": [{"slug": "visible-model"}]})

    monkeypatch.setattr(chatgpt_web.httpx, "get", get)
    return requests


@pytest.mark.parametrize("label", ["A", "B"])
def test_runtime_and_status_use_only_active_profile(label):
    with _scope(_credentials(label)):
        runtime = chatgpt_web.resolve_chatgpt_web_runtime_credentials()
        status = auth.get_chatgpt_web_auth_status()
    assert runtime["api_key"] == f"access-{label}"
    assert runtime["session_token"] == f"session-{label}"
    assert runtime["cookie_header"] == f"profile_cookie=cookie-{label}"
    assert runtime["user_agent"] == f"user-agent-{label}"
    assert runtime["device_id"] == f"device-{label}"
    assert status["api_key"] == f"access-{label}"


def test_missing_profile_credentials_preserve_shared_grant_not_ambient_profile():
    with _scope({}):
        runtime = chatgpt_web.resolve_chatgpt_web_runtime_credentials()
        status = auth.get_chatgpt_web_auth_status()
    assert runtime["api_key"] == status["api_key"] == "shared-codex-grant"
    assert runtime["source"] == "codex-oauth"
    assert status["auth_mode"] == "codex_oauth"
    assert all(runtime[field] == "" for field in ("session_token", "cookie_header", "user_agent", "device_id"))


def test_pool_fallback_remains_intentionally_shared(monkeypatch):
    entry = SimpleNamespace(
        runtime_api_key="shared-pool-grant", session_token="shared-pool-session",
        cookie_header="pool_cookie=shared", browser_cookies=None,
        user_agent="pool-agent", device_id="pool-device", label="shared-entry",
        base_url=chatgpt_web.DEFAULT_CHATGPT_WEB_BASE_URL,
    )
    pool = SimpleNamespace(has_credentials=lambda: True, select=lambda: entry)
    monkeypatch.setattr(credential_pool, "load_pool", lambda _provider: pool)
    with _scope({}):
        runtime = chatgpt_web.resolve_chatgpt_web_runtime_credentials()
        status = auth.get_chatgpt_web_auth_status()
    assert runtime["api_key"] == status["api_key"] == "shared-pool-grant"
    assert runtime["source"] == status["source"] == "pool:shared-entry"
    assert runtime["cookie_header"] == "pool_cookie=shared"
    assert runtime["user_agent"] == "pool-agent"


def test_session_exchange_never_uses_ambient_access_or_cookies(monkeypatch):
    captured = []

    def get(url, **kwargs):
        request = httpx.Request("GET", url, headers=kwargs["headers"])
        captured.append(request)
        return httpx.Response(200, request=request, json={"accessToken": "exchanged-B"})

    monkeypatch.setattr(chatgpt_web.httpx, "get", get)
    with _scope({"CHATGPT_WEB_SESSION_TOKEN": "session-B"}):
        result = chatgpt_web.resolve_chatgpt_web_runtime_credentials()
    assert result["api_key"] == "exchanged-B"
    assert result["source"] == "session-token"
    assert "session-B" in captured[0].headers["Cookie"]
    assert "AMBIENT-A" not in str(captured[0].headers)


@pytest.mark.parametrize("label", ["A", "B"])
def test_catalog_headers_use_explicit_access_with_scoped_metadata(monkeypatch, label):
    requests = _catalog(monkeypatch)
    with _scope(_credentials(label)):
        models = chatgpt_web.fetch_chatgpt_web_model_ids(access_token="explicit-access")
    assert models == ["visible-model"]
    headers = requests[0].headers
    assert headers["Authorization"] == "Bearer explicit-access"
    assert headers["User-Agent"] == f"user-agent-{label}"
    assert headers["Oai-Device-Id"] == f"device-{label}"
    assert f"cookie-{label}" in headers["Cookie"]
    assert f"session-{label}" in headers["Cookie"]
    assert "AMBIENT-A" not in str(headers)


def test_catalog_explicit_arguments_take_precedence_over_scope(monkeypatch):
    requests = _catalog(monkeypatch)
    with _scope(_credentials("B")):
        chatgpt_web.fetch_chatgpt_web_model_ids(
            access_token="explicit-access", session_token="explicit-session",
            cookie_header="profile_cookie=explicit-cookie", user_agent="explicit-agent",
            device_id="explicit-device",
            browser_cookies=[{"name": "browser_cookie", "value": "explicit-browser-cookie"}],
        )
    headers = requests[0].headers
    assert headers["Authorization"] == "Bearer explicit-access"
    assert headers["User-Agent"] == "explicit-agent"
    assert headers["Oai-Device-Id"] == "explicit-device"
    assert "explicit-session" in headers["Cookie"]
    assert "profile_cookie=explicit-cookie" in headers["Cookie"]
    assert "browser_cookie=explicit-browser-cookie" in headers["Cookie"]
    assert "session-B" not in headers["Cookie"]


def test_catalog_missing_scoped_metadata_does_not_reborrow_ambient_defaults(monkeypatch):
    requests = _catalog(monkeypatch)
    with _scope({}):
        chatgpt_web.fetch_chatgpt_web_model_ids(access_token="explicit-B")
    headers = requests[0].headers
    assert headers["User-Agent"] == chatgpt_web.DEFAULT_CHATGPT_WEB_USER_AGENT
    assert headers["Oai-Device-Id"]
    assert "AMBIENT-A" not in str(headers)
    assert "__Secure-next-auth.session-token" not in headers.get("Cookie", "")


def test_default_metadata_and_browser_selectors_follow_sequential_scopes():
    results = []
    for mapping in (_credentials("A"), _credentials("B"), {}, _credentials("A")):
        with _scope(mapping):
            results.append((
                chatgpt_web._default_user_agent(), chatgpt_web._default_device_id(),
                chatgpt_web._chatgpt_web_debug_base(), chatgpt_web._chatgpt_web_force_browser_fetch(),
            ))
    assert results[0] == results[3] == ("user-agent-A", "device-A", "http://browser-a.invalid:9222", True)
    assert results[1] == ("user-agent-B", "device-B", "http://browser-b.invalid:9222", True)
    assert results[2][0] == chatgpt_web.DEFAULT_CHATGPT_WEB_USER_AGENT
    assert results[2][1] not in {"device-A", "device-B", "device-AMBIENT-A"}
    assert results[2][2:] == ("", False)


@pytest.mark.parametrize("operation", [
    "runtime", "status", "catalog", "default-agent", "default-device", "debug", "force", "stream",
])
def test_unscoped_multiplex_read_raises_before_network(monkeypatch, operation):
    calls = []

    def unexpected(*_args, **_kwargs):
        calls.append("network")
        raise AssertionError("network must not be reached without a secret scope")

    monkeypatch.setattr(chatgpt_web.httpx, "get", unexpected)
    monkeypatch.setattr(chatgpt_web.httpx, "Client", unexpected)
    monkeypatch.setattr(chatgpt_web, "_chatgpt_web_browser_fetch_sync", unexpected)
    operations = {
        "runtime": chatgpt_web.resolve_chatgpt_web_runtime_credentials,
        "status": auth.get_chatgpt_web_auth_status,
        "catalog": lambda: chatgpt_web.fetch_chatgpt_web_model_ids(access_token="explicit-B"),
        "default-agent": chatgpt_web._default_user_agent,
        "default-device": chatgpt_web._default_device_id,
        "debug": chatgpt_web._chatgpt_web_debug_base,
        "force": chatgpt_web._chatgpt_web_force_browser_fetch,
        "stream": lambda: chatgpt_web.stream_chatgpt_web_completion(
            access_token="explicit-B", model="test-model", user_agent="explicit-agent",
            device_id="explicit-device", messages=[{"role": "user", "content": "hello"}],
        ),
    }
    with pytest.raises(secret_scope.UnscopedSecretError):
        operations[operation]()
    assert calls == []


def test_missing_debug_selector_cannot_use_another_profiles_signed_in_browser(monkeypatch):
    calls = []

    async def unexpected_browser(**_kwargs):
        calls.append("browser")
        raise AssertionError("ambient browser must not be used")

    monkeypatch.setattr(chatgpt_web, "_chatgpt_web_browser_multimodal_completion", unexpected_browser)
    with _scope({}):
        with pytest.raises(RuntimeError, match="requires CHATGPT_WEB_DEBUG_BASE"):
            chatgpt_web.stream_chatgpt_web_completion(
                access_token="explicit-B", model="test-model",
                messages=[{"role": "user", "content": [
                    {"type": "text", "text": "describe this"},
                    {"type": "image_url", "image_url": {"url": "https://image.invalid/example.png"}},
                ]}],
            )
    assert calls == []


@pytest.mark.parametrize("force", [None, "true"])
def test_stream_browser_selection_never_borrows_ambient_force_flag(monkeypatch, force):
    http_requests = []
    browser_requests = []
    event = {"message": {"id": "reply", "author": {"role": "assistant"},
                         "content": {"content_type": "text", "parts": ["done"]}}}
    stream_text = "data: " + json.dumps(event) + "\n\ndata: [DONE]\n\n"

    def payload(url):
        if url.endswith("/prepare"):
            return json.dumps({"conduit_token": "conduit"})
        if url.endswith("/chat-requirements"):
            return json.dumps({"token": "requirements"})
        return stream_text

    def handler(request):
        http_requests.append(request)
        return httpx.Response(200, request=request, text=payload(str(request.url)))

    def browser_fetch(**kwargs):
        browser_requests.append(kwargs)
        return {"status": 200, "text": payload(kwargs["url"])}

    monkeypatch.setattr(chatgpt_web, "_chatgpt_web_browser_fetch_sync", browser_fetch)
    mapping = {"CHATGPT_WEB_DEBUG_BASE": "http://browser-b.invalid:9222"}
    if force is not None:
        mapping["CHATGPT_WEB_FORCE_BROWSER_FETCH"] = force
    with httpx.Client(transport=httpx.MockTransport(handler)) as client, _scope(mapping):
        result = chatgpt_web.stream_chatgpt_web_completion(
            access_token="explicit-B", model="test-model", client=client,
            messages=[{"role": "user", "content": "hello"}],
        )
    assert result["content"] == "done"
    if force is None:
        assert http_requests and not browser_requests
    else:
        assert browser_requests and not http_requests
        assert {request["debug_base"] for request in browser_requests} == {"http://browser-b.invalid:9222"}


@pytest.mark.parametrize("install_empty_scope", [False, True])
def test_multiplex_off_preserves_single_profile_environment_behavior(install_empty_scope):
    secret_scope.set_multiplex_active(False)
    with _scope({} if install_empty_scope else None):
        runtime = chatgpt_web.resolve_chatgpt_web_runtime_credentials()
        status = auth.get_chatgpt_web_auth_status()
        assert chatgpt_web._chatgpt_web_debug_base() == "http://browser-ambient-a.invalid:9222"
        assert chatgpt_web._chatgpt_web_force_browser_fetch() is True
    assert runtime["api_key"] == status["api_key"] == "access-AMBIENT-A"
    assert runtime["session_token"] == "session-AMBIENT-A"
