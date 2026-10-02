from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ReflexionResult:
    reflection: str
    next_correction: str
    lessons: tuple[str, ...] = ()


class ReflexionEngine:
    """Turn failed evaluations into an explicit correction strategy."""

    def reflect(
        self,
        *,
        goal: str,
        answer: str,
        critic: dict[str, Any],
        evidence: dict[str, Any] | None = None,
    ) -> ReflexionResult:
        issues = critic.get("issues", [])
        messages = [
            str(item.get("message", "")).strip()
            for item in issues
            if isinstance(item, dict) and str(item.get("message", "")).strip()
        ]
        reflection = (
            f"Goal: {goal}\n"
            f"Candidate failure analysis: {'; '.join(messages) or 'critic rejected the candidate'}\n"
            f"Evidence available: {evidence or {}}\n"
            f"Candidate: {answer}"
        )
        corrections = critic.get("corrections", [])
        if corrections:
            next_correction = " ".join(str(item) for item in corrections)
        elif messages:
            next_correction = (
                "Correct the identified issues before answering again: "
                + "; ".join(messages)
            )
        else:
            next_correction = "Re-evaluate the candidate and produce a more defensible answer."

        if evidence:
            next_correction += "\nUse the available evidence: " + json.dumps(
                evidence, ensure_ascii=False, default=str,
            )

        return ReflexionResult(
            reflection=reflection,
            next_correction=next_correction,
            lessons=tuple(messages),
        )
