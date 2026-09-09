import asyncio
from contextlib import asynccontextmanager
import json
import os
from pathlib import Path
import sys
import socket

import pytest

from hermes_android.mcp_config import parse_servers, validate_schema
from hermes_android.mcp_supervisor import McpSupervisor
from tools.environments import android_linux


@pytest.fixture(autouse=True)
def _host_app_uid_scope(monkeypatch):
    # Android gives the app a private UID. Parallel host pytest files share a
    # UID, so model that boundary here while retaining real /proc identities
    # and token checks. Unmarked-process refusal is covered separately in
    # test_android_linux_environment; installed-app tests use the real UID.
    inventory = android_linux._same_uid_process_ids
    baseline = None

    def app_processes():
        nonlocal baseline
        current = inventory()
        if current is None:
            return None
        if baseline is None:
            baseline = set(current)
        tokens = {owner.owner_token for owner in android_linux._ANDROID_PROCESS_OWNERS.values()}
        return {pid for pid in current if pid in baseline or android_linux._process_owner_token(pid) in tokens}

    monkeypatch.setattr(android_linux, "_same_uid_process_ids", app_processes)


@asynccontextmanager
async def running_sdk_http(transport, *, oversized=False):
    import uvicorn
    from mcp.server import MCPServer

    fixture = MCPServer("Android HTTP lifecycle fixture", log_level="ERROR")

    @fixture.tool()
    async def echo(text: str) -> str:
        return "http fixture: " + text

    if oversized:
        @fixture.tool()
        async def oversized_result() -> str:
            return "x" * (2 * 1024 * 1024)

    app = fixture.sse_app() if transport == "sse" else fixture.streamable_http_app()
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    server = uvicorn.Server(uvicorn.Config(app, log_level="critical", access_log=False, lifespan="on"))
    serving = asyncio.create_task(server.serve(sockets=[listener]))
    try:
        async with asyncio.timeout(10):
            while not server.started:
                if serving.done():
                    await serving
                    raise RuntimeError("HTTP fixture exited before startup")
                await asyncio.sleep(0.01)
        suffix = "/sse" if transport == "sse" else "/mcp"
        yield "http://127.0.0.1:" + str(listener.getsockname()[1]) + suffix
    finally:
        server.should_exit = True
        await asyncio.wait_for(serving, 10)
        listener.close()


def fixture_config():
    return {"mcpServers": {"fixture": {"transport": "stdio", "command": sys.executable,
        "args": [str(Path(__file__).parent / "fixtures/mcp_sdk_server.py")]}}}


def test_mcp_config_rejects_credential_leaks_and_external_schema_fetches():
    for url in ("https://user:secret@example.test/mcp", "file:///private/key", "https://example.test/mcp#fragment"):
        with pytest.raises(ValueError):
            parse_servers({"mcpServers": {"private": {"transport": "http", "url": url}}})
    with pytest.raises(ValueError):
        parse_servers({"mcpServers": {"private": {"transport": "http", "url": "http://example.test/mcp",
                                                 "headers": {"Authorization": "private"}}}})
    with pytest.raises(ValueError):
        validate_schema({"properties": {"field": {"$ref": "https://example.test/schema"}}})
    validate_schema({"properties": {"field": {"$ref": "#/$defs/local"}}})
    for key in ("enabled", "autoStart"):
        with pytest.raises(ValueError, match="JSON booleans"):
            parse_servers({"mcpServers": {"fixture": {"command": "/system/bin/sh", key: "false"}}})


@pytest.mark.linux_only
@pytest.mark.asyncio
async def test_real_sdk_stdio_call_and_reload_keep_exact_android_process_ownership(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(tmp_path))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/bin/sh")
    supervisor = McpSupervisor(tmp_path)
    owned_pid = None
    try:
        status = await supervisor.reload(fixture_config(), enabled=True)
        if not status["servers"][0]["connected"]:
            raise supervisor.connections["fixture"].failure_exception
        binding = next(item for item in supervisor.catalog.values() if item["tool"] == "echo")
        assert "fixture: hello" in await supervisor.invoke(binding, {"text": "hello"})
        owners = list(android_linux._ANDROID_PROCESS_OWNERS.items())
        assert len(owners) == 1
        owned_pid, owner = owners[0]
        assert owner.kind == "mcp"
        assert android_linux._process_owner_token(owned_pid) == owner.owner_token
        assert android_linux._android_process_admission_error() == ""
        terminal = android_linux.AndroidLinuxEnvironment(cwd=str(tmp_path))
        terminal.process_shell_path = "/bin/sh"
        command = terminal._run_bash("printf terminal-owned")
        terminal_result = await asyncio.to_thread(terminal._wait_for_process, command, timeout=5)
        assert terminal_result["returncode"] == 0
        assert "terminal-owned" in terminal_result["output"]
        assert "fixture: after terminal" in await supervisor.invoke(binding, {"text": "after terminal"})
        before_definition = json.dumps(binding, sort_keys=True)
        await supervisor.reload({}, enabled=False)
        with pytest.raises(RuntimeError, match="disabled"):
            await supervisor.invoke(binding, {"text": "must not run"})
        assert json.dumps(binding, sort_keys=True) == before_definition
        assert not android_linux._ANDROID_PROCESS_OWNERS
        assert not Path(f"/proc/{owned_pid}").exists()
    finally:
        await supervisor.shutdown()


@pytest.mark.linux_only
@pytest.mark.asyncio
async def test_stop_during_real_sdk_tool_wait_closes_child_before_caller_unwinds(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(tmp_path))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/bin/sh")
    supervisor = McpSupervisor(tmp_path, call_timeout=10)
    try:
        status = await supervisor.reload(fixture_config(), enabled=True)
        assert status["servers"][0]["connected"], status
        binding = next(item for item in supervisor.catalog.values() if item["tool"] == "wait_until_cancelled")
        started = tmp_path / "tool-entered"
        waiting = asyncio.create_task(supervisor.invoke(binding, {"started_path": str(started)}))
        # The fixture acknowledges entry into the actual remote tool body.
        connection = supervisor.connections["fixture"]
        async with asyncio.timeout(10):
            while not started.exists():
                await asyncio.sleep(0.01)
        waiting.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(waiting, 10)
        assert connection.task.done()
        assert connection.cleanup_verified
        assert not android_linux._ANDROID_PROCESS_OWNERS
        assert android_linux.android_command_execution_requires_restart() == ""
    finally:
        await supervisor.shutdown()


@pytest.mark.linux_only
@pytest.mark.asyncio
async def test_real_sdk_dispatch_uses_frozen_session_schema_and_revocation(tmp_path, monkeypatch):
    from hermes_android.mcp_tools import AndroidMcpTools, constructor_tool_definitions
    from tools.registry import registry

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(tmp_path))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/bin/sh")
    monkeypatch.setattr("hermes_android.mcp_runtime.external_mcp_allowed", lambda: True)
    supervisor = McpSupervisor(tmp_path)
    owner = AndroidMcpTools(supervisor)
    try:
        assert await owner.snapshot("old-chat", has_history=True) == []
        assert await owner.snapshot("started-without-mcp", has_history=False) == []
        await supervisor.reload(fixture_config(), enabled=True)
        bindings = await owner.snapshot("new-chat", has_history=False)
        binding = next(item for item in bindings if item["tool"] == "echo")
        name = binding["definition"]["function"]["name"]

        def real_dispatch():
            with owner.construction("new-chat", has_history=True):
                assert binding["definition"] in constructor_tool_definitions()
                return registry.dispatch(name, {"text": "registered"}, task_id="new-chat")

        assert "fixture: registered" in await asyncio.to_thread(real_dispatch)
        assert "not admitted" in await asyncio.to_thread(registry.dispatch, name, {"text": "not authorized"}, task_id="other-chat")
        assert await owner.snapshot("old-chat", has_history=True) == []
        assert await owner.snapshot("started-without-mcp", has_history=True) == []
        before = canonical = json.dumps(bindings, sort_keys=True)
        await supervisor.reload({}, enabled=False)
        assert json.dumps(await owner.snapshot("new-chat", has_history=True), sort_keys=True) == before
        assert "failed or was revoked" in await asyncio.to_thread(real_dispatch)
        owner.release_registrations()
        reopened = AndroidMcpTools(supervisor)
        try:
            assert json.dumps(await reopened.snapshot("new-chat", has_history=True), sort_keys=True) == canonical
        finally:
            reopened.release_registrations()
    finally:
        await supervisor.shutdown()
        owner.release_registrations()


@pytest.mark.asyncio
@pytest.mark.parametrize("transport", ["http", "sse"])
async def test_official_sdk_http_and_sse_support_calls_and_owned_shutdown(tmp_path, transport):
    async with running_sdk_http(transport) as url:
        supervisor = McpSupervisor(tmp_path)
        try:
            status = await supervisor.reload({"mcpServers": {"fixture": {"transport": transport, "url": url}}}, enabled=True)
            connection = supervisor.connections["fixture"]
            if not status["servers"][0]["connected"]:
                raise connection.failure_exception
            binding = next(iter(supervisor.catalog.values()))
            assert "http fixture: hello" in await supervisor.invoke(binding, {"text": "hello"})
            await supervisor.shutdown()
            assert connection.task.done()
            assert connection.cleanup_verified
        finally:
            await supervisor.shutdown()


@pytest.mark.asyncio
async def test_connecting_transport_is_not_reported_ready_before_sdk_handshake(tmp_path):
    from hermes_android.mcp_supervisor import sdk_transport

    entered, release = asyncio.Event(), asyncio.Event()

    @asynccontextmanager
    async def delayed_connect(config, home):
        entered.set()
        await release.wait()
        async with sdk_transport(config, home) as streams:
            yield streams

    async with running_sdk_http("http") as url:
        supervisor = McpSupervisor(tmp_path, transport_factory=delayed_connect)
        pending = asyncio.create_task(supervisor.reload(
            {"mcpServers": {"fixture": {"transport": "http", "url": url}}}, enabled=True))
        try:
            await asyncio.wait_for(entered.wait(), 3)
            assert not supervisor.status()["servers"][0]["connected"]
            release.set()
            assert (await pending)["servers"][0]["connected"]
        finally:
            release.set()
            await pending
            await supervisor.shutdown()


@pytest.mark.linux_only
@pytest.mark.asyncio
async def test_sdk_tool_timeout_reaps_real_child_and_requires_explicit_reload(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(tmp_path))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/bin/sh")
    supervisor = McpSupervisor(tmp_path, call_timeout=2)
    try:
        await supervisor.reload(fixture_config(), enabled=True)
        binding = next(item for item in supervisor.catalog.values() if item["tool"] == "wait_until_cancelled")
        with pytest.raises(RuntimeError, match="stopped"):
            await asyncio.wait_for(supervisor.invoke(binding, {"started_path": str(tmp_path / "entered")}), 10)
        assert not android_linux._ANDROID_PROCESS_OWNERS
        assert supervisor.connections["fixture"].task.done()
        with pytest.raises(RuntimeError, match="disconnected"):
            await supervisor.invoke(binding, {"started_path": str(tmp_path / "must-not-run")})
    finally:
        await supervisor.shutdown()


def test_http_response_budget_bounds_json_and_multiline_sse_across_chunks():
    from hermes_android.mcp_http import ResponseBudget

    json_budget = ResponseBudget(event_stream=False, maximum=12)
    json_budget.feed(b"{" + b" " * 10)
    with pytest.raises(ValueError, match="byte limit"):
        json_budget.feed(b"  }")
    for newline in (b"\n", b"\r\n", b"\r"):
        events = ResponseBudget(event_stream=True, maximum=40)
        for byte in (b"data: ok" + newline + newline) * 100:
            events.feed(bytes([byte]))
        with pytest.raises(ValueError, match="byte limit"):
            for byte in (b"data: one line" + newline) * 5:
                events.feed(bytes([byte]))


@pytest.mark.asyncio
@pytest.mark.parametrize("transport", ["http", "sse"])
async def test_sdk_oversized_http_result_closes_owned_connection_before_return(tmp_path, transport):
    async with running_sdk_http(transport, oversized=True) as url:
        supervisor = McpSupervisor(tmp_path)
        try:
            status = await supervisor.reload({"mcpServers": {"fixture": {"transport": transport, "url": url}}}, enabled=True)
            assert status["servers"][0]["connected"]
            binding = next(item for item in supervisor.catalog.values() if item["tool"] == "oversized_result")
            with pytest.raises(RuntimeError, match="stopped"):
                await asyncio.wait_for(supervisor.invoke(binding, {}), 10)
            connection = supervisor.connections["fixture"]
            assert connection.task.done() and connection.cleanup_verified
        finally:
            await supervisor.shutdown()


@pytest.mark.asyncio
async def test_unwound_deadline_retains_owner_and_blocks_replacement_until_actual_exit(tmp_path):
    import anyio
    from hermes_android.mcp_supervisor import sdk_transport

    release = asyncio.Event()

    @asynccontextmanager
    async def delayed_close(config, home):
        async with sdk_transport(config, home) as streams:
            try:
                yield streams
            finally:
                with anyio.CancelScope(shield=True):
                    await release.wait()

    async with running_sdk_http("http") as url:
        supervisor = McpSupervisor(tmp_path, transport_factory=delayed_close, close_timeout=2)
        try:
            config = {"mcpServers": {"delayed": {"transport": "http", "url": url}}}
            await supervisor.reload(config, enabled=True)
            connection = supervisor.connections["delayed"]
            with pytest.raises(RuntimeError, match="not verified"):
                await supervisor.shutdown()
            assert supervisor.connections["delayed"] is connection
            assert not connection.task.done()
            with pytest.raises(RuntimeError, match="not verified"):
                await supervisor.reload(config, enabled=True)
        finally:
            release.set()
            await supervisor.shutdown()


@pytest.mark.linux_only
@pytest.mark.asyncio
@pytest.mark.live_system_guard_bypass  # Exact inherited-token fixture intentionally outlives its parent.
async def test_stdio_shutdown_reaps_detached_inherited_token_descendants(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setenv("HERMES_ANDROID_LINUX_HOME", str(tmp_path))
    monkeypatch.setenv("HERMES_ANDROID_SHELL", "/bin/sh")
    supervisor = McpSupervisor(tmp_path)
    try:
        await supervisor.reload(fixture_config(), enabled=True)
        binding = next(item for item in supervisor.catalog.values() if item["tool"] == "spawn_detached_child")
        pid_path = tmp_path / "descendant-pid"
        await supervisor.invoke(binding, {"pid_path": str(pid_path)})
        descendant = int(pid_path.read_text())
        owner = next(iter(android_linux._ANDROID_PROCESS_OWNERS.values()))
        assert android_linux._process_owner_token(descendant) == owner.owner_token
        await supervisor.shutdown()
        assert not Path(f"/proc/{descendant}").exists()
        assert not android_linux._ANDROID_PROCESS_OWNERS
    finally:
        await supervisor.shutdown()
