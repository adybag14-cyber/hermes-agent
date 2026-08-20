"""Owned request admission and finalization for the embedded Android server."""
from __future__ import annotations

import asyncio
import logging
import threading
from typing import Any, Dict

from hermes_android.runtime_identity import is_embedded_android_runtime

logger = logging.getLogger("gateway.platforms.api_server")


class OwnedApiRuntimeMixin:
    _ANDROID_REQUEST_WORKER_DRAIN_TIMEOUT_SECONDS = 5.0

    def _initialize_owned_runtime(self, idempotency_cache) -> None:
        self._idempotency_cache = idempotency_cache
        self._run_stop_events: dict[str, threading.Event] = {}
        self._owned_agent_lock = threading.Lock()
        self._owned_agents: set[Any] = set()
        self._owned_task_ids: set[str] = set()
        self._owned_shutdown_requested = False
        self._owned_runtime_failure_detail = ""
        self._enforce_owned_runtime_shutdown = is_embedded_android_runtime()

    def _publish_prepared_agent(self, agent: Any) -> None:
        try:
            if is_embedded_android_runtime():
                agent._allow_background_post_turn_work = False
            if not self._register_owned_agent(agent):
                raise RuntimeError("API server shutdown started while agent work was being prepared")
        except BaseException:
            if self._enforce_owned_runtime_shutdown:
                with self._owned_agent_lock:
                    self._owned_agents.add(agent)
                try:
                    begin = getattr(agent, "begin_owned_worker_shutdown", None)
                    if callable(begin):
                        begin("API server agent publication failed")
                    else:
                        agent.interrupt("API server agent publication failed")
                except BaseException:
                    pass
            raise

    def _run_with_owned_runtime(self, action):
        """Run one embedded request under the Android native-work lease."""
        if not self._enforce_owned_runtime_shutdown:
            return action()
        from tools.environments.android_linux import android_embedded_runtime_work_guard

        self._require_owned_runtime_admission()
        with android_embedded_runtime_work_guard():
            self._require_owned_runtime_admission()
            return action()

    def _require_owned_runtime_admission(self) -> None:
        with self._owned_agent_lock:
            if self._owned_runtime_failure_detail:
                raise RuntimeError(self._owned_runtime_failure_detail)
            if self._owned_shutdown_requested:
                raise RuntimeError(
                    "API server shutdown is already in progress; refusing to start agent work"
                )

    def _poison_owned_runtime(self, detail: str) -> None:
        with self._owned_agent_lock:
            self._owned_runtime_failure_detail = detail
            self._owned_shutdown_requested = True

    def _run_conversation_with_owned_runtime(self, agent: Any, **kwargs: Any) -> Dict[str, Any]:
        return self._run_with_owned_runtime(lambda: agent.run_conversation(**kwargs))

    def _register_owned_agent(self, agent: Any) -> bool:
        """Register an agent unless shutdown already owns admission."""
        if not self._enforce_owned_runtime_shutdown:
            return True
        session_id = str(getattr(agent, "session_id", "") or "").strip()
        self._register_owned_task_id(session_id)
        with self._owned_agent_lock:
            if self._owned_shutdown_requested:
                # Construction itself can start owned work (for example the
                # OpenRouter metadata prewarm). Retain the losing agent so the
                # shutdown waiter and final resource cleanup still own it.
                self._owned_agents.add(agent)
                return False
            self._owned_agents.add(agent)
            return True

    def _register_owned_task_id(self, task_id: str) -> None:
        if not self._enforce_owned_runtime_shutdown:
            return
        normalized = str(task_id or "").strip()
        if not normalized:
            return
        from tools.process_registry import process_registry

        with self._owned_agent_lock:
            self._owned_task_ids.add(normalized)
            # Hold the adapter inventory lock through the registry publication so
            # finalization cannot snapshot/release the ID before a concurrent
            # retain completes. If retain raises after a partial side effect, the
            # ID deliberately remains in the adapter inventory for final cleanup.
            process_registry.retain_task_ownership(normalized)

    def _complete_owned_agent(self, agent: Any, task_id: str) -> None:
        """Release a completed request agent without losing shutdown authority."""
        if not self._enforce_owned_runtime_shutdown:
            return
        self._register_owned_task_id(task_id)
        try:
            begin_owned_shutdown = getattr(agent, "begin_owned_worker_shutdown", None)
            if callable(begin_owned_shutdown):
                begin_owned_shutdown("API request completed")
            else:
                agent.interrupt("API request completed")
            wait_for_owned_workers = getattr(agent, "wait_for_owned_workers", None)
            if callable(wait_for_owned_workers):
                live_workers = wait_for_owned_workers(
                    self._ANDROID_REQUEST_WORKER_DRAIN_TIMEOUT_SECONDS,
                )
            else:
                owned_worker_names = getattr(agent, "owned_worker_names", None)
                live_workers = owned_worker_names() if callable(owned_worker_names) else []
            if live_workers:
                raise RuntimeError(
                    "agent-owned worker thread(s) did not unwind: "
                    + ", ".join(sorted(live_workers))
                )
            release_clients = getattr(agent, "release_clients", None)
            if callable(release_clients):
                release_clients()
        except BaseException as exc:  # noqa: BLE001
            # This runs before the Android runtime lease is released. Any
            # unproven worker/client unwind permanently closes same-process
            # request admission, preventing a second provider/tool turn from
            # overlapping the retained agent. App/server restart performs the
            # authoritative final cleanup.
            detail = (
                "The previous embedded Android API request did not unwind safely: "
                f"{exc}. Force stop and reopen Hermes before sending another request."
            )
            self._poison_owned_runtime(detail)
            raise RuntimeError(detail) from exc
        with self._owned_agent_lock:
            self._owned_agents.discard(agent)

    def begin_owned_shutdown(self) -> None:
        """Close agent admission and interrupt every still-owned agent."""
        with self._owned_agent_lock:
            self._owned_shutdown_requested = True
            agents = list(self._owned_agents)
        for agent in agents:
            try:
                begin_owned_shutdown = getattr(agent, "begin_owned_worker_shutdown", None)
                if callable(begin_owned_shutdown):
                    begin_owned_shutdown("API server is shutting down")
                else:
                    agent.interrupt("API server is shutting down")
            except Exception:
                # Event-loop and default-executor quiescence remain the final
                # ownership authority even if a vendor-specific interrupt fails.
                pass

    async def disconnect_owned_runtime(self, task_timeout: float = 5.0) -> None:
        """Stop this dedicated adapter and cancel every loop-owned wrapper Task.

        Executor workers are deliberately not considered stopped here: the Android
        loop thread subsequently runs ``shutdown_default_executor`` and its bounded
        join is the authority that no old agent/tool work can overlap a replacement.
        """
        deadline = asyncio.get_running_loop().time() + max(task_timeout, 0.0)
        self.begin_owned_shutdown()
        self._mark_disconnected()
        if self._site:
            await self._site.stop()
            self._site = None

        self._idempotency_cache.cancel_inflight_for_shutdown()
        current = asyncio.current_task()
        tasks = [
            task
            for task in asyncio.all_tasks()
            if task is not current and not task.done()
        ]
        for task in tasks:
            task.cancel()
        if tasks:
            _, pending = await asyncio.wait(
                tasks,
                timeout=max(deadline - asyncio.get_running_loop().time(), 0.0),
            )
            if pending:
                raise TimeoutError(
                    f"{len(pending)} API server task(s) did not unwind during shutdown"
                )

        while True:
            with self._owned_agent_lock:
                agents = list(self._owned_agents)
            live_workers = []
            for agent in agents:
                owned_worker_names = getattr(agent, "owned_worker_names", None)
                if callable(owned_worker_names):
                    live_workers.extend(owned_worker_names())
            if not live_workers:
                break
            if asyncio.get_running_loop().time() >= deadline:
                raise TimeoutError(
                    "API server agent-owned worker thread(s) did not unwind: "
                    + ", ".join(sorted(live_workers))
                )
            await asyncio.sleep(0.01)

        if self._runner:
            await self._runner.cleanup()
            self._runner = None
        self._app = None
        self._background_tasks.clear()
        self._active_run_agents.clear()
        self._active_run_tasks.clear()
        for stop_event in self._run_stop_events.values():
            stop_event.set()
        self._run_approval_sessions.clear()
        logger.info("[%s] API server stopped with owned work drained", self.name)

    def finalize_owned_runtime_resources(self) -> None:
        """Release session processes only after the loop executor is quiescent."""
        with self._owned_agent_lock:
            agents = list(self._owned_agents)
            task_ids = set(self._owned_task_ids)
        failures = []
        live_workers = []
        for agent in agents:
            owned_worker_names = getattr(agent, "owned_worker_names", None)
            if callable(owned_worker_names):
                live_workers.extend(owned_worker_names())
        if live_workers:
            # An executor worker can finish constructing an agent after
            # disconnect_owned_runtime() took its last snapshot. The agent is
            # retained by _register_owned_agent(), but a constructor-started
            # daemon (for example OpenRouter metadata prewarm) is not owned by
            # asyncio's default executor. Never close beside it or certify the
            # loop as quiescent.
            raise RuntimeError(
                "API server agent-owned worker thread(s) remained live after "
                "executor shutdown: " + ", ".join(sorted(live_workers))
            )
        for agent in agents:
            try:
                agent.close()
            except Exception as exc:
                failures.append(f"agent close failed: {exc}")
            session_id = str(getattr(agent, "session_id", "") or "").strip()
            if session_id:
                task_ids.add(session_id)

        from tools.browser_tool_lifecycle import cleanup_browser
        from tools.environments.android_linux import (
            terminate_owned_android_command_processes_verified,
        )
        from tools.process_registry import process_registry
        from tools.terminal_tool import stop_cleanup_thread_verified
        from tools.terminal_tool_lifecycle import cleanup_vm

        try:
            stop_cleanup_thread_verified(timeout=5.0)
        except Exception as exc:
            failures.append(f"terminal cleanup worker did not stop: {exc}")
        try:
            terminate_owned_android_command_processes_verified(timeout=5.0)
        except Exception as exc:
            failures.append(f"foreground command-process cleanup failed: {exc}")
        if task_ids:
            try:
                process_registry.terminate_tasks_verified(task_ids, timeout=5.0)
            except Exception as exc:
                failures.append(f"verified background-process cleanup failed: {exc}")
        for task_id in sorted(task_ids):
            try:
                cleanup_vm(task_id, raise_on_error=True)
            except Exception as exc:
                failures.append(f"terminal cleanup failed for session {task_id}: {exc}")
            try:
                cleanup_browser(task_id)
            except Exception as exc:
                failures.append(f"browser cleanup failed for session {task_id}: {exc}")

        try:
            self._close_cached_session_dbs()
            from gateway.platforms import api_server_runs

            api_server_runs._close_run_state(self)
            if self._response_store is not None:
                self._response_store.close()
                self._response_store = None
        except Exception as exc:
            failures.append(f"adapter database cleanup failed: {exc}")
        if failures:
            raise RuntimeError("; ".join(failures))
        for task_id in task_ids:
            process_registry.release_task_ownership(task_id)
        with self._owned_agent_lock:
            self._owned_agents.clear()
            self._owned_task_ids.clear()
        self._run_stop_events.clear()
