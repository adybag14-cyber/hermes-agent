from __future__ import annotations

import asyncio
import threading
import time
from concurrent.futures import TimeoutError as FutureTimeoutError
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from hermes_android.python_path import prefer_hermes_package_root

prefer_hermes_package_root()

from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_android.bootstrap import bootstrap_android_runtime
from hermes_android.runtime_env import AndroidRuntimeEnv

ANDROID_API_SERVER_CONNECT_TIMEOUT_SECONDS = 90.0


class AndroidServerStartupError(RuntimeError):
    """Startup failed and an API-server thread could not be proven stopped."""

    def __init__(self, message: str, *, unsafe_handle: AndroidServerHandle | None = None):
        super().__init__(message)
        self.unsafe_handle = unsafe_handle


@dataclass
class AndroidServerHandle:
    runtime: AndroidRuntimeEnv
    adapter: APIServerAdapter
    loop: asyncio.AbstractEventLoop
    thread: threading.Thread
    shutdown_complete: threading.Event = field(default_factory=threading.Event)
    shutdown_errors: list[BaseException] = field(default_factory=list)

    @property
    def base_url(self) -> str:
        from hermes_android.runtime_env import loopback_base_url

        return loopback_base_url(self.runtime.api_server_host, self.runtime.api_server_port)

    def stop(self, timeout: float = 20.0) -> None:
        async def _shutdown() -> None:
            owned_disconnect = getattr(self.adapter, "disconnect_owned_runtime", None)
            if callable(owned_disconnect):
                await owned_disconnect(task_timeout=min(5.0, max(timeout / 2.0, 0.01)))
            else:
                await self.adapter.disconnect()

        if not self.thread.is_alive():
            if self.shutdown_complete.is_set():
                return
            raise RuntimeError(self._incomplete_shutdown_detail())
        bounded_timeout = max(timeout, 0.0)
        deadline = time.monotonic() + bounded_timeout
        future = asyncio.run_coroutine_threadsafe(_shutdown(), self.loop)
        shutdown_error: BaseException | None = None
        try:
            future.result(timeout=min(bounded_timeout, max(deadline - time.monotonic(), 0.0)))
        except BaseException as exc:
            shutdown_error = exc
            try:
                future.cancel()
            except BaseException as cancel_exc:
                shutdown_error = shutdown_error or cancel_exc
        try:
            self.loop.call_soon_threadsafe(self.loop.stop)
        except BaseException as exc:
            shutdown_error = shutdown_error or exc
        self.thread.join(timeout=min(bounded_timeout, max(deadline - time.monotonic(), 0.0)))
        if self.thread.is_alive():
            error = TimeoutError(
                "Android local API server thread did not stop within "
                f"{bounded_timeout:.1f} seconds; its default-executor agent/tool "
                "work may still be active, so a replacement server is forbidden "
                "until Hermes is force-stopped and reopened"
            )
            if shutdown_error is not None:
                raise error from shutdown_error
            raise error
        if not self.shutdown_complete.is_set():
            raise RuntimeError(self._incomplete_shutdown_detail()) from shutdown_error
        # A timed provisional disconnect may finish during authoritative loop
        # finalization. The success event is set only after the loop retries
        # adapter teardown, drains its executor, and verifies owned resources.
        # That complete proof supersedes the earlier provisional exception.

    def _incomplete_shutdown_detail(self) -> str:
        failure = self.shutdown_errors[0] if self.shutdown_errors else None
        suffix = f": {failure}" if failure is not None else ""
        return (
            "Android local API server thread exited without proving that pending "
            "tasks, async generators, and default-executor agent/tool workers "
            f"stopped cleanly{suffix}. Force stop and reopen Hermes before retrying."
        )


def _build_runtime(runtime_payload: dict[str, Any]) -> AndroidRuntimeEnv:
    return AndroidRuntimeEnv(
        files_dir=Path(runtime_payload["files_dir"]),
        hermes_home=Path(runtime_payload["hermes_home"]),
        api_server_host=str(runtime_payload["api_server_host"]),
        api_server_port=int(runtime_payload["api_server_port"]),
        api_server_key=str(runtime_payload["api_server_key"]),
        api_server_model_name=str(runtime_payload["api_server_model_name"]),
    )


def _run_owned_event_loop(
    loop: asyncio.AbstractEventLoop,
    shutdown_complete: threading.Event,
    shutdown_errors: list[BaseException],
    finalize_owned_resources: Callable[[], None] | None = None,
    finalize_owned_disconnect: Callable[[], Any] | None = None,
) -> None:
    """Run and fully drain the dedicated Android API-server event loop."""

    asyncio.set_event_loop(loop)
    try:
        loop.run_forever()
    except BaseException as exc:
        shutdown_errors.append(exc)
    finally:
        executor_shutdown_succeeded = False
        try:
            pending = [task for task in asyncio.all_tasks(loop) if not task.done()]
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        except BaseException as exc:
            shutdown_errors.append(exc)
        try:
            loop.run_until_complete(loop.shutdown_asyncgens())
        except BaseException as exc:
            shutdown_errors.append(exc)
        try:
            # This join is intentionally inside the owned loop thread.
            # AndroidServerHandle.stop() only reports success after that
            # thread exits within its caller-wide deadline. A cancelled
            # request Task is not proof that run_in_executor agent/tool
            # work has stopped.
            loop.run_until_complete(loop.shutdown_default_executor())
            executor_shutdown_succeeded = True
        except BaseException as exc:
            shutdown_errors.append(exc)
        if executor_shutdown_succeeded and finalize_owned_disconnect is not None:
            try:
                loop.run_until_complete(finalize_owned_disconnect())
            except BaseException as exc:
                shutdown_errors.append(exc)
        if executor_shutdown_succeeded and finalize_owned_resources is not None:
            try:
                finalize_owned_resources()
            except BaseException as exc:
                shutdown_errors.append(exc)
        try:
            loop.close()
        except BaseException as exc:
            shutdown_errors.append(exc)
        if not shutdown_errors:
            shutdown_complete.set()


def start_local_api_server(
    files_dir: str,
    *,
    api_server_port: int | None = None,
    api_server_key: str | None = None,
    connect_timeout: float = ANDROID_API_SERVER_CONNECT_TIMEOUT_SECONDS,
) -> AndroidServerHandle:
    bootstrap = bootstrap_android_runtime(
        files_dir,
        api_server_port=api_server_port,
        api_server_key=api_server_key,
    )
    runtime = _build_runtime(bootstrap["runtime"])
    adapter = APIServerAdapter(
        PlatformConfig(
            enabled=True,
            extra={
                "host": runtime.api_server_host,
                "port": runtime.api_server_port,
                "key": runtime.api_server_key,
                "model_name": runtime.api_server_model_name,
                "cors_origins": [],
            },
        )
    )

    loop = asyncio.new_event_loop()
    shutdown_complete = threading.Event()
    shutdown_errors: list[BaseException] = []

    thread = threading.Thread(
        target=_run_owned_event_loop,
        args=(
            loop,
            shutdown_complete,
            shutdown_errors,
            getattr(adapter, "finalize_owned_runtime_resources", None),
            getattr(adapter, "disconnect_owned_runtime", None) or adapter.disconnect,
        ),
        name="hermes-android-api-server",
        daemon=True,
    )
    handle = AndroidServerHandle(
        runtime=runtime,
        adapter=adapter,
        loop=loop,
        thread=thread,
        shutdown_complete=shutdown_complete,
        shutdown_errors=shutdown_errors,
    )
    future = None
    connect_coro = None
    try:
        thread.start()
        connect_coro = adapter.connect()
        try:
            future = asyncio.run_coroutine_threadsafe(connect_coro, loop)
        except BaseException:
            close_coro = getattr(connect_coro, "close", None)
            if callable(close_coro):
                close_coro()
            raise
        connected = future.result(timeout=connect_timeout)
        if not connected:
            raise RuntimeError("Android local API server declined startup")
        return handle
    except FutureTimeoutError as exc:
        cleanup_failure = _cleanup_failed_start(
            handle,
            future,
            timeout=min(max(connect_timeout, 0.01), 5.0),
        )
        if cleanup_failure is not None:
            raise AndroidServerStartupError(
                "Timed out starting the Android local API server and its worker "
                "did not stop safely. Force stop and reopen Hermes before retrying.",
                unsafe_handle=handle,
            ) from cleanup_failure
        raise TimeoutError(
            "Timed out starting the Android local API server after "
            f"{connect_timeout:.0f} seconds. Free phone storage, retry Hermes, "
            "or switch to a local LiteRT-LM backend with a completed model."
        ) from exc
    except Exception as exc:
        cleanup_failure = _cleanup_failed_start(
            handle,
            future,
            timeout=min(max(connect_timeout, 0.01), 5.0),
        )
        if cleanup_failure is not None:
            raise AndroidServerStartupError(
                "Android local API server startup failed and its worker did not "
                "stop safely. Force stop and reopen Hermes before retrying.",
                unsafe_handle=handle,
            ) from cleanup_failure
        raise RuntimeError(f"Failed to start Android local API server: {exc}") from exc
    except BaseException as exc:
        cleanup_failure = _cleanup_failed_start(
            handle,
            future,
            timeout=min(max(connect_timeout, 0.01), 5.0),
        )
        if cleanup_failure is not None:
            raise AndroidServerStartupError(
                "Android local API server startup was interrupted and its worker "
                "did not stop safely. Force stop and reopen Hermes before retrying.",
                unsafe_handle=handle,
            ) from cleanup_failure
        raise
def _cleanup_failed_start(
    handle: AndroidServerHandle,
    connect_future: Any | None,
    *,
    timeout: float,
) -> BaseException | None:
    """Stop a partially started server and return why ownership remains unsafe."""

    cleanup_failures: list[BaseException] = []
    if connect_future is not None:
        try:
            connect_future.cancel()
        except BaseException as exc:  # noqa: BLE001 - ownership cleanup is mandatory
            cleanup_failures.append(exc)
    if handle.thread.ident is None and not handle.thread.is_alive():
        try:
            handle.loop.close()
        except BaseException as exc:  # noqa: BLE001
            cleanup_failures.append(exc)
        if not cleanup_failures:
            handle.shutdown_complete.set()
            return None
        return cleanup_failures[0]
    try:
        handle.stop(timeout=timeout)
    except BaseException as exc:  # noqa: BLE001
        cleanup_failures.append(exc)
    if handle.shutdown_complete.is_set() and not handle.thread.is_alive():
        return None
    if cleanup_failures:
        return cleanup_failures[0]
    return RuntimeError("Android local API server startup cleanup was not verified")
