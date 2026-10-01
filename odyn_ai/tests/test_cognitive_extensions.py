import unittest

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
