from __future__ import annotations

from typing import Any, Callable

from .dual_model_engine import DualModelEngine
from .types import (
    CognitiveRequest,
    DecisionAction,
    DecisionCycle,
    GateDecision,
    InferenceResult,
)


EvidenceRetriever = Callable[[tuple[str, ...], dict[str, Any]], dict[str, Any]]


class CognitiveEngine:
    """ODYN cognitive control plane: evaluate, gate, correct, and audit."""

    def __init__(
        self,
        dual_model: DualModelEngine,
        *,
        evidence_retriever: EvidenceRetriever | None = None,
        max_attempts: int = 3,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        self.dual_model = dual_model
        self.evidence_retriever = evidence_retriever
        self.max_attempts = max_attempts

    def adversarial_gate(
        self,
        critic,
        *,
        corrections_used: int = 0,
        max_corrections: int = 2,
    ) -> GateDecision:
        if critic.valid and critic.highest_severity == "low":
            return GateDecision(
                action=DecisionAction.ACCEPT,
                reason="Adversarial critic accepted the candidate.",
                critic=critic,
            )

        if critic.required_evidence:
            return GateDecision(
                action=DecisionAction.RETRIEVE_EVIDENCE,
                reason="Critic requires additional evidence.",
                critic=critic,
            )

        if corrections_used < max_corrections and critic.corrections:
            return GateDecision(
                action=DecisionAction.CORRECT,
                reason="Candidate failed review and has actionable corrections.",
                critic=critic,
            )

        if corrections_used < max_corrections and critic.highest_severity in {"low", "medium"}:
            return GateDecision(
                action=DecisionAction.RETRY,
                reason="Critic rejected the candidate but the failure is retryable.",
                critic=critic,
            )

        return GateDecision(
            action=DecisionAction.ESCALATE,
            reason="Review could not be satisfied within the correction budget.",
            critic=critic,
        )

    def run(self, request: CognitiveRequest) -> tuple[InferenceResult, DecisionCycle]:
        cycle = DecisionCycle(request=request)
        context = dict(request.context)
        correction: str | None = None

        for _ in range(self.max_attempts):
            output = self.dual_model.evaluate(
                request.goal,
                context=context,
                correction=correction,
            )
            gate = self.adversarial_gate(
                output.critic,
                corrections_used=cycle.corrections,
                max_corrections=request.max_corrections,
            )
            cycle.record(gate)

            if gate.action == DecisionAction.ACCEPT:
                return output.primary, cycle

            if gate.action == DecisionAction.RETRIEVE_EVIDENCE:
                if self.evidence_retriever is None:
                    cycle.record(
                        GateDecision(
                            action=DecisionAction.ESCALATE,
                            reason="Evidence requested but no retriever is configured.",
                            critic=output.critic,
                        )
                    )
                    break
                evidence = self.evidence_retriever(
                    output.critic.required_evidence,
                    context,
                )
                context.update(evidence)
                correction = None
                continue

            if gate.action == DecisionAction.CORRECT:
                correction = "\n".join(output.critic.corrections)
                continue

            if gate.action == DecisionAction.RETRY:
                correction = None
                continue

            break

        raise CognitiveEngineError(
            "ODYN cognitive cycle escalated after adversarial review.",
            cycle=cycle,
        )


class CognitiveEngineError(RuntimeError):
    def __init__(self, message: str, *, cycle: DecisionCycle) -> None:
        super().__init__(message)
        self.cycle = cycle
