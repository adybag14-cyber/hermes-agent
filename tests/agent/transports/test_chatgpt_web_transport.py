from types import SimpleNamespace

import pytest

from agent.transports import get_transport


def response(content="hello", finish_reason="stop", tool_calls=None, refusal=None):
    return SimpleNamespace(
        choices=[SimpleNamespace(
            message=SimpleNamespace(content=content, tool_calls=tool_calls, refusal=refusal),
            finish_reason=finish_reason,
        )],
        usage=None,
    )


def test_web_transport_discovered_and_keeps_thread_payload():
    transport = get_transport("chatgpt_web")
    assert transport.api_mode == "chatgpt_web"
    kwargs = transport.build_kwargs(
        "web-model", [{"role": "user", "content": "hello"}],
        instructions="runtime", conversation_id="conversation", parent_message_id="parent",
        timeout=60, history_and_training_disabled=True,
    )
    assert kwargs["conversation_id"] == "conversation"
    assert kwargs["parent_message_id"] == "parent"
    assert kwargs["history_and_training_disabled"] is True
    assert "tools" not in kwargs


@pytest.mark.parametrize("invalid", [None, SimpleNamespace(), SimpleNamespace(choices=[])])
def test_web_transport_rejects_invalid_response(invalid):
    assert not get_transport("chatgpt_web").validate_response(invalid)


@pytest.mark.parametrize("finish_reason", ["stop", "length", "content_filter"])
def test_web_transport_preserves_finish_reason(finish_reason):
    transport = get_transport("chatgpt_web")
    raw = response(finish_reason=finish_reason)
    assert transport.validate_response(raw)
    normalized = transport.normalize_response(raw)
    assert normalized.content == "hello"
    assert normalized.finish_reason == finish_reason


def test_web_transport_keeps_real_tool_names_without_xai_alias_rewrite():
    tool_call = SimpleNamespace(
        id="tool-1", type="function",
        function=SimpleNamespace(name="hermes_tool_search", arguments='{"query":"test"}'),
    )
    normalized = get_transport("chatgpt_web").normalize_response(
        response(content="", finish_reason="tool_calls", tool_calls=[tool_call]),
    )
    assert normalized.tool_calls[0].function.name == "hermes_tool_search"
    assert normalized.tool_calls[0].function.arguments == '{"query":"test"}'


def test_web_transport_preserves_content_policy_refusal():
    normalized = get_transport("chatgpt_web").normalize_response(
        response(content=None, refusal="Unable to help with that."),
    )
    assert normalized.finish_reason == "content_filter"
    assert normalized.content == "Unable to help with that."
