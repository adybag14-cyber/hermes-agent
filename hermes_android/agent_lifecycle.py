"""Agent worker ownership and policy for the embedded Android API runtime."""
from __future__ import annotations

import threading
import time
from typing import Any

from hermes_android.runtime_identity import is_embedded_android_runtime


def _context_engine_name_for_runtime(agent_config: Any) -> str:
    """Resolve context engine without admitting unowned Android plugin code."""
    if is_embedded_android_runtime():
        return "compressor"
    try:
        context_config = agent_config.get("context", {}) if isinstance(agent_config, dict) else {}
        return context_config.get("engine", "compressor") or "compressor"
    except Exception:
        return "compressor"

def _constructor_background_work_allowed(platform_name: str | None) -> bool:
    """Return whether AIAgent construction may start detached helper work."""
    return not (
        platform_name == "api_server"
        and is_embedded_android_runtime()
    )

def _android_embedded_runtime_enabled() -> bool:
    return is_embedded_android_runtime()

def _android_command_execution_restart_detail() -> str:
    if not _android_embedded_runtime_enabled():
        return ""
    from tools.environments.android_linux import (
        android_command_execution_requires_restart,
    )

    return android_command_execution_requires_restart()


def filter_android_tool_definitions(definitions: list[dict]) -> list[dict]:
    """Keep the model's schemas within the source-owned Android tool bundle."""
    from toolsets import resolve_toolset

    allowed = set(resolve_toolset("hermes-android-app", include_registry=False))
    return [
        definition for definition in definitions
        if definition.get("function", {}).get("name") in allowed
    ]


def require_android_worker_unwound(agent, worker, *, join_timeout: float = 0.0) -> None:
    """A stale provider cannot be retried beside its still-running predecessor."""
    if not is_embedded_android_runtime() or worker is None:
        return
    if join_timeout > 0 and worker.is_alive():
        worker.join(timeout=join_timeout)
    if worker.is_alive():
        agent.begin_owned_worker_shutdown(
            "Embedded Android provider call did not unwind after its stale timeout"
        )
        raise InterruptedError(
            "Embedded Android provider call did not unwind; retry is blocked "
            "until the owning worker exits"
        )


def cancel_and_join_owned_watchdogs(watchdogs: list[threading.Timer]) -> BaseException | None:
    """Keep the request owner present until every admitted watchdog exits."""
    cleanup_error = None
    for timer in watchdogs:
        try:
            timer.cancel()
        except BaseException as error:
            cleanup_error = cleanup_error or error
    for timer in watchdogs:
        while True:
            try:
                if not timer.is_alive():
                    break
            except BaseException as error:
                cleanup_error = cleanup_error or error
            try:
                timer.join()
            except BaseException as error:
                cleanup_error = cleanup_error or error
    return cleanup_error


class OwnedAgentWorkerMixin:
    """Keep admitted worker threads reachable until their actual termination."""

    def _start_owned_worker_thread(
        self,
        *,
        target,
        name: str | None = None,
    ) -> threading.Thread:
        """Start a daemon whose lifetime remains owned by this agent."""

        def _owned_target() -> None:
            # Do not self-discard in the worker's finally block. There is a
            # real tail window between returning from this callable and the OS
            # thread becoming non-live; only an owner-side liveness sweep may
            # remove the strong reference.
            target()

        worker = threading.Thread(target=_owned_target, daemon=True, name=name)
        return self._admit_and_start_owned_thread(worker)

    def _admit_and_start_owned_thread(self, worker):
        # Lightweight object.__new__ callers do not run the constructor phases.
        if not hasattr(self, "_owned_worker_lock"):
            self._owned_worker_lock = threading.RLock()
        if not hasattr(self, "_owned_worker_threads"):
            self._owned_worker_threads = set()
        if not hasattr(self, "_owned_worker_shutdown_requested"):
            self._owned_worker_shutdown_requested = False
        with self._owned_worker_lock:
            # Admission is an owner-side observation point. Sweep only workers
            # which the owner can now prove are no longer live; the worker
            # wrapper intentionally keeps its reference through the small tail
            # window between target return and native thread exit.
            dead = [
                thread
                for thread in self._owned_worker_threads
                if not thread.is_alive()
            ]
            for thread in dead:
                self._owned_worker_threads.discard(thread)
            if self._owned_worker_shutdown_requested:
                raise InterruptedError("Agent shutdown forbids new background work")
            self._owned_worker_threads.add(worker)
            try:
                worker.start()
            except BaseException:
                # Thread.start() may be interrupted after the native thread
                # has already begun.  Retain any admitted worker so adapter
                # shutdown cannot certify quiescence while it is still live;
                # an owner-side liveness sweep removes it after actual exit.
                if getattr(worker, "ident", None) is None and not worker.is_alive():
                    self._owned_worker_threads.discard(worker)
                raise
        return worker

    def begin_owned_worker_shutdown(self, reason: str = "Agent owner is shutting down") -> None:
        """Forbid new child threads and interrupt all current agent work."""
        if not hasattr(self, "_owned_worker_lock"):
            self._owned_worker_lock = threading.RLock()
        if not hasattr(self, "_owned_worker_threads"):
            self._owned_worker_threads = set()
        with self._owned_worker_lock:
            self._owned_worker_shutdown_requested = True
        self.interrupt(reason)
        # Android API agents do not enable external memory providers, but other
        # owned adapters may. Ask any configured provider to stop its queues.
        self.shutdown_memory_provider()

    def owned_worker_names(self) -> list[str]:
        """Return live child-thread names for bounded owner-side polling."""
        if not hasattr(self, "_owned_worker_lock"):
            self._owned_worker_lock = threading.RLock()
        if not hasattr(self, "_owned_worker_threads"):
            self._owned_worker_threads = set()
        with self._owned_worker_lock:
            dead = [thread for thread in self._owned_worker_threads if not thread.is_alive()]
            for thread in dead:
                self._owned_worker_threads.discard(thread)
            return sorted(
                thread.name or f"thread-{thread.ident}"
                for thread in self._owned_worker_threads
                if thread.is_alive()
            )

    def wait_for_owned_workers(self, timeout: float) -> list[str]:
        """Boundedly join every agent-owned worker and return any survivors."""
        deadline = time.monotonic() + max(float(timeout), 0.0)
        while True:
            with self._owned_worker_lock:
                dead = [thread for thread in self._owned_worker_threads if not thread.is_alive()]
                for thread in dead:
                    self._owned_worker_threads.discard(thread)
                live = [thread for thread in self._owned_worker_threads if thread.is_alive()]
            if not live:
                return []
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return self.owned_worker_names()
            for thread in live:
                if thread is threading.current_thread():
                    continue
                thread.join(timeout=min(0.05, max(deadline - time.monotonic(), 0.0)))
