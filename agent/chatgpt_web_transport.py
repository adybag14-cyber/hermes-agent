"""ChatGPT Web transport behavior for the agent."""

from __future__ import annotations

from typing import Any
from agent.prompt_builder import DEFAULT_AGENT_IDENTITY
from hermes_cli import chatgpt_web as _chatgpt_web
import copy
import json
from types import SimpleNamespace
from agent.chatgpt_web_parsing import _extract_xml_tool_calls_from_text
import uuid
import logging

logger = logging.getLogger(__name__)
import os

from agent.chatgpt_web_messages import ChatGPTWebMessagesMixin
from agent.chatgpt_web_media import ChatGPTWebMediaMixin
from agent.chatgpt_web_tools import ChatGPTWebToolsMixin


def configure_browser_credentials(agent, values):
    agent._chatgpt_web_forced_tool_call = None
    agent._chatgpt_web_forced_tool_call_mode = "always"
    agent._chatgpt_web_selected_tool_names = []
    agent._chatgpt_web_selected_tool_payload_messages = []
    for field, value in values.items():
        setattr(agent, "_chatgpt_web_" + field, value)
    if agent.api_mode != "chatgpt_web":
        return
    from agent.secret_scope import get_secret
    from agent.credential_pool import load_pool

    text_fields = ("session_token", "cookie_header", "user_agent", "device_id")
    for field in text_fields:
        attribute = "_chatgpt_web_" + field
        value = getattr(agent, attribute) or get_secret("CHATGPT_WEB_" + field.upper(), "")
        setattr(agent, attribute, str(value or "").strip())
    if any(getattr(agent, "_chatgpt_web_" + field) for field in text_fields) or agent._chatgpt_web_browser_cookies is not None:
        return
    try:
        pool = load_pool("chatgpt-web")
        entry = (pool.select() or pool.peek()) if pool and pool.has_credentials() else None
        if entry is not None:
            for field in text_fields:
                setattr(agent, "_chatgpt_web_" + field, str(getattr(entry, field, "") or "").strip())
            agent._chatgpt_web_browser_cookies = getattr(entry, "browser_cookies", None)
    except Exception:
        logger.debug("ChatGPT Web browser credentials unavailable from pool", exc_info=True)


class ChatGPTWebTransportMixin(ChatGPTWebMessagesMixin, ChatGPTWebMediaMixin, ChatGPTWebToolsMixin):
    def _chatgpt_web_messages(self, api_messages: list) -> tuple[str, list[dict[str, Any]], bool]:
        instructions = ""
        payload_messages = api_messages
        if api_messages and api_messages[0].get("role") == "system":
            instructions = str(api_messages[0].get("content") or "").strip()
            payload_messages = api_messages[1:]
        if not instructions:
            instructions = DEFAULT_AGENT_IDENTITY
        instructions = self._chatgpt_web_enrich_instructions(instructions)

        self._chatgpt_web_forced_tool_call = None
        self._chatgpt_web_forced_tool_call_mode = "always"
        self._chatgpt_web_selected_tool_names = []
        self._chatgpt_web_selected_tool_payload_messages = []
        selected_tools: list[dict[str, Any]] = []
        uses_local_tool_loop = False
        current_turn_messages = payload_messages
        if self.tools:
            payload_messages = copy.deepcopy(payload_messages)
            current_turn_messages = self._chatgpt_web_current_turn_messages(payload_messages)
            attached_images_present = _chatgpt_web._messages_include_chatgpt_web_images(current_turn_messages)
            if attached_images_present and _chatgpt_web._chatgpt_web_debug_base():
                selected_tools = []
            else:
                selected_tools = self._select_chatgpt_web_tools(current_turn_messages)
            uses_local_tool_loop = bool(selected_tools)
        if uses_local_tool_loop:
            used_tool_count = sum(
                1 for item in current_turn_messages
                if isinstance(item, dict) and item.get("role") == "tool"
            )
            tool_protocol = self._chatgpt_web_tool_protocol(selected_tools).strip()
            base_instructions = instructions.strip() or DEFAULT_AGENT_IDENTITY
            instructions = f"{base_instructions}\n\n{tool_protocol}" if tool_protocol else base_instructions
            selected_tool_names = [
                str(tool.get("function", {}).get("name") or "").strip()
                for tool in selected_tools
                if isinstance(tool, dict) and isinstance(tool.get("function"), dict)
            ]
            selected_tool_names = [name for name in selected_tool_names if name]
            self._chatgpt_web_selected_tool_names = list(selected_tool_names)
            self._chatgpt_web_selected_tool_payload_messages = copy.deepcopy(current_turn_messages)
            selected_tool_text = ", ".join(selected_tool_names)
            selected_tool_args = self._chatgpt_web_tool_args(selected_tool_names[0], current_turn_messages) if selected_tool_names else None
            selected_tool_hint = (
                "Use these exact arguments for this turn: " + json.dumps(selected_tool_args, ensure_ascii=False)
                if selected_tool_args is not None
                else (self._chatgpt_web_missing_args_hint(selected_tool_names[0]) if selected_tool_names else "")
            )
            selected_tool_argument_mirror = self._chatgpt_web_prompt_argument_mirror(selected_tool_args)
            selected_tool_example = (
                "<tool_call>\n"
                + json.dumps({"name": selected_tool_names[0], "arguments": selected_tool_args}, ensure_ascii=False)
                + "\n</tool_call>"
                if selected_tool_names and selected_tool_args is not None
                else (self._chatgpt_web_tool_guess_example(selected_tool_names[0]) if selected_tool_names else "")
            )
            original = self._chatgpt_web_original_user_request(payload_messages)
            answer_only_mode = self._chatgpt_web_answer_only_mode(original)
            prefer_consecutive_tool_flow = self._chatgpt_web_requests_consecutive_tool_flow(original)
            force_followup_tool_call = bool(
                selected_tool_names
                and selected_tool_args is not None
                and self._chatgpt_web_should_force_followup_tool_call(
                    current_turn_messages,
                    selected_tool_names[0],
                    selected_tool_args,
                )
            )
            if selected_tool_names and selected_tool_args is not None and (
                used_tool_count == 0
                or force_followup_tool_call
                or prefer_consecutive_tool_flow
            ):
                self._chatgpt_web_forced_tool_call = {
                    "name": selected_tool_names[0],
                    "arguments": selected_tool_args,
                }
                self._chatgpt_web_forced_tool_call_mode = (
                    "always"
                    if (used_tool_count == 0 or force_followup_tool_call)
                    else "if_pending_work"
                )
            final_answer_example = self._chatgpt_web_final_answer_example(original)
            for item in reversed(current_turn_messages):
                if isinstance(item, dict) and item.get("role") == "user":
                    if original:
                        reminder_lines = [f"The tool available for this turn is: {selected_tool_text}."] if selected_tool_text else []
                        if used_tool_count == 0 or self._chatgpt_web_forced_tool_call is not None:
                            reminder_lines.extend([
                                "Hermes has already determined that another tool call is required before the final answer."
                                if used_tool_count
                                else "Hermes has already determined that this turn requires a tool call.",
                                "Do not answer the user yet.",
                                "Your next reply must be EXACTLY ONE <tool_call>...</tool_call> block with no explanatory prose before or after it.",
                            ])
                            if selected_tool_hint:
                                reminder_lines.append(selected_tool_hint)
                            if selected_tool_argument_mirror:
                                reminder_lines.append(selected_tool_argument_mirror)
                            if selected_tool_example:
                                reminder_lines.append("Reply now with this exact structure:")
                                reminder_lines.append(selected_tool_example)
                        else:
                            reminder_lines.append("You have already received at least one <tool_response>.")
                            if force_followup_tool_call or prefer_consecutive_tool_flow:
                                reminder_lines.extend([
                                    "Hermes has already determined that another tool call is required before the final answer.",
                                    "Hermes expects you to keep advancing the task through tool use until the original request is actually complete.",
                                    "The user already approved the original task, so do not ask for permission to continue with the next obvious step.",
                                    "Do not answer the user yet and do not narrate that you will continue later.",
                                    "Use the available tool schema plus the latest <tool_response> to guess the single best next tool call needed for the main task.",
                                    "Your next reply should be EXACTLY ONE <tool_call>...</tool_call> block with no explanatory prose before or after it.",
                                ])
                                if selected_tool_hint:
                                    reminder_lines.append(selected_tool_hint)
                                if selected_tool_argument_mirror:
                                    reminder_lines.append(selected_tool_argument_mirror)
                                if selected_tool_example:
                                    reminder_lines.append("Reply now with this exact structure:")
                                    reminder_lines.append(selected_tool_example)
                            else:
                                reminder_lines.extend([
                                    "Do not make unsupported claims about created files, saved skills, packaged artifacts, or completed debugging unless a tool result already proved them.",
                                    "If the main task is still in progress, your next reply should usually be EXACTLY ONE <tool_call>...</tool_call> block for the best next tool call.",
                                    "The user already approved the original task, so do not ask whether to continue with the next obvious step.",
                                    "Use the available tool schema plus the latest <tool_response> to guess the next tool call whenever another step is still needed.",
                                    "If another tool is still required, emit EXACTLY ONE <tool_call>...</tool_call> block.",
                                    "Otherwise, give the final answer directly with no extra tool-call markup.",
                                    "When you give the final answer, follow the original user's requested output format exactly, including any 'answer only' constraint.",
                                    "Do not add preambles, extra prose, commas, or quotes unless the user explicitly requested them.",
                                ])
                                if answer_only_mode == "line":
                                    reminder_lines.append(
                                        "For this request, the final answer must be ONLY the exact source line itself with no explanation, no line number, and no words like 'defined at line'."
                                    )
                                elif answer_only_mode == "path":
                                    reminder_lines.append(
                                        "For this request, the final answer must be ONLY the path string itself with no explanation."
                                    )
                                elif answer_only_mode in {"result", "value"}:
                                    reminder_lines.append(
                                        "For this request, the final answer must be ONLY the raw result value with no explanation."
                                    )
                                if final_answer_example:
                                    reminder_lines.append(final_answer_example)
                                if selected_tool_hint:
                                    reminder_lines.append(selected_tool_hint)
                                if selected_tool_argument_mirror:
                                    reminder_lines.append(selected_tool_argument_mirror)
                        item["content"] = (
                            f"Original user request:\n{original}\n\nRuntime reminder:\n"
                            + "\n".join(reminder_lines)
                        )
                    break

        if not uses_local_tool_loop:
            payload_messages = copy.deepcopy(payload_messages)
            for item in reversed(payload_messages):
                if not isinstance(item, dict) or item.get("role") != "user":
                    continue
                item["content"] = self._chatgpt_web_build_multimodal_user_content(item.get("content"))
                break

        if self._chatgpt_web_conversation_id and payload_messages and not uses_local_tool_loop:
            latest_user = None
            for item in reversed(payload_messages):
                if isinstance(item, dict) and item.get("role") == "user":
                    latest_user = item
                    break
            payload_messages = [latest_user] if latest_user else payload_messages[-1:]
        return instructions, payload_messages, uses_local_tool_loop

    def _wrap_chatgpt_web_response(self, result: dict[str, Any]):
        message_text = str(result.get("content") or "")
        finish_reason = str(result.get("finish_reason") or "stop")
        tool_calls = None
        forced_tool_call = self._chatgpt_web_forced_tool_call
        forced_tool_call_mode = self._chatgpt_web_forced_tool_call_mode
        selected_tool_names = list(getattr(self, "_chatgpt_web_selected_tool_names", []) or [])
        selected_tool_payload_messages = list(getattr(self, "_chatgpt_web_selected_tool_payload_messages", []) or [])
        self._chatgpt_web_forced_tool_call = None
        self._chatgpt_web_forced_tool_call_mode = "always"
        self._chatgpt_web_selected_tool_names = []
        self._chatgpt_web_selected_tool_payload_messages = []
        if self.tools and message_text:
            extracted_tool_calls, cleaned_text = _extract_xml_tool_calls_from_text(message_text)
            if extracted_tool_calls:
                tool_calls = self._chatgpt_web_normalize_extracted_tool_calls(
                    extracted_tool_calls,
                    selected_tool_payload_messages,
                )
                message_text = cleaned_text
            else:
                salvaged_tool_calls = self._chatgpt_web_salvage_malformed_tool_call(
                    message_text,
                    selected_tool_payload_messages,
                )
                if salvaged_tool_calls:
                    tool_calls = salvaged_tool_calls
                    message_text = ""
            if tool_calls is None and isinstance(forced_tool_call, dict):
                synth_mode = str(forced_tool_call_mode or "always").strip().lower()
                should_synthesize = synth_mode == "always"
                if synth_mode == "if_pending_work":
                    should_synthesize = self._chatgpt_web_response_signals_pending_tool_work(message_text)
                if should_synthesize:
                    synthetic_block = (
                        "<tool_call>\n"
                        + json.dumps({
                            "name": forced_tool_call.get("name"),
                            "arguments": forced_tool_call.get("arguments", {}),
                        }, ensure_ascii=False)
                        + "\n</tool_call>"
                    )
                    extracted_tool_calls, _ = _extract_xml_tool_calls_from_text(synthetic_block)
                    if extracted_tool_calls:
                        tool_calls = extracted_tool_calls
                        message_text = ""
            elif tool_calls is None and len(selected_tool_names) == 1 and self._chatgpt_web_response_signals_pending_tool_work(message_text):
                inferred_args = self._chatgpt_web_tool_args(
                    selected_tool_names[0],
                    selected_tool_payload_messages or [{"role": "user", "content": message_text}],
                )
                if inferred_args is not None:
                    synthetic_block = (
                        "<tool_call>\n"
                        + json.dumps({
                            "name": selected_tool_names[0],
                            "arguments": inferred_args,
                        }, ensure_ascii=False)
                        + "\n</tool_call>"
                    )
                    extracted_tool_calls, _ = _extract_xml_tool_calls_from_text(synthetic_block)
                    if extracted_tool_calls:
                        tool_calls = extracted_tool_calls
                        message_text = ""
        assistant_message = SimpleNamespace(content=message_text, tool_calls=tool_calls, role="assistant")
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

        import httpx as _httpx

        call_kwargs = {
            "access_token": self.api_key,
            "model": api_kwargs.get("model") or self.model,
            "messages": api_kwargs.get("messages") or [],
            "instructions": api_kwargs.get("instructions") or DEFAULT_AGENT_IDENTITY,
            "conversation_id": api_kwargs.get("conversation_id") or None,
            "parent_message_id": api_kwargs.get("parent_message_id") or None,
            "session_token": self._chatgpt_web_session_token,
            "cookie_header": self._chatgpt_web_cookie_header,
            "browser_cookies": self._chatgpt_web_browser_cookies,
            "user_agent": self._chatgpt_web_user_agent,
            "device_id": self._chatgpt_web_device_id,
            "timeout": api_kwargs.get("timeout") or float(os.getenv("HERMES_API_TIMEOUT", 1800.0)),
            "history_and_training_disabled": bool(api_kwargs.get("history_and_training_disabled", False)),
            "on_delta": _on_delta,
            "client": client,
        }

        retried_stale_thread = False
        while True:
            try:
                result = _chatgpt_web.stream_chatgpt_web_completion(**call_kwargs)
                break
            except _httpx.HTTPStatusError as exc:
                status = getattr(getattr(exc, "response", None), "status_code", None)
                request_url = str(getattr(getattr(exc, "request", None), "url", "") or "")
                response_url = str(getattr(getattr(exc, "response", None), "url", "") or "")
                failed_url = request_url or response_url
                had_remote_thread = bool(
                    call_kwargs.get("conversation_id")
                    or call_kwargs.get("parent_message_id")
                    or self._chatgpt_web_conversation_id
                    or self._chatgpt_web_parent_message_id
                )
                should_reset_remote_thread = (
                    not retried_stale_thread
                    and status in {404, 500}
                    and "backend-api/f/conversation" in failed_url
                    and had_remote_thread
                )
                if should_reset_remote_thread:
                    logger.warning(
                        "ChatGPT Web conversation thread returned %s; resetting remote thread and retrying once.",
                        status,
                    )
                    retried_stale_thread = True
                    self._chatgpt_web_conversation_id = None
                    self._chatgpt_web_parent_message_id = None
                    call_kwargs["conversation_id"] = None
                    call_kwargs["parent_message_id"] = None
                    continue
                raise
        self._chatgpt_web_conversation_id = result.get("conversation_id") or self._chatgpt_web_conversation_id
        self._chatgpt_web_parent_message_id = result.get("parent_message_id") or self._chatgpt_web_parent_message_id
        return self._wrap_chatgpt_web_response(result)
