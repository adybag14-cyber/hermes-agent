"""Keep embedded compression and its watchdogs owned through native thread exit."""
from __future__ import annotations

from concurrent.futures import Future

from hermes_android.runtime_identity import is_embedded_android_runtime


def submit_owned_compression(owner, callback, fence):
    """A per-attempt Future whose thread is retained by the Android agent."""
    future = Future()

    def run():
        if not future.set_running_or_notify_cancel():
            return
        try:
            result = callback(fence)
        except BaseException as exc:
            future.set_exception(exc)
        else:
            future.set_result(result)

    worker = owner._start_owned_worker_thread(target=run, name="android-context-compression")
    return future, worker


def start_compression_watchdog(thread, stop_event, owner):
    """Publish ownership before start, including a partially successful start."""
    try:
        if is_embedded_android_runtime():
            owner._admit_and_start_owned_thread(thread)
        else:
            thread.start()
    except BaseException:
        stop_event.set()
        raise
