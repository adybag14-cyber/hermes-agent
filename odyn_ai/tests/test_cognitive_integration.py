import unittest
from unittest.mock import patch

from odyn_ai.cognition import CognitiveEngine, CognitiveRequest, DualModelEngine
from odyn_ai.cognition.hermes_execution import HermesToolExecutor
from odyn_ai.cognition.reflexion import ReflexionEngine
from odyn_ai.cognition.temporal_rag import InMemoryTemporalRAG


class Backend:
    def __init__(self, model_id, responses):
        self.model_id = model_id
        self.responses = list(responses)

    def generate(self, prompt, *, context=None):
        return self.responses.pop(0)


class CognitiveIntegrationTests(unittest.TestCase):
    def test_engine_uses_temporal_rag_for_critic_evidence_requests(self):
        rag = InMemoryTemporalRAG()
        rag.add("verified current source", source="docs", timestamp=100)
        primary = Backend("p", ["draft", "final"])
        critic = Backend("c", [
            '{"valid":false,"confidence":0.9,"issues":[],"corrections":[],"required_evidence":["verified source"]}',
            '{"valid":true,"confidence":0.9,"issues":[],"corrections":[],"required_evidence":[]}',
        ])
        engine = CognitiveEngine(
            DualModelEngine(primary, critic),
            temporal_rag=rag,
            clock=lambda: 150,
        )
        result, cycle = engine.run(CognitiveRequest("verified source"))

        self.assertEqual(result.answer, "final")
        self.assertEqual(cycle.status.value, "accepted")

    def test_reflexion_output_drives_next_primary_correction(self):
        primary = Backend("p", ["bad", "good"])
        critic = Backend("c", [
            '{"valid":false,"confidence":0.9,"issues":[{"code":"x","severity":"high","message":"missing validation"}],"corrections":[],"required_evidence":[]}',
            '{"valid":true,"confidence":0.9,"issues":[],"corrections":[],"required_evidence":[]}',
        ])
        engine = CognitiveEngine(
            DualModelEngine(primary, critic),
            reflexion=ReflexionEngine(),
        )
        result, _ = engine.run(CognitiveRequest("goal", max_corrections=1))
        self.assertEqual(result.answer, "good")

    def test_tools_execute_only_after_accepted_cycle(self):
        engine = CognitiveEngine(
            DualModelEngine(
                Backend("p", ["accepted answer"]),
                Backend("c", ['{"valid":true,"confidence":0.95,"issues":[],"corrections":[],"required_evidence":[]}']),
            ),
            tool_executor=HermesToolExecutor(lambda name, args: "ok"),
        )
        result, cycle = engine.run_and_execute(
            CognitiveRequest("build plan"),
            tool_calls=[{"name": "read_file", "arguments": {"path": "README.md"}}],
        )
        self.assertEqual(result.answer, "accepted answer")
        self.assertEqual(result.tool_results[0]["result"], "ok")

    def test_hermes_dispatch_adapter_calls_real_dispatcher(self):
        from odyn_ai.cognition.hermes_execution import hermes_dispatcher
        with patch("model_tools.handle_function_call", return_value='{"ok":true}') as dispatch:
            executor = HermesToolExecutor(hermes_dispatcher())
            result = executor.execute([{"name": "read_file", "arguments": {"path": "README.md"}}])
        self.assertEqual(result[0]["result"], '{"ok":true}')
        dispatch.assert_called_once()


if __name__ == "__main__":
    unittest.main()
