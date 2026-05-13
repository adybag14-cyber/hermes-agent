"""The fork's dedicated ZAI route participates in live provider discovery."""

import pytest

from providers import get_provider_profile


@pytest.mark.parametrize(
    "name",
    ["zai-coding-plan", "glm-coding-plan", "zai-coding", "zai_coding_plan", "z-ai-coding-plan"],
)
def test_coding_plan_aliases_are_live_profiles_and_model_prefixes(name):
    from agent.model_metadata import _strip_provider_prefix

    profile = get_provider_profile(name)
    assert profile is get_provider_profile("zai-coding-plan")
    assert profile.name == "zai-coding-plan"
    assert profile.base_url == "https://api.z.ai/api/coding/paas/v4"
    assert profile.env_vars[:2] == ("GLM_CODING_PLAN_API_KEY", "ZAI_CODING_PLAN_API_KEY")
    assert _strip_provider_prefix(f"{name}:glm-5.1") == "glm-5.1"
    assert _strip_provider_prefix(f"{name}:latest") == f"{name}:latest"


def test_coding_plan_uses_current_zai_reasoning_policy_without_replacing_standard_route():
    from agent.model_metadata import _infer_provider_from_url

    standard = get_provider_profile("zai")
    coding = get_provider_profile("zai-coding-plan")
    assert standard is not coding
    assert standard.base_url == "https://api.z.ai/api/paas/v4"
    assert _infer_provider_from_url(standard.base_url) == "zai"
    for model, config in (
        ("glm-5.1", {"enabled": False}),
        ("glm-5.2", {"enabled": True, "effort": "medium"}),
        ("glm-5.3", {"enabled": True, "effort": "low"}),
    ):
        assert coding.build_api_kwargs_extras(model=model, reasoning_config=config) == (
            standard.build_api_kwargs_extras(model=model, reasoning_config=config)
        )
    assert coding.build_api_kwargs_extras(
        model="glm-5.1", reasoning_config={"enabled": False},
    )[0] == {"thinking": {"type": "disabled"}}


def test_dynamic_registry_preserves_unrelated_providers_and_ollama_tags():
    from agent.model_metadata import _strip_provider_prefix

    assert _strip_provider_prefix("novita:any-model") == "any-model"
    assert _strip_provider_prefix("qwen3.5:27b") == "qwen3.5:27b"
    assert _strip_provider_prefix("unknown-vendor:some-model") == "unknown-vendor:some-model"
