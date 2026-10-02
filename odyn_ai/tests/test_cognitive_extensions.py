import unittest
from unittest.mock import Mock

from odyn_ai.cognition.hermes_execution import HermesToolExecutor
from odyn_ai.cognition.reflexion import ReflexionEngine
from odyn_ai.cognition.temporal_rag import InMemoryTemporalRAG


class TemporalRAGTests(unittest.TestCase):
    def test_retrieves_recent_evidence_and_excludes_future_items(self):
        rag = InMemoryTemporalRAG()
        rag.add("old", source="a", timestamp=100.0)
        rag.add("recent", source="b", timestamp=200.0)
        rag.add("future", source="c", timestamp=300.0)

        results = rag.retrieve("recent", as_of=250.0, limit=2)

        self.assertEqual([item.content for item in results], ["recent", "old"])


class ReflexionTests(unittest.TestCase):
    def test_builds_reflection_from_critic_and_evidence(self):
        engine = ReflexionEngine()
        result = engine.reflect(
            goal="solve",
            answer="bad answer",
            critic={"issues": [{"message": "missing source"}]},
            evidence={"source": "verified"},
        )

        self.assertIn("missing source", result.reflection)
        self.assertIn("verified", result.next_correction)


class HermesToolExecutorTests(unittest.TestCase):
    def test_invalid_later_call_blocks_the_entire_batch(self):
        for invalid in (None, {"name": ""}, {"name": "write_file", "arguments": []},
                        {"name": "write_file", "arguments": {"value": float("nan")}}):
            with self.subTest(call=invalid):
                dispatch = Mock()
                executor = HermesToolExecutor(dispatch)
                with self.assertRaises(ValueError):
                    executor.execute([
                        {"name": "write_file", "arguments": {"path": "first.txt"}},
                        invalid,
                    ])
                dispatch.assert_not_called()

    def test_dispatcher_cannot_mutate_the_callers_nested_plan(self):
        plan = [{"name": "write_file", "arguments": {"options": {"path": "original.txt"}}}]

        def dispatch(name, arguments):
            arguments["options"]["path"] = "changed.txt"
            return "ok"

        HermesToolExecutor(dispatch).execute(plan)
        self.assertEqual(plan[0]["arguments"]["options"]["path"], "original.txt")

    def test_executes_explicit_tool_calls_through_hermes_dispatch(self):
        calls = []

        def dispatch(name, arguments):
            calls.append((name, arguments))
            return '{"ok":true}'

        executor = HermesToolExecutor(dispatch)
        results = executor.execute(
            [{"name": "read_file", "arguments": {"path": "README.md"}}]
        )

        self.assertEqual(calls, [("read_file", {"path": "README.md"})])
        self.assertEqual(results[0]["result"], '{"ok":true}')


if __name__ == "__main__":
    unittest.main()
