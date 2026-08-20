"""Embedded auxiliary-provider admission and watchdog ownership contracts."""

import threading
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from agent.auxiliary_client import (
    _CodexCompletionsAdapter, _try_configured_fallback_chain, resolve_provider_client,
)


class TestAndroidAuxWatchdogOwnership:
    def test_embedded_android_joins_timeout_watchdog_before_return(self, monkeypatch):
        class FakeStream:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def __iter__(self):
                return iter(())

            def get_final_response(self):
                return SimpleNamespace(
                    output=[SimpleNamespace(
                        type="message",
                        content=[SimpleNamespace(type="output_text", text="summary")],
                    )],
                    usage=None,
                )

        class FakeResponses:
            def __init__(self):
                self.kwargs = None

            def create(self, **kwargs):
                self.kwargs = kwargs
                return FakeStream().get_final_response()

        fake_client = SimpleNamespace(responses=FakeResponses())
        adapter = _CodexCompletionsAdapter(fake_client, "gpt-5.5")
        monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

        class FakeTimer:
            instances = []

            def __init__(self, interval, target):
                self.interval = interval
                self.target = target
                self.daemon = False
                self.started = False
                self.cancelled = False
                self.joined = False
                self.alive = False
                self.instances.append(self)

            def start(self):
                self.started = True
                self.alive = True

            def cancel(self):
                self.cancelled = True

            def join(self):
                self.joined = True
                self.alive = False

            def is_alive(self):
                return self.alive

        with patch("agent.auxiliary_client.threading.Timer", FakeTimer):
            response = adapter.create(
                messages=[{"role": "user", "content": "summarize this"}],
                timeout=12.5,
            )

        assert len(FakeTimer.instances) == 1
        timer = FakeTimer.instances[0]
        assert timer.started is True
        assert timer.cancelled is True
        assert timer.joined is True
        assert fake_client.responses.kwargs["timeout"] == 12.5
        assert response.choices[0].message.content == "summary"


    def test_embedded_android_joins_watchdog_admitted_before_start_raises(self, monkeypatch):
        fake_client = SimpleNamespace(responses=MagicMock(), close=lambda: None)
        adapter = _CodexCompletionsAdapter(fake_client, "gpt-5.5")
        monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
        admitted = threading.Event()
        release = threading.Event()
        created = []

        class StartRaisesAfterAdmission:
            def __init__(self, interval, target):
                del interval, target
                self.daemon = False
                self.cancelled = False
                self.joined = False
                self._inner = threading.Thread(
                    target=lambda: (admitted.set(), release.wait(timeout=5.0)),
                    daemon=True,
                )
                created.append(self)

            def start(self):
                self._inner.start()
                assert admitted.wait(timeout=1.0)
                raise KeyboardInterrupt("timer start publication interrupted")

            def cancel(self):
                self.cancelled = True
                release.set()

            def join(self):
                self.joined = True
                self._inner.join()

            def is_alive(self):
                return self._inner.is_alive()

        with (
            patch("agent.auxiliary_client.threading.Timer", StartRaisesAfterAdmission),
            pytest.raises(KeyboardInterrupt, match="timer start publication interrupted"),
        ):
            adapter.create(
                messages=[{"role": "user", "content": "summarize this"}],
                timeout=12.5,
            )

        timer = created[0]
        assert timer.cancelled is True
        assert timer.joined is True
        assert timer.is_alive() is False


    def test_embedded_android_joins_watchdog_even_when_cancel_raises(self, monkeypatch):
        class FakeStream:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def __iter__(self):
                return iter(())

            def get_final_response(self):
                return SimpleNamespace(output=[], usage=None)

        fake_client = SimpleNamespace(
            responses=SimpleNamespace(stream=lambda **kwargs: FakeStream()),
            close=lambda: None,
        )
        adapter = _CodexCompletionsAdapter(fake_client, "gpt-5.5")
        monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
        admitted = threading.Event()
        release = threading.Event()
        created = []

        class CancelRaisesAfterReleasing:
            def __init__(self, interval, target):
                del interval, target
                self.daemon = False
                self.joined = False
                self._inner = threading.Thread(
                    target=lambda: (admitted.set(), release.wait(timeout=5.0)),
                    daemon=True,
                )
                created.append(self)

            def start(self):
                self._inner.start()
                assert admitted.wait(timeout=1.0)

            def cancel(self):
                release.set()
                raise KeyboardInterrupt("timer cancel interrupted")

            def join(self):
                self.joined = True
                self._inner.join()

            def is_alive(self):
                return self._inner.is_alive()

        with (
            patch("agent.auxiliary_client.threading.Timer", CancelRaisesAfterReleasing),
            pytest.raises(KeyboardInterrupt, match="timer cancel interrupted"),
        ):
            adapter.create(
                messages=[{"role": "user", "content": "summarize this"}],
                timeout=12.5,
            )

        timer = created[0]
        assert timer.joined is True
        assert timer.is_alive() is False


@pytest.mark.parametrize(
    ("provider", "base_url", "api_mode"),
    [
        ("copilot-acp", None, None),
        ("custom", "acp+tcp://127.0.0.1:9999", None),
        ("custom", "https://example.invalid/v1", "codex_app_server"),
    ],
)
def test_embedded_android_rejects_process_backed_auxiliary_transport(
    monkeypatch,
    provider,
    base_url,
    api_mode,
):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

    with pytest.raises(RuntimeError, match="process-backed"):
        resolve_provider_client(
            provider,
            model="test-model",
            explicit_base_url=base_url,
            api_mode=api_mode,
        )


def test_embedded_android_rejects_named_auxiliary_provider_command(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    unsafe_entry = {
        "name": "unsafe-command-provider",
        "base_url": "https://example.invalid/v1",
        "command": "unowned-provider",
        "args": ["serve"],
    }

    with patch(
        "hermes_cli.runtime_provider._get_named_custom_provider",
        return_value=unsafe_entry,
    ), pytest.raises(RuntimeError, match="command/args"):
        resolve_provider_client("unsafe-command-provider", model="test-model")


def test_embedded_android_fallback_chain_rejects_provider_alias_that_normalizes_to_process(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    fallback_config = {
        "fallback_chain": [
            {
                "provider": "custom:copilot-acp",
                "model": "unsafe/model",
            },
        ],
    }

    with (
        patch("agent.auxiliary_client._get_auxiliary_task_config", return_value=fallback_config),
        patch("hermes_cli.auth.resolve_external_process_provider_credentials") as external_creds,
    ):
        client, model, label = _try_configured_fallback_chain(
            "compression",
            "openrouter",
        )

    assert (client, model, label) == (None, None, "")
    external_creds.assert_not_called()
