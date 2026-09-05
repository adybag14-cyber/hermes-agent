"""Real compression workers must remain owned after an Android timeout."""
import threading
from types import SimpleNamespace

import pytest

from agent.conversation_compression import (
    CompressionCommitFence, _CompressionActivityHeartbeat, _CompressionLockLeaseRefresher,
    run_compress_context_with_progress_timeout,
)
from hermes_android.agent_lifecycle import OwnedAgentWorkerMixin
from agent import auxiliary_client as aux


class Owner(OwnedAgentWorkerMixin):
    def __init__(self):
        self.interrupted = []

    def interrupt(self, reason):
        self.interrupted.append(reason)

    def shutdown_memory_provider(self):
        pass

    def _touch_activity(self, *args, **kwargs):
        pass


def test_android_timeout_retains_worker_and_forbids_retry(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    owner, fence = Owner(), CompressionCommitFence()
    release, entered = threading.Event(), threading.Event()
    calls = []

    def worker(active_fence):
        calls.append(active_fence)
        def provider(_kwargs):
            entered.set()
            assert release.wait(10)
            assert not active_fence.begin_commit()
            return [], "late"
        with aux.aux_interrupt_protection(cancel_check=lambda: active_fence.is_cancelled):
            return aux._run_protected_sync_provider_call(provider, {})

    try:
        with pytest.raises(InterruptedError, match="did not unwind"):
            run_compress_context_with_progress_timeout(
                worker=worker, messages=[], system_prompt_fallback="fallback",
                idle_timeout_seconds=0.1, total_ceiling_seconds=20,
                telemetry_agent=owner, fence=fence,
            )
        assert entered.is_set()
        assert calls == [fence]
        assert owner.interrupted
        assert owner.owned_worker_names() == ["android-context-compression"]
        assert fence.is_cancelled
        with pytest.raises(InterruptedError, match="forbids"):
            owner._start_owned_worker_thread(target=lambda: None)
    finally:
        release.set()
        assert owner.wait_for_owned_workers(5) == []


@pytest.mark.parametrize("kind", ["heartbeat", "lease"])
def test_android_compression_watchdogs_are_registered_until_exit(monkeypatch, kind):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    owner = Owner()
    release, entered = threading.Event(), threading.Event()

    def blocked_callback(*args, **kwargs):
        entered.set()
        assert release.wait(10)
        return True

    if kind == "heartbeat":
        watchdog = _CompressionActivityHeartbeat(owner, interval_seconds=0.1)
        watchdog._emit_progress_status = blocked_callback
    else:
        watchdog = _CompressionLockLeaseRefresher(
            SimpleNamespace(refresh_compression_lock=blocked_callback), "session", "holder", 60,
            worker_owner=owner,
        )
    watchdog.start()
    try:
        assert entered.wait(5)
        watchdog.stop()
        assert watchdog._thread.name in owner.owned_worker_names()
    finally:
        release.set()
        watchdog.stop()
        assert owner.wait_for_owned_workers(5) == []
