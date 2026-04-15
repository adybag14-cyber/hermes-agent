"""Helpers for ChatGPT Web credential bootstrap."""

from __future__ import annotations

import json
import urllib.request


DEFAULT_CHATGPT_WEB_BASE_URL = "https://chatgpt.com/backend-api/codex"
CHATGPT_WEB_SESSION_URL = "https://chatgpt.com/api/auth/session"


def _fetch_chatgpt_web_access_token_from_session(
    session_token: str,
    *,
    timeout: float = 20.0,
    session_url: str = CHATGPT_WEB_SESSION_URL,
) -> str:
    token = (session_token or "").strip()
    if not token:
        raise ValueError("session token is empty")

    request = urllib.request.Request(
        session_url,
        headers={
            "Accept": "application/json",
            "Cookie": f"__Secure-next-auth.session-token={token}",
            "User-Agent": "Hermes-Agent/ChatGPT-Web-Auth",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.load(response)

    access_token = _extract_access_token(payload)
    if not access_token:
        raise RuntimeError("ChatGPT session response did not include an access token")
    return access_token


def _extract_access_token(payload: object) -> str | None:
    if not isinstance(payload, dict):
        return None
    for key in ("accessToken", "access_token"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    for nested_key in ("token", "tokens"):
        nested = payload.get(nested_key)
        if isinstance(nested, dict):
            value = _extract_access_token(nested)
            if value:
                return value
    return None
