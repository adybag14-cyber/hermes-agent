"""Embedded admission precedes every lazy plugin worker/discovery entry point."""

from hermes_cli import plugins


def test_embedded_plugin_consumers_do_not_start_or_join_discovery(monkeypatch):
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

    def forbidden(*args, **kwargs):
        raise AssertionError("embedded consumers must not enter plugin discovery")

    monkeypatch.setattr(plugins, "get_plugin_manager", forbidden)
    monkeypatch.setattr(plugins, "_join_background_discovery", forbidden)
    assert plugins.discover_plugins(force=True) is None
    assert plugins.start_background_plugin_discovery() is None
    assert plugins.invoke_hook("pre_llm_call") == []
    assert plugins.invoke_middleware("model_request") == []
    assert plugins.render_system_prompt_sections({}) == []
    assert plugins.has_hook("pre_llm_call") is False
    assert plugins.has_middleware("model_request") is False
    assert plugins.iter_hook_callbacks("pre_llm_call") == ()
    assert plugins.get_plugin_context_engine() is None
    assert plugins.get_plugin_command_handler("custom") is None
