from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol

from .types import CriticIssue, CriticResult, InferenceResult


class InferenceBackend(Protocol):
    """Provider-neutral inference boundary for GGUF, remote, LiteRT-LM, etc."""

    model_id: str

    def generate(self, prompt: str, *, context: dict[str, Any] | None = None) -> str:
        ...


@dataclass(frozen=True)
class DualModelOutput:
    primary: InferenceResult
    critic: CriticResult


class DualModelEngine:
    """Run a primary model and adversarial critic as one cognitive unit."""

    def __init__(
        self,
        primary: InferenceBackend,
        critic: InferenceBackend,
        *,
        critic_confidence_threshold: float = 0.65,
    ) -> None:
        if not 0.0 <= critic_confidence_threshold <= 1.0:
            raise ValueError("critic_confidence_threshold must be between 0 and 1")
        self.primary = primary
        self.critic = critic
        self.critic_confidence_threshold = critic_confidence_threshold

    def evaluate(
        self,
        goal: str,
        *,
        context: dict[str, Any] | None = None,
        correction: str | None = None,
    ) -> DualModelOutput:
        context = dict(context or {})
        answer = self.primary.generate(
            self._build_primary_prompt(goal, correction),
            context=context,
        )
        primary = InferenceResult(
            answer=answer,
            model_id=self.primary.model_id,
            metadata={"correction": correction},
        )
        raw = self.critic.generate(
            self._build_critic_prompt(goal, answer, context),
            context=context,
        )
        return DualModelOutput(primary=primary, critic=self._parse_critic(raw))

    @staticmethod
    def _build_primary_prompt(goal: str, correction: str | None) -> str:
        prompt = f"Goal:\n{goal}\n"
        if correction:
            prompt += f"\nRequired correction:\n{correction}\n"
        return prompt + "\nProduce the best answer. Do not fabricate evidence."

    @staticmethod
    def _build_critic_prompt(goal: str, answer: str, context: dict[str, Any]) -> str:
        schema = (
            '{"valid":bool,"confidence":number,"issues":['
            '{"code":str,"severity":"low|medium|high|critical","message":str,"correction":str|null}],'
            '"corrections":[str],"required_evidence":[str]}'
        )
        return (
            "You are ODYN's adversarial critic. Return ONLY valid JSON matching this schema: "
            + schema
            + f"\nGOAL:\n{goal}\nCANDIDATE:\n{answer}\nCONTEXT:\n"
            + json.dumps(context, ensure_ascii=False, default=str)
        )

    def _parse_critic(self, raw: str) -> CriticResult:
        try:
            payload = self._extract_json(raw)
            issues = tuple(
                CriticIssue(
                    code=str(item["code"]),
                    severity=str(item["severity"]),
                    message=str(item["message"]),
                    correction=item.get("correction"),
                )
                for item in payload.get("issues", [])
            )
            confidence = float(payload.get("confidence", 0.0))
            valid = bool(payload.get("valid", False))
            if valid and confidence < self.critic_confidence_threshold:
                valid = False
                issues += (
                    CriticIssue(
                        code="low_critic_confidence",
                        severity="medium",
                        message="Critic confidence is below the acceptance threshold.",
                    ),
                )
            return CriticResult(
                valid=valid,
                confidence=confidence,
                issues=issues,
                corrections=tuple(map(str, payload.get("corrections", []))),
                required_evidence=tuple(map(str, payload.get("required_evidence", []))),
                critic_model_id=self.critic.model_id,
                raw=payload,
            )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            return CriticResult(
                valid=False,
                confidence=0.0,
                issues=(
                    CriticIssue(
                        code="malformed_critic_result",
                        severity="critical",
                        message=f"Critic returned an invalid structured result: {exc}",
                    ),
                ),
                corrections=("Retry the critic with strict JSON output.",),
                critic_model_id=self.critic.model_id,
                raw={"raw": raw},
            )

    @staticmethod
    def _extract_json(raw: str) -> dict[str, Any]:
        raw = raw.strip()
        try:
            value = json.loads(raw)
        except json.JSONDecodeError:
            start, end = raw.find("{"), raw.rfind("}")
            if start < 0 or end <= start:
                raise ValueError("critic response does not contain JSON")
            value = json.loads(raw[start:end + 1])
        if not isinstance(value, dict):
            raise ValueError("critic JSON must be an object")
        return value
