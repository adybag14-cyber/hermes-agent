"""Plugin platform resolution exposes one identity through values and attributes."""

import pytest

from gateway import config
from gateway.platform_registry import platform_registry


@pytest.mark.parametrize("source", ["bundled", "runtime"])
def test_registered_platform_attribute_matches_value_without_accepting_unknowns(monkeypatch, source):
    value = f"fixture-platform-{source}"
    name = value.upper().replace("-", "_")
    monkeypatch.setattr(config, "_Platform__bundled_plugin_names", {value} if source == "bundled" else set())
    monkeypatch.setattr(platform_registry, "is_registered", lambda v: v == value and source == "runtime")
    try:
        member = config.Platform(value)
        assert getattr(config.Platform, name) is member
        assert config.Platform(value) is member
        with pytest.raises(ValueError):
            config.Platform("fixture-platform-not-registered")
    finally:
        config.Platform._value2member_map_.pop(value, None)
        config.Platform._member_map_.pop(name, None)
        if name in config.Platform.__dict__:
            type.__delattr__(config.Platform, name)
