"""ChatGPT Web conversation transport for AIAgent."""

import os
import uuid
from types import SimpleNamespace
from typing import Any

from agent.prompt_builder import DEFAULT_AGENT_IDENTITY
from hermes_cli import chatgpt_web as _chatgpt_web


class ChatGPTWebTransportMixin:
    def _chatgpt_web_messages(self, api_messages: list) -> tuple[str, list[dict[str, Any]]]:
        instructions = ""
        payload_messages = api_messages
        if api_messages and api_messages[0].get("role") == "system":
            instructions = str(api_messages[0].get("content") or "").strip()
            payload_messages = api_messages[1:]
        if not instructions:
            instructions = DEFAULT_AGENT_IDENTITY
        if self.tools:
            instructions = (
                instructions.rstrip()
                + "\n\nImportant runtime limitation: this ChatGPT Web transport currently does not support Hermes tool calls. "
                  "Never claim to have run a tool or accessed live system state through a tool."
            )
        if self._chatgpt_web_conversation_id and payload_messages:
            latest_user = None
            for item in reversed(payload_messages):
                if isinstance(item, dict) and item.get("role") == "user":
                    latest_user = item
                    break
            payload_messages = [latest_user] if latest_user else payload_messages[-1:]
        return instructions, payload_messages


    def _wrap_chatgpt_web_response(self, result: dict[str, Any]):
        message_text = str(result.get("content") or "")
        finish_reason = str(result.get("finish_reason") or "stop")
        assistant_message = SimpleNamespace(content=message_text, tool_calls=None, role="assistant")
        choice = SimpleNamespace(message=assistant_message, finish_reason=finish_reason)
        return SimpleNamespace(
            id=result.get("message_id") or result.get("parent_message_id") or str(uuid.uuid4()),
            model=result.get("model") or self.model,
            choices=[choice],
            usage=None,
        )


    def _run_chatgpt_web_completion(self, api_kwargs: dict, *, client=None):
        def _on_delta(text: str):
            if not text:
                return
            callback = getattr(self, "_chatgpt_web_on_delta", None)
            if callback is not None:
                callback(text)

        result = _chatgpt_web.stream_chatgpt_web_completion(
            access_token=self.api_key,
            model=api_kwargs.get("model") or self.model,
            messages=api_kwargs.get("messages") or [],
            instructions=api_kwargs.get("instructions") or DEFAULT_AGENT_IDENTITY,
            conversation_id=api_kwargs.get("conversation_id") or None,
            parent_message_id=api_kwargs.get("parent_message_id") or None,
            timeout=api_kwargs.get("timeout") or float(os.getenv("HERMES_API_TIMEOUT", 1800.0)),
            history_and_training_disabled=bool(api_kwargs.get("history_and_training_disabled", False)),
            on_delta=_on_delta,
            client=client,
        )
        self._chatgpt_web_conversation_id = result.get("conversation_id") or self._chatgpt_web_conversation_id
        self._chatgpt_web_parent_message_id = result.get("parent_message_id") or self._chatgpt_web_parent_message_id
        return self._wrap_chatgpt_web_response(result)
