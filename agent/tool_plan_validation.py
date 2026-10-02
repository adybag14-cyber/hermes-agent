"""Validation for model-generated Hermes tool-call plans.

This module is deliberately side-effect free. It validates the argument envelope
only; authorization and execution remain in Hermes' existing policy and dispatcher.
"""
from __future__ import annotations

import json
import math
from typing import Any


class ToolPlanValidationError(ValueError):
    """Raised when a generated tool call does not contain a valid argument object."""


def _reject_non_finite_constant(value: str) -> None:
    raise ToolPlanValidationError(f"tool-plan JSON contains a non-finite number: {value}")


def _parse_finite_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ToolPlanValidationError("tool-plan JSON number exceeds the finite range")
    return parsed


def parse_tool_plan_arguments(raw_arguments: Any) -> dict[str, Any]:
    """Parse one model-generated tool-call argument payload as a JSON object."""
    if not isinstance(raw_arguments, str):
        raise ToolPlanValidationError("tool arguments must be a JSON string")
    try:
        value = json.loads(
            raw_arguments,
            parse_constant=_reject_non_finite_constant,
            parse_float=_parse_finite_float,
        )
    except json.JSONDecodeError as exc:
        raise ToolPlanValidationError(f"invalid tool-plan JSON: {exc.msg}") from exc
    if not isinstance(value, dict):
        raise ToolPlanValidationError("tool-plan arguments must be a JSON object")
    return value
