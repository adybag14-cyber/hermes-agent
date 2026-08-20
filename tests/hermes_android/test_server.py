import asyncio
import json
import threading
from unittest.mock import Mock, patch

import pytest

from hermes_android.server import (
    AndroidServerHandle,
    AndroidServerStartupError,
    _run_owned_event_loop,
    start_local_api_server,
)
from hermes_android import server_bridge
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter


class FakeAdapter:
    def __init__(self, config):
        self.config = config
        self.connected = False
        self.disconnected = False

    async def connect(self):
        self.connected = True
        return True

    async def disconnect(self):
        self.disconnected = True


class SlowAdapter:
    def __init__(self, config):
        self.config = config

    async def connect(self):
        await asyncio.sleep(5)
        return True

    async def disconnect(self):
        return None


class FailingAdapter:
    instances = []

    def __init__(self, config):
        self.config = config
        self.disconnected = False
        self.__class__.instances.append(self)

    async def connect(self):
        raise RuntimeError("adapter connect exploded")

    async def disconnect(self):
        self.disconnected = True


class NonStoppingThread:
    def __init__(self):
        self.join_timeout = None

    def join(self, timeout=None):
        self.join_timeout = timeout

    def is_alive(self):
        return True


class StopLoop:
    def __init__(self):
        self.stop_requested = False

    def stop(self):
        self.stop_requested = True

    def call_soon_threadsafe(self, callback):
        callback()


class OwnedAgent:
    def __init__(self, session_id="owned-session", live_worker_names=None):
        self.session_id = session_id
        self.shutdown_reasons = []
        self.closed = False
        self.clients_released = False
        self.wait_timeout = None
        self._live_worker_names = live_worker_names or (lambda: [])

    def begin_owned_worker_shutdown(self, reason):
        self.shutdown_reasons.append(reason)

    def owned_worker_names(self):
        return list(self._live_worker_names())

    def wait_for_owned_workers(self, timeout):
        self.wait_timeout = timeout
        return self.owned_worker_names()

    def close(self):
        self.closed = True

    def release_clients(self):
        self.clients_released = True


def _android_api_adapter():
    with patch.dict("os.environ", {"HERMES_ANDROID_BOOTSTRAP": "1"}):
        return APIServerAdapter(PlatformConfig(enabled=True, extra={}))


def _release_adapter_task_pins(adapter):
    from tools.process_registry import process_registry

    for task_id in list(adapter._owned_task_ids):
        process_registry.release_task_ownership(task_id)


def test_start_local_api_server_bootstraps_and_starts(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8765,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }

    with patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload), \
         patch("hermes_android.server.APIServerAdapter", FakeAdapter):
        handle = start_local_api_server(str(tmp_path / "files"), api_server_port=8765, api_server_key="android-key")

    try:
        assert handle.base_url == "http://127.0.0.1:8765"
        assert handle.adapter.connected is True
        assert handle.adapter.config.extra["host"] == "127.0.0.1"
        assert handle.adapter.config.extra["port"] == 8765
        assert handle.adapter.config.extra["key"] == "android-key"
    finally:
        handle.stop()
        assert handle.adapter.disconnected is True


def test_start_local_api_server_timeout_is_actionable(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8766,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }

    with patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload), \
         patch("hermes_android.server.APIServerAdapter", SlowAdapter), \
         pytest.raises(TimeoutError) as error:
        start_local_api_server(
            str(tmp_path / "files"),
            api_server_port=8766,
            api_server_key="android-key",
            connect_timeout=0.01,
        )

    message = str(error.value)
    assert "Timed out starting the Android local API server" in message
    assert "Free phone storage" in message


def test_start_local_api_server_connect_exception_unwinds_thread(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8767,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }
    FailingAdapter.instances.clear()

    with (
        patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload),
        patch("hermes_android.server.APIServerAdapter", FailingAdapter),
        pytest.raises(RuntimeError, match="adapter connect exploded"),
    ):
        start_local_api_server(
            str(tmp_path / "files"),
            api_server_port=8767,
            api_server_key="android-key",
            connect_timeout=0.5,
        )

    assert FailingAdapter.instances[-1].disconnected is True
    assert not any(
        thread.name == "hermes-android-api-server" and thread.is_alive()
        for thread in threading.enumerate()
    )


def test_start_local_api_server_scheduling_failure_still_unwinds_owned_thread(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8768,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }
    original_schedule = asyncio.run_coroutine_threadsafe
    schedule_count = 0

    def schedule(coroutine, loop):
        nonlocal schedule_count
        schedule_count += 1
        if schedule_count == 1:
            raise RuntimeError("connect scheduling failed")
        return original_schedule(coroutine, loop)

    with (
        patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload),
        patch("hermes_android.server.APIServerAdapter", FakeAdapter),
        patch("hermes_android.server.asyncio.run_coroutine_threadsafe", side_effect=schedule),
        pytest.raises(RuntimeError, match="connect scheduling failed"),
    ):
        start_local_api_server(str(tmp_path / "files"), connect_timeout=0.5)

    assert schedule_count >= 2
    assert not any(
        thread.name == "hermes-android-api-server" and thread.is_alive()
        for thread in threading.enumerate()
    )


def test_start_local_api_server_base_exception_still_unwinds_owned_thread(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8769,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }
    original_schedule = asyncio.run_coroutine_threadsafe
    schedule_count = 0

    def schedule(coroutine, loop):
        nonlocal schedule_count
        schedule_count += 1
        if schedule_count == 1:
            raise KeyboardInterrupt("startup interrupted after thread start")
        return original_schedule(coroutine, loop)

    with (
        patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload),
        patch("hermes_android.server.APIServerAdapter", FakeAdapter),
        patch("hermes_android.server.asyncio.run_coroutine_threadsafe", side_effect=schedule),
        pytest.raises(KeyboardInterrupt, match="startup interrupted after thread start"),
    ):
        start_local_api_server(str(tmp_path / "files"), connect_timeout=0.5)

    assert schedule_count >= 2
    assert not any(
        thread.name == "hermes-android-api-server" and thread.is_alive()
        for thread in threading.enumerate()
    )


def test_start_local_api_server_truth_check_base_exception_unwinds_owned_thread(tmp_path):
    bootstrap_payload = {
        "runtime": {
            "files_dir": str(tmp_path / "files"),
            "hermes_home": str(tmp_path / "files" / "hermes-home"),
            "api_server_host": "127.0.0.1",
            "api_server_port": 8770,
            "api_server_key": "android-key",
            "api_server_model_name": "hermes-agent-android",
        }
    }

    class TruthBomb:
        def __bool__(self):
            raise KeyboardInterrupt("startup publication interrupted")

    class TruthBombAdapter(FakeAdapter):
        instances = []

        def __init__(self, config):
            super().__init__(config)
            self.__class__.instances.append(self)

        async def connect(self):
            self.connected = True
            return TruthBomb()

    with (
        patch("hermes_android.server.bootstrap_android_runtime", return_value=bootstrap_payload),
        patch("hermes_android.server.APIServerAdapter", TruthBombAdapter),
        pytest.raises(KeyboardInterrupt, match="startup publication interrupted"),
    ):
        start_local_api_server(str(tmp_path / "files"), connect_timeout=0.5)

    assert TruthBombAdapter.instances[-1].disconnected is True
    assert not any(
        thread.name == "hermes-android-api-server" and thread.is_alive()
        for thread in threading.enumerate()
    )


def test_failed_start_cleanup_retains_base_exception_when_stop_is_not_verified():
    from hermes_android.server import _cleanup_failed_start

    future = Mock()
    future.cancel.side_effect = KeyboardInterrupt("cancel interrupted")
    handle = Mock()
    handle.thread.ident = 123
    handle.thread.is_alive.return_value = True
    handle.stop.side_effect = MemoryError("stop allocation failed")
    handle.shutdown_complete.is_set.return_value = False

    failure = _cleanup_failed_start(handle, future, timeout=0.1)

    assert isinstance(failure, KeyboardInterrupt)
    handle.stop.assert_called_once_with(timeout=0.1)


def test_server_handle_stop_rejects_a_thread_that_remains_alive():
    thread = NonStoppingThread()
    loop = StopLoop()
    scheduled = Mock()
    scheduled.result.return_value = None
    handle = AndroidServerHandle(
        runtime=Mock(),
        adapter=Mock(),
        loop=loop,
        thread=thread,
    )

    def schedule_shutdown(coroutine, _loop):
        coroutine.close()
        return scheduled

    with (
        patch("hermes_android.server.asyncio.run_coroutine_threadsafe", side_effect=schedule_shutdown),
        pytest.raises(TimeoutError, match="replacement server is forbidden"),
    ):
        handle.stop(timeout=0.01)

    assert 0.0 <= thread.join_timeout <= 0.01
    assert loop.stop_requested is True


def test_server_handle_stop_still_stops_loop_when_future_cancel_raises():
    thread = NonStoppingThread()
    loop = StopLoop()
    scheduled = Mock()
    scheduled.result.side_effect = RuntimeError("disconnect failed")
    scheduled.cancel.side_effect = KeyboardInterrupt("cancel interrupted")
    handle = AndroidServerHandle(
        runtime=Mock(),
        adapter=Mock(),
        loop=loop,
        thread=thread,
    )

    def schedule_shutdown(coroutine, _loop):
        coroutine.close()
        return scheduled

    with (
        patch("hermes_android.server.asyncio.run_coroutine_threadsafe", side_effect=schedule_shutdown),
        pytest.raises(TimeoutError, match="replacement server is forbidden"),
    ):
        handle.stop(timeout=0.01)

    assert loop.stop_requested is True
    assert thread.join_timeout is not None
    assert 0.0 <= thread.join_timeout <= 0.01


def test_server_handle_requires_default_executor_quiescence_before_stop_succeeds():
    loop = asyncio.new_event_loop()
    shutdown_complete = threading.Event()
    shutdown_errors = []
    thread = threading.Thread(
        target=_run_owned_event_loop,
        args=(loop, shutdown_complete, shutdown_errors),
        daemon=True,
    )
    worker_started = threading.Event()
    release_worker = threading.Event()

    def blocking_agent_work():
        worker_started.set()
        release_worker.wait(timeout=5.0)

    class OwnedAdapter:
        async def disconnect_owned_runtime(self, task_timeout=5.0):
            return None

    thread.start()
    submitted = asyncio.run_coroutine_threadsafe(
        _submit_default_executor_work(loop, blocking_agent_work),
        loop,
    )
    submitted.result(timeout=1.0)
    assert worker_started.wait(timeout=1.0)
    handle = AndroidServerHandle(
        runtime=Mock(),
        adapter=OwnedAdapter(),
        loop=loop,
        thread=thread,
        shutdown_complete=shutdown_complete,
        shutdown_errors=shutdown_errors,
    )
    try:
        with pytest.raises(TimeoutError, match="default-executor agent/tool work may still be active"):
            handle.stop(timeout=0.05)
        assert thread.is_alive()
    finally:
        release_worker.set()
        thread.join(timeout=2.0)
    assert not thread.is_alive()
    assert shutdown_complete.is_set()
    assert shutdown_errors == []


def test_authoritative_loop_finalization_supersedes_provisional_disconnect_error():
    loop = asyncio.new_event_loop()
    shutdown_complete = threading.Event()
    shutdown_errors = []

    class RetryDisconnectAdapter:
        def __init__(self):
            self.calls = 0

        async def disconnect_owned_runtime(self, task_timeout=5.0):
            self.calls += 1
            if self.calls == 1:
                raise TimeoutError("provisional disconnect timed out")

    adapter = RetryDisconnectAdapter()
    thread = threading.Thread(
        target=_run_owned_event_loop,
        args=(loop, shutdown_complete, shutdown_errors, None, adapter.disconnect_owned_runtime),
        daemon=True,
    )
    thread.start()
    handle = AndroidServerHandle(
        runtime=Mock(),
        adapter=adapter,
        loop=loop,
        thread=thread,
        shutdown_complete=shutdown_complete,
        shutdown_errors=shutdown_errors,
    )

    handle.stop(timeout=2.0)

    assert not thread.is_alive()
    assert adapter.calls == 2
    assert shutdown_complete.is_set()
    assert shutdown_errors == []


async def _submit_default_executor_work(loop, callback):
    loop.run_in_executor(None, callback)


def test_dead_loop_thread_without_successful_finalizers_is_not_safe_to_replace():
    loop = asyncio.new_event_loop()
    shutdown_complete = threading.Event()
    shutdown_errors = []

    async def broken_default_executor_shutdown():
        raise RuntimeError("executor shutdown exploded")

    loop.shutdown_default_executor = broken_default_executor_shutdown
    thread = threading.Thread(
        target=_run_owned_event_loop,
        args=(loop, shutdown_complete, shutdown_errors),
        daemon=True,
    )
    thread.start()
    loop.call_soon_threadsafe(loop.stop)
    thread.join(timeout=2.0)
    assert not thread.is_alive()
    assert not shutdown_complete.is_set()
    assert any("executor shutdown exploded" in str(error) for error in shutdown_errors)

    handle = AndroidServerHandle(
        runtime=Mock(),
        adapter=Mock(),
        loop=loop,
        thread=thread,
        shutdown_complete=shutdown_complete,
        shutdown_errors=shutdown_errors,
    )
    with pytest.raises(RuntimeError, match="without proving"):
        handle.stop(timeout=0.01)


def test_api_adapter_shutdown_closes_agent_admission_and_interrupts_owned_agents():
    adapter = _android_api_adapter()
    first = OwnedAgent(session_id="first")
    second = OwnedAgent(session_id="second")

    try:
        assert adapter._register_owned_agent(first) is True
        adapter.begin_owned_shutdown()

        assert first.shutdown_reasons == ["API server is shutting down"]
        assert adapter._register_owned_agent(second) is False
        assert second in adapter._owned_agents
    finally:
        _release_adapter_task_pins(adapter)


def test_api_adapter_finalizer_rejects_late_agent_owned_worker():
    adapter = _android_api_adapter()
    worker_live = True
    late = OwnedAgent(
        session_id="late-session",
        live_worker_names=lambda: ["openrouter-prewarm"] if worker_live else [],
    )
    adapter._owned_shutdown_requested = True

    try:
        assert adapter._register_owned_agent(late) is False
        with pytest.raises(RuntimeError, match="openrouter-prewarm"):
            adapter.finalize_owned_runtime_resources()

        assert late.closed is False
        assert late in adapter._owned_agents
    finally:
        _release_adapter_task_pins(adapter)


def test_completed_agent_releases_clients_but_retains_task_ids_for_finalizer():
    adapter = _android_api_adapter()
    agent = OwnedAgent(session_id="agent-session")
    assert adapter._register_owned_agent(agent) is True

    adapter._complete_owned_agent(agent, "generated-effective-task")

    assert agent.shutdown_reasons == ["API request completed"]
    assert agent.wait_timeout == adapter._ANDROID_REQUEST_WORKER_DRAIN_TIMEOUT_SECONDS
    assert agent.clients_released is True
    assert agent not in adapter._owned_agents
    assert adapter._owned_task_ids == {"agent-session", "generated-effective-task"}
    _release_adapter_task_pins(adapter)


def test_completed_agent_with_live_worker_poison_closes_next_request_admission():
    adapter = _android_api_adapter()
    agent = OwnedAgent(
        session_id="unsafe-session",
        live_worker_names=lambda: ["agent-api-call"],
    )
    try:
        assert adapter._register_owned_agent(agent) is True

        with pytest.raises(RuntimeError, match="did not unwind safely"):
            adapter._complete_owned_agent(agent, "unsafe-effective-task")

        assert agent in adapter._owned_agents
        assert adapter._owned_shutdown_requested is True
        with patch("run_agent.AIAgent") as agent_constructor:
            with pytest.raises(RuntimeError, match="did not unwind safely"):
                adapter._run_with_owned_runtime(agent_constructor)
        agent_constructor.assert_not_called()
    finally:
        _release_adapter_task_pins(adapter)


def test_completed_agent_retains_task_id_with_process_history():
    from tools.process_registry import process_registry

    adapter = _android_api_adapter()
    agent = OwnedAgent(session_id="agent-session")
    try:
        with patch.object(process_registry, "has_tracked_sessions_for_task", return_value=True):
            assert adapter._register_owned_agent(agent) is True
            adapter._complete_owned_agent(agent, "generated-effective-task")

        assert adapter._owned_task_ids == {"agent-session", "generated-effective-task"}
    finally:
        _release_adapter_task_pins(adapter)


def test_owned_agent_worker_tracker_retains_child_until_it_exits():
    from run_agent import AIAgent

    agent = object.__new__(AIAgent)
    agent._owned_worker_lock = threading.Lock()
    agent._owned_worker_threads = set()
    agent._owned_worker_shutdown_requested = False
    agent.interrupt = Mock()
    agent.shutdown_memory_provider = Mock()
    worker_started = threading.Event()
    release_worker = threading.Event()

    def blocking_child():
        worker_started.set()
        release_worker.wait(timeout=5.0)

    worker = agent._start_owned_worker_thread(
        target=blocking_child,
        name="owned-test-child",
    )
    try:
        assert worker_started.wait(timeout=1.0)
        agent.begin_owned_worker_shutdown("test shutdown")
        assert agent.owned_worker_names() == ["owned-test-child"]
        agent.interrupt.assert_called_once_with("test shutdown")
        agent.shutdown_memory_provider.assert_called_once_with()
    finally:
        release_worker.set()
        worker.join(timeout=2.0)

    assert not worker.is_alive()
    assert agent.owned_worker_names() == []


def test_owned_worker_reference_is_swept_only_after_owner_observes_thread_exit():
    from run_agent import AIAgent

    class RecordingSet(set):
        def __init__(self):
            super().__init__()
            self.discard_threads = []

        def discard(self, value):
            self.discard_threads.append(threading.current_thread().name)
            super().discard(value)

    agent = object.__new__(AIAgent)
    agent._owned_worker_lock = threading.RLock()
    agent._owned_worker_threads = RecordingSet()
    agent._owned_worker_shutdown_requested = False
    worker = agent._start_owned_worker_thread(target=lambda: None, name="owned-tail-window")
    worker.join(timeout=2.0)

    assert worker.is_alive() is False
    assert worker in agent._owned_worker_threads
    assert agent._owned_worker_threads.discard_threads == []
    assert agent.owned_worker_names() == []
    assert agent._owned_worker_threads.discard_threads == [threading.current_thread().name]


def test_owned_worker_admission_sweeps_finished_references_with_bounded_retention():
    from run_agent import AIAgent

    agent = object.__new__(AIAgent)
    agent._owned_worker_lock = threading.RLock()
    agent._owned_worker_threads = set()
    agent._owned_worker_shutdown_requested = False

    for index in range(64):
        worker = agent._start_owned_worker_thread(
            target=lambda: None,
            name=f"owned-finished-{index}",
        )
        worker.join(timeout=2.0)

        assert worker.is_alive() is False
        assert agent._owned_worker_threads == {worker}


def test_owned_agent_worker_start_interruption_retains_admitted_thread():
    from run_agent import AIAgent

    agent = object.__new__(AIAgent)
    agent._owned_worker_lock = threading.RLock()
    agent._owned_worker_threads = set()
    agent._owned_worker_shutdown_requested = False
    worker_started = threading.Event()
    release_worker = threading.Event()
    original_thread = threading.Thread
    created = []

    class StartRaisesAfterAdmission:
        def __init__(self, *, target, daemon, name):
            self._inner = original_thread(target=target, daemon=daemon, name=name)
            self.name = name
            created.append(self)

        @property
        def ident(self):
            return self._inner.ident

        def is_alive(self):
            return self._inner.is_alive()

        def start(self):
            self._inner.start()
            assert worker_started.wait(timeout=1.0)
            raise KeyboardInterrupt("thread start publication interrupted")

        def join(self, timeout=None):
            self._inner.join(timeout=timeout)

    def blocking_child():
        worker_started.set()
        release_worker.wait(timeout=5.0)

    with (
        patch("run_agent.threading.Thread", StartRaisesAfterAdmission),
        pytest.raises(KeyboardInterrupt, match="thread start publication interrupted"),
    ):
        agent._start_owned_worker_thread(
            target=blocking_child,
            name="owned-start-race-child",
        )

    worker = created[0]
    try:
        assert agent.owned_worker_names() == ["owned-start-race-child"]
    finally:
        release_worker.set()
        worker.join(timeout=2.0)

    assert worker.is_alive() is False
    assert agent.owned_worker_names() == []


def test_android_api_agent_constructor_cannot_start_background_work():
    from hermes_android.agent_lifecycle import _constructor_background_work_allowed

    with patch.dict("os.environ", {"HERMES_ANDROID_BOOTSTRAP": "1"}):
        assert _constructor_background_work_allowed("api_server") is False
        assert _constructor_background_work_allowed("cli") is True
    with patch.dict("os.environ", {}, clear=True):
        assert _constructor_background_work_allowed("api_server") is True


def test_server_bridge_retains_and_poisons_handle_when_stop_fails():
    original_handle = server_bridge._ACTIVE_HANDLE
    original_stop_error = server_bridge._UNSAFE_STOP_ERROR
    handle = Mock()
    handle.stop.side_effect = TimeoutError("server thread is still alive")
    server_bridge._ACTIVE_HANDLE = handle
    server_bridge._UNSAFE_STOP_ERROR = ""
    try:
        with pytest.raises(RuntimeError, match="Force stop and reopen Hermes"):
            server_bridge.stop_server()
        assert server_bridge._ACTIVE_HANDLE is handle
        status = json.loads(server_bridge.current_server_status())
        assert status["started"] is False
        assert status["requires_app_restart"] is True
        assert "agent/tool workers" in status["error"]
        with (
            patch("hermes_android.server_bridge.start_local_api_server") as retry,
            pytest.raises(RuntimeError, match="Force stop and reopen Hermes"),
        ):
            server_bridge.ensure_server("/tmp/hermes")
        retry.assert_not_called()
    finally:
        server_bridge._ACTIVE_HANDLE = original_handle
        server_bridge._UNSAFE_STOP_ERROR = original_stop_error


def test_server_bridge_poison_blocks_retry_after_unwound_startup_cannot_be_verified():
    original_handle = server_bridge._ACTIVE_HANDLE
    original_unsafe = server_bridge._UNSAFE_STARTUP_HANDLE
    original_error = server_bridge._UNSAFE_STARTUP_ERROR
    original_stop_error = server_bridge._UNSAFE_STOP_ERROR
    unsafe_handle = Mock()
    unsafe_handle.thread.is_alive.return_value = True
    unsafe_handle.shutdown_complete.is_set.return_value = False
    startup_error = AndroidServerStartupError(
        "startup worker is still alive; force stop and reopen Hermes",
        unsafe_handle=unsafe_handle,
    )
    server_bridge._ACTIVE_HANDLE = None
    server_bridge._UNSAFE_STARTUP_HANDLE = None
    server_bridge._UNSAFE_STARTUP_ERROR = ""
    server_bridge._UNSAFE_STOP_ERROR = ""
    try:
        with (
            patch("hermes_android.server_bridge.start_local_api_server", side_effect=startup_error) as start,
            pytest.raises(AndroidServerStartupError, match="force stop and reopen"),
        ):
            server_bridge.ensure_server("/tmp/hermes")

        with (
            patch("hermes_android.server_bridge.start_local_api_server") as retry,
            pytest.raises(RuntimeError, match="force stop and reopen"),
        ):
            server_bridge.ensure_server("/tmp/hermes")

        assert start.call_count == 1
        retry.assert_not_called()
        status = json.loads(server_bridge.current_server_status())
        assert status == {
            "error": "startup worker is still alive; force stop and reopen Hermes",
            "requires_app_restart": True,
            "started": False,
        }
    finally:
        server_bridge._ACTIVE_HANDLE = original_handle
        server_bridge._UNSAFE_STARTUP_HANDLE = original_unsafe
        server_bridge._UNSAFE_STARTUP_ERROR = original_error
        server_bridge._UNSAFE_STOP_ERROR = original_stop_error
