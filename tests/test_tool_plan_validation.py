"""Tests for generated tool-plan argument validation."""
import unittest

from agent.tool_plan_validation import ToolPlanValidationError, parse_tool_plan_arguments


class ToolPlanValidationTests(unittest.TestCase):
    def test_parses_object(self):
        self.assertEqual(parse_tool_plan_arguments('{"path":"README.md"}'), {"path":"README.md"})

    def test_rejects_malformed_json(self):
        with self.assertRaises(ToolPlanValidationError):
            parse_tool_plan_arguments('{"path":')

    def test_rejects_non_object_json(self):
        for payload in ('[]', 'null', '"text"', '42'):
            with self.subTest(payload=payload), self.assertRaises(ToolPlanValidationError):
                parse_tool_plan_arguments(payload)

    def test_rejects_non_string_input(self):
        with self.assertRaises(ToolPlanValidationError):
            parse_tool_plan_arguments(None)

    def test_rejects_non_finite_numbers_at_any_depth(self):
        for value in ("NaN", "Infinity", "-Infinity", "1e999"):
            with self.subTest(value=value), self.assertRaises(ToolPlanValidationError):
                parse_tool_plan_arguments('{"options":{"values":[' + value + ']}}')

    def test_preserves_finite_numeric_arguments(self):
        self.assertEqual(parse_tool_plan_arguments('{"ratio":0.5,"offset":-2,"enabled":true}'),
                         {"ratio": 0.5, "offset": -2, "enabled": True})


if __name__ == "__main__":
    unittest.main()
