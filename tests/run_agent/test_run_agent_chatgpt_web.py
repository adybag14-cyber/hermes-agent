import sys
import threading
import time
import types
from types import SimpleNamespace

import pytest

sys.modules.setdefault("fire", types.SimpleNamespace(Fire=lambda *a, **k: None))
sys.modules.setdefault("firecrawl", types.SimpleNamespace(Firecrawl=object))
sys.modules.setdefault("fal_client", types.SimpleNamespace())

import run_agent
import model_tools


DEFAULT_WEB_BASE = "https://chatgpt.com/backend-api/f"


def _patch_agent_bootstrap(monkeypatch):
    monkeypatch.setattr(model_tools, "get_tool_definitions", lambda **kwargs: [])
    monkeypatch.setattr(model_tools, "check_toolset_requirements", lambda: {})


def _build_agent(monkeypatch):
    _patch_agent_bootstrap(monkeypatch)

    agent = run_agent.AIAgent(
        model="gpt-5-thinking",
        provider="chatgpt-web",
        api_mode="chatgpt_web",
        base_url=DEFAULT_WEB_BASE,
        api_key="chatgpt-web-token",
        quiet_mode=True,
        max_iterations=4,
        skip_context_files=True,
        skip_memory=True,
    )
    agent._cleanup_task_resources = lambda task_id: None
    agent._persist_session = lambda messages, history=None: None
    agent._save_trajectory = lambda messages, user_message, completed: None
    agent._save_session_log = lambda messages: None
    return agent


def test_build_api_kwargs_chatgpt_web_carries_thread_state(monkeypatch):
    agent = _build_agent(monkeypatch)
    agent._chatgpt_web_conversation_id = "conv_existing"
    agent._chatgpt_web_parent_message_id = "msg_existing"

    kwargs = agent._build_api_kwargs([
        {"role": "system", "content": "Be concise."},
        {"role": "user", "content": "Hello from Hermes."},
    ])

    assert kwargs["model"] == "gpt-5-thinking"
    assert kwargs["conversation_id"] == "conv_existing"
    assert kwargs["parent_message_id"] == "msg_existing"
    assert kwargs["messages"][-1]["content"] == "Hello from Hermes."
    assert "tools" not in kwargs


def test_interruptible_api_call_chatgpt_web_returns_openai_like_response(monkeypatch):
    agent = _build_agent(monkeypatch)

    monkeypatch.setattr(
        "hermes_cli.chatgpt_web.stream_chatgpt_web_completion",
        lambda **kwargs: {
            "content": "Hello from ChatGPT web.",
            "conversation_id": "conv_123",
            "parent_message_id": "msg_456",
            "message_id": "msg_456",
            "model": "gpt-5-thinking",
            "finish_reason": "stop",
        },
    )

    response = agent._interruptible_api_call({
        "model": "gpt-5-thinking",
        "messages": [{"role": "user", "content": "hi"}],
        "conversation_id": None,
        "parent_message_id": None,
        "instructions": "Be concise.",
    })

    assert response.choices[0].message.content == "Hello from ChatGPT web."
    assert response.choices[0].finish_reason == "stop"
    assert agent._chatgpt_web_conversation_id == "conv_123"
    assert agent._chatgpt_web_parent_message_id == "msg_456"


def test_interruptible_streaming_api_call_chatgpt_web_emits_deltas(monkeypatch):
    agent = _build_agent(monkeypatch)
    deltas = []
    agent.stream_delta_callback = deltas.append

    def _fake_stream(**kwargs):
        on_delta = kwargs.get("on_delta")
        assert on_delta is not None
        on_delta("Hel")
        on_delta("lo")
        return {
            "content": "Hello",
            "conversation_id": "conv_stream",
            "parent_message_id": "msg_stream",
            "message_id": "msg_stream",
            "model": "gpt-5-thinking",
            "finish_reason": "stop",
        }

    monkeypatch.setattr(
        "hermes_cli.chatgpt_web.stream_chatgpt_web_completion",
        _fake_stream,
    )

    response = agent._interruptible_streaming_api_call({
        "model": "gpt-5-thinking",
        "messages": [{"role": "user", "content": "hi"}],
        "conversation_id": None,
        "parent_message_id": None,
        "instructions": "Be concise.",
    })

    assert deltas == ["Hel", "lo"]
    assert response.choices[0].message.content == "Hello"
    assert agent._chatgpt_web_conversation_id == "conv_stream"
    assert agent._chatgpt_web_parent_message_id == "msg_stream"


def test_interruptible_streaming_api_call_chatgpt_web_fires_first_delta_without_text_arg(monkeypatch):
    agent = _build_agent(monkeypatch)
    deltas = []
    first_delta_calls = []
    agent.stream_delta_callback = deltas.append

    def _fake_stream(**kwargs):
        on_delta = kwargs.get("on_delta")
        assert on_delta is not None
        on_delta("Hel")
        on_delta("lo")
        return {
            "content": "Hello",
            "conversation_id": "conv_first",
            "parent_message_id": "msg_first",
            "message_id": "msg_first",
            "model": "gpt-5-thinking",
            "finish_reason": "stop",
        }

    monkeypatch.setattr(
        "hermes_cli.chatgpt_web.stream_chatgpt_web_completion",
        _fake_stream,
    )

    response = agent._interruptible_streaming_api_call(
        {
            "model": "gpt-5-thinking",
            "messages": [{"role": "user", "content": "hi"}],
            "conversation_id": None,
            "parent_message_id": None,
            "instructions": "Be concise.",
        },
        on_first_delta=lambda: first_delta_calls.append("fired"),
    )

    assert first_delta_calls == ["fired"]
    assert deltas == ["Hel", "lo"]
    assert response.choices[0].message.content == "Hello"


def test_interruptible_api_call_chatgpt_web_closes_request_client_on_interrupt(monkeypatch):
    agent = _build_agent(monkeypatch)
    entered = threading.Event()
    abort_seen = threading.Event()
    interrupt_seen = threading.Event()
    close_seen = threading.Event()
    created_clients = []
    close_reasons = []
    abort_threads = []
    close_threads = []
    interrupt_errors = []

    class _FakeClient:
        def __init__(self, *args, **kwargs):
            self.closed = False
            self.owner_tid = threading.get_ident()

        def close(self):
            close_threads.append(threading.get_ident())
            assert threading.get_ident() == self.owner_tid
            self.closed = True
            close_seen.set()

    def _fake_client_factory(*args, **kwargs):
        client = _FakeClient(*args, **kwargs)
        created_clients.append(client)
        return client

    def _fake_close_request_client(client, *, reason):
        close_reasons.append(reason)
        client.close()

    def _fake_force_close_tcp_sockets(client):
        abort_threads.append(threading.get_ident())
        assert client is created_clients[0]
        assert threading.get_ident() != client.owner_tid
        assert not client.closed
        abort_seen.set()
        return 1

    def _fake_stream(**kwargs):
        client = kwargs.get("client")
        assert client is created_clients[0]
        entered.set()
        assert abort_seen.wait(timeout=5), "Blocked stream did not receive a transport abort"
        raise RuntimeError("aborted")

    monkeypatch.setattr("httpx.Client", _fake_client_factory)
    monkeypatch.setattr(
        "hermes_cli.chatgpt_web.stream_chatgpt_web_completion",
        _fake_stream,
    )
    monkeypatch.setattr(agent, "_close_request_openai_client", _fake_close_request_client)
    monkeypatch.setattr(agent, "_force_close_tcp_sockets", _fake_force_close_tcp_sockets)

    def _interrupt_once_stream_starts():
        if not entered.wait(timeout=5):
            interrupt_errors.append("Stream did not start")
            return
        agent._interrupt_requested = True
        interrupt_seen.set()

    interrupter = threading.Thread(target=_interrupt_once_stream_starts, daemon=True)
    interrupter.start()

    try:
        with pytest.raises(InterruptedError, match="Agent interrupted during API call"):
            agent._interruptible_api_call({
                "model": "gpt-5-thinking",
                "messages": [{"role": "user", "content": "hi"}],
                "conversation_id": None,
                "parent_message_id": None,
                "instructions": "Be concise.",
            })
    finally:
        # Unblock the fake transport even if an assertion fails, so the
        # request worker can still execute its owner-thread cleanup.
        abort_seen.set()
        interrupter.join(timeout=5)

    assert not interrupter.is_alive()
    assert not interrupt_errors
    assert interrupt_seen.is_set()
    assert close_seen.wait(timeout=5)
    assert created_clients
    assert abort_threads == [threading.get_ident()]
    assert close_threads == [created_clients[0].owner_tid]
    assert close_reasons == ["request_error_cleanup"]
    assert created_clients[0].closed is True
    assert agent._chatgpt_web_conversation_id is None
    assert agent._chatgpt_web_parent_message_id is None
