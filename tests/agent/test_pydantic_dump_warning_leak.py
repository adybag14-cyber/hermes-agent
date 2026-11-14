"""Serializer-warning leak regression tests (#82xxx).

Older Anthropic SDK streaming snapshots emitted pydantic union warnings.
The current SDK fixed that mismatch. Keep genuine stream snapshots in these
tests so serialization retains their content and stays quiet, alongside the
helpers' compatibility with duck-typed models.
"""

import warnings

import pytest

anthropic = pytest.importorskip("anthropic")

from anthropic._models import build
from anthropic.lib.streaming._messages import accumulate_event
from anthropic.lib.streaming._types import ParsedMessageStopEvent
from anthropic.types import (
    Message,
    RawContentBlockDeltaEvent,
    RawContentBlockStartEvent,
    RawMessageStartEvent,
    Usage,
)


def _accumulated_stop_event():
    """Build a message_stop event exactly the way the SDK stream does."""
    start = RawMessageStartEvent(
        type="message_start",
        message=Message(
            id="msg_1",
            content=[],
            model="claude-x",
            role="assistant",
            stop_reason=None,
            stop_sequence=None,
            type="message",
            usage=Usage(input_tokens=1, output_tokens=0),
        ),
    )
    cb_start = RawContentBlockStartEvent(
        type="content_block_start", index=0, content_block={"type": "text", "text": ""}
    )
    cb_delta = RawContentBlockDeltaEvent(
        type="content_block_delta",
        index=0,
        delta={"type": "text_delta", "text": "hello"},
    )
    snapshot = None
    json_bufs = {}
    for event in (start, cb_start, cb_delta):
        snapshot = accumulate_event(event=event, current_snapshot=snapshot, json_bufs=json_bufs)
    return build(ParsedMessageStopEvent, type="message_stop", message=snapshot), snapshot


def _pydantic_warnings(recorded):
    return [w for w in recorded if "Pydantic serializer warnings" in str(w.message)]


def test_current_sdk_stop_event_serializes_without_warning():
    """The current SDK fixed the ParsedMessage union warning; retain real output coverage."""
    stop_event, _ = _accumulated_stop_event()
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter("always")
        payload = stop_event.model_dump()
    assert payload["message"]["content"][0]["text"] == "hello"
    assert not _pydantic_warnings(recorded)


def test_relay_llm_jsonable_no_warning_leak():
    from agent.relay_llm import _jsonable

    stop_event, _ = _accumulated_stop_event()
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter("always")
        payload = _jsonable(stop_event)
    assert not _pydantic_warnings(recorded)
    assert payload["message"]["content"][0]["text"] == "hello"


def test_relay_tools_jsonable_no_warning_leak():
    from agent.relay_tools import _jsonable

    stop_event, _ = _accumulated_stop_event()
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter("always")
        payload = _jsonable(stop_event)
    assert not _pydantic_warnings(recorded)
    assert payload["message"]["content"][0]["text"] == "hello"


def test_anthropic_to_plain_data_no_warning_leak():
    from agent.anthropic_message_convert import _to_plain_data

    _, snapshot = _accumulated_stop_event()
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter("always")
        payload = _to_plain_data(snapshot)
    assert not _pydantic_warnings(recorded)
    assert payload["content"][0]["text"] == "hello"


def test_hook_jsonable_no_warning_leak():
    from run_agent import AIAgent

    stop_event, _ = _accumulated_stop_event()
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter("always")
        payload = AIAgent._hook_jsonable(stop_event)
    assert not _pydantic_warnings(recorded)
    assert payload["message"]["content"][0]["text"] == "hello"


def test_duck_typed_model_dump_fallback():
    """Non-pydantic objects with a bare model_dump() must still serialize."""
    from agent.relay_llm import _jsonable as rl_jsonable
    from agent.relay_tools import _jsonable as rt_jsonable
    from agent.anthropic_message_convert import _to_plain_data

    class Duck:
        def model_dump(self):  # no mode/warnings kwargs
            return {"quack": True}

    assert rl_jsonable(Duck()) == {"quack": True}
    assert rt_jsonable(Duck()) == {"quack": True}
    assert _to_plain_data(Duck()) == {"quack": True}
