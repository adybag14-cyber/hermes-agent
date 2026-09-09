"""Loop-owned official MCP SDK sessions; no desktop MCP globals or daemon loops."""
from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack, asynccontextmanager
from dataclasses import dataclass
import json
from pathlib import Path
import threading

import anyio

from hermes_android.mcp_config import (
    MAX_CATALOG_BYTES, MAX_TOOLS, ServerConfig, canonical, parse_servers, tool_binding, tool_result_text,
)


@asynccontextmanager
async def sdk_transport(config: ServerConfig, hermes_home: Path):
    if config.transport == "stdio":
        from hermes_android.mcp_stdio import owned_stdio_transport
        from tools.environments.android_linux import AndroidLinuxEnvironment

        workspace = hermes_home / "workspace"
        workspace.mkdir(parents=True, exist_ok=True)
        environment = AndroidLinuxEnvironment(cwd=str(workspace))._build_run_env() | config.environment
        async with owned_stdio_transport(list(config.argv), cwd=str(workspace), environment=environment) as streams:
            yield streams
        return
    import certifi
    import httpx2
    import ssl
    from hermes_android.mcp_http import bound_sdk_response

    client_options = dict(verify=ssl.create_default_context(cafile=certifi.where()), trust_env=False,
                          follow_redirects=False, timeout=httpx2.Timeout(10, read=65),
                          event_hooks={"response": [bound_sdk_response]},
                          limits=httpx2.Limits(max_connections=4, max_keepalive_connections=2))
    if config.transport == "sse":
        from mcp.client.sse import sse_client

        def client_factory(headers=None, timeout=None, auth=None):
            return httpx2.AsyncClient(**client_options,
                                     headers={**(headers or config.headers), "Accept-Encoding": "identity"}, auth=auth)

        async with sse_client(config.url, headers=config.headers, timeout=10, sse_read_timeout=65,
                              httpx_client_factory=client_factory) as streams:
            yield streams
        return
    from mcp.client.streamable_http import streamable_http_client

    async with httpx2.AsyncClient(**client_options, headers={**config.headers, "Accept-Encoding": "identity"}) as http:
        async with streamable_http_client(config.url, http_client=http, terminate_on_close=True) as streams:
            yield streams


@dataclass
class _Call:
    tool: str
    arguments: dict
    result: asyncio.Future


class OwnedConnection:
    """Enter, use and exit SDK/AnyIO contexts on one persistent owner Task."""

    def __init__(self, config, hermes_home, *, transport_factory=sdk_transport,
                 connect_timeout=10.0, call_timeout=60.0, close_timeout=5.0):
        self.config, self.hermes_home = config, hermes_home
        self.transport_factory = transport_factory
        self.connect_timeout, self.call_timeout, self.close_timeout = connect_timeout, call_timeout, close_timeout
        self.queue = asyncio.Queue(maxsize=1)
        self.ready = asyncio.get_running_loop().create_future()
        self.task = None
        self.scope = None
        self.stopping = False
        self.bindings = []
        self.error = ""
        self.cleanup_verified = True
        self.failure_exception = None

    async def start(self):
        if self.task is not None:
            raise RuntimeError("MCP connection already has an owner")
        self.task = asyncio.create_task(self._own(), name="android-mcp-connection")
        try:
            return await asyncio.wait_for(asyncio.shield(self.ready), self.connect_timeout + self.close_timeout)
        except BaseException:
            await self.stop()
            if self.ready.done() and not self.ready.cancelled():
                self.ready.exception()
            raise

    def request_stop(self):
        self.stopping = True
        if self.scope is not None:
            self.scope.cancel()

    async def stop(self):
        self.request_stop()
        if self.task is not None:
            await asyncio.wait_for(asyncio.shield(self.task), self.close_timeout)
        if not self.cleanup_verified:
            raise RuntimeError("MCP transport cleanup was not verified") from self.failure_exception

    @asynccontextmanager
    async def _transport(self):
        stack = AsyncExitStack()
        try:
            streams = await stack.enter_async_context(self.transport_factory(self.config, self.hermes_home))
            self.cleanup_verified = False
            yield streams
        finally:
            # Exit on the same owner Task, without passing a protocol/body error
            # into the transport's task group. A cleanup failure is separate
            # from a routine invalid response or a cancelled tool call.
            # Each concrete transport shields its own bounded cleanup. A new
            # cancel scope here would sit above its still-entered task-group
            # scope and violate AnyIO's strict context-stack ordering.
            await stack.aclose()
            self.cleanup_verified = True

    async def call(self, tool, arguments):
        if self.stopping or self.task is None or self.task.done():
            raise RuntimeError("MCP server is disconnected; reload MCP before retrying")
        result = asyncio.get_running_loop().create_future()
        self.queue.put_nowait(_Call(tool, arguments, result))
        try:
            return await asyncio.shield(result)
        except asyncio.CancelledError:
            # Stop the entire affected connection, including its exact child
            # descendants, before the request owner is allowed to unwind.
            await self.stop()
            if result.done() and not result.cancelled():
                result.exception()
            raise

    async def _own(self):
        from mcp import Client

        active = None
        failure = None
        try:
            with anyio.CancelScope() as scope:
                self.scope = scope
                if self.stopping:
                    scope.cancel()
                async with Client(self._transport(), mode="auto", cache=None,
                                  read_timeout_seconds=self.call_timeout, input_required_max_rounds=0) as client:
                    with anyio.fail_after(self.connect_timeout):
                        cursor, seen = None, set()
                        while True:
                            page = await client.list_tools(cursor=cursor)
                            self.bindings.extend(tool_binding(self.config, tool) for tool in page.tools)
                            if len(self.bindings) > MAX_TOOLS or len(canonical(self.bindings).encode()) > MAX_CATALOG_BYTES:
                                raise ValueError("MCP server tool catalog exceeds the limit")
                            cursor = page.next_cursor
                            if cursor is None:
                                break
                            if cursor in seen:
                                raise ValueError("MCP server returned a repeated tool-list cursor")
                            seen.add(cursor)
                        names = [item["tool"] for item in self.bindings]
                        if len(names) != len(set(names)):
                            raise ValueError("MCP server returned duplicate tool names")
                    self.ready.set_result(self.bindings)
                    while True:
                        active = await self.queue.get()
                        with anyio.fail_after(self.call_timeout):
                            result = await client.call_tool(active.tool, active.arguments)
                        active.result.set_result(tool_result_text(result))
                        active = None
        except BaseException as exc:
            # Arbitrary server errors can contain URLs, headers and arguments.
            # Keep public status categorical; don't copy them into app logs.
            failure = exc
            self.failure_exception = exc
            self.error = type(exc).__name__
        finally:
            self.scope = None
            self.stopping = True
            if failure is not None and isinstance(failure, asyncio.CancelledError):
                self.error = "cancelled"
            if not self.ready.done():
                self.ready.set_exception(RuntimeError("MCP connection failed: " + (self.error or "stopped")))
            pending = [active] if active is not None else []
            while not self.queue.empty():
                pending.append(self.queue.get_nowait())
            for request in pending:
                if not request.result.done():
                    request.result.set_exception(RuntimeError("MCP request stopped: " + (self.error or "cancelled")))


class McpSupervisor:
    """One supervisor per existing Android API loop; stopped owners stay retained."""

    def __init__(self, hermes_home, *, transport_factory=sdk_transport, **connection_limits):
        self.hermes_home = Path(hermes_home)
        self.loop = asyncio.get_running_loop()
        self.loop_thread_id = threading.get_ident()
        self.transport_factory = transport_factory
        self.connection_limits = connection_limits
        self.connections = {}
        self.catalog = {}
        self.enabled = False
        self.failure = ""
        self._mutation = asyncio.Lock()

    async def _stop_connections(self):
        results = await asyncio.gather(*(item.stop() for item in self.connections.values()), return_exceptions=True)
        if any(isinstance(item, BaseException) for item in results):
            self.failure = "MCP shutdown was not verified; force stop and reopen Hermes"
            raise RuntimeError(self.failure) from next(item for item in results if isinstance(item, BaseException))
        from tools.environments.android_linux import android_command_execution_requires_restart

        detail = android_command_execution_requires_restart()
        if detail:
            self.failure = detail
            raise RuntimeError(detail)
        self.connections.clear()

    async def reload(self, config, *, enabled):
        async with self._mutation:
            if self.failure:
                raise RuntimeError(self.failure)
            # Revocation happens before cancellation/cleanup and before any
            # changed configuration becomes callable through an old schema.
            self.enabled = False
            self.catalog = {}
            await self._stop_connections()
            if not enabled:
                return self.status()
            selected = parse_servers(config)
            self.enabled = True
            for name, value in selected.items():
                self.connections[name] = OwnedConnection(value, self.hermes_home,
                    transport_factory=self.transport_factory, **self.connection_limits)
            results = await asyncio.gather(*(item.start() for item in self.connections.values()), return_exceptions=True)
            for connection, result in zip(self.connections.values(), results):
                if isinstance(result, BaseException):
                    continue
                for binding in result:
                    self.catalog[binding["definition"]["function"]["name"]] = binding
            if len(self.catalog) > MAX_TOOLS or len(canonical(self.catalog).encode()) > MAX_CATALOG_BYTES:
                self.enabled = False
                self.catalog = {}
                await self._stop_connections()
                raise ValueError("Combined MCP tool catalog exceeds the Android limit")
            return self.status()

    def status(self):
        return {"enabled": self.enabled, "requires_app_restart": bool(self.failure),
                "server_count": len(self.connections), "tool_count": len(self.catalog),
                "servers": [{"name": name, "connected": (
                    not item.stopping and item.ready.done() and not item.ready.cancelled()
                    and item.ready.exception() is None and item.task is not None and not item.task.done()
                ), "error": item.error}
                            for name, item in self.connections.items()]}

    async def shutdown(self):
        async with self._mutation:
            self.enabled = False
            self.catalog = {}
            await self._stop_connections()

    async def invoke(self, binding, arguments):
        if self.failure or not self.enabled:
            raise RuntimeError("External MCP is disabled or requires an app restart")
        name = binding["definition"]["function"]["name"]
        current = self.catalog.get(name)
        if current != binding:
            raise RuntimeError("This conversation's MCP tool changed or was revoked; start a new chat after reload")
        if not isinstance(arguments, dict) or len(canonical(arguments).encode()) > MAX_CATALOG_BYTES:
            raise ValueError("Invalid or oversized MCP arguments")
        connection = self.connections[binding["server"]]
        try:
            return await connection.call(binding["tool"], arguments)
        except BaseException:
            if connection.stopping and (not connection.task.done() or not connection.cleanup_verified):
                self.failure = "MCP cancellation did not unwind safely; force stop and reopen Hermes"
            raise
