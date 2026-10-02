"""Stable adapter contract between the Hermes loop and ODYN cognition.

The gate is deliberately optional: when no gate is configured, Hermes keeps
its existing behavior. The adapter reviews the exact candidate produced by
the conversation model and never dispatches tools or changes authorization.
"""
from __future__ import annotations

from copy import deepcopy
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

    The runtime enforces this decision through review_hermes_turn;
    authorization and dispatch remain in Hermes.
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
        tool_calls=tuple(deepcopy(calls)),
        finish_reason=finish_reason,
    )
    context = HermesGateContext(
        task_id=task_id,
        session_id=getattr(agent, "session_id", None),
        messages=tuple(deepcopy(messages)),
    )
    evaluator = getattr(gate, "evaluate_turn", None)
    if not callable(evaluator):
        raise TypeError("_cognitive_gate must implement evaluate_turn(candidate, context=...)")
    decision = evaluator(candidate, context=context)
    if decision is None:
        raise ValueError("A configured cognitive gate must return a decision")
    agent._last_cognitive_gate_decision = decision
    return decision


@dataclass(frozen=True)
class HermesGateReview:
    """Dispatch control returned by the shared review boundary."""

    allows_execution: bool
    escalated: bool = False
    feedback: str = ""
    reason: str = ""


def review_hermes_turn(
    agent: Any,
    assistant_message: Any,
    messages: Sequence[Mapping[str, Any]],
    task_id: str | None,
    finish_reason: str | None,
) -> HermesGateReview:
    """Fail closed on review failures and bound rejected candidate retries.

    Both Hermes loop implementations use this boundary. It never dispatches,
    grants approval, or rewrites the live conversation history.
    """
    try:
        decision = evaluate_hermes_turn(
            agent, assistant_message, messages, task_id, finish_reason,
        )
        if decision is None:  # Optional gate is not configured.
            return HermesGateReview(allows_execution=True)
        action = decision.get("action") if isinstance(decision, Mapping) else getattr(decision, "action", None)
        action = getattr(action, "value", action)
        if isinstance(action, str) and action == "accept":
            agent._cognitive_gate_attempts = 0
            return HermesGateReview(allows_execution=True)

        reason = decision.get("reason", "") if isinstance(decision, Mapping) else getattr(decision, "reason", "")
        reason = str(reason or "The cognitive gate rejected this candidate.")
        critic = decision.get("critic") if isinstance(decision, Mapping) else getattr(decision, "critic", None)
        required = critic.get("required_evidence", ()) if isinstance(critic, Mapping) else getattr(critic, "required_evidence", ())
        if isinstance(required, str):
            required = (required,)
        hint = " Required evidence: " + "; ".join(map(str, required)) if action == "retrieve_evidence" and required else ""

        if getattr(agent, "_cognitive_gate_task_id", None) != task_id:
            agent._cognitive_gate_attempts = 0
            agent._cognitive_gate_task_id = task_id
        attempts = getattr(agent, "_cognitive_gate_attempts", 0) + 1
        limit = getattr(agent, "_cognitive_gate_max_attempts", 3)
        if type(limit) is not int or limit < 1:
            raise ValueError("Cognitive gate attempt limit must be a positive integer")
        escalated = attempts >= limit or not isinstance(action, str) or action not in {
            "correct", "retry", "retrieve_evidence",
        }
        agent._cognitive_gate_attempts = 0 if escalated else attempts
        return HermesGateReview(
            allows_execution=False,
            escalated=escalated,
            feedback=f"Cognitive gate action: {action}. {reason}{hint}",
            reason=reason,
        )
    except Exception as exc:
        agent._cognitive_gate_attempts = 0
        reason = f"Cognitive review failed ({type(exc).__name__}); no tool was executed."
        return HermesGateReview(
            allows_execution=False, escalated=True, feedback=reason, reason=reason,
        )


def record_blocked_turn(agent: Any, assistant_message: Any, messages: list, finish_reason: str | None, review: HermesGateReview) -> None:
    """Record a rejected candidate and synthetic results for every call."""
    messages.append(agent._build_assistant_message(assistant_message, finish_reason))
    for call in assistant_message.tool_calls:
        messages.append({
            "role": "tool", "name": call.function.name,
            "tool_call_id": call.id,
            "content": "[Blocked by Hermes cognitive review; no tool was executed.] " + review.feedback,
        })
