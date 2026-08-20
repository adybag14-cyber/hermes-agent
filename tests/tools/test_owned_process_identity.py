"""Verified cleanup must preserve the existing PID-reuse protection."""
import time
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from tools.process_registry import ProcessRegistry, ProcessSession


@pytest.mark.parametrize("observed_start", [222, None])
def test_verified_cleanup_rejects_changed_or_unreadable_identity(monkeypatch, observed_start):
    registry = ProcessRegistry()
    session = SimpleNamespace(pid=4321, host_start_time=111, process=Mock(), _pty=Mock())
    monkeypatch.setattr(registry, "_is_host_pid_alive", lambda _pid: True)
    monkeypatch.setattr(registry, "_safe_host_start_time", lambda _pid: observed_start)
    inventory = Mock(side_effect=AssertionError("foreign PID must not be inventoried"))
    monkeypatch.setattr(registry, "_snapshot_host_process_tree", inventory)
    for method in (
        registry._terminate_local_process_verified,
        registry._terminate_pty_process_verified,
        registry._terminate_detached_host_process_verified,
    ):
        assert "identity" in method(session, time.monotonic() + 0.1)
    inventory.assert_not_called()
    session.process.terminate.assert_not_called()
    session.process.kill.assert_not_called()
    session._pty.terminate.assert_not_called()


def test_verified_cleanup_uses_raw_owner_not_shared_container_key(monkeypatch):
    registry = ProcessRegistry()
    owned = ProcessSession("owned", "work", task_id="shared", owner_task_id="request-a")
    other = ProcessSession("other", "work", task_id="shared", owner_task_id="request-b")
    registry._running.update({owned.id: owned, other.id: other})
    stopped = []
    monkeypatch.setattr(registry, "_terminate_session_verified", lambda session, deadline: stopped.append(session.id))
    assert registry.terminate_tasks_verified({"shared"}) == 0
    assert registry.terminate_tasks_verified({"request-a"}) == 1
    assert stopped == [owned.id]
    assert registry.has_tracked_sessions_for_task("request-a")
    assert not registry.has_tracked_sessions_for_task("shared")
