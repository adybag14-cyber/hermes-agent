"""ChatGPT Web auxiliary client adapters and provider routing."""

from __future__ import annotations

import ast
import hashlib
import json
import logging
import re
from types import SimpleNamespace
from typing import Any, Optional

from hermes_cli.chatgpt_web import resolve_chatgpt_web_runtime_credentials, stream_chatgpt_web_completion
from agent.chatgpt_credentials import (
    CHATGPT_WEB_METADATA_FIELDS, chatgpt_web_credential_fingerprint,
    matching_chatgpt_web_credentials, validate_chatgpt_web_credentials,
)

logger = logging.getLogger("agent.auxiliary_client")


_CHATGPT_WEB_AUX_MODEL = "gpt-5"


_CHATGPT_WEB_TOOL_CALL_BLOCK_RE = re.compile(
    r"(?:['\"]?<?)tool_call>\s*(\{.*?\})\s*</tool_call>",
    re.DOTALL | re.IGNORECASE,
)


def _flatten_chatgpt_web_message_content(content: Any) -> str:
    """Best-effort text rendering for auxiliary ChatGPT Web prompts."""
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return str(content) if content is not None else ""

    flattened: list[str] = []
    for part in content:
        if isinstance(part, str):
            flattened.append(part)
            continue
        if not isinstance(part, dict):
            flattened.append(str(part))
            continue
        ptype = str(part.get("type") or "").strip().lower()
        if ptype in {"text", "input_text"}:
            flattened.append(str(part.get("text") or ""))
            continue
        if ptype in {"image_url", "input_image"}:
            image_data = part.get("image_url", {})
            if isinstance(image_data, dict):
                image_url = str(image_data.get("url") or "")
            else:
                image_url = str(image_data or "")
            if image_url:
                flattened.append(f"[image: {image_url}]")
            continue
        text = str(part.get("text") or "").strip()
        if text:
            flattened.append(text)
    return "\n".join(part for part in flattened if part).strip()


def _normalize_chatgpt_web_message_content(content: Any) -> Any:
    """Preserve multimodal blocks when ChatGPT Web can consume them directly."""
    if isinstance(content, list):
        normalized: list[dict[str, Any]] = []
        saw_media = False
        for part in content:
            if not isinstance(part, dict):
                if isinstance(part, str) and part:
                    normalized.append({"type": "text", "text": part})
                continue
            ptype = str(part.get("type") or "").strip().lower()
            if ptype in {"text", "input_text"}:
                normalized.append({"type": "text", "text": str(part.get("text") or "")})
                continue
            if ptype in {"image_url", "input_image"}:
                image_data = part.get("image_url", {})
                if isinstance(image_data, dict):
                    image_url = str(image_data.get("url") or "")
                else:
                    image_url = str(image_data or "")
                if image_url:
                    saw_media = True
                    normalized.append({"type": "input_image", "image_url": image_url})
                continue
        if saw_media:
            return normalized
    return _flatten_chatgpt_web_message_content(content)


def _extract_chatgpt_web_tool_calls(text: str) -> tuple[list[SimpleNamespace], str]:
    """Parse auxiliary ChatGPT Web XML tool-call blocks into OpenAI-like objects."""
    if not isinstance(text, str) or not text.strip():
        return [], ""

    extracted: list[SimpleNamespace] = []
    consumed_spans: list[tuple[int, int]] = []

    def _load_tool_call(raw_json: str) -> Optional[dict[str, Any]]:
        for loader in (json.loads, ast.literal_eval):
            try:
                payload = loader(raw_json)
            except Exception:
                continue
            if isinstance(payload, dict):
                return payload
        return None

    for match in _CHATGPT_WEB_TOOL_CALL_BLOCK_RE.finditer(text):
        payload = _load_tool_call(match.group(1))
        if not isinstance(payload, dict):
            continue
        tool_name = str(payload.get("name") or "").strip()
        if not tool_name:
            continue
        tool_args = payload.get("arguments", {})
        if not isinstance(tool_args, str):
            tool_args = json.dumps(tool_args, ensure_ascii=False)
        call_id = str(payload.get("id") or f"chatgpt_web_aux_call_{len(extracted) + 1}")
        extracted.append(
            SimpleNamespace(
                id=call_id,
                type="function",
                function=SimpleNamespace(name=tool_name, arguments=tool_args),
            )
        )
        consumed_spans.append((match.start(), match.end()))

    if not consumed_spans:
        return extracted, text.strip()

    cleaned_parts: list[str] = []
    cursor = 0
    for start, end in consumed_spans:
        cleaned_parts.append(text[cursor:start])
        cursor = end
    cleaned_parts.append(text[cursor:])
    cleaned = "".join(cleaned_parts).strip()
    return extracted, cleaned


def _chatgpt_web_auxiliary_tool_protocol(
    tools: list[dict[str, Any]],
    tool_choice: Any = None,
) -> str:
    """Explain Hermes's XML tool protocol to ChatGPT Web auxiliary calls."""
    rendered_tools: list[str] = []
    tool_names: list[str] = []
    for tool in tools or []:
        function = tool.get("function") if isinstance(tool, dict) else None
        if not isinstance(function, dict):
            continue
        name = str(function.get("name") or "").strip()
        if not name:
            continue
        tool_names.append(name)
        description = str(function.get("description") or "").strip()
        parameters = function.get("parameters", {})
        rendered_tools.append(
            "- "
            + json.dumps(
                {
                    "name": name,
                    "description": description,
                    "parameters": parameters,
                },
                ensure_ascii=False,
            )
        )

    if not rendered_tools:
        return ""

    forced_tool_name = ""
    if isinstance(tool_choice, dict):
        choice_type = str(tool_choice.get("type") or "").strip().lower()
        if choice_type == "function":
            forced_tool_name = str(
                (tool_choice.get("function") or {}).get("name") or ""
            ).strip()
    elif isinstance(tool_choice, str) and tool_choice.lower() not in {"", "auto", "none"}:
        forced_tool_name = str(tool_choice).strip()

    lines = [
        "# Hermes auxiliary tool protocol",
        "If you need a tool, respond with ONLY one or more XML blocks in this exact format:",
        '<tool_call>{"name":"tool_name","arguments":{...}}</tool_call>',
        "Do not wrap the XML in markdown fences.",
        "Do not invent tools or arguments outside the schemas below.",
    ]
    if forced_tool_name:
        lines.append(f"You MUST use the tool named {forced_tool_name} if a tool call is required.")
    elif len(tool_names) == 1:
        lines.append(f"If a tool is needed for this task, use the single available tool: {tool_names[0]}.")
    lines.append("Available tools:")
    lines.extend(rendered_tools)
    return "\n".join(lines)


class _ChatGptWebCompletionsAdapter:
    """OpenAI-like chat.completions adapter backed by ChatGPT Web transport."""

    def __init__(
        self,
        *,
        access_token: str,
        model: str,
        base_url: str,
        session_token: str = "",
        cookie_header: str = "", browser_cookies=None,
        user_agent: str = "", device_id: str = "",
    ):
        self._access_token = access_token
        self._model = model
        self._base_url = base_url
        self._session_token = session_token
        self._cookie_header = cookie_header
        self._browser_cookies = browser_cookies
        self._user_agent = user_agent
        self._device_id = device_id
        self._credential_snapshot = validate_chatgpt_web_credentials({
            "api_key": access_token, "session_token": session_token, "cookie_header": cookie_header,
            "browser_cookies": browser_cookies, "user_agent": user_agent, "device_id": device_id,
        }, access_token)

    def create(self, **kwargs) -> Any:
        from agent.auxiliary_client import _notify_aux_provider_response

        messages = kwargs.get("messages", []) or []
        model = kwargs.get("model", self._model)
        timeout = float(kwargs.get("timeout") or 1800.0)
        tools = kwargs.get("tools") or []
        tool_choice = kwargs.get("tool_choice")

        instructions_parts: list[str] = []
        payload_messages: list[dict[str, Any]] = []
        for msg in messages:
            if not isinstance(msg, dict):
                continue
            role = str(msg.get("role") or "user").strip().lower() or "user"
            content = _normalize_chatgpt_web_message_content(msg.get("content"))
            if role == "system":
                if isinstance(content, list):
                    rendered = _flatten_chatgpt_web_message_content(content)
                    if rendered:
                        instructions_parts.append(rendered)
                elif content:
                    instructions_parts.append(content)
                continue
            payload_messages.append({"role": role, "content": content})

        tool_protocol = _chatgpt_web_auxiliary_tool_protocol(tools, tool_choice=tool_choice)
        if tool_protocol:
            instructions_parts.append(tool_protocol)
        instructions = "\n\n".join(part for part in instructions_parts if part).strip()

        if not payload_messages:
            payload_messages = [{"role": "user", "content": "Proceed using the developer instructions above."}]

        result = stream_chatgpt_web_completion(
            access_token=self._access_token,
            model=model,
            messages=payload_messages,
            instructions=instructions,
            session_token=self._session_token,
            cookie_header=self._cookie_header,
            browser_cookies=self._browser_cookies,
            user_agent=self._user_agent,
            device_id=self._device_id,
            credential_snapshot=self._credential_snapshot,
            timeout=timeout,
            history_and_training_disabled=True,
            on_delta=lambda text: _notify_aux_provider_response() if text else None,
        )
        _notify_aux_provider_response()
        return self._wrap_result(result, model)

    @staticmethod
    def _wrap_result(result: dict[str, Any], model: str) -> Any:
        message_text = str(result.get("content") or "")
        tool_calls, cleaned_text = _extract_chatgpt_web_tool_calls(message_text)
        assistant_message = SimpleNamespace(
            role="assistant",
            content=cleaned_text if cleaned_text else (None if tool_calls else message_text),
            tool_calls=tool_calls or None,
        )
        choice = SimpleNamespace(
            index=0,
            message=assistant_message,
            finish_reason="tool_calls" if tool_calls else str(result.get("finish_reason") or "stop"),
        )
        return SimpleNamespace(
            choices=[choice],
            model=result.get("model") or model,
            usage=None,
        )


class _ChatGptWebChatShim:
    def __init__(self, adapter: _ChatGptWebCompletionsAdapter):
        self.completions = adapter


class ChatGptWebAuxiliaryClient:
    """OpenAI-client-compatible wrapper over ChatGPT Web transport."""

    HERMES_SKIP_TRANSPORT_WRAP = True

    def __init__(self, *, access_token: str, model: str, base_url: str, session_token: str = "",
                 cookie_header: str = "", browser_cookies=None, user_agent: str = "", device_id: str = ""):
        self._access_token = access_token
        self._session_token = session_token
        self.api_key = access_token
        self.base_url = base_url
        self.chat = _ChatGptWebChatShim(
            _ChatGptWebCompletionsAdapter(
                access_token=access_token,
                model=model,
                base_url=base_url,
                session_token=session_token,
                cookie_header=cookie_header,
                browser_cookies=browser_cookies,
                user_agent=user_agent,
                device_id=device_id,
            )
        )
        self._chatgpt_web_credentials = self.chat.completions._credential_snapshot

    def close(self):
        return None


class _AsyncChatGptWebCompletionsAdapter:
    def __init__(self, sync_adapter: _ChatGptWebCompletionsAdapter):
        self._sync = sync_adapter

    async def create(self, **kwargs) -> Any:
        import asyncio
        return await asyncio.to_thread(self._sync.create, **kwargs)


class _AsyncChatGptWebChatShim:
    def __init__(self, adapter: _AsyncChatGptWebCompletionsAdapter):
        self.completions = adapter


class AsyncChatGptWebAuxiliaryClient:
    HERMES_SKIP_TRANSPORT_WRAP = True

    def __init__(self, sync_wrapper: "ChatGptWebAuxiliaryClient"):
        self.chat = _AsyncChatGptWebChatShim(
            _AsyncChatGptWebCompletionsAdapter(sync_wrapper.chat.completions)
        )
        self.api_key = sync_wrapper.api_key
        self.base_url = sync_wrapper.base_url
        self._chatgpt_web_credentials = sync_wrapper._chatgpt_web_credentials


def resolve_chatgpt_web(req):
    from agent import auxiliary_client as aux
    from agent.secret_scope import UnscopedSecretError, get_secret

    if aux._aux_probe_active():
        # Availability checks never exchange cookies or refresh OAuth grants.
        access = get_secret("CHATGPT_WEB_ACCESS_TOKEN", "") or ""
        session = get_secret("CHATGPT_WEB_SESSION_TOKEN", "") or ""
        available = bool(req.explicit_api_key or access or session)
        if not available:
            for provider in ("chatgpt-web", "openai-codex"):
                entry = aux._peek_pool_entry(provider)
                if entry is not None and (aux._pool_runtime_api_key(entry) or getattr(entry, "session_token", "")):
                    available = True
                    break
        if not available:
            try:
                from hermes_cli.auth import _read_codex_tokens
                stored = _read_codex_tokens().get("tokens", {})
                available = bool(stored.get("access_token") or stored.get("refresh_token"))
            except UnscopedSecretError:
                raise
            except Exception:
                available = False
        if not available:
            return None, None
        model = aux._normalize_resolved_model(req.model or _CHATGPT_WEB_AUX_MODEL, req.provider)
        base = str(req.explicit_base_url or "https://chatgpt.com/backend-api/f").rstrip("/")
        return aux._AuxProbeClientStub(api_key="", base_url=base), model

    main = req.main_runtime or {}
    paired_main = main.get("chatgpt_web_credentials")
    if paired_main is not None and (not req.explicit_api_key or req.explicit_api_key == main.get("api_key")):
        snapshot = validate_chatgpt_web_credentials(paired_main, main.get("api_key"))
        creds = {"api_key": snapshot.api_key, "base_url": main.get("base_url"),
                 **{name: getattr(snapshot, name) for name in CHATGPT_WEB_METADATA_FIELDS}}
    elif req.explicit_api_key:
        key = str(req.explicit_api_key).strip()
        if key == get_secret("CHATGPT_WEB_ACCESS_TOKEN", "").strip():
            creds = {"api_key": key, **{name: get_secret("CHATGPT_WEB_" + name.upper(), "") or ""
                                      for name in CHATGPT_WEB_METADATA_FIELDS if name != "browser_cookies"}}
        else:
            snapshot = matching_chatgpt_web_credentials(key)
            creds = {"api_key": key, **{name: getattr(snapshot, name) for name in CHATGPT_WEB_METADATA_FIELDS}}
    else:
        try:
            creds = resolve_chatgpt_web_runtime_credentials()
        except UnscopedSecretError:
            raise
        except Exception as exc:
            logger.warning("ChatGPT Web auxiliary credentials could not be resolved (%s)", exc)
            return None, None
    access_token = str(req.explicit_api_key or creds.get("api_key") or "").strip()
    if not access_token:
        logger.warning("No ChatGPT Web auxiliary runtime credential was found")
        return None, None
    session_token = str(creds.get("session_token") or "").strip()
    base = str(req.explicit_base_url or creds.get("base_url") or "https://chatgpt.com/backend-api/f").strip().rstrip("/")
    model = aux._normalize_resolved_model(req.model or aux._read_main_model() or _CHATGPT_WEB_AUX_MODEL, req.provider)
    client = ChatGptWebAuxiliaryClient(
        access_token=access_token, model=model, base_url=base, session_token=session_token,
        cookie_header=str(creds.get("cookie_header") or "").strip(), browser_cookies=creds.get("browser_cookies"),
        user_agent=str(creds.get("user_agent") or "").strip(), device_id=str(creds.get("device_id") or "").strip(),
    )
    return aux._route_client(req, client, model)


def cache_scope_hint(provider, runtime):
    from agent import auxiliary_client as aux
    from agent.secret_scope import get_secret

    resolved = aux._normalize_aux_provider(provider)
    if resolved == "auto":
        resolved = aux._normalize_aux_provider(runtime.get("provider") or aux._read_main_provider())
    if resolved != "chatgpt-web":
        return ""
    if runtime.get("api_key"):
        paired_hint = chatgpt_web_credential_fingerprint(runtime)
        if paired_hint is not None:
            return f":chatgpt-web:{paired_hint}"
    values = [str(aux.get_hermes_home())] + [get_secret(name, "") or "" for name in (
        "CHATGPT_WEB_ACCESS_TOKEN", "CHATGPT_WEB_SESSION_TOKEN", "CHATGPT_WEB_COOKIE_HEADER",
        "CHATGPT_WEB_USER_AGENT", "CHATGPT_WEB_DEVICE_ID",
    )]
    digest = hashlib.blake2b(json.dumps(values, ensure_ascii=False).encode("utf-8"), digest_size=16).hexdigest()
    return f":chatgpt-web:{digest}"
