"""Finite-iteration reminders appended after a complete tool batch."""

import json
import math
import sys
from typing import Optional


class IterationWarningsMixin:
    def _get_budget_warning(self, api_call_count: int) -> Optional[str]:
        """Return an in-band warning when the current turn is close to its cap."""
        if not getattr(self, "_budget_pressure_enabled", True):
            return None
        try:
            max_iterations = float(self.max_iterations)
        except (TypeError, ValueError):
            return None
        if max_iterations <= 0 or not math.isfinite(max_iterations) or max_iterations >= sys.maxsize:
            return None

        total = int(max_iterations)
        used = max(0, int(api_call_count))
        ratio = used / total if total else 0
        if ratio < 0.70:
            return None

        remaining = max(total - used, 0)
        if ratio >= 0.90:
            return (
                f"[BUDGET WARNING: {remaining} iteration(s) left. "
                "Provide your final response NOW if you have enough information.]"
            )
        return (
            f"[BUDGET: {remaining} iterations left. "
            "Prioritize remaining work and avoid unnecessary tool calls.]"
        )


    def _inject_budget_warning_into_last_tool_result(self, messages: list, api_call_count: int) -> None:
        warning = self._get_budget_warning(api_call_count)
        if not warning or not messages:
            return
        last = messages[-1]
        if not isinstance(last, dict) or last.get("role") != "tool":
            return

        content = last.get("content", "")
        if isinstance(content, list):
            # Preserve multimodal tool results instead of stringifying media.
            if not any(
                isinstance(part, dict) and part.get("type") == "text"
                and part.get("text") == warning for part in content
            ):
                last["content"] = [*content, {"type": "text", "text": warning}]
            return
        if isinstance(content, str):
            try:
                parsed = json.loads(content)
            except (json.JSONDecodeError, TypeError):
                if warning not in content:
                    last["content"] = content + f"\n\n{warning}"
                return
            if isinstance(parsed, dict):
                parsed.setdefault("_budget_warning", warning)
                last["content"] = json.dumps(parsed, ensure_ascii=False)
                return
        last["content"] = f"{content}\n\n{warning}"
