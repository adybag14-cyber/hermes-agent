"""The orphan reaper must release the global resume lock before finalization."""
import threading
import types

from tui_gateway import server


def _session(agent=None, **extra):
    return {
        "agent": agent if agent is not None else types.SimpleNamespace(),
        "session_key": "session-key",
        "history": [],
        "history_lock": threading.Lock(),
        "history_version": 0,
        "running": False,
        "attached_images": [],
        "image_counter": 0,
        "cols": 80,
        "slash_worker": None,
        "show_reasoning": False,
        "tool_progress_mode": "all",
        **extra,
    }


def test_ws_orphan_reap_releases_resume_lock_before_slow_teardown(monkeypatch):
    """Grace reaping claims under the lock but finalizes after releasing it."""
    scheduled = {}
    teardown_started = threading.Event()
    release_teardown = threading.Event()

    class _Timer:
        def __init__(self, _delay, callback):
            scheduled["callback"] = callback

        def start(self):
            return None

    def _slow_teardown(_session, *, end_reason="tui_close"):
        assert end_reason == "ws_orphan_reap"
        teardown_started.set()
        assert release_teardown.wait(timeout=2.0)

    monkeypatch.setattr(server, "_WS_ORPHAN_REAP_GRACE_S", 0.01)
    monkeypatch.setattr(server.threading, "Timer", _Timer)
    monkeypatch.setattr(server, "_teardown_session", _slow_teardown)
    server._sessions["slow-orphan"] = _session(
        transport=server._detached_ws_transport,
        running=False,
    )

    server._schedule_ws_orphan_reap("slow-orphan")
    thread = threading.Thread(target=scheduled["callback"])
    thread.start()
    acquired = False
    try:
        assert teardown_started.wait(timeout=1.0)
        assert "slow-orphan" not in server._sessions
        acquired = server._session_resume_lock.acquire(timeout=0.2)
        assert acquired, "orphan teardown kept the global resume lock held"
    finally:
        if acquired:
            server._session_resume_lock.release()
        release_teardown.set()
        thread.join(timeout=2.0)
        server._sessions.pop("slow-orphan", None)

    assert not thread.is_alive()
