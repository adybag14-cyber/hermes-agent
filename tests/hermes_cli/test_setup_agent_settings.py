"""Tests for agent-settings copy in the interactive setup wizard."""

import copy
import sys

import pytest

from hermes_cli.config import TURN_LIMIT_UNLIMITED, resolve_turn_limit
from hermes_cli.setup import setup_agent_settings


def test_setup_agent_settings_uses_displayed_max_iterations_value(tmp_path, monkeypatch, capsys):
    """The helper text should match the value shown in the prompt.

    After PR#18413 max_turns is read exclusively from config.yaml — the
    .env `HERMES_MAX_ITERATIONS` fallback was removed because it was
    shadowing the user's current config (see the 60-vs-500 incident).
    """
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))

    config = {
        "agent": {"max_turns": 60},
        "display": {"tool_progress": "all"},
        "compression": {"threshold": 0.50},
        "session_reset": {"mode": "both", "idle_minutes": 1440, "at_hour": 4},
    }

    prompt_answers = iter(["60", "all", "0.5"])

    monkeypatch.setattr("hermes_cli.setup.prompt", lambda *args, **kwargs: next(prompt_answers))
    monkeypatch.setattr("hermes_cli.setup.prompt_choice", lambda *args, **kwargs: 4)
    monkeypatch.setattr("hermes_cli.setup.save_env_value", lambda *args, **kwargs: None)
    monkeypatch.setattr("hermes_cli.setup.remove_env_value", lambda *args, **kwargs: None)
    monkeypatch.setattr("hermes_cli.setup.save_config", lambda *args, **kwargs: None)

    setup_agent_settings(config)

    out = capsys.readouterr().out
    assert "Press Enter to keep 60." in out
    assert "Default is 90" not in out


def test_setup_agent_settings_prefers_config_over_stale_env(tmp_path, monkeypatch, capsys):
    """Config.yaml wins even when a stale .env value disagrees.

    Regression guard for the bug where `.env HERMES_MAX_ITERATIONS=60`
    from an old `hermes setup` run shadowed `agent.max_turns: 500` in
    config.yaml. The wizard must now display the config value.
    """
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))

    config = {
        "agent": {"max_turns": 500},  # user bumped this in config.yaml
        "display": {"tool_progress": "all"},
        "compression": {"threshold": 0.50},
        "session_reset": {"mode": "both", "idle_minutes": 1440, "at_hour": 4},
    }

    prompt_answers = iter(["500", "all", "0.5"])

    # Simulate stale .env value — the wizard must ignore this.
    monkeypatch.setattr(
        "hermes_cli.setup.get_env_value",
        lambda key: "60" if key == "HERMES_MAX_ITERATIONS" else "",
    )
    monkeypatch.setattr("hermes_cli.setup.prompt", lambda *args, **kwargs: next(prompt_answers))
    monkeypatch.setattr("hermes_cli.setup.prompt_choice", lambda *args, **kwargs: 4)
    monkeypatch.setattr("hermes_cli.setup.save_env_value", lambda *args, **kwargs: None)

    removed_keys: list[str] = []
    monkeypatch.setattr(
        "hermes_cli.setup.remove_env_value",
        lambda key: (removed_keys.append(key), True)[1],
    )
    monkeypatch.setattr("hermes_cli.setup.save_config", lambda *args, **kwargs: None)

    setup_agent_settings(config)

    out = capsys.readouterr().out
    # Config value wins
    assert "Press Enter to keep 500." in out
    assert "Press Enter to keep 60." not in out
    # And the stale .env entry gets cleaned up
    assert "HERMES_MAX_ITERATIONS" in removed_keys


@pytest.fixture
def agent_settings_wizard(monkeypatch):
    removed = []
    saved = []
    prompts = []
    monkeypatch.setattr("hermes_cli.setup.prompt_choice", lambda *args, **kwargs: 4)
    monkeypatch.setattr("hermes_cli.setup.remove_env_value", removed.append)
    monkeypatch.setattr("hermes_cli.setup.save_config", lambda config: saved.append(copy.deepcopy(config)))

    def run(answer, current=60):
        config = {
            "agent": {"max_turns": current, "verbose": True},
            "max_turns": 22,
            "display": {"tool_progress": "all"},
            "compression": {"threshold": 0.5},
            "session_reset": {"mode": "none"},
        }

        def prompt(question, default=None, **kwargs):
            prompts.append((question, default))
            return (answer.strip() or default) if question == "Max iterations" else default

        monkeypatch.setattr("hermes_cli.setup.prompt", prompt)
        setup_agent_settings(config)
        return config, removed, saved, prompts

    return run


@pytest.mark.parametrize("answer, expected", [("1", 1), ("240", 240), (" +007 ", 7)])
def test_setup_agent_settings_accepts_positive_integer_caps(agent_settings_wizard, answer, expected):
    config, removed, saved, _ = agent_settings_wizard(answer)
    assert config["agent"]["max_turns"] == expected
    assert type(config["agent"]["max_turns"]) is int
    assert "max_turns" not in config
    assert removed == ["HERMES_MAX_ITERATIONS"]
    assert saved[-1]["agent"]["max_turns"] == expected


@pytest.mark.parametrize("answer", ["unlimited", " InFiNiTe ", "infinity", "INF", "none", "null", "∞", "no-limit", "nolimit"])
def test_setup_agent_settings_persists_unlimited_aliases_as_text(agent_settings_wizard, answer, capsys):
    config, removed, saved, _ = agent_settings_wizard(answer)
    assert config["agent"]["max_turns"] == "unlimited"
    assert saved[-1]["agent"]["max_turns"] == "unlimited"
    runtime_limit = resolve_turn_limit(config["agent"]["max_turns"])
    assert type(runtime_limit) is int
    assert runtime_limit == TURN_LIMIT_UNLIMITED
    assert removed == ["HERMES_MAX_ITERATIONS"]
    assert "Max iterations set to unlimited" in capsys.readouterr().out


@pytest.mark.parametrize("answer", ["0", "-1", "-30", "1.5", "garbage", "NaN", "-Infinity"])
@pytest.mark.parametrize("current", [60, "unlimited"])
def test_invalid_iteration_input_preserves_existing_budget(agent_settings_wizard, answer, current, capsys):
    config, removed, saved, _ = agent_settings_wizard(answer, current)
    assert config["agent"] == {"max_turns": current, "verbose": True}
    assert config["max_turns"] == 22
    assert removed == []
    assert all(snapshot["agent"]["max_turns"] == current for snapshot in saved)
    assert "Invalid value, keeping current setting" in capsys.readouterr().out


@pytest.mark.parametrize("current", [None, "unlimited", "inf", 0, sys.maxsize])
def test_unlimited_budget_is_displayed_and_kept_on_enter(agent_settings_wizard, current, capsys):
    from hermes_cli.setup_migration import _get_section_config_summary

    config, _removed, _saved, prompts = agent_settings_wizard("", current)
    assert prompts[0] == ("Max iterations", "unlimited")
    assert config["agent"]["max_turns"] == "unlimited"
    assert _get_section_config_summary(config, "agent") == "max turns: unlimited"
    assert "Press Enter to keep unlimited." in capsys.readouterr().out


@pytest.mark.parametrize("answer, expected", [("unlimited", "unlimited"), ("300", 300)])
def test_setup_iteration_choice_round_trips_config(agent_settings_wizard, tmp_path, monkeypatch, answer, expected):
    from hermes_cli.config import load_config, save_config

    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    config, _removed, _saved, _prompts = agent_settings_wizard(answer)
    save_config(config)
    reloaded = load_config()
    assert reloaded["agent"]["max_turns"] == expected
    assert type(reloaded["agent"]["max_turns"]) is type(expected)
