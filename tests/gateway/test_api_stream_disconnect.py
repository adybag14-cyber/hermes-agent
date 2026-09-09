"""Real idle-stream disconnects must not wait for the next buffered SSE write."""
import asyncio
import socket

from aiohttp import ClientSession, TCPConnector, web
import pytest

from gateway.platforms.api_server import APIServerAdapter


@pytest.mark.asyncio
@pytest.mark.parametrize("handler_cancellation", [False, True])
async def test_idle_chat_socket_close_interrupts_and_unwinds_before_keepalive(handler_cancellation):
    interrupted, unwound, release = asyncio.Event(), asyncio.Event(), asyncio.Event()
    adapter = object.__new__(APIServerAdapter)
    observed = {}

    class WaitingAgent:
        def interrupt(self, _message=None):
            interrupted.set()

    async def handle(request):
        observed["request"] = request
        async def run():
            try:
                await release.wait()
                return ({"final_response": "done"}, {})
            finally:
                unwound.set()

        task = asyncio.create_task(run())
        return await adapter._write_sse_chat_completion(request, "disconnect-fixture", "fixture", 0,
            asyncio.Queue(), task, [WaitingAgent()], session_id="disconnect-fixture")

    app = web.Application()
    app.router.add_post("/v1/chat/completions", handle)
    runner = web.AppRunner(app, handler_cancellation=handler_cancellation, shutdown_timeout=2)
    await runner.setup()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        await web.SockSite(runner, listener).start()
        url = "http://127.0.0.1:" + str(listener.getsockname()[1]) + "/v1/chat/completions"
        try:
            async with ClientSession(connector=TCPConnector(force_close=True)) as client:
                response = await client.post(url)
                assert (await response.content.readline()).startswith(b"data: ")
                response.close()
                try:
                    await asyncio.wait_for(interrupted.wait(), 3)
                except TimeoutError:
                    request = observed["request"]
                    pytest.fail(f"Disconnect not signalled: transport={request.transport!r}, "
                                f"closing={request.transport.is_closing() if request.transport else None}, "
                                f"payload_error={request.content.exception()!r}, unwound={unwound.is_set()}")
                await asyncio.wait_for(unwound.wait(), 3)
        finally:
            release.set()
            await runner.cleanup()
