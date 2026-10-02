import unittest
from copy import deepcopy
from unittest.mock import Mock, patch

from odyn_ai.cognition import CognitiveEngine, CognitiveRequest, DualModelEngine
from odyn_ai.cognition.cognitive_engine import CognitiveEngineError
from odyn_ai.cognition.hermes_execution import HermesToolExecutor
from odyn_ai.cognition.reflexion import ReflexionEngine
from odyn_ai.cognition.temporal_rag import InMemoryTemporalRAG


class Backend:
    def __init__(self, model_id, responses):
        self.model_id = model_id
        self.responses = list(responses)
        self.contexts = []

    def generate(self, prompt, *, context=None):
        self.contexts.append(deepcopy(context))
        return self.responses.pop(0)


class CognitiveIntegrationTests(unittest.TestCase):
    def test_retrieved_evidence_cannot_replace_the_plan_before_approval(self):
        plan = [{"name": "write_file", "arguments": {"path": "protected.txt"}}]

        class PlanCheckingCritic(Backend):
            def generate(self, prompt, *, context=None):
                self.contexts.append(deepcopy(context))
                if not context.get("proof"):
                    return '{"valid":false,"confidence":0.99,"required_evidence":["proof"]}'
                if context["tool_calls"][0]["name"] == "write_file":
                    return '{"valid":false,"confidence":0.99,"issues":[{"code":"forbidden_write","severity":"critical","message":"writes are forbidden"}]}'
                return '{"valid":true,"confidence":0.99}'

        def retrieve(keys, context):
            context["tool_calls"][0]["arguments"]["path"] = "different.txt"
            return {"proof": "fetched", "tool_calls": [{"name": "read_file", "arguments": {}}]}

        critic = PlanCheckingCritic("c", [])
        dispatch = Mock()
        engine = CognitiveEngine(
            DualModelEngine(Backend("p", ["draft", "revised"]), critic),
            evidence_retriever=retrieve, tool_executor=HermesToolExecutor(dispatch),
        )

        with self.assertRaises(CognitiveEngineError):
            engine.run_and_execute(CognitiveRequest("inspect files"), tool_calls=plan)

        dispatch.assert_not_called()
        self.assertEqual([c["tool_calls"] for c in critic.contexts], [plan, plan])
        self.assertEqual(critic.contexts[1]["proof"], "fetched")

    def test_critic_rejects_the_actual_plan_instead_of_a_stale_request_plan(self):
        plan = [{"name": "write_file", "arguments": {"path": "actual.txt"}}]
        context = {"tool_calls": [{"name": "read_file", "arguments": {"path": "stale.txt"}}]}
        primary = Backend("p", ["safe-sounding answer"])
        critic = Backend("c", ['{"valid":false,"confidence":0.99,"issues":[{"code":"unsafe_write","severity":"critical","message":"write is forbidden"}]}'])
        dispatch = Mock()
        engine = CognitiveEngine(DualModelEngine(primary, critic),
                                 tool_executor=HermesToolExecutor(dispatch))

        with self.assertRaises(CognitiveEngineError):
            engine.run_and_execute(CognitiveRequest("inspect files", context=context), tool_calls=plan)

        dispatch.assert_not_called()
        self.assertEqual(critic.contexts[0]["tool_calls"], plan)
        self.assertEqual(context["tool_calls"][0]["name"], "read_file")

    def test_primary_context_mutation_cannot_change_the_reviewed_or_executed_plan(self):
        class MutatingPrimary(Backend):
            def generate(self, prompt, *, context=None):
                context["tool_calls"][0]["arguments"]["path"] = "unreviewed.txt"
                context["preferences"]["mode"] = "changed"
                return super().generate(prompt, context=context)

        plan = [{"name": "read_file", "arguments": {"path": "reviewed.txt"}}]
        context = {"preferences": {"mode": "original"}}
        critic = Backend("c", ['{"valid":true,"confidence":0.99}'])
        dispatch = Mock(return_value="ok")
        engine = CognitiveEngine(
            DualModelEngine(MutatingPrimary("p", ["answer"]), critic),
            tool_executor=HermesToolExecutor(dispatch),
        )

        engine.run_and_execute(CognitiveRequest("read", context=context), tool_calls=plan)

        self.assertEqual(critic.contexts[0]["tool_calls"], plan)
        dispatch.assert_called_once_with("read_file", {"path": "reviewed.txt"})
        self.assertEqual(context, {"preferences": {"mode": "original"}})

    def test_invalid_plan_is_blocked_before_inference_or_dispatch(self):
        primary = Backend("p", [])
        critic = Backend("c", [])
        dispatch = Mock()
        engine = CognitiveEngine(DualModelEngine(primary, critic),
                                 tool_executor=HermesToolExecutor(dispatch))
        with self.assertRaises(ValueError):
            engine.run_and_execute(CognitiveRequest("write files"), tool_calls=[
                {"name": "write_file", "arguments": {"path": "first.txt"}},
                {"name": "write_file", "arguments": "invalid"},
            ])
        self.assertEqual(primary.contexts, [])
        self.assertEqual(critic.contexts, [])
        dispatch.assert_not_called()

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

    def test_required_evidence_prevents_acceptance_even_for_valid_low_severity_critic(self):
        rag = InMemoryTemporalRAG()
        rag.add("evidence payload", source="unit-test", timestamp=100)
        primary = Backend("p", ["draft", "final"])
        critic = Backend("c", [
            '{"valid":true,"confidence":0.99,"issues":[],"corrections":[],"required_evidence":["source-key"]}',
            '{"valid":true,"confidence":0.99,"issues":[],"corrections":[],"required_evidence":[]}',
        ])
        engine = CognitiveEngine(
            DualModelEngine(primary, critic),
            temporal_rag=rag,
            clock=lambda: 150,
        )

        result, cycle = engine.run(CognitiveRequest("source-key"))

        self.assertEqual(result.answer, "final")
        self.assertEqual(cycle.history[0].action.value, "retrieve_evidence")
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
        result = engine.run_and_execute(
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
