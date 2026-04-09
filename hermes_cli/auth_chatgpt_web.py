"""ChatGPT Web authentication status."""

import os
from typing import Any, Dict


def get_chatgpt_web_auth_status() -> Dict[str, Any]:
    """Status snapshot for ChatGPT Web auth.

    Reuses Codex OAuth credentials when no explicit ChatGPT web env vars are set.
    """
    access_token = os.getenv("CHATGPT_WEB_ACCESS_TOKEN", "").strip()
    session_token = os.getenv("CHATGPT_WEB_SESSION_TOKEN", "").strip()
    if access_token:
        return {
            "logged_in": True,
            "auth_mode": "access_token",
            "source": "env:CHATGPT_WEB_ACCESS_TOKEN",
            "api_key": access_token,
        }
    if session_token:
        return {
            "logged_in": True,
            "auth_mode": "session_token",
            "source": "env:CHATGPT_WEB_SESSION_TOKEN",
            "api_key": "",
        }

    from hermes_cli.auth import get_codex_auth_status

    codex_status = get_codex_auth_status()
    if codex_status.get("logged_in"):
        return {
            **codex_status,
            "auth_mode": "codex_oauth",
            "source": codex_status.get("source") or "codex-oauth",
        }
    return codex_status
