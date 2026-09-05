"""Keep a selected ChatGPT access grant and its browser metadata together."""

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import asdict, dataclass, field
import hashlib
import json
from typing import Any


CHATGPT_WEB_METADATA_FIELDS = (
    "session_token", "cookie_header", "browser_cookies", "user_agent", "device_id",
)


@dataclass(frozen=True)
class ChatGPTWebCredentials:
    """A resolved pair; blank metadata is authoritative, not a lookup request."""

    api_key: str = field(repr=False)
    session_token: str = field(default="", repr=False)
    cookie_header: str = field(default="", repr=False)
    browser_cookies: Any = field(default=None, repr=False, hash=False)
    user_agent: str = field(default="", repr=False)
    device_id: str = field(default="", repr=False)


def validate_chatgpt_web_credentials(value: Any, api_key: Any) -> ChatGPTWebCredentials:
    """Copy a snapshot and reject an accidentally mixed access grant."""
    if isinstance(value, ChatGPTWebCredentials):
        snapshot = deepcopy(value)
    elif isinstance(value, Mapping):
        snapshot = ChatGPTWebCredentials(
            api_key=str(value.get("api_key") or "").strip(),
            session_token=str(value.get("session_token") or "").strip(),
            cookie_header=str(value.get("cookie_header") or "").strip(),
            browser_cookies=deepcopy(value.get("browser_cookies")),
            user_agent=str(value.get("user_agent") or "").strip(),
            device_id=str(value.get("device_id") or "").strip(),
        )
    else:
        raise TypeError("ChatGPT Web credentials must be a resolved snapshot")
    if not isinstance(api_key, str) or snapshot.api_key != api_key.strip():
        raise ValueError("ChatGPT Web access grant does not match its browser credential snapshot")
    return snapshot


def chatgpt_web_agent_kwargs(runtime: Mapping[str, Any] | None) -> dict[str, Any]:
    """Project only ChatGPT runtimes into the matching constructor argument."""
    if not isinstance(runtime, Mapping):
        return {}
    provider = str(runtime.get("provider") or "").strip().lower()
    api_mode = str(runtime.get("api_mode") or "").strip().lower()
    if provider != "chatgpt-web" and api_mode != "chatgpt_web":
        return {}
    value = runtime.get("chatgpt_web_credentials")
    if value is None:
        value = runtime
    return {
        "chatgpt_web_credentials": validate_chatgpt_web_credentials(value, runtime.get("api_key") or ""),
    }


def chatgpt_web_switch_kwargs(result: Any) -> dict[str, Any]:
    """Project the ephemeral credential pair from a successful model selection."""
    return chatgpt_web_agent_kwargs({
        "provider": result.target_provider, "api_mode": result.api_mode, "api_key": result.api_key,
        "chatgpt_web_credentials": getattr(result, "chatgpt_web_credentials", None),
    })


def chatgpt_web_parent_kwargs(parent: Any) -> dict[str, Any]:
    """Freeze the parent's actual grant and metadata for same-route children."""
    key = getattr(parent, "api_key", "")
    if not key:
        key = (getattr(parent, "_client_kwargs", None) or {}).get("api_key", "")
    runtime = {
        "provider": getattr(parent, "provider", None),
        "api_mode": getattr(parent, "api_mode", None),
        "api_key": key,
        "chatgpt_web_credentials": getattr(parent, "_chatgpt_web_credentials", None),
    }
    runtime.update({name: getattr(parent, "_chatgpt_web_" + name, None) for name in CHATGPT_WEB_METADATA_FIELDS})
    return chatgpt_web_agent_kwargs(runtime)


def chatgpt_web_credential_fingerprint(runtime: Mapping[str, Any]) -> str | None:
    """A non-secret cache identity that changes with any paired metadata."""
    kwargs = chatgpt_web_agent_kwargs(runtime)
    if not kwargs:
        return None
    encoded = json.dumps(
        asdict(kwargs["chatgpt_web_credentials"]), sort_keys=True,
        separators=(",", ":"), ensure_ascii=True, default=str,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def matching_chatgpt_web_credentials(api_key: str) -> ChatGPTWebCredentials:
    """Legacy metadata lookup by the already-bound grant, never pool selection."""
    runtime = {"api_key": api_key}
    if api_key:
        from agent.credential_pool import load_pool

        try:
            pool = load_pool("chatgpt-web")
            entries = pool.entries() if pool and pool.has_credentials() else []
            for entry in entries:
                key = getattr(entry, "runtime_api_key", None) or getattr(entry, "access_token", "")
                if key == api_key:
                    runtime.update({name: getattr(entry, name, None) for name in CHATGPT_WEB_METADATA_FIELDS})
                    break
        except Exception:
            pass
    return validate_chatgpt_web_credentials(runtime, api_key)


def bind_chatgpt_web_credentials(agent: Any, value: Any) -> ChatGPTWebCredentials:
    """Bind all browser fields atomically to the agent's current access grant."""
    snapshot = validate_chatgpt_web_credentials(value, agent.api_key)
    for name in CHATGPT_WEB_METADATA_FIELDS:
        setattr(agent, "_chatgpt_web_" + name, getattr(snapshot, name))
    agent._chatgpt_web_credentials = snapshot
    return snapshot


def rebind_chatgpt_web_credentials(agent: Any, value: Any = None) -> None:
    """Keep identity transitions paired, clearing Web-only secrets on other routes."""
    if getattr(agent, "api_mode", None) != "chatgpt_web":
        agent._chatgpt_web_credentials = None
        for name in CHATGPT_WEB_METADATA_FIELDS:
            setattr(agent, "_chatgpt_web_" + name, None if name == "browser_cookies" else "")
        return
    if value is None:
        previous = getattr(agent, "_chatgpt_web_credentials", None)
        value = previous if isinstance(previous, ChatGPTWebCredentials) and previous.api_key == agent.api_key else matching_chatgpt_web_credentials(agent.api_key)
    bind_chatgpt_web_credentials(agent, value)


def apply_chatgpt_web_runtime_override(
    before: Mapping[str, Any],
    merged: dict[str, Any],
    overrides: Mapping[str, Any],
) -> dict[str, Any]:
    """Update the credential pair after a runtime's ordinary field merge.

    Model-only overrides keep the selected pair. A new provider/mode/key or
    explicitly supplied metadata replaces it in full, so an access grant can
    never inherit the previous account's browser session. This helper only
    mutates the ephemeral snapshot field, never persisted model settings.
    """
    identity_fields = ("provider", "api_mode", "api_key")
    changed = any(
        str(before.get(name) or "").strip() != str(merged.get(name) or "").strip()
        for name in identity_fields
    )
    candidate = {name: merged.get(name) for name in identity_fields}
    if "chatgpt_web_credentials" in overrides:
        candidate["chatgpt_web_credentials"] = overrides["chatgpt_web_credentials"]
    elif any(name in overrides for name in CHATGPT_WEB_METADATA_FIELDS):
        candidate.update({name: overrides.get(name) for name in CHATGPT_WEB_METADATA_FIELDS})
    elif not changed:
        candidate.update({name: merged.get(name) for name in CHATGPT_WEB_METADATA_FIELDS})
        candidate["chatgpt_web_credentials"] = merged.get("chatgpt_web_credentials")

    # Validate before touching the destination, including an explicit snapshot
    # whose grant does not match the newly merged api_key.
    paired_kwargs = chatgpt_web_agent_kwargs(candidate)
    merged.pop("chatgpt_web_credentials", None)
    merged.update(paired_kwargs)
    return merged
