"""Validation for model-generated Hermes tool-call plans.

This module is deliberately side-effect free. It validates the argument envelope
only; authorization and execution remain in Hermes' existing policy and dispatcher.
"""
from __future__ import annotations

import json
from typing import Any


class ToolPlanValidationError(ValueError):
    """Raised when a generated tool call does not contain a valid argument object."""


def parse_tool_plan_arguments(raw_arguments: Any) -> dict[str, Any]:
    """Parse one model-generated tool-call argument payload as a JSON object."""
    if not isinstance(raw_arguments, str):
        raise ToolPlanValidationError("tool arguments must be a JSON string")
    try:
        value = json.loads(raw_arguments)
    except json.JSONDecodeError as exc:
        raise ToolPlanValidationError(f"invalid tool-plan JSON: {exc.msg}") from exc
    if not isinstance(value, dict):
        raise ToolPlanValidationError("tool-plan arguments must be a JSON object")
    return value
