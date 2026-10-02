"""Integration coverage for the Hermes cognitive gate in the real agent loop.

The model and executor are faked, but run_conversation and its gate/dispatch
control flow are real. These tests assert the executor boundary directly.
"""
import json
import uuid
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from run_agent import AIAgent


def _tool_defs():
    return [{
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Search the web",
            "parameters": {"type": "object", "properties": {}},
        },
    }]


def _tool_call():
    return SimpleNamespace(
        id=f"call_{uuid.uuid4().hex[:8]}",
        type="function",
        function=SimpleNamespace(name="web_search", arguments=json.dumps({"query": "test"})),
    )


def _response(*, with_tool=False, content="done"):
    message = SimpleNamespace(
        content=content,
        tool_calls=[_tool_call()] if with_tool else None,
    )
    choice = SimpleNamespace(
        message=message,
        finish_reason="tool_calls" if with_tool else "stop",
    )
    return SimpleNamespace(choices=[choice], model="test/model", usage=None)


@pytest.fixture
def agent():
    with (
        patch("run_agent.get_tool_definitions", return_value=_tool_defs()),
        patch("run_agent.check_toolset_requirements", return_value={}),
        patch("run_agent.OpenAI"),
    ):
        instance = AIAgent(
            api_key="test-key-1234567890",
            base_url="https://openrouter.ai/api/v1",
            max_iterations=5,
            quiet_mode=True,
            skip_context_files=True,
            skip_memory=True,
        )
    instance.client = MagicMock()
    instance._cached_system_prompt = "You are helpful."
    instance._use_prompt_caching = False
    instance.tool_delay = 0
    instance.compression_enabled = False
    instance.save_trajectories = False
    instance._cognitive_gate_attempts = 0
    instance._cognitive_gate_max_attempts = 3
    return instance


def _decision(action, reason="test decision"):
    return SimpleNamespace(action=action, reason=reason, critic=None)


def _run(agent, decisions, responses):
    agent.client.chat.completions.create.side_effect = responses
    executor_calls = []

    def fake_execute(*args, **kwargs):
        executor_calls.append(args)

    with (
        patch("agent.cognitive_gate.evaluate_hermes_turn", side_effect=decisions),
        patch.object(agent, "_execute_tool_calls", side_effect=fake_execute),
        patch.object(agent, "_persist_session"),
        patch.object(agent, "_save_trajectory"),
        patch.object(agent, "_cleanup_task_resources"),
    ):
        result = agent.run_conversation("perform a search")

    return result, executor_calls


def test_accept_allows_dispatch(agent):
    result, calls = _run(
        agent,
        [_decision("accept")],
        [_response(with_tool=True), _response(content="finished")],
    )
    assert len(calls) == 1
    assert result["turn_exit_reason"].startswith("text_response")


@pytest.mark.parametrize("action", ["correct", "retry", "retrieve_evidence"])
def test_non_accept_retry_actions_do_not_dispatch_candidate(agent, action):
    result, calls = _run(
        agent,
        [_decision(action), _decision("escalate")],
        [_response(with_tool=True), _response(with_tool=True)],
    )
    assert calls == []
    assert result["turn_exit_reason"] == "cognitive_gate_escalation"
    assert any(
        "Blocked by ODYN cognitive review" in str(message.get("content", ""))
        for message in result["messages"]
        if message.get("role") == "tool"
    )


def test_escalate_does_not_dispatch(agent):
    result, calls = _run(
        agent,
        [_decision("escalate")],
        [_response(with_tool=True)],
    )
    assert calls == []
    assert result["turn_exit_reason"] == "cognitive_gate_escalation"


def test_unknown_decision_is_fail_closed(agent):
    result, calls = _run(
        agent,
        [_decision("unexpected-action")],
        [_response(with_tool=True)],
    )
    assert calls == []
    assert result["turn_exit_reason"] == "cognitive_gate_escalation"


def test_retry_attempt_exhaustion_does_not_dispatch(agent):
    agent._cognitive_gate_max_attempts = 1
    result, calls = _run(
        agent,
        [_decision("correct")],
        [_response(with_tool=True)],
    )
    assert calls == []
    assert result["turn_exit_reason"] == "cognitive_gate_escalation"
