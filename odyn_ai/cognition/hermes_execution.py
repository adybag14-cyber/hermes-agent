from __future__ import annotations

import json
from typing import Any, Callable, Mapping


def validate_tool_calls(tool_calls: list[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Snapshot and validate the complete plan before any dispatch."""
    validated = []
    for call in tool_calls:
        if not isinstance(call, Mapping):
            raise ValueError("tool calls must be objects")
        name = call.get("name")
        arguments = call.get("arguments", {})
        if not isinstance(name, str) or not name.strip():
            raise ValueError("tool call name must be a non-empty string")
        if not isinstance(arguments, dict):
            raise ValueError(f"tool arguments for {name} must be an object")
        # The JSON round trip validates serializability and breaks shared
        # references, so reviewers/dispatchers cannot change the caller's plan.
        arguments = json.loads(json.dumps(arguments, allow_nan=False))
        validated.append({"name": name, "arguments": arguments})
    return validated


class HermesToolExecutor:
    """Cognition-to-Hermes bridge for explicit structured tool calls."""

    def __init__(self, dispatch: Callable[[str, dict[str, Any]], str]) -> None:
        self._dispatch = dispatch

    def execute(self, tool_calls: list[Mapping[str, Any]]) -> list[dict[str, str]]:
        validated = validate_tool_calls(tool_calls)
        results = []
        for call in validated:
            name = call["name"]
            arguments = call["arguments"]
            result = self._dispatch(name, arguments)
            results.append({
                "name": name,
                "result": result if isinstance(result, str)
                else json.dumps(result, ensure_ascii=False, default=str),
            })
        return results


def hermes_dispatcher(
    *,
    task_id: str | None = None,
    session_id: str | None = None,
    user_task: str | None = None,
    enabled_tools: list[str] | None = None,
) -> Callable[[str, dict[str, Any]], str]:
    """Bind the real Hermes model_tools dispatcher with session context."""
    from model_tools import handle_function_call

    def dispatch(name: str, arguments: dict[str, Any]) -> str:
        return handle_function_call(
            name,
            arguments,
            task_id=task_id,
            session_id=session_id,
            user_task=user_task,
            enabled_tools=enabled_tools,
        )

    return dispatch
