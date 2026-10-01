from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Callable

from .dual_model_engine import DualModelEngine
from .hermes_execution import HermesToolExecutor
from .reflexion import ReflexionEngine
from .temporal_rag import TemporalRAG
from .types import (
    CognitiveRequest,
    DecisionAction,
    DecisionCycle,
    GateDecision,
    InferenceResult,
)


EvidenceRetriever = Callable[[tuple[str, ...], dict[str, Any]], dict[str, Any]]


@dataclass(frozen=True)
class CognitiveExecutionResult:
    inference: InferenceResult
    cycle: DecisionCycle
    tool_results: list[dict[str, str]]


class CognitiveEngine:
    """ODYN cognitive control plane with retrieval, reflection, and execution."""

    def __init__(
        self,
        dual_model: DualModelEngine,
        *,
        evidence_retriever: EvidenceRetriever | None = None,
        temporal_rag: TemporalRAG | None = None,
        reflexion: ReflexionEngine | None = None,
        tool_executor: HermesToolExecutor | None = None,
        clock: Callable[[], float] = time.time,
        max_attempts: int = 3,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        self.dual_model = dual_model
        self.temporal_rag = temporal_rag
        self.reflexion = reflexion or ReflexionEngine()
        self.tool_executor = tool_executor
        self.clock = clock
        self.evidence_retriever = evidence_retriever or (
            self._retrieve_temporal_evidence if temporal_rag is not None else None
        )
        self.max_attempts = max_attempts

    def _retrieve_temporal_evidence(
        self,
        keys: tuple[str, ...],
        context: dict[str, Any],
    ) -> dict[str, Any]:
        assert self.temporal_rag is not None
        items = []
        for key in keys:
            items.extend(
                self.temporal_rag.retrieve(
                    key,
                    as_of=self.clock(),
                    limit=3,
                )
            )
        # Deduplicate by source/timestamp/content while preserving relevance order.
        unique = {}
        for item in items:
            unique[(item.source, item.timestamp, item.content)] = item
        return {
            "temporal_evidence": [
                {
                    "content": item.content,
                    "source": item.source,
                    "timestamp": item.timestamp,
                    "metadata": item.metadata or {},
                }
                for item in unique.values()
            ]
        }

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
        if corrections_used < max_corrections and (
            critic.corrections or critic.highest_severity in {"low", "medium", "high"}
        ):
            return GateDecision(
                action=DecisionAction.CORRECT,
                reason="Candidate failed review; reflexion will formulate a correction.",
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
                    cycle.record(GateDecision(
                        action=DecisionAction.ESCALATE,
                        reason="Evidence requested but no retriever is configured.",
                        critic=output.critic,
                    ))
                    break
                evidence = self.evidence_retriever(output.critic.required_evidence, context)
                context.update(evidence)
                correction = None
                continue

            if gate.action == DecisionAction.CORRECT:
                reflection = self.reflexion.reflect(
                    goal=request.goal,
                    answer=output.primary.answer,
                    critic={
                        "issues": [
                            {
                                "code": issue.code,
                                "severity": issue.severity,
                                "message": issue.message,
                                "correction": issue.correction,
                            }
                            for issue in output.critic.issues
                        ],
                        "corrections": list(output.critic.corrections),
                    },
                    evidence=context.get("temporal_evidence"),
                )
                correction = reflection.next_correction
                continue

            break

        raise CognitiveEngineError(
            "ODYN cognitive cycle escalated after adversarial review.",
            cycle=cycle,
        )

    def run_and_execute(
        self,
        request: CognitiveRequest,
        *,
        tool_calls: list[dict[str, Any]],
    ) -> CognitiveExecutionResult:
        if self.tool_executor is None:
            raise CognitiveEngineError(
                "Hermes tool executor is not configured.",
                cycle=DecisionCycle(request=request),
            )
        inference, cycle = self.run(request)
        # Tool calls are executed only after the cognitive gate accepts.
        results = self.tool_executor.execute(tool_calls)
        return CognitiveExecutionResult(
            inference=inference,
            cycle=cycle,
            tool_results=results,
        )


class CognitiveEngineError(RuntimeError):
    def __init__(self, message: str, *, cycle: DecisionCycle) -> None:
        super().__init__(message)
        self.cycle = cycle
