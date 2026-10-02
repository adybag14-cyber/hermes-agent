import json
import unittest
from types import SimpleNamespace

from odyn_ai.cognition import DualModelEngine, HermesDualModelGate


class FakeBackend:
    def __init__(self, model_id, outputs):
        self.model_id = model_id
        self.outputs = list(outputs)
        self.prompts = []

    def generate(self, prompt, *, context=None):
        self.prompts.append((prompt, context))
        return self.outputs.pop(0)


def context(messages=None, task_id="task-1"):
    return SimpleNamespace(messages=messages or [{"role": "user", "content": "Do the task"}],
                           task_id=task_id, session_id="session-1")


def candidate(content="Hermes answer", calls=None):
    return SimpleNamespace(content=content, tool_calls=calls or (), finish_reason="tool_calls")


class HermesDualModelGateTests(unittest.TestCase):
    def make_gate(self, critic_outputs, max_attempts=3):
        primary = FakeBackend("primary", [])
        critic = FakeBackend("critic", critic_outputs)
        return HermesDualModelGate(DualModelEngine(primary, critic), max_attempts=max_attempts), primary, critic

    def test_accepts_actual_candidate_without_regenerating_it(self):
        gate, primary, critic = self.make_gate([
            '{"valid":true,"confidence":0.95,"issues":[],"corrections":[],"required_evidence":[]}'
        ])
        actual = candidate("Exact Hermes output", [{"id": "c1", "name": "read_file", "arguments": {"path": "x"}}])
        decision = gate.evaluate_turn(actual, context=context())
        self.assertEqual(decision.action.value, "accept")
        self.assertEqual(primary.prompts, [])
        self.assertIn("Exact Hermes output", critic.prompts[0][0])
        self.assertIn('"name": "read_file"', critic.prompts[0][0])

    def test_maps_review_failure_to_correction(self):
        gate, _, _ = self.make_gate([
            '{"valid":false,"confidence":0.9,"issues":[{"code":"missing","severity":"high","message":"missing check"}],"corrections":["add check"],"required_evidence":[]}'
        ])
        self.assertEqual(gate.evaluate_turn(candidate(), context=context()).action.value, "correct")

    def test_required_evidence_takes_precedence(self):
        gate, _, _ = self.make_gate([
            '{"valid":true,"confidence":0.99,"issues":[],"corrections":[],"required_evidence":["source A"]}'
        ])
        decision = gate.evaluate_turn(candidate(), context=context())
        self.assertEqual(decision.action.value, "retrieve_evidence")
        self.assertEqual(decision.critic.required_evidence, ("source A",))

    def test_malformed_critic_result_escalates(self):
        gate, _, _ = self.make_gate(["not json"])
        decision = gate.evaluate_turn(candidate(), context=context())
        self.assertEqual(decision.action.value, "escalate")
        self.assertEqual(decision.critic.issues[0].code, "malformed_critic_result")

    def test_attempt_budget_escalates_after_bounded_corrections(self):
        failed = '{"valid":false,"confidence":0.9,"issues":[{"code":"x","severity":"high","message":"issue"}],"corrections":["fix"],"required_evidence":[]}'
        gate, _, _ = self.make_gate([failed, failed], max_attempts=2)
        self.assertEqual(gate.evaluate_turn(candidate(), context=context()).action.value, "correct")
        self.assertEqual(gate.evaluate_turn(candidate(), context=context()).action.value, "escalate")


if __name__ == "__main__":
    unittest.main()
