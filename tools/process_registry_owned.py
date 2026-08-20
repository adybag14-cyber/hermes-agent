"""Verified task-owned process teardown and retention for embedded adapters."""
from __future__ import annotations

import os
import signal
import threading
import time
from typing import TYPE_CHECKING

from hermes_cli._subprocess_compat import IS_WINDOWS as _IS_WINDOWS

if TYPE_CHECKING:
    from tools.process_registry import ProcessSession


def _owner_task_id(session) -> str:
    # The container key can be shared by unrelated tasks. New records carry
    # the raw spawning task; legacy records only have the old task_id.
    return getattr(session, "owner_task_id", "") or session.task_id


class OwnedProcessRegistryMixin:
    """Keep process identities and readers owned until quiescence is proven."""

    def _task_ownership_is_retained(self, session) -> bool:
        return _owner_task_id(session) in self._retained_task_ids

    def terminate_tasks_verified(self, task_ids, timeout: float = 5.0) -> int:
        """Stop task-owned host processes and prove their readers are quiescent.

        This is intentionally stricter than the interactive ``kill_process``
        command. It is used at an embedded-runtime ownership boundary where
        marking a session exited after merely sending SIGTERM would permit a
        replacement runtime to overlap a still-live process tree.
        """

        normalized = {str(task_id).strip() for task_id in task_ids if str(task_id).strip()}
        if not normalized:
            return 0
        with self._lock:
            sessions = list(self._running.values()) + list(self._finished.values())
        targets = [session for session in sessions if _owner_task_id(session) in normalized]
        deadline = time.monotonic() + max(float(timeout), 0.0)
        failures = []
        stopped = 0
        for session in targets:
            error = self._terminate_session_verified(session, deadline)
            if error:
                failures.append(f"{session.id}: {error}")
            else:
                stopped += 1
        if failures:
            raise RuntimeError("; ".join(failures))
        return stopped


    def retain_task_ownership(self, task_id: str) -> None:
        normalized = str(task_id or "").strip()
        if not normalized:
            return
        with self._lock:
            self._retained_task_ids.add(normalized)


    def release_task_ownership(self, task_id: str) -> None:
        normalized = str(task_id or "").strip()
        if not normalized:
            return
        with self._lock:
            self._retained_task_ids.discard(normalized)
            self._prune_if_needed()


    def has_tracked_sessions_for_task(self, task_id: str) -> bool:
        normalized = str(task_id or "").strip()
        if not normalized:
            return False
        with self._lock:
            return any(
                _owner_task_id(session) == normalized
                for session in (*self._running.values(), *self._finished.values())
            )


    def _terminate_session_verified(self, session: ProcessSession, deadline: float) -> str:
        if session.process is not None:
            error = self._terminate_local_process_verified(session, deadline)
        elif session._pty is not None:
            error = self._terminate_pty_process_verified(session, deadline)
        elif session.detached and session.pid_scope == "host" and session.pid:
            error = self._terminate_detached_host_process_verified(session, deadline)
        elif session.env_ref is not None and session.pid:
            # The environment abstraction exposes neither a waitable process
            # tree nor a descendant inventory. Sending a signal and trusting
            # the wrapper PID would be the same false proof this API exists to
            # prevent. Keep shutdown poisoned rather than claim quiescence.
            return "non-local process-tree termination cannot be verified"
        elif session.exited:
            return ""
        else:
            return "process has no verifiable ownership handle"
        if error:
            return error

        reader = session._reader_thread
        if reader is not None and reader is not threading.current_thread() and reader.is_alive():
            reader.join(timeout=max(deadline - time.monotonic(), 0.0))
        if reader is not None and reader.is_alive():
            return "process exited but its output reader thread remained live"

        with session._lock:
            session.exited = True
            if session.process is not None:
                session.exit_code = session.process.poll()
            elif session._pty is not None:
                session.exit_code = getattr(session._pty, "exitstatus", None)
        self._move_to_finished(session)
        return ""


    @staticmethod
    def _snapshot_host_process_tree(pid: int):
        try:
            import psutil

            parent = psutil.Process(pid)
            return [parent, *parent.children(recursive=True)]
        except Exception:
            return []


    @staticmethod
    def _host_tree_is_alive(processes) -> bool:
        if not processes:
            return False
        try:
            import psutil
        except ImportError:
            return False
        for process in processes:
            try:
                if process.is_running() and process.status() != psutil.STATUS_ZOMBIE:
                    return True
            except psutil.NoSuchProcess:
                continue
            except Exception:
                return True
        return False


    @staticmethod
    def _signal_host_tree(processes, *, force: bool) -> None:
        for process in reversed(processes):
            try:
                if force:
                    process.kill()
                else:
                    process.terminate()
            except Exception:
                pass


    @staticmethod
    def _posix_process_group_is_alive(process_group_id: int) -> bool:
        if _IS_WINDOWS or not process_group_id:
            return False
        try:
            os.killpg(process_group_id, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        except OSError:
            return False


    @staticmethod
    def _signal_local_process_group(process_group_id: int, *, force: bool) -> None:
        if _IS_WINDOWS or not process_group_id:
            return
        try:
            os.killpg(process_group_id, signal.SIGKILL if force else signal.SIGTERM)
        except (ProcessLookupError, PermissionError, OSError):
            pass


    @staticmethod
    def _wait_for_verified_exit(check_alive, deadline: float) -> bool:
        while True:
            if not check_alive():
                return True
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(0.02, remaining))


    def _verified_host_identity_error(self, session: ProcessSession, pid: int) -> str:
        """Reject recycled or unidentifiable PIDs before inventory or signalling."""
        if not pid:
            return "process has no host PID"
        if not self._is_host_pid_alive(pid):
            return ""
        expected = session.host_start_time
        if expected is not None:
            if self._safe_host_start_time(pid) != expected:
                return "host PID identity changed or cannot be verified; refusing to signal it"
            return ""
        # A live Popen/PTY handle still identifies the spawned process. A
        # detached legacy record with no start token does not.
        if session.process is not None and session.process.poll() is None:
            return ""
        if session._pty is not None and session._pty.isalive():
            return ""
        return "live host PID has no verifiable ownership identity"


    def _terminate_local_process_verified(self, session: ProcessSession, deadline: float) -> str:
        process = session.process
        pid = int(session.pid or getattr(process, "pid", 0) or 0)
        identity_error = self._verified_host_identity_error(session, pid)
        if identity_error:
            return identity_error
        tree = self._snapshot_host_process_tree(pid) if pid else []

        def alive() -> bool:
            try:
                parent_alive = process.poll() is None
            except Exception:
                parent_alive = True
            return (
                parent_alive
                or self._posix_process_group_is_alive(pid)
                or self._host_tree_is_alive(tree)
            )

        if not alive():
            return ""
        self._signal_local_process_group(pid, force=False)
        self._signal_host_tree(tree, force=False)
        try:
            process.terminate()
        except Exception:
            pass
        graceful_deadline = min(deadline, time.monotonic() + 1.0)
        if self._wait_for_verified_exit(alive, graceful_deadline):
            return ""

        identity_error = self._verified_host_identity_error(session, pid)
        if identity_error:
            return identity_error
        self._signal_local_process_group(pid, force=True)
        self._signal_host_tree(tree, force=True)
        try:
            process.kill()
        except Exception:
            pass
        if not self._wait_for_verified_exit(alive, deadline):
            return "process tree remained alive after TERM and KILL"
        return ""


    def _terminate_pty_process_verified(self, session: ProcessSession, deadline: float) -> str:
        pty = session._pty
        pid = int(session.pid or getattr(pty, "pid", 0) or 0)
        identity_error = self._verified_host_identity_error(session, pid)
        if identity_error:
            return identity_error
        tree = self._snapshot_host_process_tree(pid) if pid else []

        def alive() -> bool:
            try:
                pty_alive = bool(pty.isalive())
            except Exception:
                pty_alive = True
            return (
                pty_alive
                or self._posix_process_group_is_alive(pid)
                or self._host_tree_is_alive(tree)
            )

        if not alive():
            return ""
        try:
            pty.terminate(force=False)
        except Exception:
            pass
        self._signal_local_process_group(pid, force=False)
        self._signal_host_tree(tree, force=False)
        graceful_deadline = min(deadline, time.monotonic() + 1.0)
        if self._wait_for_verified_exit(alive, graceful_deadline):
            return ""
        identity_error = self._verified_host_identity_error(session, pid)
        if identity_error:
            return identity_error
        try:
            pty.terminate(force=True)
        except Exception:
            pass
        self._signal_local_process_group(pid, force=True)
        self._signal_host_tree(tree, force=True)
        if not self._wait_for_verified_exit(alive, deadline):
            return "PTY process tree remained alive after TERM and KILL"
        return ""


    def _terminate_detached_host_process_verified(
        self,
        session: ProcessSession,
        deadline: float,
    ) -> str:
        pid = int(session.pid or 0)
        identity_error = self._verified_host_identity_error(session, pid)
        if identity_error:
            return identity_error
        tree = self._snapshot_host_process_tree(pid)

        def alive() -> bool:
            return self._is_host_pid_alive(pid) or self._host_tree_is_alive(tree)

        if not alive():
            return ""
        self._terminate_host_pid(pid, session.host_start_time)
        self._signal_host_tree(tree, force=False)
        graceful_deadline = min(deadline, time.monotonic() + 1.0)
        if self._wait_for_verified_exit(alive, graceful_deadline):
            return ""
        identity_error = self._verified_host_identity_error(session, pid)
        if identity_error:
            return identity_error
        self._signal_host_tree(tree, force=True)
        if not _IS_WINDOWS:
            try:
                os.kill(pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        if not self._wait_for_verified_exit(alive, deadline):
            return "detached host process tree remained alive after TERM and KILL"
        return ""
