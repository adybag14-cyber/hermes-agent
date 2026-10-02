"""Bridge ODYN's critic-only review into the Hermes turn-gate contract."""
from __future__ import annotations

import json
from typing import Any

from .dual_model_engine import DualModelEngine
from .types import DecisionAction, GateDecision


class HermesDualModelGate:
    """Evaluate the exact Hermes candidate; never generate or dispatch tools."""

    def __init__(self, engine: DualModelEngine, *, max_attempts: int = 3) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        self.engine = engine
        self.max_attempts = max_attempts
        self._attempts: dict[str, int] = {}

    def evaluate_turn(self, candidate: Any, *, context: Any) -> GateDecision:
        messages = list(getattr(context, "messages", ()) or ())
        goal = next(
            (str(m.get("content") or "") for m in reversed(messages)
             if isinstance(m, dict) and m.get("role") == "user"),
            "",
        )
        task_key = str(getattr(context, "task_id", None) or getattr(context, "session_id", None) or "default")
        calls = list(getattr(candidate, "tool_calls", ()) or ())
        candidate_payload = {
            "content": getattr(candidate, "content", None),
            "tool_calls": calls,
            "finish_reason": getattr(candidate, "finish_reason", None),
        }
        result = self.engine.review_candidate(
            goal,
            json.dumps(candidate_payload, ensure_ascii=False, default=str),
            tool_calls=calls,
            context={"messages": messages, "task_id": task_key},
        )
        if result.required_evidence:
            action = DecisionAction.RETRIEVE_EVIDENCE
            reason = "Critic requires additional evidence."
        elif result.valid and result.highest_severity == "low":
            action = DecisionAction.ACCEPT
            reason = "Adversarial critic accepted the actual Hermes candidate."
            self._attempts.pop(task_key, None)
        elif result.highest_severity == "critical":
            action = DecisionAction.ESCALATE
            reason = "Critic identified a critical issue."
        else:
            used = self._attempts.get(task_key, 0) + 1
            self._attempts[task_key] = used
            action = DecisionAction.CORRECT if used < self.max_attempts else DecisionAction.ESCALATE
            reason = "Candidate failed adversarial review."
        return GateDecision(action=action, reason=reason, critic=result)
