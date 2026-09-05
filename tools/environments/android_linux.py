"""Android shell execution environment for the native Android app.

The Kotlin bridge prefers APK-packaged executable native launchers for the
embedded Linux suite, then falls back to Android's platform shell if that probe
fails. This backend mirrors whichever mode the bridge selected.
"""

from __future__ import annotations

import os
import signal
import shlex
import subprocess
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from hermes_android.runtime_identity import is_embedded_android_runtime

from tools.environments.base import BaseEnvironment
from tools.environments.base_output import _pipe_stdin


_ANDROID_PROCESS_OWNER_LOCK = threading.Lock()
_ANDROID_COMMAND_EXECUTION_LOCK = threading.Lock()
_ANDROID_RUNTIME_WORK_LOCK = threading.RLock()
_ANDROID_COMMAND_OWNER_ENV = "HERMES_ANDROID_COMMAND_OWNER"
_ANDROID_QUIESCENCE_CONFIRMATION_SECONDS = 0.1


@dataclass(frozen=True)
class _AndroidProcessOwner:
    baseline_process_ids: frozenset[int]
    owner_token: str


_ANDROID_PROCESS_OWNERS: dict[int, _AndroidProcessOwner] = {}
_ANDROID_PROCESS_HANDLES: dict[int, subprocess.Popen] = {}
_ANDROID_UNSAFE_PROCESS_DETAIL = ""
_ANDROID_UNRECOVERABLE_PROCESS_POISON = False


def _android_process_ownership_enabled() -> bool:
    return is_embedded_android_runtime()


def _same_uid_process_ids() -> set[int] | None:
    """Return currently visible processes owned by this Android app UID."""
    if not _android_process_ownership_enabled():
        return set()
    if not hasattr(os, "getuid"):
        return None  # No POSIX UID authority: fail closed, never infer app ownership.
    owned = set()
    own_uid = os.getuid()  # windows-footgun: ok -- hasattr guard above, no Windows UID inference
    proc_root = Path("/proc")
    try:
        entries = list(proc_root.iterdir())
    except OSError:
        return None
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            status = (entry / "status").read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        uid_line = next((line for line in status.splitlines() if line.startswith("Uid:")), "")
        fields = uid_line.split()
        if len(fields) >= 2 and fields[1].isdigit() and int(fields[1]) == own_uid:
            owned.add(int(entry.name))
    if os.getpid() not in owned:
        return None
    return owned


def _process_group_is_alive(process_group_id: int) -> bool:
    if not hasattr(os, "killpg"):
        return True  # An unsupported host cannot certify that the group is gone.
    try:
        os.killpg(process_group_id, 0)  # windows-footgun: ok -- hasattr guard above
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False


def _direct_process_reaped(process_group_id: int) -> bool:
    """Poll the retained Popen so a dead direct child cannot remain a zombie."""
    with _ANDROID_PROCESS_OWNER_LOCK:
        proc = _ANDROID_PROCESS_HANDLES.get(process_group_id)
    if proc is None:
        return True
    try:
        return proc.poll() is not None
    except BaseException:  # noqa: BLE001 - failed reap must remain unsafe
        return False


def _process_owner_token(process_id: int) -> str | None:
    try:
        raw = (Path("/proc") / str(process_id) / "environ").read_bytes()
    except OSError:
        return None
    prefix = f"{_ANDROID_COMMAND_OWNER_ENV}=".encode()
    for entry in raw.split(b"\0"):
        if entry.startswith(prefix):
            return entry[len(prefix) :].decode("utf-8", errors="replace")
    return None


def _signal_owned_processes(
    process_ids: set[int],
    owner_token: str,
    sig: int,
) -> None:
    for pid in sorted(process_ids):
        if pid == os.getpid():
            continue
        # Revalidate immediately before signaling so PID reuse cannot turn an
        # earlier ownership snapshot into authority over an unrelated process.
        if _process_owner_token(pid) != owner_token:
            continue
        try:
            os.kill(pid, sig)
        except (ProcessLookupError, PermissionError, OSError):
            pass


def _terminate_android_process_owner(
    process_group_id: int,
    owner: _AndroidProcessOwner,
    *,
    timeout: float,
) -> tuple[int, str]:
    """Terminate a command group plus exact inherited-token descendants."""
    deadline = time.monotonic() + max(timeout, 0.0)
    observed_owned = set()
    observed_ambiguous = set()
    clean_since: float | None = None

    def remaining() -> tuple[set[int], set[int]]:
        current = _same_uid_process_ids()
        if current is None:
            raise RuntimeError("Android app-UID process inventory is unavailable")
        candidates = current - {os.getpid()}
        # Check the inherited owner token before applying the pre-spawn PID
        # baseline. A descendant can reuse a PID which existed at baseline; its
        # exact token is still positive ownership proof and must not be hidden
        # by PID-number reuse. Baseline processes without our token remain
        # outside the ambiguous set and are never signaled.
        owned = {
            pid
            for pid in candidates
            if _process_owner_token(pid) == owner.owner_token
        }
        ambiguous = candidates - owned - set(owner.baseline_process_ids)
        observed_owned.update(owned)
        observed_ambiguous.update(ambiguous)
        return owned, ambiguous

    def stably_clean(owned: set[int], ambiguous: set[int]) -> bool:
        nonlocal clean_since
        if (
            owned
            or ambiguous
            or _process_group_is_alive(process_group_id)
            or not _direct_process_reaped(process_group_id)
        ):
            clean_since = None
            return False
        now = time.monotonic()
        if clean_since is None:
            clean_since = now
            return False
        return now - clean_since >= _ANDROID_QUIESCENCE_CONFIRMATION_SECONDS

    try:
        owned, ambiguous = remaining()
    except RuntimeError as exc:
        return 0, str(exc)
    if (
        not owned
        and not ambiguous
        and not _process_group_is_alive(process_group_id)
        and _direct_process_reaped(process_group_id)
    ):
        confirmation_deadline = min(
            deadline,
            time.monotonic() + _ANDROID_QUIESCENCE_CONFIRMATION_SECONDS,
        )
        while time.monotonic() < confirmation_deadline:
            try:
                owned, ambiguous = remaining()
            except RuntimeError as exc:
                return len(observed_owned), str(exc)
            if stably_clean(owned, ambiguous):
                return len(observed_owned), ""
            time.sleep(0.02)
    _signal_owned_processes(owned, owner.owner_token, signal.SIGTERM)
    graceful_deadline = min(deadline, time.monotonic() + 0.5)
    while time.monotonic() < graceful_deadline:
        try:
            owned, ambiguous = remaining()
        except RuntimeError as exc:
            return len(observed_owned), str(exc)
        if stably_clean(owned, ambiguous):
            return len(observed_owned), ""
        time.sleep(0.02)

    try:
        owned, ambiguous = remaining()
    except RuntimeError as exc:
        return len(observed_owned), str(exc)
    _signal_owned_processes(owned, owner.owner_token, signal.SIGKILL)  # windows-footgun: ok -- Android /proc ownership required
    while time.monotonic() < deadline:
        try:
            owned, ambiguous = remaining()
        except RuntimeError as exc:
            return len(observed_owned), str(exc)
        if stably_clean(owned, ambiguous):
            return len(observed_owned), ""
        _signal_owned_processes(owned, owner.owner_token, signal.SIGKILL)  # windows-footgun: ok -- Android /proc ownership required
        time.sleep(0.02)
    try:
        owned, ambiguous = remaining()
    except RuntimeError as exc:
        return len(observed_owned), str(exc)
    group_alive = _process_group_is_alive(process_group_id)
    direct_reaped = _direct_process_reaped(process_group_id)
    if owned or ambiguous or group_alive or not direct_reaped or not stably_clean(owned, ambiguous):
        parts = []
        if owned:
            parts.append("owned=" + ",".join(str(pid) for pid in sorted(owned)))
        if ambiguous:
            parts.append(
                "ambiguous-unmarked=" + ",".join(str(pid) for pid in sorted(ambiguous))
            )
        if group_alive:
            parts.append(f"group={process_group_id}")
        if not direct_reaped:
            parts.append(f"direct-parent-unreaped={process_group_id}")
        if not parts:
            parts.append("quiescence-window-incomplete")
        return (
            len(observed_owned),
            "Android command ownership could not be verified after cleanup: "
            + "; ".join(parts),
        )
    return len(observed_owned), ""


def _finish_android_stdin_writer(process_group_id: int, *, timeout: float) -> str:
    """Close and join the tracked stdin writer for one Android command."""
    with _ANDROID_PROCESS_OWNER_LOCK:
        proc = _ANDROID_PROCESS_HANDLES.get(process_group_id)
    if proc is None:
        return ""
    writer = getattr(proc, "_hermes_android_stdin_writer", None)
    if writer is not None:
        if getattr(writer, "ident", None) is not None:
            try:
                writer.join(timeout=max(timeout, 0.0))
            except RuntimeError as exc:
                return f"Android command stdin writer cleanup failed: {exc}"
            if writer.is_alive():
                # Never call close()/flush beside a writer which may hold the
                # BufferedWriter lock. Process termination should break the pipe;
                # if it did not, the writer itself remains owned and unsafe.
                return "Android command stdin writer did not stop after process cleanup"
    stdin = getattr(proc, "stdin", None)
    if stdin is not None:
        try:
            stdin.close()
        except (BrokenPipeError, OSError, ValueError):
            pass
    return ""


def _finish_android_stdout_drain(process_group_id: int, *, timeout: float) -> str:
    """Boundedly join the tracked stdout reader before releasing ownership."""
    with _ANDROID_PROCESS_OWNER_LOCK:
        proc = _ANDROID_PROCESS_HANDLES.get(process_group_id)
    if proc is None:
        return ""
    drain = getattr(proc, "_hermes_stdout_drain_thread", None)
    if drain is not None:
        try:
            drain_started = getattr(drain, "ident", None) is not None or drain.is_alive()
            if drain_started:
                drain.join(timeout=max(timeout, 0.0))
            if drain.is_alive():
                # Never close a TextIOWrapper beside a live os.read(). Retain
                # both the Popen and owner so finalization can retry the join.
                return "Android command stdout drain did not stop after process cleanup"
        except RuntimeError as exc:
            return f"Android command stdout drain cleanup failed: {exc}"
    stdout = getattr(proc, "stdout", None)
    if stdout is not None:
        try:
            stdout.close()
        except (BrokenPipeError, OSError, ValueError):
            pass
    return ""


def _forget_android_process_owner(process_group_id: int) -> None:
    with _ANDROID_PROCESS_OWNER_LOCK:
        _ANDROID_PROCESS_OWNERS.pop(process_group_id, None)
        _ANDROID_PROCESS_HANDLES.pop(process_group_id, None)


def _release_android_command_execution_lock(proc) -> None:
    if getattr(proc, "_hermes_android_execution_lock_held", False):
        proc._hermes_android_execution_lock_held = False
        _ANDROID_COMMAND_EXECUTION_LOCK.release()


def terminate_owned_android_command_processes_verified(timeout: float = 5.0) -> None:
    """Final embedded-server proof that no foreground command descendants remain."""
    global _ANDROID_UNSAFE_PROCESS_DETAIL
    deadline = time.monotonic() + max(timeout, 0.0)
    with _ANDROID_PROCESS_OWNER_LOCK:
        owners = list(_ANDROID_PROCESS_OWNERS.items())
        unrecoverable = _ANDROID_UNRECOVERABLE_PROCESS_POISON
        unrecoverable_detail = _ANDROID_UNSAFE_PROCESS_DETAIL
    failures = []
    for process_group_id, owner in owners:
        with _ANDROID_PROCESS_OWNER_LOCK:
            proc = _ANDROID_PROCESS_HANDLES.get(process_group_id)
        _, error = _terminate_android_process_owner(
            process_group_id,
            owner,
            timeout=max(deadline - time.monotonic(), 0.0),
        )
        writer_error = _finish_android_stdin_writer(
            process_group_id,
            timeout=max(deadline - time.monotonic(), 0.0),
        )
        if writer_error:
            error = f"{error}; {writer_error}" if error else writer_error
        drain_error = _finish_android_stdout_drain(
            process_group_id,
            timeout=max(deadline - time.monotonic(), 0.0),
        )
        if drain_error:
            error = f"{error}; {drain_error}" if error else drain_error
        if error:
            failures.append(error)
        else:
            try:
                _forget_android_process_owner(process_group_id)
            finally:
                if proc is not None:
                    _release_android_command_execution_lock(proc)
    with _ANDROID_PROCESS_OWNER_LOCK:
        unowned_handles = sorted(set(_ANDROID_PROCESS_HANDLES) - set(_ANDROID_PROCESS_OWNERS))
    if unowned_handles:
        failures.append(
            "Android command process handles lost ownership: "
            + ",".join(str(pid) for pid in unowned_handles)
        )
    if unrecoverable:
        failures.append(
            unrecoverable_detail
            or "Android command process ownership was irrecoverably lost"
        )
    if failures:
        with _ANDROID_PROCESS_OWNER_LOCK:
            _ANDROID_UNSAFE_PROCESS_DETAIL = "; ".join(failures)
        raise RuntimeError("; ".join(failures))
    with _ANDROID_PROCESS_OWNER_LOCK:
        if not _ANDROID_PROCESS_OWNERS and not _ANDROID_UNRECOVERABLE_PROCESS_POISON:
            _ANDROID_UNSAFE_PROCESS_DETAIL = ""


def _record_unsafe_android_process(detail: str, *, unrecoverable: bool = False) -> None:
    global _ANDROID_UNRECOVERABLE_PROCESS_POISON, _ANDROID_UNSAFE_PROCESS_DETAIL
    normalized = str(detail or "").strip() or "Android command cleanup was not verified"
    with _ANDROID_PROCESS_OWNER_LOCK:
        _ANDROID_UNSAFE_PROCESS_DETAIL = normalized
        if unrecoverable:
            _ANDROID_UNRECOVERABLE_PROCESS_POISON = True


def android_command_execution_requires_restart() -> str:
    """Return the process-wide fail-closed detail for embedded Android work."""
    if not _android_process_ownership_enabled():
        return ""
    with _ANDROID_PROCESS_OWNER_LOCK:
        detail = _ANDROID_UNSAFE_PROCESS_DETAIL
    if not detail:
        return ""
    return (
        "Android command execution requires an app force-stop and reopen because "
        f"the previous process tree did not unwind safely: {detail}"
    )


class AndroidRuntimeWorkRejected(RuntimeError):
    """Raised when embedded runtime work would overlap unsafe Android work."""


@contextmanager
def android_embedded_runtime_work_guard():
    """Serialize embedded API/tool turns and reject them after unsafe cleanup."""
    if not _android_process_ownership_enabled():
        yield
        return
    with _ANDROID_RUNTIME_WORK_LOCK:
        detail = android_command_execution_requires_restart()
        if detail:
            raise AndroidRuntimeWorkRejected(detail)
        yield


def _android_process_admission_error() -> str:
    restart_detail = android_command_execution_requires_restart()
    if restart_detail:
        return restart_detail
    with _ANDROID_PROCESS_OWNER_LOCK:
        owner_ids = sorted(_ANDROID_PROCESS_OWNERS)
    if not owner_ids:
        return ""
    owner_text = f" (owners: {', '.join(str(pid) for pid in owner_ids)})" if owner_ids else ""
    return (
        "Android command execution is already active and owns a live process tree"
        f"{owner_text}"
    )


class AndroidLinuxEnvironment(BaseEnvironment):
    """Run commands through Android's native system shell.

    Files persist in the app-private Hermes workspace, while command execution
    uses /system/bin/sh and Android's built-in command set. This keeps the
    native app independent of Termux and avoids Android's noexec policy for
    writable app storage.
    """

    _profile_scoped_passthrough = True

    def _additional_profile_scoped_passthrough_names(self):
        from tools.environments.local import LocalEnvironment

        return LocalEnvironment._additional_profile_scoped_passthrough_names(self)

    def __init__(self, cwd: str = "", timeout: int = 60, env: dict | None = None):
        self.prefix_path = os.environ.get("HERMES_ANDROID_LINUX_PREFIX", "").strip()
        self.shell_path = (
            os.environ.get("HERMES_ANDROID_SHELL", "").strip()
            or os.environ.get("HERMES_ANDROID_LINUX_BASH", "").strip()
            or "/system/bin/sh"
        )
        self.native_shell_path = (
            os.environ.get("HERMES_ANDROID_NATIVE_SHELL", "").strip()
            or os.environ.get("HERMES_ANDROID_LINUX_NATIVE_BASH", "").strip()
        )
        self.bin_path = os.environ.get("HERMES_ANDROID_LINUX_BIN", "").strip()
        self.lib_path = os.environ.get("HERMES_ANDROID_LINUX_LIB", "").strip()
        self.native_library_dir = os.environ.get("HERMES_ANDROID_NATIVE_LIB", "").strip()
        self.home_path = os.environ.get("HERMES_ANDROID_LINUX_HOME", "").strip()
        self.tmp_path = os.environ.get("HERMES_ANDROID_LINUX_TMP", "").strip()
        self.execution_mode = os.environ.get("HERMES_ANDROID_EXECUTION_MODE", "android_system_shell").strip()
        self.shell_library_dir = ""
        library_shell_path = self.native_shell_path or self.shell_path
        if library_shell_path and not library_shell_path.startswith("/system/"):
            self.shell_library_dir = str(Path(library_shell_path).parent)
        if not self.native_library_dir:
            self.native_library_dir = self.shell_library_dir
        self.process_shell_path = (
            self.shell_path if self.shell_path.startswith("/system/") else "/system/bin/sh"
        )

        if not self.shell_path:
            raise ValueError("Android shell environment is not configured")

        for required in (self.prefix_path, self.home_path, self.tmp_path):
            if required:
                Path(required).mkdir(parents=True, exist_ok=True)

        super().__init__(cwd=cwd or self.home_path or os.getcwd(), timeout=timeout, env=env)
        self.init_session()

    def get_temp_dir(self) -> str:
        return self.tmp_path or self.home_path or self.prefix_path or os.getcwd()

    def _build_run_env(self) -> dict[str, str]:
        from tools.environments.local import build_subprocess_env

        run_env = build_subprocess_env(extra=self.env)

        system_path = "/system/bin:/system/xbin:/vendor/bin:/odm/bin"
        existing_path = run_env.get("PATH", "")
        path_parts = [system_path]
        if existing_path:
            path_parts.append(existing_path)
        if (
            self.bin_path
            and run_env.get("HERMES_ANDROID_ALLOW_PREFIX_BIN") == "1"
            and self.bin_path not in path_parts
        ):
            path_parts.append(self.bin_path)
        run_env["PATH"] = ":".join(path_parts)

        if self.prefix_path:
            run_env["PREFIX"] = self.prefix_path
        run_env["HOME"] = self.home_path or self.prefix_path
        run_env["TMPDIR"] = self.tmp_path or self.get_temp_dir()
        run_env["HERMES_ANDROID_SHELL"] = self.shell_path
        if self.native_shell_path:
            run_env["HERMES_ANDROID_NATIVE_SHELL"] = self.native_shell_path
        run_env["HERMES_ANDROID_EXECUTION_MODE"] = self.execution_mode or "android_system_shell"
        ld_parts = [
            self.shell_library_dir,
            self.native_library_dir,
            self.lib_path,
            run_env.get("LD_LIBRARY_PATH", ""),
        ]
        run_env["LD_LIBRARY_PATH"] = ":".join(item for item in ld_parts if item)
        run_env.setdefault("TERM", "xterm-256color")
        run_env.setdefault("LANG", "C.UTF-8")
        return run_env

    def init_session(self):
        """Use a lightweight native-shell session without bash snapshots."""
        self._snapshot_ready = False
        try:
            Path(self._cwd_file).parent.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass

    def _wrap_command(self, command: str, cwd: str) -> str:
        escaped = command.replace("'", "'\\''")
        quoted_cwd = self._quote_cwd_for_cd(cwd)
        quoted_cwd_file = shlex.quote(self._cwd_file)
        return "\n".join(
            [
                f"cd {quoted_cwd} || exit 126",
                f"eval '{escaped}'",
                "__hermes_ec=$?",
                f"pwd -P > {quoted_cwd_file} 2>/dev/null || true",
                f"printf '\\n{self._cwd_marker}%s{self._cwd_marker}\\n' \"$(pwd -P)\"",
                "exit $__hermes_ec",
            ]
        )

    def _run_bash(
        self,
        cmd_string: str,
        *,
        login: bool = False,
        timeout: int = 120,
        stdin_data: str | None = None,
    ) -> subprocess.Popen:
        del timeout  # spawn-per-call; enforced by BaseEnvironment
        del login
        args = [self.process_shell_path, "-c", cmd_string]
        ownership_enabled = _android_process_ownership_enabled()
        if ownership_enabled:
            _ANDROID_COMMAND_EXECUTION_LOCK.acquire()
        proc = None
        owner = None
        ownership_registered = False
        try:
            if ownership_enabled:
                admission_error = _android_process_admission_error()
                if admission_error:
                    raise RuntimeError(admission_error)
            baseline_process_ids = _same_uid_process_ids()
            if ownership_enabled and baseline_process_ids is None:
                raise RuntimeError("Android app-UID process inventory is unavailable")
            owner_token = uuid.uuid4().hex if ownership_enabled else ""
            if ownership_enabled:
                owner = _AndroidProcessOwner(
                    baseline_process_ids=frozenset(baseline_process_ids),
                    owner_token=owner_token,
                )
            run_env = self._build_run_env()
            if ownership_enabled:
                run_env[_ANDROID_COMMAND_OWNER_ENV] = owner_token
            spawn_kwargs: dict[str, object] = {}
            if os.name == "nt":
                spawn_kwargs["creationflags"] = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            else:
                spawn_kwargs["start_new_session"] = True
            proc = subprocess.Popen(
                args,
                text=True,
                env=run_env,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                stdin=subprocess.PIPE if stdin_data is not None else subprocess.DEVNULL,
                **spawn_kwargs,
            )
            if ownership_enabled:
                with _ANDROID_PROCESS_OWNER_LOCK:
                    _ANDROID_PROCESS_OWNERS[proc.pid] = owner
                    _ANDROID_PROCESS_HANDLES[proc.pid] = proc
                    ownership_registered = True
            if stdin_data is not None:
                _pipe_stdin(
                    proc,
                    stdin_data,
                    register=lambda writer: setattr(
                        proc,
                        "_hermes_android_stdin_writer",
                        writer,
                    ),
                )
            if ownership_enabled:
                proc._hermes_android_execution_lock_held = True
            return proc
        except BaseException as setup_error:
            cleanup_failure = None
            try:
                if ownership_enabled and proc is not None and owner is not None:
                    try:
                        _, cleanup_error = _terminate_android_process_owner(
                            proc.pid,
                            owner,
                            timeout=2.0,
                        )
                        writer_error = _finish_android_stdin_writer(
                            proc.pid,
                            timeout=1.0,
                        )
                        if writer_error:
                            cleanup_error = (
                                f"{cleanup_error}; {writer_error}"
                                if cleanup_error
                                else writer_error
                            )
                        drain_error = _finish_android_stdout_drain(
                            proc.pid,
                            timeout=1.0,
                        )
                        if drain_error:
                            cleanup_error = (
                                f"{cleanup_error}; {drain_error}"
                                if cleanup_error
                                else drain_error
                            )
                        if cleanup_error:
                            _record_unsafe_android_process(
                                cleanup_error,
                                unrecoverable=not ownership_registered,
                            )
                        else:
                            _forget_android_process_owner(proc.pid)
                    except BaseException as cleanup_error:  # noqa: BLE001
                        cleanup_failure = cleanup_error
                        _record_unsafe_android_process(
                            "Android command spawn cleanup raised "
                            f"{type(cleanup_error).__name__}: {cleanup_error}",
                            unrecoverable=not ownership_registered,
                        )
            finally:
                if ownership_enabled:
                    _ANDROID_COMMAND_EXECUTION_LOCK.release()
            if cleanup_failure is not None:
                raise cleanup_failure from setup_error
            raise

    def _wait_for_process(self, proc, timeout: int = 120, **wait_options) -> dict:
        ownership_enabled = _android_process_ownership_enabled()
        owner = None
        try:
            if ownership_enabled:
                with _ANDROID_PROCESS_OWNER_LOCK:
                    owner = _ANDROID_PROCESS_OWNERS.get(proc.pid)
                if owner is None:
                    detail = "Android command process ownership was not registered"
                    _record_unsafe_android_process(detail, unrecoverable=True)
                    raise RuntimeError(detail)
            try:
                result = super()._wait_for_process(proc, timeout=timeout, **wait_options)
            except BaseException:
                if ownership_enabled:
                    self._cleanup_abandoned_process(proc)
                raise
            if not ownership_enabled:
                return result
            if getattr(proc, "_hermes_android_cleanup_verified", False):
                return result
            try:
                detached_count, error = _terminate_android_process_owner(
                    proc.pid,
                    owner,
                    timeout=2.0,
                )
                writer_error = _finish_android_stdin_writer(proc.pid, timeout=1.0)
                if writer_error:
                    error = f"{error}; {writer_error}" if error else writer_error
                drain_error = _finish_android_stdout_drain(proc.pid, timeout=1.0)
                if drain_error:
                    error = f"{error}; {drain_error}" if error else drain_error
                if error:
                    _record_unsafe_android_process(error)
                    raise RuntimeError(error)
                _forget_android_process_owner(proc.pid)
            except BaseException as cleanup_error:  # noqa: BLE001
                _record_unsafe_android_process(
                    "Android command post-exit cleanup raised "
                    f"{type(cleanup_error).__name__}: {cleanup_error}"
                )
                raise
            if detached_count:
                result["returncode"] = 125
                result["output"] = (
                    result.get("output", "")
                    + "\nHermes stopped detached Android command descendants. Persistent "
                    "terminal background commands are disabled in the embedded Android "
                    "runtime because their full process lifetime cannot be certified."
                ).lstrip("\n")
            return result
        finally:
            _release_android_command_execution_lock(proc)

    def _cleanup_abandoned_process(self, proc) -> None:
        if not _android_process_ownership_enabled():
            super()._cleanup_abandoned_process(proc)
            return
        try:
            if getattr(proc, "_hermes_android_cleanup_verified", False):
                return
            with _ANDROID_PROCESS_OWNER_LOCK:
                owner = _ANDROID_PROCESS_OWNERS.get(proc.pid)
            if owner is None:
                detail = "Android command process ownership was not registered"
                _record_unsafe_android_process(detail, unrecoverable=True)
                raise RuntimeError(detail)
            detached_count, error = _terminate_android_process_owner(
                proc.pid,
                owner,
                timeout=2.0,
            )
            writer_error = _finish_android_stdin_writer(proc.pid, timeout=1.0)
            if writer_error:
                error = f"{error}; {writer_error}" if error else writer_error
            drain_error = _finish_android_stdout_drain(proc.pid, timeout=1.0)
            if drain_error:
                error = f"{error}; {drain_error}" if error else drain_error
            if error:
                _record_unsafe_android_process(error)
                raise RuntimeError(error)
            _forget_android_process_owner(proc.pid)
            proc._hermes_android_cleanup_verified = True
            proc._hermes_android_detached_count = detached_count
        except BaseException as exc:
            _record_unsafe_android_process(
                f"Android command abandonment cleanup raised {type(exc).__name__}: {exc}"
            )
            raise
        finally:
            _release_android_command_execution_lock(proc)

    def _kill_process(self, proc):
        if _android_process_ownership_enabled():
            with _ANDROID_PROCESS_OWNER_LOCK:
                owner = _ANDROID_PROCESS_OWNERS.get(proc.pid)
            if owner is None:
                detail = "Android command process ownership was not registered"
                _record_unsafe_android_process(detail, unrecoverable=True)
                raise RuntimeError(detail)
            detached_count, error = _terminate_android_process_owner(
                proc.pid,
                owner,
                timeout=2.0,
            )
            writer_error = _finish_android_stdin_writer(proc.pid, timeout=1.0)
            if writer_error:
                error = f"{error}; {writer_error}" if error else writer_error
            drain_error = _finish_android_stdout_drain(proc.pid, timeout=1.0)
            if drain_error:
                error = f"{error}; {drain_error}" if error else drain_error
            if error:
                _record_unsafe_android_process(error)
                raise RuntimeError(error)
            _forget_android_process_owner(proc.pid)
            proc._hermes_android_cleanup_verified = True
            proc._hermes_android_detached_count = detached_count
            return
        if os.name == "nt":
            try:
                proc.terminate()
                try:
                    proc.wait(timeout=1.0)
                except subprocess.TimeoutExpired:
                    proc.kill()
            except Exception:
                pass
            return
        try:
            pgid = os.getpgid(proc.pid)
            os.killpg(pgid, signal.SIGTERM)  # windows-footgun: ok - POSIX branch only
            try:
                proc.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                os.killpg(  # windows-footgun: ok - POSIX branch only
                    pgid,
                    getattr(signal, "SIGKILL", signal.SIGTERM),
                )
        except (ProcessLookupError, PermissionError):
            try:
                proc.kill()
            except Exception:
                pass

    def cleanup(self):
        for file_path in (self._snapshot_path, self._cwd_file):
            try:
                os.unlink(file_path)
            except OSError:
                pass
