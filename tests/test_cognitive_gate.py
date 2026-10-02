import unittest
from types import SimpleNamespace

from agent.cognitive_gate import evaluate_hermes_turn


class FakeGate:
    def __init__(self, decision):
        self.decision = decision
        self.calls = []

    def evaluate_turn(self, candidate, *, context):
        self.calls.append((candidate, context))
        return self.decision


class HermesCognitiveGateContractTests(unittest.TestCase):
    def test_gate_receives_actual_model_candidate_and_context(self):
        decision = {"action": "accept"}
        gate = FakeGate(decision)
        agent = SimpleNamespace(_cognitive_gate=gate, session_id="session-1")
        call = SimpleNamespace(
            id="call-1",
            function=SimpleNamespace(
                name="read_file",
                arguments='{"path":"README.md"}',
            ),
        )
        assistant = SimpleNamespace(
            content="I will inspect the README.",
            tool_calls=[call],
        )
        messages = [{"role": "user", "content": "Inspect the project"}]

        result = evaluate_hermes_turn(
            agent, assistant, messages, "task-1", "tool_calls"
        )

        self.assertIs(result, decision)
        self.assertIs(agent._last_cognitive_gate_decision, decision)
        candidate, context = gate.calls[0]
        self.assertEqual(candidate.content, "I will inspect the README.")
        self.assertEqual(candidate.tool_calls[0]["name"], "read_file")
        self.assertEqual(candidate.tool_calls[0]["arguments"], '{"path":"README.md"}')
        self.assertEqual(context.task_id, "task-1")
        self.assertEqual(context.session_id, "session-1")
        self.assertEqual(context.messages[0]["content"], "Inspect the project")

    def test_missing_gate_preserves_existing_hermes_flow(self):
        agent = SimpleNamespace(session_id="session-2")
        assistant = SimpleNamespace(content="answer", tool_calls=[])

        result = evaluate_hermes_turn(agent, assistant, [], None, "stop")

        self.assertIsNone(result)
        self.assertFalse(hasattr(agent, "_last_cognitive_gate_decision"))

    def test_invalid_gate_contract_fails_explicitly(self):
        agent = SimpleNamespace(_cognitive_gate=object(), session_id=None)
        assistant = SimpleNamespace(content="answer", tool_calls=[])

        with self.assertRaisesRegex(TypeError, "evaluate_turn"):
            evaluate_hermes_turn(agent, assistant, [], None, "stop")


if __name__ == "__main__":
    unittest.main()
