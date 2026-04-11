"""Exercise the real Firecrawl plugin with an unavailable optional SDK."""

import asyncio
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from plugins.web.firecrawl import http_client as hc
from plugins.web.firecrawl import provider as fp
from tools import web_tools as wt


@pytest.fixture(autouse=True)
def missing_sdk(monkeypatch):
    monkeypatch.setattr(fp, "_FIRECRAWL_CLS_CACHE", None)
    monkeypatch.setitem(sys.modules, "firecrawl", None)
    monkeypatch.setattr("tools.lazy_deps.ensure", lambda *args, **kwargs: False)
    monkeypatch.setattr(wt, "_firecrawl_client", None)
    monkeypatch.setattr(wt, "_firecrawl_client_config", None)
    for name in ("FIRECRAWL_API_KEY", "FIRECRAWL_API_URL", "FIRECRAWL_GATEWAY_URL",
                 "TOOL_GATEWAY_USER_TOKEN", "TOOL_GATEWAY_DOMAIN"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("FIRECRAWL_API_KEY", "fc-test")
    _configure("firecrawl")


def _configure(backend):
    home = Path(os.environ["HERMES_HOME"])
    home.mkdir(parents=True, exist_ok=True)
    (home / "config.yaml").write_text(
        f"web:\n  backend: {backend}\n", encoding="utf-8"
    )


def _transport(monkeypatch, handler):
    real_client = httpx.Client
    monkeypatch.setattr(
        hc.httpx, "Client",
        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs),
    )


@pytest.mark.parametrize(
    "key,url,expected_url",
    [
        ("fc-test", None, "https://api.firecrawl.dev/v1/search"),
        (None, "http://localhost:3002", "http://localhost:3002/v1/search"),
        ("fc-test", "https://api.example/v1", "https://api.example/v1/search"),
    ],
)
def test_real_search_dispatch_falls_back_without_sdk(monkeypatch, key, url, expected_url):
    if key is None:
        monkeypatch.delenv("FIRECRAWL_API_KEY")
    if url is not None:
        monkeypatch.setenv("FIRECRAWL_API_URL", url)
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={
            "success": True,
            "data": [{"url": "https://example.com", "title": "Result", "description": "Found"}],
        })

    _transport(monkeypatch, handler)
    result = json.loads(wt.web_search_tool("unique fallback " + expected_url))
    assert result["success"] is True
    assert result["data"]["web"][0]["title"] == "Result"
    assert isinstance(fp._get_firecrawl_client(), hc._FirecrawlHTTPCompatClient)
    assert str(requests[0].url) == expected_url
    assert requests[0].headers.get("authorization") == (f"Bearer {key}" if key else None)
    assert "origin" not in json.loads(requests[0].content)


def test_real_plugin_extract_retains_redirect_safety(monkeypatch):
    calls = []
    monkeypatch.setattr(fp, "check_website_access", lambda url: None)
    monkeypatch.setattr(fp, "is_safe_url", lambda url: url == "https://example.com")

    def handler(request):
        calls.append(request)
        target = json.loads(request.content)["url"]
        return httpx.Response(200, json={"success": True, "data": {
            "markdown": "content",
            "metadata": {"title": "Title", "sourceURL": (
                "https://example.com" if target.endswith("/safe") else "http://127.0.0.1/private"
            )},
        }})

    _transport(monkeypatch, handler)
    result = asyncio.run(fp.FirecrawlWebSearchProvider().extract([
        "https://example.com/safe", "https://example.com/redirect",
    ]))
    assert result[0]["content"] == "content"
    assert "private or internal" in result[1]["error"]
    assert result[1]["content"] == ""
    assert all(request.url.path == "/v1/scrape" for request in calls)


def test_managed_selection_uses_gateway_not_direct_key(monkeypatch):
    _configure("nous")
    monkeypatch.setattr(fp._gateway, "resolve_managed_tool_gateway", lambda *args, **kwargs: SimpleNamespace(
        nous_user_token="gateway-test", gateway_origin="https://gateway.example",
    ))
    client = fp._get_firecrawl_client()
    assert isinstance(client, hc._FirecrawlHTTPCompatClient)
    assert client.api_key == "gateway-test"
    assert client.api_url == "https://gateway.example"


def test_managed_selection_unavailable_fails_closed(monkeypatch):
    _configure("nous")
    monkeypatch.setattr(fp._gateway, "resolve_managed_tool_gateway", lambda *args, **kwargs: None)
    with pytest.raises(ValueError, match="Nous|nous"):
        fp._get_firecrawl_client()
    assert wt._firecrawl_client is None


def test_explicit_keyless_selection_does_not_enter_sdk_fallback(monkeypatch):
    monkeypatch.delenv("FIRECRAWL_API_KEY")
    monkeypatch.setattr("tools.lazy_deps.ensure", lambda *args, **kwargs: pytest.fail("SDK requested"))
    assert isinstance(fp._get_firecrawl_client(), fp._KeylessFirecrawlClient)


def test_sdk_constructor_failure_is_not_hidden_by_fallback(monkeypatch):
    class BrokenSDK:
        def __init__(self, **kwargs):
            raise RuntimeError("invalid SDK configuration")

    monkeypatch.setitem(sys.modules, "firecrawl", SimpleNamespace(Firecrawl=BrokenSDK))
    with pytest.raises(RuntimeError, match="invalid SDK configuration"):
        fp._get_firecrawl_client()
    assert wt._firecrawl_client is None


def test_readiness_only_peeks_at_oauth_token(monkeypatch):
    monkeypatch.delenv("FIRECRAWL_API_KEY")
    _configure("nous")
    monkeypatch.setattr(fp._gateway, "read_nous_access_token", lambda: pytest.fail("OAuth refreshed during readiness"))
    monkeypatch.setattr(fp._gateway, "peek_nous_access_token", lambda: "peek-token")
    readers = []

    def resolve(vendor, *, token_reader):
        readers.append(token_reader())
        return SimpleNamespace() if readers[-1] else None

    monkeypatch.setattr(fp._gateway, "resolve_managed_tool_gateway", resolve)
    assert fp._is_tool_gateway_ready() is True
    assert readers == ["peek-token"]


def test_profile_scope_does_not_reuse_ambient_or_other_profile_token(monkeypatch):
    from agent.secret_scope import reset_secret_scope, set_multiplex_active, set_secret_scope

    monkeypatch.setenv("FIRECRAWL_API_KEY", "ambient-token")
    set_multiplex_active(True)
    try:
        for scoped_key in ("profile-a", "profile-b"):
            token = set_secret_scope({"FIRECRAWL_API_KEY": scoped_key})
            try:
                assert fp._get_firecrawl_client().api_key == scoped_key
            finally:
                reset_secret_scope(token)
        token = set_secret_scope({})
        try:
            assert isinstance(fp._get_firecrawl_client(), fp._KeylessFirecrawlClient)
        finally:
            reset_secret_scope(token)
    finally:
        set_multiplex_active(False)


@pytest.mark.parametrize("next_reference", [
    "https://api.firecrawl.dev/v1/crawl/job?page=2", "?page=2",
])
def test_http_crawl_merges_same_origin_pages(monkeypatch, next_reference):
    requests = []

    def handler(request):
        requests.append(request)
        if request.method == "POST":
            assert json.loads(request.content)["scrapeOptions"] == {"formats": ["markdown"]}
            return httpx.Response(200, json={"success": True, "id": "job"})
        if request.url.query:
            assert request.url.path == "/v1/crawl/job"
            return httpx.Response(200, json={"data": [{"markdown": "second"}]})
        return httpx.Response(200, json={
            "status": "completed", "data": [{"markdown": "first"}],
            "next": next_reference,
        })

    _transport(monkeypatch, handler)
    result = fp._get_firecrawl_client().crawl(
        url="https://example.com", scrape_options={"formats": ["markdown"]}, max_concurrency=4,
    )
    assert [row["markdown"] for row in result["data"]] == ["first", "second"]
    assert result["next"] is None
    assert len(requests) == 3
    assert all(r.headers["authorization"] == "Bearer fc-test" for r in requests)


@pytest.mark.parametrize("next_url", [
    "https://foreign.example/page", "//foreign.example/page",
    "http://api.firecrawl.dev/page", "https://api.firecrawl.dev:444/page",
    "https://api.firecrawl.dev:0/page",
    "https://user@api.firecrawl.dev/page", "http://127.0.0.1/private",
])
def test_pagination_rejects_foreign_origin_before_request(monkeypatch, next_url):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={"status": "completed", "data": [], "next": next_url})

    _transport(monkeypatch, handler)
    with pytest.raises(ValueError, match="configured API origin"):
        fp._get_firecrawl_client()._wait_for_crawl("job")
    assert len(requests) == 1
    assert requests[0].url.host == "api.firecrawl.dev"


def test_backend_redirect_never_forwards_credentials(monkeypatch):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(307, headers={"Location": "https://foreign.example/leak"})

    _transport(monkeypatch, handler)
    with pytest.raises(httpx.HTTPStatusError):
        fp._get_firecrawl_client().scrape(url="https://example.com")
    assert len(requests) == 1
    assert requests[0].url.host == "api.firecrawl.dev"


def test_pagination_detects_cycles(monkeypatch):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={
            "status": "completed", "data": [], "next": "/v1/crawl/job",
        })

    _transport(monkeypatch, handler)
    with pytest.raises(RuntimeError, match="cycle"):
        fp._get_firecrawl_client()._wait_for_crawl("job")
    assert len(requests) == 1


def test_unique_pagination_is_finitely_bounded(monkeypatch):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={
            "status": "completed", "data": [], "next": f"/v1/crawl/job?page={len(calls)}",
        })

    _transport(monkeypatch, handler)
    client = fp._get_firecrawl_client()
    monkeypatch.setattr(client, "_MAX_CRAWL_REQUESTS", 3)
    with pytest.raises(RuntimeError, match="request limit"):
        client._wait_for_crawl("job")
    assert len(calls) == 3


def test_pagination_deadline_bounds_every_request(monkeypatch):
    clock = [0.0]
    calls = []
    monkeypatch.setattr(hc, "time", SimpleNamespace(monotonic=lambda: clock[0], sleep=lambda duration: None))

    def handler(request):
        calls.append(request)
        assert request.extensions["timeout"]["read"] <= 1.0
        clock[0] += 2.0
        return httpx.Response(200, json={
            "status": "completed", "data": [], "next": "/v1/crawl/job?page=2",
        })

    _transport(monkeypatch, handler)
    with pytest.raises(TimeoutError, match="deadline"):
        fp._get_firecrawl_client()._wait_for_crawl("job", timeout=1)
    assert len(calls) == 1


def test_slow_drip_body_cannot_defer_deadline_check(monkeypatch):
    clock = [0.0]
    consumed = []
    monkeypatch.setattr(hc, "time", SimpleNamespace(monotonic=lambda: clock[0], sleep=lambda duration: None))

    class SlowBody(httpx.SyncByteStream):
        def __iter__(self):
            for index in range(20):
                clock[0] += 0.6
                consumed.append(index)
                yield b" "

    _transport(monkeypatch, lambda request: httpx.Response(200, stream=SlowBody()))
    with pytest.raises(TimeoutError, match="deadline"):
        fp._get_firecrawl_client()._wait_for_crawl("job", timeout=1)
    assert consumed == [0, 1]


def test_response_body_size_is_bounded(monkeypatch):
    _transport(monkeypatch, lambda request: httpx.Response(200, content=b"123456789"))
    client = fp._get_firecrawl_client()
    monkeypatch.setattr(client, "_MAX_RESPONSE_BYTES", 8)
    with pytest.raises(RuntimeError, match="size limit"):
        client.search(query="test")


def test_interrupt_during_pagination_stops_next_request(monkeypatch):
    interrupted = [False]
    calls = []
    monkeypatch.setattr(hc, "is_interrupted", lambda: interrupted[0])

    def handler(request):
        calls.append(request)
        interrupted[0] = True
        return httpx.Response(200, json={
            "status": "completed", "data": [], "next": "/v1/crawl/job?page=2",
        })

    _transport(monkeypatch, handler)
    with pytest.raises(InterruptedError):
        fp._get_firecrawl_client()._wait_for_crawl("job")
    assert len(calls) == 1


def test_polling_checks_interrupt_in_short_waits(monkeypatch):
    clock = [0.0]
    interrupted = [False]
    monkeypatch.setattr(hc, "is_interrupted", lambda: interrupted[0])

    def sleep(duration):
        assert duration <= 0.1
        clock[0] += duration
        interrupted[0] = True

    monkeypatch.setattr(hc, "time", SimpleNamespace(monotonic=lambda: clock[0], sleep=sleep))
    _transport(monkeypatch, lambda request: httpx.Response(200, json={"status": "scraping"}))
    with pytest.raises(InterruptedError):
        fp._get_firecrawl_client()._wait_for_crawl("job")


def test_preexisting_interrupt_prevents_any_http_request(monkeypatch):
    monkeypatch.setattr(hc, "is_interrupted", lambda: True)
    _transport(monkeypatch, lambda request: pytest.fail("HTTP request after interrupt"))
    with pytest.raises(InterruptedError):
        fp._get_firecrawl_client().search(query="test")


def test_non_json_response_has_clear_error(monkeypatch):
    _transport(monkeypatch, lambda request: httpx.Response(200, text="not JSON"))
    with pytest.raises(RuntimeError, match="non-JSON"):
        fp._get_firecrawl_client().search(query="test")


@pytest.mark.parametrize("payload", [[], {"success": False, "error": "private backend text"}])
def test_invalid_or_unsuccessful_responses_fail(monkeypatch, payload):
    _transport(monkeypatch, lambda request: httpx.Response(200, json=payload))
    with pytest.raises(RuntimeError):
        fp._get_firecrawl_client().search(query="test")
