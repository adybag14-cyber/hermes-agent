"""ChatGPT Web payloads with the shared OpenAI-shaped response contract.

The browser transport and Hermes tool protocol live in the fork's existing
adapters. Response normalization uses the current shared transport so new
refusal, truncation and tool-result handling applies to this backend too.
"""

from agent.transports import register_transport
from agent.transports.chat_completions import ChatCompletionsTransport


class ChatGPTWebTransport(ChatCompletionsTransport):
    def __init__(self):
        # ChatGPT Web does not use the xAI wire-name aliases.
        self._last_wire_aliases = {}

    @property
    def api_mode(self) -> str:
        return "chatgpt_web"

    def build_kwargs(self, model, messages, tools=None, **params):
        return {
            "model": model,
            "messages": messages,
            "instructions": params.get("instructions", ""),
            "conversation_id": params.get("conversation_id"),
            "parent_message_id": params.get("parent_message_id"),
            "timeout": params.get("timeout"),
            "history_and_training_disabled": bool(params.get("history_and_training_disabled", False)),
        }


register_transport("chatgpt_web", ChatGPTWebTransport)
