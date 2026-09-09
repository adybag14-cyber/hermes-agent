"""Bounded, secret-redacted public tool activity for the Android chat timeline."""

from __future__ import annotations

import json
import re
from itertools import islice
from typing import Any

from agent.redact import redact_sensitive_text

_SECRET_KEY = re.compile(
    r"password|passwd|secret|authorization|cookie|credential|api[_-]?key|access[_-]?token|refresh[_-]?token",
    re.IGNORECASE,
)


def _display_value(value: Any, budget: list[int], depth: int = 0) -> Any:
    budget[0] -= 1
    if budget[0] < 0 or depth > 5:
        return "[truncated]"
    if isinstance(value, dict):
        return {
            str(key)[:100]: "[redacted]" if _SECRET_KEY.search(str(key)) else _display_value(item, budget, depth + 1)
            for key, item in islice(value.items(), 32)
        }
    if isinstance(value, (list, tuple)):
        return [_display_value(item, budget, depth + 1) for item in value[:32]]
    if isinstance(value, str):
        return redact_sensitive_text(value[:8192], force=True)
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return "[unsupported value]"


def tool_activity_preview(value: Any, *, limit: int = 8192) -> str:
    """Redact structured keys before serializing; display never changes the model's tool result."""
    if isinstance(value, str) and len(value) > 65536:
        return "[output omitted: exceeds preview limit]"
    if isinstance(value, str) and len(value) <= 65536:
        try:
            value = json.loads(value)
        except (ValueError, RecursionError):
            pass
    scrubbed = _display_value(value, [128])
    text = scrubbed if isinstance(scrubbed, str) else json.dumps(scrubbed, ensure_ascii=False)
    return text[:limit] + ("\n[truncated]" if len(text) > limit else "")


def android_tool_activity_details(function_args: Any, function_result: Any = None, *, completed: bool = False) -> dict:
    if not completed:
        return {"arguments": tool_activity_preview(function_args, limit=2048)}
    failed = isinstance(function_result, dict) and (
        function_result.get("success") is False or function_result.get("isError") is True or bool(function_result.get("error"))
    )
    return {"result": tool_activity_preview(function_result), "status": "failed" if failed else "completed"}
