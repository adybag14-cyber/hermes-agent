import json

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_android.tool_activity import tool_activity_preview


def test_preview_redacts_nested_secrets_and_bounds_data_without_mutating_result():
    result = {"password": "private-password", "nested": {"api_key": "private-key"}, "output": "x" * 100000}
    preview = tool_activity_preview(json.dumps(result), limit=300)
    assert len(preview) <= 312
    assert "private-password" not in preview
    assert "private-key" not in preview
    assert result["password"] == "private-password"
    small = tool_activity_preview({"result": {"Authorization": "Bearer private-token", "answer": "ok"}})
    assert "private-token" not in small
    assert "ok" in small


@pytest.mark.asyncio
@pytest.mark.parametrize("embedded,request_details", [(False, True), (True, False), (True, True)])
async def test_real_chat_sse_route_exposes_results_only_to_opted_in_android_client(monkeypatch, embedded, request_details):
    if embedded:
        monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    else:
        monkeypatch.delenv("HERMES_ANDROID_BOOTSTRAP", raising=False)
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "", "model_name": "test"}))

    async def run_agent(**kwargs):
        kwargs["tool_start_callback"]("call-1", "terminal", {"command": "pwd", "password": "private-password"})
        kwargs["tool_complete_callback"]("call-1", "terminal", {}, {"output": "/app/workspace", "secret": "private-result"})
        kwargs["stream_delta_callback"]("Finished.")
        return {"final_response": "Finished.", "messages": []}, {}

    monkeypatch.setattr(adapter, "_run_agent", run_agent)
    app = web.Application()
    app.router.add_post("/v1/chat/completions", adapter._handle_chat_completions)
    async with TestClient(TestServer(app)) as client:
        response = await client.post("/v1/chat/completions", headers={"X-Hermes-Tool-Activity": "v1"} if request_details else {},
                                     json={"model": "test", "messages": [{"role": "user", "content": "where am I?"}], "stream": True})
        assert response.status == 200
        body = await response.text()
    data = [json.loads(line[6:]) for line in body.splitlines() if line.startswith("data: ") and line != "data: [DONE]"]
    progress = [item for item in data if "toolCallId" in item]
    assert [(item["toolCallId"], item["status"]) for item in progress] == [("call-1", "running"), ("call-1", "completed")]
    assert ("result" in progress[1]) is (embedded and request_details)
    assert "private-password" not in body
    assert "private-result" not in body
    assert "[DONE]" in body
    assert all("workspace" not in choice.get("delta", {}).get("content", "")
               for item in data for choice in item.get("choices", []))
