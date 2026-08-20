from pathlib import Path

import pytest

from hermes_android.mobile_defaults import (
    DEFAULT_ANDROID_API_SERVER_TOOLSETS,
    ensure_android_defaults,
    resolved_android_api_server_toolsets,
    should_force_android_api_server_toolsets,
    validate_android_provider_runtime,
)


def test_resolved_android_api_server_toolsets_defaults_for_missing_config():
    assert resolved_android_api_server_toolsets({}) == DEFAULT_ANDROID_API_SERVER_TOOLSETS
    assert should_force_android_api_server_toolsets({}) is True


def test_resolved_android_api_server_toolsets_defaults_for_invalid_override():
    config = {"platform_toolsets": {"api_server": ["does-not-exist"]}}
    assert resolved_android_api_server_toolsets(config) == DEFAULT_ANDROID_API_SERVER_TOOLSETS
    assert should_force_android_api_server_toolsets(config) is True


def test_resolved_android_api_server_toolsets_rejects_valid_but_unowned_override():
    config = {"platform_toolsets": {"api_server": ["hermes-api-server"]}}
    assert resolved_android_api_server_toolsets(config) == DEFAULT_ANDROID_API_SERVER_TOOLSETS
    assert should_force_android_api_server_toolsets(config) is True


def test_resolved_android_api_server_toolsets_accepts_only_exact_android_profile():
    config = {"platform_toolsets": {"api_server": ["hermes-android-app"]}}
    assert resolved_android_api_server_toolsets(config) == DEFAULT_ANDROID_API_SERVER_TOOLSETS
    assert should_force_android_api_server_toolsets(config) is False


def test_ensure_android_defaults_persists_api_server_toolset(tmp_path, monkeypatch):
    hermes_home = tmp_path / ".hermes"
    hermes_home.mkdir()
    monkeypatch.setenv("HERMES_HOME", str(hermes_home))

    config = ensure_android_defaults(config={}, persist=True)

    assert config["platform_toolsets"]["api_server"] == DEFAULT_ANDROID_API_SERVER_TOOLSETS
    config_text = (hermes_home / "config.yaml").read_text()
    assert "platform_toolsets:" in config_text
    assert "hermes-android-app" in config_text


def test_ensure_android_defaults_replaces_valid_but_unsafe_api_profile(tmp_path, monkeypatch):
    hermes_home = tmp_path / ".hermes"
    hermes_home.mkdir()
    monkeypatch.setenv("HERMES_HOME", str(hermes_home))
    config = {"platform_toolsets": {"api_server": ["hermes-api-server"]}}

    updated = ensure_android_defaults(config=config, persist=True)

    assert updated["platform_toolsets"]["api_server"] == DEFAULT_ANDROID_API_SERVER_TOOLSETS


@pytest.mark.parametrize(
    "entry",
    [
        {"provider": "copilot-acp"},
        {"base_url": "acp+tcp://127.0.0.1:9999"},
        {"api_mode": "codex_app_server"},
        {"command": "unowned-provider", "args": ["serve"]},
    ],
)
def test_android_provider_policy_rejects_process_backed_primary(entry):
    with pytest.raises(RuntimeError, match="process-backed|command/args"):
        validate_android_provider_runtime(entry, None)


def test_android_provider_policy_rejects_process_backed_fallback_chain():
    primary = {
        "provider": "openrouter",
        "base_url": "https://openrouter.ai/api/v1",
        "api_mode": "openai",
        "command": None,
        "args": [],
    }
    with pytest.raises(RuntimeError, match=r"fallback\[1\].*process-backed"):
        validate_android_provider_runtime(
            primary,
            [
                {"provider": "openai", "model": "safe"},
                {"provider": "copilot-acp", "model": "unsafe"},
            ],
        )


def test_android_provider_policy_allows_http_primary_and_fallback():
    validate_android_provider_runtime(
        {
            "provider": "openrouter",
            "base_url": "https://openrouter.ai/api/v1",
            "api_mode": "openai",
            "command": None,
            "args": [],
        },
        [{"provider": "openai", "base_url": "https://api.openai.com/v1"}],
    )
