"""Wire-level parsing for the ChatGPT Web tool protocol."""

from __future__ import annotations

import ast
import json
import re
from types import SimpleNamespace
from typing import Any, Optional


_XML_TOOL_CALL_BLOCK_RE = re.compile(r"(?:['\"]?<?)tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL | re.IGNORECASE)


_CHATGPT_WEB_HERMES_INTRO = (
    "# Hermes Agent web-model runtime\n"
    "You are Hermes Agent running through the ChatGPT Web transport, not the plain consumer chat UI. "
    "Hermes provides the real operating contract through developer instructions, tool definitions, skills, context files, "
    "memory, environment hints, and session state.\n"
    "- Treat the developer instructions as the authoritative Hermes runtime specification on every turn.\n"
    "- If tools are available, use them instead of describing what you would do.\n"
    "- Each turn has one job: either emit exactly one Hermes tool-call block or provide the final user-facing answer.\n"
    "- If the user explicitly names a Hermes tool such as terminal, read_file, search_files, execute_code, memory, or skill_manage, treat that as proof the tool is available in this session.\n"
    "- After a Hermes tool response, if the main task is not complete yet, continue immediately with the single best next tool call instead of narrating future intentions.\n"
    "- The user's original request already authorizes the obvious in-scope tool calls needed to complete that request. Do not ask for permission to continue, retry, inspect, patch, browse, or run the next obvious step.\n"
    "- Build tool calls from the Hermes tool schema: choose a listed tool name, provide a JSON arguments object that matches the schema, and infer the safest obvious next call from the user request plus tool outputs.\n"
    "- Do not say you will continue later, do not claim a file/skill/package was created unless a tool result proved it, and do not invent success states.\n"
    "- For create, write, edit, package, install, or migration work, verify the result with follow-up tool evidence before claiming completion.\n"
    "- Respect exact output constraints such as answer-only, one-line, path-only, or JSON-only responses.\n"
    "- Skills are first-class Hermes artifacts. If a relevant skill exists, load it. If the user asks to create or save a skill, "
    "produce a reusable skill with frontmatter, workflow steps, validation, and pitfalls rather than a stub.\n"
    "- Session summaries and compacted context are authoritative state. Continue from them instead of restarting work.\n"
    "- Keep working until the request is complete or you hit a real external blocker."
)


def _extract_xml_tool_calls_from_text(text: str) -> tuple[list[SimpleNamespace], str]:
    if not isinstance(text, str) or not text.strip():
        return [], ""

    extracted: list[SimpleNamespace] = []
    consumed_spans: list[tuple[int, int]] = []

    def _load_tool_call_object(raw_json: str) -> Optional[dict[str, Any]]:
        for loader in (json.loads, ast.literal_eval):
            try:
                loaded = loader(raw_json)
            except Exception:
                continue
            if isinstance(loaded, dict):
                return loaded
        return None

    def _try_add_tool_call(raw_json: str) -> None:
        obj = _load_tool_call_object(raw_json)
        if not isinstance(obj, dict):
            return

        function_block = obj.get("function") if isinstance(obj.get("function"), dict) else None
        if function_block is not None:
            function_name = function_block.get("name")
            function_args = function_block.get("arguments", "{}")
        else:
            function_name = obj.get("name")
            function_args = obj.get("arguments", {})

        if not isinstance(function_name, str) or not function_name.strip():
            return
        if not isinstance(function_args, str):
            function_args = json.dumps(function_args, ensure_ascii=False)

        call_id = obj.get("id")
        if not isinstance(call_id, str) or not call_id.strip():
            call_id = f"chatgpt_web_call_{len(extracted) + 1}"

        extracted.append(
            SimpleNamespace(
                id=call_id,
                call_id=call_id,
                response_item_id=None,
                type="function",
                function=SimpleNamespace(
                    name=function_name.strip(),
                    arguments=function_args,
                ),
            )
        )

    for match in _XML_TOOL_CALL_BLOCK_RE.finditer(text):
        _try_add_tool_call(match.group(1))
        consumed_spans.append((match.start(), match.end()))

    if not consumed_spans:
        return extracted, text.strip()

    consumed_spans.sort()
    merged_spans: list[tuple[int, int]] = []
    for start, end in consumed_spans:
        if not merged_spans or start > merged_spans[-1][1]:
            merged_spans.append((start, end))
        else:
            merged_spans[-1] = (merged_spans[-1][0], max(merged_spans[-1][1], end))

    remaining_parts: list[str] = []
    cursor = 0
    for start, end in merged_spans:
        if cursor < start:
            remaining_parts.append(text[cursor:start])
        cursor = max(cursor, end)
    if cursor < len(text):
        remaining_parts.append(text[cursor:])

    cleaned = "\n".join(part.strip() for part in remaining_parts if isinstance(part, str) and part.strip()).strip()
    return extracted, cleaned


def _parse_tool_call_arguments(raw_args: Any) -> Optional[dict[str, Any]]:
    """Normalize tool-call arguments from string-or-dict payloads."""
    if isinstance(raw_args, dict):
        return raw_args
    if isinstance(raw_args, str):
        try:
            parsed = json.loads(raw_args)
        except json.JSONDecodeError:
            return None
        return parsed if isinstance(parsed, dict) else None
    return None
