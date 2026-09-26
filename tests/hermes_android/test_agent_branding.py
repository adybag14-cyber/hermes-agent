from types import SimpleNamespace

from agent.prompt_builder import DEFAULT_AGENT_IDENTITY
from hermes_android.branding import (
    ANDROID_APP_HELP_GUIDANCE, default_identity_for_runtime, help_guidance_for_runtime,
)


def test_android_default_identity_uses_agent_without_losing_behavior_guidance(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    actual = default_identity_for_runtime(DEFAULT_AGENT_IDENTITY)
    assert actual.startswith("You are Agent, an independently maintained Android assistant.")
    assert "You are Hermes" not in actual
    assert actual.split(". ", 1)[1] == DEFAULT_AGENT_IDENTITY.split(". ", 1)[1]
    assert help_guidance_for_runtime("upstream help") == ANDROID_APP_HELP_GUIDANCE
    assert "Settings > Models" in ANDROID_APP_HELP_GUIDANCE


def test_non_android_identity_and_help_are_not_renamed(monkeypatch):
    monkeypatch.delenv("HERMES_ANDROID_BOOTSTRAP", raising=False)
    monkeypatch.setattr("sys.platform", "linux")
    assert default_identity_for_runtime(DEFAULT_AGENT_IDENTITY) == DEFAULT_AGENT_IDENTITY
    assert help_guidance_for_runtime("upstream help") == "upstream help"


def test_android_prompt_assembly_uses_independent_identity(monkeypatch):
    from agent import system_prompt
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    agent = SimpleNamespace(load_soul_identity=False, skip_context_files=True)
    parts, custom_persona = system_prompt._identity_parts(agent, None)
    assert not custom_persona
    assert parts == [default_identity_for_runtime(DEFAULT_AGENT_IDENTITY)]


def test_explicit_user_persona_is_preserved(monkeypatch):
    from agent import system_prompt
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setattr(system_prompt._pb, "load_soul_md", lambda *a, **k: "My original persona")
    monkeypatch.setattr(system_prompt, "_agent_home", lambda _: None)
    agent = SimpleNamespace(load_soul_identity=True, skip_context_files=False)
    assert system_prompt._identity_parts(agent, None) == (["My original persona"], True)
