"""Conversation-frozen schemas and owned synchronous dispatch for Android MCP."""
from __future__ import annotations

import asyncio
from concurrent.futures import TimeoutError as FutureTimeout
from contextlib import contextmanager
from contextvars import ContextVar
import copy
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import uuid

from hermes_android.mcp_config import MAX_CATALOG_BYTES, MAX_RESULT_CHARS, canonical, validate_schema

_CONSTRUCTOR_TOOLS = ContextVar("android_owned_mcp_constructor_tools", default=None)


class AndroidMcpTools:
    def __init__(self, supervisor):
        self.supervisor = supervisor
        self.registrations = {}
        self.sessions = {}

    async def snapshot(self, session_id: str, *, has_history: bool):
        if not session_id or len(session_id) > 4096:
            raise ValueError("MCP requires a bounded Android conversation ID")
        async with self.supervisor._mutation:
            if session_id not in self.sessions:
                directory = self.supervisor.hermes_home / "mcp/session-tools"
                path = directory / (hashlib.sha256(session_id.encode()).hexdigest() + ".json")
                if path.is_symlink():
                    raise ValueError("MCP conversation tool snapshot must not be a link")
                if path.exists():
                    if path.stat().st_size > MAX_CATALOG_BYTES:
                        raise ValueError("MCP conversation tool snapshot exceeds the size limit")
                    value = json.loads(path.read_text(encoding="utf-8"))
                    if not isinstance(value, dict) or value.get("schema") != "android-mcp-session-tools-v1":
                        raise ValueError("Invalid MCP conversation tool snapshot")
                    bindings = value["tools"]
                    if not isinstance(bindings, list):
                        raise ValueError("Invalid MCP conversation tool list")
                    validate_schema(bindings)
                else:
                    # A v156/existing transcript had no external MCP prefix.
                    # Enabling MCP must not rewrite that conversation's prefix.
                    bindings = [] if has_history else copy.deepcopy(list(self.supervisor.catalog.values()))
                    payload = canonical({"schema": "android-mcp-session-tools-v1", "tools": bindings})
                    if len(payload.encode()) > MAX_CATALOG_BYTES:
                        raise ValueError("MCP conversation tool snapshot exceeds the size limit")
                    directory.mkdir(parents=True, exist_ok=True)
                    temporary = path.with_suffix("." + uuid.uuid4().hex + ".tmp")
                    try:
                        with temporary.open("x", encoding="utf-8") as target:
                            target.write(payload)
                            target.flush()
                            os.fsync(target.fileno())
                        temporary.replace(path)
                    finally:
                        temporary.unlink(missing_ok=True)
                self.sessions[session_id] = bindings
                for binding in bindings:
                    self._register(binding)
            return copy.deepcopy(self.sessions[session_id])

    def _register(self, binding):
        from tools.registry import registry

        name = binding["definition"]["function"]["name"]
        if name in self.registrations:
            if registry.get_entry(name) is not self.registrations[name]:
                raise RuntimeError("Android MCP tool registration changed ownership")
            return
        if registry.get_entry(name) is not None:
            raise RuntimeError("Android MCP tool name collides with an existing registration")

        def invoke(arguments, **kwargs):
            from hermes_android.mcp_runtime import external_mcp_allowed

            if not external_mcp_allowed():
                return canonical({"error": "External MCP consent is not enabled in this Full-edition app"})
            session_id = kwargs.get("task_id")
            if binding not in self.sessions.get(session_id, []):
                return canonical({"error": "MCP tool is not admitted for this Android conversation"})
            try:
                return self.run_sync(self.supervisor.invoke(binding, arguments), timeout=70, interruptible=True)
            except InterruptedError:
                return canonical({"error": "MCP tool was stopped"})
            except Exception:
                # SDK errors may embed configuration secrets or remote data.
                return canonical({"error": "MCP tool failed or was revoked; inspect MCP settings and reload if needed"})

        registry.register(name=name, toolset="mcp-android-owned", schema=binding["definition"]["function"],
                          handler=invoke, max_result_size_chars=MAX_RESULT_CHARS + 4096)
        entry = registry.get_entry(name)
        if entry is None or entry.handler is not invoke:
            raise RuntimeError("Android MCP tool registration was rejected")
        self.registrations[name] = entry

    def run_sync(self, operation, *, timeout=25.0, interruptible=False):
        from tools.interrupt import is_interrupted

        if threading.get_ident() == self.supervisor.loop_thread_id:
            operation.close()
            raise RuntimeError("MCP synchronous dispatch cannot run on its owner loop")
        finished = threading.Event()

        async def owned_operation():
            try:
                return await operation
            finally:
                finished.set()

        wrapper = owned_operation()
        try:
            future = asyncio.run_coroutine_threadsafe(wrapper, self.supervisor.loop)
        except BaseException:
            wrapper.close()
            operation.close()
            raise
        deadline = time.monotonic() + timeout
        try:
            while True:
                if interruptible and is_interrupted():
                    raise InterruptedError("MCP tool was stopped")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("MCP operation exceeded its deadline")
                try:
                    return future.result(timeout=min(0.05, remaining))
                except FutureTimeout:
                    if future.done():
                        raise
        except BaseException:
            future.cancel()
            # concurrent.futures cancellation is immediate; this separate event
            # is set only after the real loop coroutine has finished cleanup.
            if not finished.wait(timeout=7):
                self.supervisor.failure = "MCP cancellation did not unwind; force stop and reopen Hermes"
                raise RuntimeError(self.supervisor.failure)
            if self.supervisor.failure:
                raise RuntimeError(self.supervisor.failure)
            raise

    @contextmanager
    def construction(self, session_id, *, has_history):
        bindings = self.run_sync(self.snapshot(session_id, has_history=has_history))
        token = _CONSTRUCTOR_TOOLS.set((self, bindings))
        try:
            yield
        finally:
            _CONSTRUCTOR_TOOLS.reset(token)

    def release_registrations(self):
        from tools.registry import registry

        for name, entry in self.registrations.items():
            registry.restore_registration(name, entry, None)
        self.registrations.clear()
        self.sessions.clear()


def constructor_tool_definitions():
    from tools.registry import registry

    bound = _CONSTRUCTOR_TOOLS.get()
    if bound is None:
        return []
    owner, bindings = bound
    result = []
    for binding in bindings:
        name = binding["definition"]["function"]["name"]
        if registry.get_entry(name) is not owner.registrations.get(name):
            raise RuntimeError("Android MCP constructor tool lost its registered owner")
        result.append(copy.deepcopy(binding["definition"]))
    return result
