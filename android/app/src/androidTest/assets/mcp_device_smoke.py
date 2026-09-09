"""Instrumentation-only fixtures; never packaged in the application APK."""
import asyncio
from contextlib import asynccontextmanager
import json
import os
from pathlib import Path
import socket
import uuid


def owner():
    from hermes_android.server_bridge import _ACTIVE_HANDLE

    assert _ACTIVE_HANDLE is not None and _ACTIVE_HANDLE.thread.is_alive()
    result = _ACTIVE_HANDLE.adapter._android_mcp
    assert result.supervisor.loop is _ACTIVE_HANDLE.loop
    return result


@asynccontextmanager
async def sdk_server(transport):
    import uvicorn
    from mcp.server import MCPServer

    fixture = MCPServer("Android installed SDK fixture", log_level="ERROR")

    @fixture.tool()
    async def echo(text: str) -> str:
        return "mcp-device-echo: " + text

    @fixture.tool()
    async def wait_for_stop() -> str:
        directory = owner().supervisor.hermes_home / "workspace"
        (directory / "mcp-chat-wait-entered").write_text("started", encoding="ascii")
        try:
            await asyncio.Event().wait()
        finally:
            (directory / "mcp-chat-wait-closed").write_text("stopped", encoding="ascii")
        return "unreachable"

    app = fixture.sse_app() if transport == "sse" else fixture.streamable_http_app()
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    server = uvicorn.Server(uvicorn.Config(app, log_level="critical", access_log=False, lifespan="on"))
    serving = asyncio.create_task(server.serve(sockets=[listener]), name="instrumentation-mcp-fixture")
    try:
        async with asyncio.timeout(10):
            while not server.started:
                if serving.done():
                    await serving
                    raise RuntimeError("Fixture stopped before startup")
                await asyncio.sleep(0.01)
        yield "http://127.0.0.1:" + str(listener.getsockname()[1]) + ("/sse" if transport == "sse" else "/mcp")
    finally:
        server.should_exit = True
        await asyncio.wait_for(serving, 10)
        listener.close()


# This small shell fixture checks Android pipe/process ownership, not server
# interoperability. HTTP/SSE here and all three host transports use the real SDK
# server. It needs no shell-visible Python or Node executable on the device.
STDIO_FIXTURE = r'''
while IFS= read -r line; do
    id=$(printf '%s' "$line" | /system/bin/sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
    case "$line" in
        *'"method":"initialize"'*)
            printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"android-shell-fixture","version":"1"}}}\n' "$id" ;;
        *'"method":"tools/list"'*)
            printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"echo","description":"fixture echo","inputSchema":{"type":"object","properties":{}}},{"name":"wait_for_stop","description":"fixture wait","inputSchema":{"type":"object","properties":{}}}]}}\n' "$id" ;;
        *'"method":"tools/call"'*'"wait_for_stop"'*) printf started > "$MCP_SMOKE_ACK" ;;
        *'"method":"tools/call"'*)
            printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"mcp-android-stdio-ok"}]}}\n' "$id" ;;
        *)
            if [ -n "$id" ]; then
                printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32601,"message":"Method not found"}}\n' "$id"
            fi ;;
    esac
done
'''


async def transport_checks(controller):
    from tools.environments import android_linux
    from tools.registry import registry

    supervisor = controller.supervisor
    receipts = []
    for transport in ("http", "sse"):
        async with sdk_server(transport) as url:
            status = await supervisor.reload({"mcpServers": {"device": {"transport": transport, "url": url}}}, enabled=True)
            connection = supervisor.connections["device"]
            if not status["servers"][0]["connected"]:
                raise connection.failure_exception
            session_id = "instrumentation-" + uuid.uuid4().hex
            bindings = await controller.snapshot(session_id, has_history=False)
            binding = next(item for item in bindings if item["tool"] == "echo")
            name = binding["definition"]["function"]["name"]
            result = await asyncio.to_thread(registry.dispatch, name, {"text": transport}, task_id=session_id)
            assert "mcp-device-echo: " + transport in result, result
            assert await controller.snapshot("existing-" + uuid.uuid4().hex, has_history=True) == []
            await supervisor.shutdown()
            assert connection.task.done() and connection.cleanup_verified
            receipts.append({"transport": transport, "registry_dispatch": True, "cleanup_verified": True})

    fixture = supervisor.hermes_home / "workspace/mcp-instrumentation-fixture.sh"
    ack = fixture.with_suffix(".ack")
    fixture.write_text(STDIO_FIXTURE, encoding="utf-8")
    try:
        config = {"mcpServers": {"device": {"transport": "stdio", "command": "/system/bin/sh",
            "args": [str(fixture)], "env": {"MCP_SMOKE_ACK": str(ack)}}}}
        status = await supervisor.reload(config, enabled=True)
        connection = supervisor.connections["device"]
        if not status["servers"][0]["connected"]:
            raise connection.failure_exception
        processes = list(android_linux._ANDROID_PROCESS_OWNERS.items())
        assert len(processes) == 1, processes
        pid, process_owner = processes[0]
        assert android_linux._process_owner_token(pid) == process_owner.owner_token
        binding = next(item for item in supervisor.catalog.values() if item["tool"] == "echo")
        assert "mcp-android-stdio-ok" in await supervisor.invoke(binding, {})
        from tools.terminal_tool import terminal_tool
        terminal = json.loads(await asyncio.to_thread(terminal_tool, "printf mcp-terminal-coexists", False, 10,
                                                       "instrumentation-terminal", True))
        assert terminal.get("exit_code") == 0 and "mcp-terminal-coexists" in terminal.get("output", ""), terminal
        pending_binding = next(item for item in supervisor.catalog.values() if item["tool"] == "wait_for_stop")
        pending = asyncio.create_task(supervisor.invoke(pending_binding, {}))
        async with asyncio.timeout(10):
            while not ack.exists():
                if pending.done():
                    await pending
                    raise AssertionError("Wait fixture did not acknowledge entry")
                await asyncio.sleep(0.02)
        pending.cancel()
        try:
            await pending
        except asyncio.CancelledError:
            pass
        assert connection.task.done() and connection.cleanup_verified
        assert not Path(f"/proc/{pid}").exists()
        assert not android_linux._ANDROID_PROCESS_OWNERS
        await supervisor.reload(config, enabled=True)
        assert supervisor.status()["servers"][0]["connected"]
        await supervisor.shutdown()
        assert not android_linux._ANDROID_PROCESS_OWNERS
        receipts.append({"transport": "stdio", "pid": pid, "terminal_coexistence": True,
                         "stop_reaped_child": True, "reload": True, "cleanup_verified": True})
    finally:
        await supervisor.shutdown()
        fixture.unlink(missing_ok=True)
        ack.unlink(missing_ok=True)
    return receipts


def run_device_checks():
    import cffi
    import cryptography
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    import jsonschema
    import rpds
    from importlib.metadata import version
    from hermes_android.runtime_capabilities import read_runtime_capabilities
    from tools.terminal_tool import terminal_tool

    ffi = cffi.FFI()
    assert ffi.new("int *", 157)[0] == 157
    aes = AESGCM(bytes(range(32)))
    encrypted = aes.encrypt(bytes(range(12)), b"android-native-crypto", b"mcp-fixture")
    assert aes.decrypt(bytes(range(12)), encrypted, b"mcp-fixture") == b"android-native-crypto"
    assert rpds.HashTrieMap({"fixture": 157})["fixture"] == 157
    jsonschema.validate({"fixture": "ok"}, {"type": "object", "required": ["fixture"]})
    controller = owner()
    resources = read_runtime_capabilities(controller.supervisor.hermes_home)
    assert resources["storage"]["writable"] and resources["storage"]["available_bytes"] > 0, resources
    assert resources["embedded_python"]["running"]
    shell_python = json.loads(terminal_tool("python3 --version", False, 15, "instrumentation-python-path", True))
    assert shell_python.get("exit_code") == 0 and "Python " in shell_python.get("output", ""), shell_python
    return json.dumps({"uid": os.getuid(), "native_imports": {name: version(name) for name in
                       ("mcp", "cffi", "cryptography", "rpds-py", "jsonschema")},
                       "resources": resources, "shell_python_probe": shell_python,
                       "transports": controller.run_sync(transport_checks(controller), timeout=100)})


_chat_fixture = None


def start_chat_fixture():
    for name in ("mcp-chat-wait-entered", "mcp-chat-wait-closed"):
        (owner().supervisor.hermes_home / "workspace" / name).unlink(missing_ok=True)

    async def start():
        global _chat_fixture
        ready, stop = asyncio.get_running_loop().create_future(), asyncio.Event()

        async def serve():
            try:
                async with sdk_server("http") as url:
                    ready.set_result(url)
                    await stop.wait()
            except BaseException as error:
                if not ready.done():
                    ready.set_exception(error)
                raise

        task = asyncio.create_task(serve(), name="instrumentation-chat-mcp-fixture")
        _chat_fixture = (task, stop)
        return await asyncio.wait_for(ready, 15)

    return owner().run_sync(start())


def stop_chat_fixture():
    async def stop():
        global _chat_fixture
        if _chat_fixture is not None:
            task, signal = _chat_fixture
            signal.set()
            await asyncio.wait_for(task, 15)
            _chat_fixture = None

    owner().run_sync(stop())


def chat_connection_stopped():
    async def inspect():
        connection = owner().supervisor.connections.get("chat")
        return connection is not None and connection.task.done() and connection.cleanup_verified

    return owner().run_sync(inspect())
