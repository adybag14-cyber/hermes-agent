"""Import-safety tests for the Discord gateway adapter."""

import builtins
import importlib
import sys


class TestDiscordImportSafety:
    def test_module_imports_even_when_discord_dependency_is_missing(self, monkeypatch):
        original_import = builtins.__import__
        original_module = sys.modules.get("gateway.platforms.discord")
        original_pkg_attr = None
        original_pkg_had_attr = False
        try:
            import gateway.platforms as _gateway_platforms
            original_pkg_had_attr = hasattr(_gateway_platforms, "discord")
            if original_pkg_had_attr:
                original_pkg_attr = _gateway_platforms.discord
        except Exception:
            _gateway_platforms = None

        def fake_import(name, globals=None, locals=None, fromlist=(), level=0):
            if name == "discord" or name.startswith("discord."):
                raise ImportError("discord unavailable for test")
            return original_import(name, globals, locals, fromlist, level)

        monkeypatch.delitem(sys.modules, "gateway.platforms.discord", raising=False)
        monkeypatch.setattr(builtins, "__import__", fake_import)

        module = importlib.import_module("gateway.platforms.discord")

        assert module.DISCORD_AVAILABLE is False
        assert module.discord is None

        # Restore the original module object so later tests patch and import
        # the same instance they collected against.
        monkeypatch.delitem(sys.modules, "gateway.platforms.discord", raising=False)
        if original_module is not None:
            sys.modules["gateway.platforms.discord"] = original_module
        try:
            import gateway.platforms as _gateway_platforms
            if original_pkg_had_attr:
                _gateway_platforms.discord = original_pkg_attr
            elif hasattr(_gateway_platforms, "discord"):
                delattr(_gateway_platforms, "discord")
        except Exception:
            pass
