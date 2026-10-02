"""Evidence-first decision gate for ODYN cognitive workflows.

This module is deliberately side-effect free. It decides whether a critic result
may proceed; it does not retrieve evidence, authorize tools, or execute actions.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from math import isfinite
from typing import Iterable


class GateAction(str, Enum):
    ACCEPT = "accept"
    RETRIEVE_EVIDENCE = "retrieve_evidence"
    BLOCKED_EVIDENCE = "blocked_evidence"
    REJECT = "reject"
    ESCALATE = "escalate"


@dataclass(frozen=True)
class CriticResult:
    valid: bool
    severity: float
    required_evidence: tuple[str, ...] = ()
    rationale: str = ""


@dataclass(frozen=True)
class GateDecision:
    action: GateAction
    missing_evidence: tuple[str, ...] = ()
    reason: str = ""


def adversarial_gate(
    critic: CriticResult,
    *,
    available_evidence: Iterable[str] = (),
    retrieval_attempted: bool = False,
    severity_threshold: float = 0.5,
) -> GateDecision:
    """Evaluate a critic result, always resolving evidence requirements first.

    An attempted retrieval that yields no required evidence is a blocking
    outcome, never evidence of correctness. Tool authorization and execution
    must be handled by a separate policy boundary.
    """
    if not isinstance(critic, CriticResult):
        raise TypeError("critic must be a CriticResult")
    if not isinstance(critic.valid, bool):
        raise TypeError("critic.valid must be bool")
    if not isfinite(critic.severity) or not 0.0 <= critic.severity <= 1.0:
        raise ValueError("critic.severity must be finite and between 0 and 1")
    if not isfinite(severity_threshold) or not 0.0 <= severity_threshold <= 1.0:
        raise ValueError("severity_threshold must be finite and between 0 and 1")

    required = tuple(dict.fromkeys(item.strip() for item in critic.required_evidence))
    if any(not item for item in required):
        raise ValueError("required_evidence entries must be non-empty strings")

    available = {item.strip() for item in available_evidence if isinstance(item, str)}
    missing = tuple(item for item in required if item not in available)
    if missing:
        if retrieval_attempted:
            return GateDecision(
                GateAction.BLOCKED_EVIDENCE,
                missing,
                "Required evidence remains unavailable after retrieval.",
            )
        return GateDecision(
            GateAction.RETRIEVE_EVIDENCE,
            missing,
            "Required evidence is missing; retrieve it before evaluating acceptance.",
        )

    if not critic.valid:
        return GateDecision(GateAction.REJECT, reason="Critic marked the result invalid.")
    if critic.severity >= severity_threshold:
        return GateDecision(GateAction.ESCALATE, reason="Severity meets or exceeds the escalation threshold.")
    return GateDecision(GateAction.ACCEPT, reason="Evidence requirements are satisfied and critic accepted the result.")
