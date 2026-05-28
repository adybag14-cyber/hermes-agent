"""Tests for shared tool result classification helpers."""

import json

from agent.display import _detect_tool_failure
from agent.tool_guardrails import classify_tool_failure
from agent.tool_result_classification import file_mutation_result_landed


def test_write_file_with_nested_lint_error_counts_as_landed():
    result = json.dumps({
        "bytes_written": 12,
        "lint": {"status": "error", "output": "SyntaxError: invalid syntax"},
    })

    assert file_mutation_result_landed("write_file", result) is True






def test_display_and_guardrail_classifiers_share_file_mutation_landed_import():
    result = json.dumps({"bytes_written": 12})

    assert _detect_tool_failure("write_file", result) == (False, "")
    assert classify_tool_failure("write_file", result) == (False, "")


def test_side_effect_classification_keeps_session_mutations():
    from agent.tool_result_classification import tool_may_have_side_effect

    assert tool_may_have_side_effect("todo") is True
    assert tool_may_have_side_effect("memory") is True
    assert tool_may_have_side_effect("write_file") is True
    assert tool_may_have_side_effect("mcp_unknown") is True
    assert tool_may_have_side_effect("read_file") is False
    assert tool_may_have_side_effect("web_search") is False
