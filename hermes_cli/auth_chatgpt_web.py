"""ChatGPT Web authentication status."""

from typing import Any, Dict
from agent.secret_scope import get_secret


def get_chatgpt_web_auth_status() -> Dict[str, Any]:
    """Status snapshot for ChatGPT Web auth.

    Prefers explicit credentials, then the ChatGPT Web pool, then Codex OAuth.
    """
    access_token = get_secret("CHATGPT_WEB_ACCESS_TOKEN", "").strip()
    session_token = get_secret("CHATGPT_WEB_SESSION_TOKEN", "").strip()
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

    from hermes_cli.auth import _auth_file_path, get_codex_auth_status
    from hermes_cli.auth_codex import _codex_access_token_is_expiring

    try:
        from agent.credential_pool import load_pool

        pool = load_pool("chatgpt-web")
        if pool and pool.has_credentials():
            entry = pool.select() or pool.peek()
            if entry is None:
                entries = pool.entries()
                entry = entries[0] if entries else None
            if entry is not None:
                api_key = str(getattr(entry, "runtime_api_key", None) or getattr(entry, "access_token", "") or "").strip()
                session_token = str(getattr(entry, "session_token", "") or "").strip()
                if api_key and not _codex_access_token_is_expiring(api_key, 0):
                    return {
                        "logged_in": True,
                        "auth_store": str(_auth_file_path()),
                        "last_refresh": getattr(entry, "last_refresh", None),
                        "auth_mode": getattr(entry, "auth_type", None) or "oauth",
                        "source": f"pool:{getattr(entry, 'label', 'unknown')}",
                        "api_key": api_key,
                    }
                if session_token:
                    return {
                        "logged_in": True,
                        "auth_store": str(_auth_file_path()),
                        "last_refresh": getattr(entry, "last_refresh", None),
                        "auth_mode": "session_token",
                        "source": f"pool:{getattr(entry, 'label', 'unknown')}",
                        "api_key": api_key,
                    }
    except Exception:
        pass

    codex_status = get_codex_auth_status()
    if codex_status.get("logged_in"):
        return {
            **codex_status,
            "auth_mode": "codex_oauth",
            "source": codex_status.get("source") or "codex-oauth",
        }
    return codex_status
