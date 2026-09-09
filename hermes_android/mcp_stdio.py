"""Official-SDK stdio streams backed by the Android runtime's process owner."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import os
import subprocess
import time
import uuid

import anyio

MAX_MESSAGE_BYTES = 1024 * 1024


def spawn_owned_stdio(argv: list[str], *, cwd: str, environment: dict[str, str]):
    from tools.environments import android_linux as processes

    if not processes._android_process_ownership_enabled():
        raise RuntimeError("Android MCP stdio requires the embedded runtime process owner")
    if not processes._ANDROID_COMMAND_EXECUTION_LOCK.acquire(blocking=False):
        raise RuntimeError("A terminal command is active; retry MCP reload after it finishes")
    try:
        error = processes._android_process_admission_error()
        if error:
            raise RuntimeError(error)
        baseline = processes._same_uid_process_ids()
        if baseline is None:
            raise RuntimeError("Android app-UID process inventory is unavailable")
        owner = processes._AndroidProcessOwner(frozenset(baseline), uuid.uuid4().hex, kind="mcp")
        child_env = environment | {processes._ANDROID_COMMAND_OWNER_ENV: owner.owner_token}
        child = subprocess.Popen(argv, cwd=cwd, env=child_env, stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
                                 start_new_session=True)
        # Retain the exact Popen immediately, before any stream configuration
        # can fail. Final Android shutdown inventories this same registry.
        with processes._ANDROID_PROCESS_OWNER_LOCK:
            processes._ANDROID_PROCESS_OWNERS[child.pid] = owner
            processes._ANDROID_PROCESS_HANDLES[child.pid] = child
        return child
    finally:
        processes._ANDROID_COMMAND_EXECUTION_LOCK.release()


def close_owned_stdio(child, timeout: float = 3.0) -> None:
    from tools.environments import android_linux as processes

    with processes._ANDROID_PROCESS_OWNER_LOCK:
        owner = processes._ANDROID_PROCESS_OWNERS.get(child.pid)
        retained = processes._ANDROID_PROCESS_HANDLES.get(child.pid)
    if owner is None or owner.kind != "mcp" or retained is not child:
        detail = "MCP child lost its exact Android process ownership"
        processes._record_unsafe_android_process(detail, unrecoverable=True)
        raise RuntimeError(detail)
    deadline = time.monotonic() + timeout
    child.stdin.close()
    graceful_deadline = min(deadline, time.monotonic() + 0.5)
    while child.poll() is None and time.monotonic() < graceful_deadline:
        time.sleep(0.01)
    _, error = processes._terminate_android_process_owner(child.pid, owner,
        timeout=max(deadline - time.monotonic(), 0.0))
    if error:
        processes._record_unsafe_android_process(error)
        raise RuntimeError(error)
    for stream in (child.stdin, child.stdout, child.stderr):
        stream.close()
    processes._forget_android_process_owner(child.pid)


async def _read_messages(fd: int, destination) -> None:
    from mcp.shared.message import SessionMessage
    from mcp_types import jsonrpc_message_adapter

    buffer = bytearray()
    async with destination:
        while True:
            await anyio.wait_readable(fd)
            try:
                chunk = os.read(fd, 65536)
            except BlockingIOError:
                continue
            if not chunk:
                if buffer:
                    raise ValueError("MCP stdio ended with an incomplete protocol message")
                return
            buffer.extend(chunk)
            while b"\n" in buffer:
                line, _, remainder = buffer.partition(b"\n")
                buffer = bytearray(remainder)
                if len(line) > MAX_MESSAGE_BYTES:
                    raise ValueError("MCP stdio message exceeds the size limit")
                if line.strip():
                    try:
                        message = jsonrpc_message_adapter.validate_json(line, by_name=False)
                    except ValueError:
                        await destination.send(ValueError("MCP server sent an invalid protocol message"))
                        return
                    await destination.send(SessionMessage(message))
            if len(buffer) > MAX_MESSAGE_BYTES:
                raise ValueError("MCP stdio message exceeds the size limit")


async def _write_messages(fd: int, source) -> None:
    async with source:
        async for item in source:
            payload = (item.message.model_dump_json(by_alias=True, exclude_none=True) + "\n").encode()
            if len(payload) > MAX_MESSAGE_BYTES:
                raise ValueError("MCP stdio request exceeds the size limit")
            remaining = memoryview(payload)
            while remaining:
                await anyio.wait_writable(fd)
                try:
                    count = os.write(fd, remaining)
                except BlockingIOError:
                    continue
                remaining = remaining[count:]


async def _drain_stderr(fd: int) -> None:
    # Stderr is not protocol data. Drain without retaining credentials or
    # arbitrary server logs in the conversation or Android logcat.
    while True:
        await anyio.wait_readable(fd)
        try:
            if not os.read(fd, 65536):
                return
        except BlockingIOError:
            continue


@asynccontextmanager
async def owned_stdio_transport(argv: list[str], *, cwd: str, environment: dict[str, str]):
    from mcp.shared.message import SessionMessage

    child = spawn_owned_stdio(argv, cwd=cwd, environment=environment)
    try:
        for stream in (child.stdin, child.stdout, child.stderr):
            os.set_blocking(stream.fileno(), False)
        incoming_send, incoming_receive = anyio.create_memory_object_stream[SessionMessage | Exception](0)
        outgoing_send, outgoing_receive = anyio.create_memory_object_stream[SessionMessage](0)
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(_read_messages, child.stdout.fileno(), incoming_send)
            tasks.start_soon(_write_messages, child.stdin.fileno(), outgoing_receive)
            tasks.start_soon(_drain_stderr, child.stderr.fileno())
            try:
                async with incoming_receive, outgoing_send:
                    yield incoming_receive, outgoing_send
            finally:
                tasks.cancel_scope.cancel()
    finally:
        with anyio.CancelScope(shield=True):
            # The enclosing Android loop owns and joins its default executor.
            # A timed-out caller never releases the retained process handle.
            await asyncio.to_thread(close_owned_stdio, child)
