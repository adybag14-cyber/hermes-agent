"""Focused regression tests for the excerpt-first session search fast path."""

import asyncio
import json
from unittest.mock import MagicMock

from tools.session_search_tool import (
    _build_search_excerpt,
    _format_search_context,
    session_search,
)


def test_format_search_context_supports_string_and_message_lists():
    assert _format_search_context("  raw context  ") == "raw context"
    assert _format_search_context([
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "world"},
    ]) == "[USER]: hello\n\n[ASSISTANT]: world"
    assert _format_search_context(None) == ""


def test_build_search_excerpt_deduplicates_repeated_match_windows():
    rows = [
        {
            "snippet": "deploy >>>error<<< message",
            "context": [
                {"role": "user", "content": "show me the deploy error"},
                {"role": "assistant", "content": "here is the traceback"},
            ],
        },
        {
            "snippet": "deploy >>>error<<< message",
            "context": [
                {"role": "user", "content": "show me the deploy error"},
                {"role": "assistant", "content": "here is the traceback"},
            ],
        },
    ]

    excerpt = _build_search_excerpt(rows, "deploy error")

    assert excerpt.startswith("Search query: deploy error")
    assert excerpt.count("[MATCH") == 1
    assert "Nearby conversation" in excerpt


def test_session_search_uses_excerpt_before_loading_full_transcript(monkeypatch):
    captured = {}

    async def fake_summarize(text, query, meta):
        captured["text"] = text
        captured["query"] = query
        captured["meta"] = meta
        return "summary"

    monkeypatch.setattr("tools.session_search_tool._summarize_session", fake_summarize)
    monkeypatch.setattr("model_tools._run_async", lambda coro: asyncio.run(coro))

    mock_db = MagicMock()
    mock_db.search_messages.return_value = [
        {
            "session_id": "child-session",
            "source": "cli",
            "session_started": 1709500000,
            "model": "test-model",
            "snippet": "docker >>>compose<<< timeout",
            "context": [
                {"role": "user", "content": "docker compose is timing out"},
                {"role": "assistant", "content": "check the network bridge"},
            ],
        },
        {
            "session_id": "child-session",
            "source": "cli",
            "session_started": 1709500000,
            "model": "test-model",
            "snippet": "docker >>>compose<<< timeout",
            "context": [
                {"role": "user", "content": "docker compose is timing out"},
                {"role": "assistant", "content": "check the network bridge"},
            ],
        },
    ]

    def _get_session(session_id):
        if session_id == "child-session":
            return {
                "id": "child-session",
                "parent_session_id": "root-session",
                "source": "cli",
                "started_at": 1709500000,
                "model": "test-model",
            }
        if session_id == "root-session":
            return {
                "id": "root-session",
                "parent_session_id": None,
                "source": "cli",
                "started_at": 1709400000,
                "model": "test-model",
            }
        return None

    mock_db.get_session.side_effect = _get_session

    result = json.loads(session_search(query="docker compose", db=mock_db, limit=1))

    assert result["success"] is True
    assert result["count"] == 1
    assert result["results"][0]["summary"] == "summary"
    assert "Conversation excerpts around the strongest full-text matches" in captured["text"]
    assert captured["text"].count("[MATCH") == 1
    mock_db.get_messages_as_conversation.assert_not_called()


def test_session_search_falls_back_to_full_transcript_when_excerpt_is_empty(monkeypatch):
    async def fake_summarize(text, _query, _meta):
        return text

    monkeypatch.setattr("tools.session_search_tool._summarize_session", fake_summarize)
    monkeypatch.setattr("model_tools._run_async", lambda coro: asyncio.run(coro))

    mock_db = MagicMock()
    mock_db.search_messages.return_value = [
        {
            "session_id": "root-session",
            "source": "cli",
            "session_started": 1709500000,
            "model": "test-model",
            "snippet": "",
            "context": [],
            "content": "",
        },
    ]
    mock_db.get_session.return_value = {
        "id": "root-session",
        "parent_session_id": None,
        "source": "cli",
        "started_at": 1709500000,
        "model": "test-model",
    }
    mock_db.get_messages_as_conversation.return_value = [
        {"role": "user", "content": "full transcript user turn"},
        {"role": "assistant", "content": "full transcript assistant turn"},
    ]

    result = json.loads(session_search(query="full transcript", db=mock_db, limit=1))

    assert result["success"] is True
    assert "[USER]: full transcript user turn" in result["results"][0]["summary"]
    mock_db.get_messages_as_conversation.assert_called_once_with(
        "root-session",
        include_ancestors=False,
    )
