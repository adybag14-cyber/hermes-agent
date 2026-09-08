"""The embedded request path must consult Android's consent before running an agent."""
import sys
from types import SimpleNamespace

import pytest

from hermes_android import privacy
from hermes_android.api_lifecycle import OwnedApiRuntimeMixin


def test_embedded_request_denies_agent_work_until_app_consent_authority_accepts(monkeypatch):
    monkeypatch.setattr(privacy, "is_embedded_android_runtime", lambda: True)
    events = []
    allowed = False

    def require_consent():
        events.append("consent")
        if not allowed:
            raise PermissionError("Remote processing consent is absent")

    def jclass(name):
        assert name == "com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore"
        return SimpleNamespace(requireConfiguredRemoteConsent=require_consent)

    monkeypatch.setitem(sys.modules, "java", SimpleNamespace(jclass=jclass))
    adapter = OwnedApiRuntimeMixin()
    adapter._enforce_owned_runtime_shutdown = False
    agent = SimpleNamespace(run_conversation=lambda **kwargs: events.append("agent") or kwargs)

    with pytest.raises(PermissionError, match="consent is absent"):
        adapter._run_conversation_with_owned_runtime(agent, user_message="not sent")
    assert events == ["consent"]
    allowed = True
    result = adapter._run_conversation_with_owned_runtime(agent, user_message="approved")
    assert events == ["consent", "consent", "agent"]
    assert result == {"user_message": "approved"}


def test_non_embedded_runtime_does_not_import_android_bridge_but_embedded_failure_is_closed(monkeypatch):
    monkeypatch.delitem(sys.modules, "java", raising=False)
    monkeypatch.setattr(privacy, "is_embedded_android_runtime", lambda: False)
    privacy.require_remote_processing_consent()
    monkeypatch.setattr(privacy, "is_embedded_android_runtime", lambda: True)
    # A missing bridge is not interpreted as consent, and there is no environment-variable fallback.
    monkeypatch.setitem(sys.modules, "java", None)
    with pytest.raises(ModuleNotFoundError):
        privacy.require_remote_processing_consent()
