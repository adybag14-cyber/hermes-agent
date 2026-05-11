"""Tests that invalid context_length values in config produce visible warnings."""

from unittest.mock import patch

import pytest

def _build_agent(
    model_cfg,
    custom_providers=None,
    model=None,
    provider=None,
    base_url=None,
):
    """Build an AIAgent with the given model config."""
    cfg = {"model": model_cfg}
    if custom_providers is not None:
        cfg["custom_providers"] = custom_providers

    base_url = model_cfg.get("base_url", "") if base_url is None else base_url

    with (
        patch("hermes_cli.config.load_config", return_value=cfg),
        patch("hermes_cli.config.load_config_readonly", return_value=cfg),
        patch("agent.model_metadata.get_model_context_length", return_value=128_000),
        patch("agent.context_compressor.get_model_context_length", return_value=128_000),
        patch("model_tools.get_tool_definitions", return_value=[]),
        patch("model_tools.check_toolset_requirements", return_value={}),
        patch("agent.process_bootstrap.OpenAI"),
    ):
        from run_agent import AIAgent

        agent = AIAgent(
            model=model or model_cfg.get("default") or "anthropic/claude-opus-4.6",
            api_key="test-key-1234567890",
            base_url=base_url,
            provider=provider,
            quiet_mode=True,
            skip_context_files=True,
            skip_memory=True,
        )
        # The current compressor resolves metadata lazily. Resolve it while
        # the fake catalog is scoped so assertions cannot make live probes.
        _ = agent.context_compressor.context_length
    return agent


def test_valid_integer_context_length_no_warning():
    """Plain integer context_length should work silently."""
    with patch("run_agent.logger") as mock_logger:
        agent = _build_agent({"default": "gpt5.4", "provider": "custom",
                              "base_url": "http://localhost:4000/v1",
                              "context_length": 256000})
    assert agent._config_context_length == 256000
    # No warning about invalid context_length
    for c in mock_logger.warning.call_args_list:
        assert "Invalid" not in str(c)


def test_string_k_suffix_context_length_warns():
    """context_length: '256K' should warn the user clearly."""
    with patch("run_agent.logger") as mock_logger:
        agent = _build_agent({"default": "gpt5.4", "provider": "custom",
                              "base_url": "http://localhost:4000/v1",
                              "context_length": "256K"})
    assert agent._config_context_length is None
    # Should have warned
    warning_calls = [c for c in mock_logger.warning.call_args_list
                     if "Invalid" in str(c) and "256K" in str(c)]
    assert len(warning_calls) == 1
    assert "plain integer" in str(warning_calls[0])




@pytest.mark.parametrize("configured_model", ["local-model.gguf", "gpt-5-thinking"])
def test_config_context_length_ignored_for_provider_override(configured_model):
    """A local endpoint context override must not poison explicit provider runs."""
    agent = _build_agent(
        {
            "default": configured_model,
            "provider": "custom",
            "base_url": "http://127.0.0.1:18191/v1",
            "context_length": 40072,
        },
        model="gpt-5-thinking",
        provider="chatgpt-web",
        base_url="https://chatgpt.com/backend-api/f",
    )

    assert agent._config_context_length is None
    assert agent.context_compressor.context_length == 128_000


def test_custom_providers_valid_context_length():
    """Valid integer in custom_providers should work silently."""
    custom_providers = [
        {
            "name": "LiteLLM",
            "base_url": "http://localhost:4000/v1",
            "models": {
                "gpt5.4": {"context_length": 256000}
            },
        }
    ]
    with patch("run_agent.logger") as mock_logger:
        agent = _build_agent(
            {"default": "gpt5.4", "provider": "custom",
             "base_url": "http://localhost:4000/v1"},
            custom_providers=custom_providers,
            model="gpt5.4",
        )
    for c in mock_logger.warning.call_args_list:
        assert "Invalid" not in str(c)
