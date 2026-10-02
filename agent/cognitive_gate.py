"""Stable adapter contract between the Hermes loop and ODYN cognition.

The gate is deliberately optional: when no gate is configured, Hermes keeps
its existing behavior. The adapter reviews the exact candidate produced by
the conversation model and never dispatches tools or changes authorization.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Protocol, Sequence


@dataclass(frozen=True)
class HermesTurnCandidate:
    """A snapshot of one actual model turn before tool execution."""

    content: str | None
    tool_calls: tuple[Mapping[str, Any], ...] = ()
    finish_reason: str | None = None


@dataclass(frozen=True)
class HermesGateContext:
    """Read-only context supplied to a configured cognitive gate."""

    task_id: str | None = None
    session_id: str | None = None
    messages: Sequence[Mapping[str, Any]] = field(default_factory=tuple)


class HermesCognitiveGate(Protocol):
    """One review contract implemented by ODYN's cognitive control plane."""

    def evaluate_turn(
        self,
        candidate: HermesTurnCandidate,
        *,
        context: HermesGateContext,
    ) -> Any:
        """Return a structured gate decision; must not execute tools."""
        ...


def evaluate_hermes_turn(
    agent: Any,
    assistant_message: Any,
    messages: Sequence[Mapping[str, Any]],
    task_id: str | None,
    finish_reason: str | None,
) -> Any | None:
    """Invoke the configured gate on the real Hermes model result.

    This function is a review seam only. Decision enforcement is a separate
    integration step; authorization and dispatch remain in Hermes.
    """
    gate = getattr(agent, "_cognitive_gate", None)
    if gate is None:
        return None

    calls = []
    for tool_call in getattr(assistant_message, "tool_calls", None) or ():
        function = getattr(tool_call, "function", None)
        calls.append({
            "id": getattr(tool_call, "id", None),
            "name": getattr(function, "name", None),
            "arguments": getattr(function, "arguments", None),
        })
    candidate = HermesTurnCandidate(
        content=getattr(assistant_message, "content", None),
        tool_calls=tuple(calls),
        finish_reason=finish_reason,
    )
    context = HermesGateContext(
        task_id=task_id,
        session_id=getattr(agent, "session_id", None),
        messages=tuple(messages),
    )
    evaluator = getattr(gate, "evaluate_turn", None)
    if not callable(evaluator):
        raise TypeError("_cognitive_gate must implement evaluate_turn(candidate, context=...)")
    decision = evaluator(candidate, context=context)
    agent._last_cognitive_gate_decision = decision
    return decision
