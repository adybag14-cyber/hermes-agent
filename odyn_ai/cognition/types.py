from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


@dataclass(frozen=True)
class CognitiveRequest:
    goal: str
    context: dict[str, Any] = field(default_factory=dict)
    max_corrections: int = 2

    def __post_init__(self) -> None:
        if not self.goal.strip():
            raise ValueError("goal must not be empty")
        if self.max_corrections < 0:
            raise ValueError("max_corrections must be >= 0")


@dataclass(frozen=True)
class InferenceResult:
    answer: str
    model_id: str
    confidence: float = 0.0
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.answer.strip():
            raise ValueError("answer must not be empty")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence must be between 0 and 1")


@dataclass(frozen=True)
class CriticIssue:
    code: str
    severity: str
    message: str
    correction: str | None = None

    def __post_init__(self) -> None:
        if self.severity not in {"low", "medium", "high", "critical"}:
            raise ValueError(f"invalid severity: {self.severity}")


@dataclass(frozen=True)
class CriticResult:
    valid: bool
    confidence: float
    issues: tuple[CriticIssue, ...] = ()
    corrections: tuple[str, ...] = ()
    required_evidence: tuple[str, ...] = ()
    critic_model_id: str = "unknown"
    raw: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("critic confidence must be between 0 and 1")

    @property
    def highest_severity(self) -> str:
        order = {"low": 0, "medium": 1, "high": 2, "critical": 3}
        return max((i.severity for i in self.issues), key=order.get, default="low")


class DecisionAction(str, Enum):
    ACCEPT = "accept"
    CORRECT = "correct"
    RETRIEVE_EVIDENCE = "retrieve_evidence"
    RETRY = "retry"
    ESCALATE = "escalate"


class DecisionStatus(str, Enum):
    PENDING = "pending"
    ACCEPTED = "accepted"
    CORRECTING = "correcting"
    RETRIEVING = "retrieving"
    RETRYING = "retrying"
    ESCALATED = "escalated"


@dataclass(frozen=True)
class GateDecision:
    action: DecisionAction
    reason: str
    critic: CriticResult


@dataclass
class DecisionCycle:
    request: CognitiveRequest
    status: DecisionStatus = DecisionStatus.PENDING
    attempts: int = 0
    corrections: int = 0
    history: list[GateDecision] = field(default_factory=list)

    def record(self, decision: GateDecision) -> None:
        self.history.append(decision)
        self.status = {
            DecisionAction.ACCEPT: DecisionStatus.ACCEPTED,
            DecisionAction.CORRECT: DecisionStatus.CORRECTING,
            DecisionAction.RETRIEVE_EVIDENCE: DecisionStatus.RETRIEVING,
            DecisionAction.RETRY: DecisionStatus.RETRYING,
            DecisionAction.ESCALATE: DecisionStatus.ESCALATED,
        }[decision.action]
        self.attempts += 1
        if decision.action == DecisionAction.CORRECT:
            self.corrections += 1


# Compatibility alias reserved for the future richer Decision object.
Decision = GateDecision
