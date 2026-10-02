"""Integration coverage for the Hermes cognitive gate in the real agent loop.

The model and executor are faked, but run_conversation and its gate/dispatch
control flow are real. These tests assert the executor boundary directly.
"""
import json
import uuid
from contextlib import nullcontext
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


def _tool_call(query="test"):
    return SimpleNamespace(
        id=f"call_{uuid.uuid4().hex[:8]}",
        type="function",
        function=SimpleNamespace(name="web_search", arguments=json.dumps({"query": query})),
    )


def _response(*, with_tool=False, content="done", query="test", calls=None):
    message = SimpleNamespace(
        content=content,
        tool_calls=calls if calls is not None else ([_tool_call(query)] if with_tool else None),
    )
    choice = SimpleNamespace(
        message=message,
        finish_reason="tool_calls" if with_tool else "stop",
    )
    return SimpleNamespace(choices=[choice], model="test/model", usage=None)


def _create_agent(**kwargs):
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
            **kwargs,
        )
    instance.client = MagicMock()
    instance._cached_system_prompt = "You are helpful."
    instance._use_prompt_caching = False
    instance.tool_delay = 0
    instance.compression_enabled = False
    instance.save_trajectories = False
    instance._cognitive_gate_attempts = 0
    return instance


@pytest.fixture
def agent():
    return _create_agent()


def test_public_gate_configuration_controls_native_dispatch():
    gate = SimpleNamespace(evaluate_turn=MagicMock(return_value=_decision("accept")))
    instance = _create_agent(cognitive_gate=gate, cognitive_gate_max_attempts=2)
    result, calls = _run(
        instance, None, [_response(with_tool=True), _response(content="finished")],
        configure_gate=False,
    )
    assert result["final_response"] == "finished"
    assert len(calls) == 1
    gate.evaluate_turn.assert_called_once()
    assert instance._cognitive_gate_max_attempts == 2


@pytest.mark.parametrize("limit", [0, -1, True, 1.5, "3"])
def test_invalid_public_gate_budget_is_rejected(limit):
    with pytest.raises(ValueError, match="positive integer"):
        AIAgent(cognitive_gate_max_attempts=limit)


def _decision(action, reason="test decision"):
    return SimpleNamespace(action=action, reason=reason, critic=None)


def _run(agent, decisions, responses, *, mock_executor=True, configure_gate=True):
    if configure_gate:
        if decisions is not None:
            agent._cognitive_gate = SimpleNamespace(evaluate_turn=MagicMock(side_effect=decisions))
        else:
            agent._cognitive_gate = None
    agent.client.chat.completions.create.side_effect = responses
    executor_calls = []

    def fake_execute(*args, **kwargs):
        executor_calls.append(args)
        assistant, messages = args[:2]
        for call in assistant.tool_calls:
            messages.append({"role": "tool", "name": call.function.name,
                             "tool_call_id": call.id, "content": '{"ok":true}'})

    with (
        patch.object(agent, "_execute_tool_calls", side_effect=fake_execute) if mock_executor else nullcontext(),
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
    assert agent._cognitive_gate.evaluate_turn.call_count == 1
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
        "Blocked by Hermes cognitive review" in str(message.get("content", ""))
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


@pytest.mark.parametrize("action", ["correct", "retry", "retrieve_evidence"])
def test_regeneration_dispatches_only_the_accepted_new_candidate(agent, action):
    result, calls = _run(
        agent, [_decision(action), _decision("accept")],
        [_response(with_tool=True, query="rejected"),
         _response(with_tool=True, query="verified"), _response(content="finished")],
    )
    assert len(calls) == 1
    assert json.loads(calls[0][0].tool_calls[0].function.arguments) == {"query": "verified"}
    assert agent._cognitive_gate.evaluate_turn.call_count == 2
    assert result["turn_exit_reason"].startswith("text_response")


@pytest.mark.parametrize("decision", [None, {}, {"action": []}, {"action": "ACCEPT"}, RuntimeError("review failed")])
def test_malformed_or_failed_gate_never_dispatches(agent, decision):
    result, calls = _run(agent, [decision], [_response(with_tool=True)])
    assert calls == []
    assert result["turn_exit_reason"] == "cognitive_gate_escalation"


def test_missing_gate_preserves_native_dispatch(agent):
    result, calls = _run(agent, None, [_response(with_tool=True), _response(content="finished")])
    assert len(calls) == 1
    assert result["turn_exit_reason"].startswith("text_response")


def test_accepted_gate_does_not_bypass_authorization(agent):
    with patch("hermes_cli.plugins.get_pre_tool_call_block_message", return_value="Policy denied"), patch.object(agent, "_invoke_tool") as invoke:
        result, _ = _run(agent, [_decision("accept")],
                         [_response(with_tool=True), _response(content="finished")], mock_executor=False)
    invoke.assert_not_called()
    assert any("Policy denied" in str(m.get("content", "")) for m in result["messages"] if m.get("role") == "tool")


@pytest.mark.parametrize("arguments", ["[]", "null", "42", '"text"', '{"query":', None])
def test_executor_rejects_invalid_argument_envelopes_before_dispatch(agent, arguments):
    call = _tool_call()
    call.function.arguments = arguments
    message = SimpleNamespace(tool_calls=[call])
    messages = []
    with patch.object(agent, "_execute_tool_calls_sequential") as sequential, patch.object(agent, "_execute_tool_calls_concurrent") as concurrent:
        agent._execute_tool_calls(message, messages, "task-1")
    sequential.assert_not_called()
    concurrent.assert_not_called()
    assert json.loads(messages[0]["content"])["stage"] == "plan_validation"


def test_real_critic_adapter_controls_native_dispatch(agent):
    from odyn_ai.cognition import DualModelEngine, HermesDualModelGate
    primary = SimpleNamespace(model_id="primary", generate=MagicMock())
    critic = SimpleNamespace(model_id="critic", generate=MagicMock(return_value='{"valid":true,"confidence":0.95,"issues":[],"corrections":[],"required_evidence":[]}'))
    actual = HermesDualModelGate(DualModelEngine(primary, critic))
    result, calls = _run(agent, actual.evaluate_turn,
                         [_response(with_tool=True), _response(content="finished")])
    assert len(calls) == 1
    primary.generate.assert_not_called()
    critic.generate.assert_called_once()
    assert result["turn_exit_reason"].startswith("text_response")


@pytest.mark.parametrize("invalid_fields", [
    {"valid": "false"},
    {"valid": 1},
    {"confidence": "0.95"},
    {"confidence": True},
    {"issues": [{"code": "x", "severity": "low", "message": []}]},
])
def test_malformed_real_critic_cannot_authorize_native_tools(invalid_fields):
    from odyn_ai.cognition import DualModelEngine, HermesDualModelGate
    payload = {"valid": True, "confidence": 0.95, **invalid_fields}
    primary = SimpleNamespace(model_id="primary", generate=MagicMock())
    critic = SimpleNamespace(model_id="critic", generate=MagicMock(return_value=json.dumps(payload)))
    instance = _create_agent(cognitive_gate=HermesDualModelGate(DualModelEngine(primary, critic)))

    result, calls = _run(instance, None, [_response(with_tool=True)], configure_gate=False)

    assert calls == []
    assert result["turn_exit_reason"] == "cognitive_gate_escalation"
    primary.generate.assert_not_called()
    critic.generate.assert_called_once()


def test_rejected_batch_records_every_call_without_dispatch(agent):
    batch = [_tool_call("one"), _tool_call("two")]
    result, calls = _run(agent, [_decision("escalate")], [_response(with_tool=True, calls=batch)])
    assert calls == []
    tool_results = [m for m in result["messages"] if m.get("role") == "tool"]
    assert {m["tool_call_id"] for m in tool_results} == {c.id for c in batch}


def test_native_loop_rejects_non_object_arguments_before_review(agent):
    responses = []
    for _ in range(3):
        call = _tool_call()
        call.function.arguments = "[]"
        responses.append(_response(with_tool=True, calls=[call]))
    responses.append(_response(content="finished"))
    result, calls = _run(agent, [], responses)
    assert calls == []
    agent._cognitive_gate.evaluate_turn.assert_not_called()
    assert result["turn_exit_reason"].startswith("text_response")
