import json
import unittest

from odyn_ai.cognition import (
    CognitiveEngine,
    CognitiveRequest,
    DecisionAction,
    DecisionStatus,
    DualModelEngine,
)
from odyn_ai.cognition.cognitive_engine import CognitiveEngineError


class FakeBackend:
    def __init__(self, model_id, responses):
        self.model_id = model_id
        self.responses = list(responses)
        self.prompts = []

    def generate(self, prompt, *, context=None):
        self.prompts.append((prompt, dict(context or {})))
        if not self.responses:
            raise AssertionError(f"No response configured for {self.model_id}")
        return self.responses.pop(0)


def critic_json(*, valid, confidence, issues=None, corrections=None, evidence=None):
    return json.dumps({
        "valid": valid,
        "confidence": confidence,
        "issues": issues or [],
        "corrections": corrections or [],
        "required_evidence": evidence or [],
    })


class CognitiveCoreTests(unittest.TestCase):
    def test_dual_model_returns_structured_critic_result(self):
        primary = FakeBackend("primary-1", ["candidate"])
        critic = FakeBackend(
            "critic-1",
            [critic_json(valid=True, confidence=0.95)],
        )
        engine = DualModelEngine(primary, critic)

        result = engine.evaluate("answer the goal")

        self.assertEqual(result.primary.answer, "candidate")
        self.assertTrue(result.critic.valid)
        self.assertEqual(result.critic.critic_model_id, "critic-1")
        self.assertEqual(result.critic.highest_severity, "low")

    def test_malformed_critic_fails_closed(self):
        primary = FakeBackend("primary-1", ["candidate"])
        critic = FakeBackend("critic-1", ["not json"])
        result = DualModelEngine(primary, critic).evaluate("goal").critic

        self.assertFalse(result.valid)
        self.assertEqual(result.highest_severity, "critical")
        self.assertEqual(result.issues[0].code, "malformed_critic_result")

    def test_low_critic_confidence_cannot_accept_candidate(self):
        primary = FakeBackend("primary-1", ["candidate"])
        critic = FakeBackend(
            "critic-1",
            [critic_json(valid=True, confidence=0.2)],
        )
        result = DualModelEngine(primary, critic).evaluate("goal").critic

        self.assertFalse(result.valid)
        self.assertEqual(result.issues[0].code, "low_critic_confidence")

    def test_adversarial_gate_accepts_only_clean_result(self):
        primary = FakeBackend("primary-1", ["candidate"])
        critic = FakeBackend(
            "critic-1",
            [critic_json(valid=True, confidence=0.95)],
        )
        engine = CognitiveEngine(DualModelEngine(primary, critic))

        _, cycle = engine.run(CognitiveRequest("goal"))

        self.assertEqual(cycle.status, DecisionStatus.ACCEPTED)
        self.assertEqual(cycle.history[-1].action, DecisionAction.ACCEPT)

    def test_correction_is_reinjected_into_primary(self):
        primary = FakeBackend("primary-1", ["bad", "corrected"])
        critic = FakeBackend(
            "critic-1",
            [
                critic_json(
                    valid=False,
                    confidence=0.95,
                    issues=[{
                        "code": "missing_constraint",
                        "severity": "high",
                        "message": "Constraint missing",
                        "correction": "Include the required constraint.",
                    }],
                    corrections=["Include the required constraint."],
                ),
                critic_json(valid=True, confidence=0.95),
            ],
        )
        result, cycle = CognitiveEngine(
            DualModelEngine(primary, critic),
        ).run(CognitiveRequest("goal", max_corrections=1))

        self.assertEqual(result.answer, "corrected")
        self.assertEqual(cycle.status, DecisionStatus.ACCEPTED)
        self.assertEqual(cycle.corrections, 1)
        self.assertIn("Include the required constraint.", primary.prompts[1][0])

    def test_evidence_request_uses_retriever_before_retry(self):
        primary = FakeBackend("primary-1", ["answer", "answer"])
        critic = FakeBackend(
            "critic-1",
            [
                critic_json(
                    valid=False,
                    confidence=0.9,
                    evidence=["current_source"],
                ),
                critic_json(valid=True, confidence=0.9),
            ],
        )
        seen = []

        def retrieve(keys, context):
            seen.append((keys, context))
            return {"evidence": "verified source"}

        result, cycle = CognitiveEngine(
            DualModelEngine(primary, critic),
            evidence_retriever=retrieve,
        ).run(CognitiveRequest("goal"))

        self.assertEqual(result.answer, "answer")
        self.assertEqual(seen[0][0], ("current_source",))
        self.assertEqual(primary.prompts[1][1]["evidence"], "verified source")
        self.assertEqual(cycle.status, DecisionStatus.ACCEPTED)

    def test_escalates_after_correction_budget(self):
        primary = FakeBackend("primary-1", ["bad"])
        critic_response = critic_json(
            valid=False,
            confidence=0.9,
            issues=[{
                "code": "fatal",
                "severity": "critical",
                "message": "Cannot verify",
            }],
        )
        critic = FakeBackend("critic-1", [critic_response])
        with self.assertRaises(CognitiveEngineError) as error:
            CognitiveEngine(
                DualModelEngine(primary, critic),
                max_attempts=1,
            ).run(CognitiveRequest("goal", max_corrections=0))

        self.assertEqual(error.exception.cycle.status, DecisionStatus.ESCALATED)
        self.assertEqual(
            error.exception.cycle.history[-1].action,
            DecisionAction.ESCALATE,
        )


if __name__ == "__main__":
    unittest.main()
