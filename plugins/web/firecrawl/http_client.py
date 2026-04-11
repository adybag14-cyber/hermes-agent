"""SDK-independent Firecrawl v1 transport for stripped/Android installations.

Provider selection, profile credentials, and target website/SSRF policy remain
owned by the Firecrawl plugin. API pagination stays on the configured origin.
"""

from __future__ import annotations

import math
import json
import time
from typing import Any
from urllib.parse import quote, urljoin, urlsplit

import httpx

from tools.interrupt import is_interrupted


def _normalize_firecrawl_api_url(api_url: str) -> str:
    normalized = str(api_url or "").strip().rstrip("/")
    parsed = urlsplit(normalized)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("Firecrawl API URL must be an HTTP(S) endpoint without credentials, query, or fragment")
    if normalized.endswith(("/v1", "/v2")):
        normalized = normalized.rsplit("/", 1)[0]
    return str(httpx.URL(normalized)).rstrip("/")


def _camelize_firecrawl_payload(value: Any) -> Any:
    if isinstance(value, list):
        return [_camelize_firecrawl_payload(item) for item in value]
    if not isinstance(value, dict):
        return value
    aliases = {
        "scrape_options": "scrapeOptions", "include_paths": "includePaths",
        "exclude_paths": "excludePaths", "max_depth": "maxDepth",
        "max_discovery_depth": "maxDiscoveryDepth", "crawl_entire_domain": "crawlEntireDomain",
        "allow_backward_links": "allowBackwardLinks", "allow_external_links": "allowExternalLinks",
        "ignore_sitemap": "ignoreSitemap", "deduplicate_similar_urls": "deduplicateSimilarURLs",
        "ignore_query_parameters": "ignoreQueryParameters", "regex_on_full_url": "regexOnFullURL",
        "allow_subdomains": "allowSubdomains", "max_concurrency": "maxConcurrency",
        "zero_data_retention": "zeroDataRetention",
    }
    return {
        aliases.get(key, key): _camelize_firecrawl_payload(item)
        for key, item in value.items() if item is not None
    }


class _FirecrawlHTTPCompatClient:
    """Duck-type Firecrawl search/scrape/crawl without the optional SDK."""

    _MAX_CRAWL_REQUESTS = 100
    _MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    def __init__(self, *, api_key: str = "", api_url: str = "https://api.firecrawl.dev"):
        self.api_key = str(api_key or "").strip()
        self.api_url = _normalize_firecrawl_api_url(api_url)
        self._api_origin = self._origin(self.api_url)

    @staticmethod
    def _origin(url: str) -> tuple[str, str, int]:
        parsed = urlsplit(url)
        port = parsed.port if parsed.port is not None else (443 if parsed.scheme == "https" else 80)
        return parsed.scheme, parsed.hostname or "", port

    def _api_url(self, reference: str, *, relative_to: str | None = None) -> str:
        if not isinstance(reference, str) or not reference.strip():
            raise ValueError("Firecrawl returned an invalid pagination URL")
        url = str(httpx.URL(urljoin(relative_to or self.api_url + "/", reference)))
        parsed = urlsplit(url)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.username is not None
            or parsed.password is not None
            or parsed.fragment
            or self._origin(url) != self._api_origin
        ):
            raise ValueError("Firecrawl pagination must stay on the configured API origin")
        return url

    @staticmethod
    def _check_interrupt() -> None:
        if is_interrupted():
            raise InterruptedError("Firecrawl request interrupted")

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept-Encoding": "identity"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def _request_json(
        self, method: str, url: str, *, payload: dict[str, Any] | None = None,
        timeout: float = 30.0, deadline: float | None = None,
    ) -> dict[str, Any]:
        self._check_interrupt()
        url = self._api_url(url)
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Firecrawl crawl deadline exceeded")
            timeout = min(timeout, remaining)
        # Fail closed on redirects; only explicitly origin-checked pagination
        # links are followed. Configure the backend's canonical API URL.
        body = bytearray()
        with httpx.Client(timeout=timeout, follow_redirects=False) as client:
            with client.stream(method, url, headers=self._headers(), json=payload) as response:
                response.raise_for_status()
                # HTTPX read timeouts are inactivity limits, not total body
                # deadlines. Consume incrementally so a slow-drip response
                # cannot postpone the budget check until after the full JSON.
                for chunk in response.iter_bytes():
                    self._check_interrupt()
                    if deadline is not None and time.monotonic() >= deadline:
                        raise TimeoutError("Firecrawl crawl deadline exceeded")
                    if len(body) + len(chunk) > self._MAX_RESPONSE_BYTES:
                        raise RuntimeError("Firecrawl response exceeded the size limit")
                    body.extend(chunk)
        self._check_interrupt()
        if deadline is not None and time.monotonic() >= deadline:
            raise TimeoutError("Firecrawl crawl deadline exceeded")
        try:
            data = json.loads(body)
        except ValueError as exc:
            raise RuntimeError("Firecrawl returned a non-JSON response") from exc
        if not isinstance(data, dict):
            raise RuntimeError("Firecrawl returned a non-object response")
        if data.get("success") is False:
            raise RuntimeError("Firecrawl reported an unsuccessful request")
        return data

    def _post_json(self, path: str, payload: dict[str, Any], *, default_timeout: float = 30.0) -> dict[str, Any]:
        timeout = default_timeout
        timeout_ms = payload.get("timeout")
        if isinstance(timeout_ms, (int, float)) and not isinstance(timeout_ms, bool):
            if math.isfinite(timeout_ms) and timeout_ms > 0:
                timeout = min(180.0, timeout_ms / 1000.0 + 5.0)
        return self._request_json("POST", self.api_url + path, payload=payload, timeout=timeout)

    def _get_json(self, reference: str, *, deadline: float) -> dict[str, Any]:
        return self._request_json("GET", reference, deadline=deadline)

    def search(self, *, query: str, limit: int = 5, **kwargs: Any) -> dict[str, Any]:
        payload = {"query": query, "limit": limit, **_camelize_firecrawl_payload(kwargs)}
        return self._post_json("/v1/search", payload)

    def scrape(self, *, url: str, formats: list[str] | None = None, **kwargs: Any) -> dict[str, Any]:
        payload = {"url": url, **_camelize_firecrawl_payload(kwargs)}
        if formats is not None:
            payload["formats"] = formats
        return self._post_json("/v1/scrape", payload, default_timeout=60.0)

    def crawl(self, *, url: str, **kwargs: Any) -> dict[str, Any]:
        payload = {**_camelize_firecrawl_payload(kwargs), "url": url}
        start = self._post_json("/v1/crawl", payload)
        job_id = start.get("id") or start.get("jobId")
        if not isinstance(job_id, str) or not job_id:
            raise RuntimeError("Firecrawl crawl response did not include a job id")
        return self._wait_for_crawl(job_id)

    def _wait_for_crawl(self, job_id: str, *, poll_interval: float = 2.0, timeout: float = 180.0) -> dict[str, Any]:
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("Firecrawl crawl timeout must be positive and finite")
        if not math.isfinite(poll_interval) or poll_interval < 0:
            raise ValueError("Firecrawl poll interval must be non-negative and finite")
        deadline = time.monotonic() + timeout
        status_url = self.api_url + "/v1/crawl/" + quote(job_id, safe="")
        requests_left = self._MAX_CRAWL_REQUESTS
        while requests_left > 0:
            requests_left -= 1
            status_data = self._get_json(status_url, deadline=deadline)
            status = str(status_data.get("status") or "").lower()
            if status == "completed":
                data = list(status_data.get("data") or [])
                visited = {self._api_url(status_url)}
                page_url = status_url
                next_url = status_data.get("next")
                while next_url:
                    next_url = self._api_url(next_url, relative_to=page_url)
                    if next_url in visited:
                        raise RuntimeError("Firecrawl pagination cycle detected")
                    if requests_left <= 0:
                        raise RuntimeError("Firecrawl crawl request limit exceeded")
                    visited.add(next_url)
                    requests_left -= 1
                    page = self._get_json(next_url, deadline=deadline)
                    page_url = next_url
                    data.extend(list(page.get("data") or []))
                    next_url = page.get("next")
                return {**status_data, "data": data, "next": None}
            if status in {"failed", "cancelled", "error"}:
                raise RuntimeError(f"Firecrawl crawl ended with status '{status}'")
            wake_at = min(deadline, time.monotonic() + poll_interval)
            while time.monotonic() < wake_at:
                self._check_interrupt()
                time.sleep(min(0.1, max(0.0, wake_at - time.monotonic())))
        raise RuntimeError("Firecrawl crawl request limit exceeded")
