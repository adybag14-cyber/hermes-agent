import json


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload
        self.text = json.dumps(payload)

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


def test_get_firecrawl_client_falls_back_to_http_client(monkeypatch):
    from tools import web_tools

    monkeypatch.setattr(web_tools, "_firecrawl_client", None)
    monkeypatch.setattr(web_tools, "_firecrawl_client_config", None)
    monkeypatch.setattr(web_tools, "_load_firecrawl_client_class", lambda: None)
    monkeypatch.setenv("FIRECRAWL_API_KEY", "fc-test")
    monkeypatch.setenv("FIRECRAWL_API_URL", "https://api.firecrawl.dev/v1")

    client = web_tools._get_firecrawl_client()

    assert isinstance(client, web_tools._FirecrawlHTTPCompatClient)
    assert client.api_url == "https://api.firecrawl.dev"


def test_firecrawl_http_compat_search_uses_v1_endpoint(monkeypatch):
    from tools import web_tools

    captured = {}

    class _FakeClient:
        def __init__(self, *args, **kwargs):
            captured["client_kwargs"] = kwargs

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def post(self, url, headers=None, json=None):
            captured["url"] = url
            captured["headers"] = headers
            captured["json"] = json
            return _FakeResponse({"success": True, "data": []})

    monkeypatch.setattr(web_tools.httpx, "Client", _FakeClient)

    client = web_tools._FirecrawlHTTPCompatClient(
        api_key="fc-test",
        api_url="https://api.firecrawl.dev/v1",
    )
    result = client.search(query="termux hermes", limit=3)

    assert result == {"success": True, "data": []}
    assert captured["url"] == "https://api.firecrawl.dev/v1/search"
    assert captured["headers"]["Authorization"] == "Bearer fc-test"
    assert captured["json"]["query"] == "termux hermes"
    assert captured["json"]["limit"] == 3
    assert captured["json"]["origin"] == "hermes-agent"


def test_firecrawl_http_compat_crawl_polls_until_completed(monkeypatch):
    from tools import web_tools

    posted = {}
    get_calls = []

    class _FakeClient:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def post(self, url, headers=None, json=None):
            posted["url"] = url
            posted["json"] = json
            return _FakeResponse({"success": True, "id": "crawl-123"})

        def get(self, url, headers=None):
            get_calls.append(url)
            return _FakeResponse({
                "status": "completed",
                "data": [{"markdown": "hello", "metadata": {"sourceURL": "https://example.com"}}],
            })

    monkeypatch.setattr(web_tools.httpx, "Client", _FakeClient)
    monkeypatch.setattr(web_tools.time, "sleep", lambda *_args, **_kwargs: None)

    client = web_tools._FirecrawlHTTPCompatClient(
        api_key="fc-test",
        api_url="https://api.firecrawl.dev",
    )
    result = client.crawl(
        url="https://example.com",
        scrape_options={"formats": ["markdown"]},
        max_concurrency=4,
    )

    assert posted["url"] == "https://api.firecrawl.dev/v1/crawl"
    assert posted["json"]["url"] == "https://example.com"
    assert posted["json"]["scrapeOptions"] == {"formats": ["markdown"]}
    assert posted["json"]["maxConcurrency"] == 4
    assert get_calls == ["https://api.firecrawl.dev/v1/crawl/crawl-123"]
    assert result["status"] == "completed"
    assert result["data"][0]["metadata"]["sourceURL"] == "https://example.com"
