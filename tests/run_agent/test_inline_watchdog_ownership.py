"""Inline-request cleanup retains even a partially admitted watchdog."""

import threading
import time

import pytest

from agent.chat_completion_helpers import _InlineRequest
from hermes_android.agent_lifecycle import OwnedAgentWorkerMixin


@pytest.mark.parametrize("failure", ["start", "cancel"])
def test_inline_watchdogs_remain_owned_through_start_or_cancel_failure(monkeypatch, failure):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    admitted, release = threading.Event(), threading.Event()
    timers = []

    class Agent(OwnedAgentWorkerMixin):
        def _touch_activity(self, _label):
            pass

    class Timer:
        def __init__(self, interval, target):
            self._thread = threading.Thread(target=lambda: (admitted.set(), release.wait(5)), daemon=True)
            timers.append(self)

        @property
        def ident(self):
            return self._thread.ident

        def is_alive(self):
            return self._thread.is_alive()

        def start(self):
            self._thread.start()
            assert admitted.wait(2)
            if failure == "start":
                raise KeyboardInterrupt("admitted timer start interrupted")

        def cancel(self):
            release.set()
            if failure == "cancel":
                raise KeyboardInterrupt("admitted timer cancel interrupted")

        def join(self, timeout=None):
            self._thread.join(timeout)

    monkeypatch.setattr(threading, "Timer", Timer)
    agent = Agent()
    request = _InlineRequest(agent, {}, 60, time.time())
    try:
        with pytest.raises(KeyboardInterrupt, match=f"admitted timer {failure} interrupted"):
            request.start_watchdogs()
            request.stop_watchdogs()
        assert request.done
        assert timers and all(not timer.is_alive() for timer in timers)
        assert agent.owned_worker_names() == []
    finally:
        release.set()
        request._hb_stop.set()
        for timer in timers:
            timer.join(2)
        if request._hb is not None:
            request._hb.join(2)
