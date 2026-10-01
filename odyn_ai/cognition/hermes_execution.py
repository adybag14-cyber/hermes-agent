from __future__ import annotations

import json
from typing import Any, Callable, Mapping


class HermesToolExecutor:
    """Cognition-to-Hermes bridge for explicit structured tool calls."""

    def __init__(self, dispatch: Callable[[str, dict[str, Any]], str]) -> None:
        self._dispatch = dispatch

    def execute(self, tool_calls: list[Mapping[str, Any]]) -> list[dict[str, str]]:
        results = []
        for call in tool_calls:
            name = call.get("name")
            arguments = call.get("arguments", {})
            if not isinstance(name, str) or not name.strip():
                raise ValueError("tool call name must be a non-empty string")
            if not isinstance(arguments, dict):
                raise ValueError(f"tool arguments for {name} must be an object")
            result = self._dispatch(name, arguments)
            results.append({"name": name, "result": result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, default=str)})
        return results
