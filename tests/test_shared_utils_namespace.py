"""Collision-safe utility imports and legacy module identity contracts."""

import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest


def _fresh_python(code, tmp_path):
    env = dict(os.environ)
    env.update({
        "HERMES_HOME": str(tmp_path / "hermes"),
        "CODEX_HOME": str(tmp_path / "codex"),
        "HOME": str(tmp_path),
        "USERPROFILE": str(tmp_path),
        "HERMES_DISABLE_LAZY_INSTALLS": "1",
    })
    result = subprocess.run(
        [sys.executable, "-c", textwrap.dedent(code)],
        cwd=Path(__file__).resolve().parents[1], env=env,
        capture_output=True, text=True, encoding="utf-8", timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return result


def test_legacy_and_namespaced_imports_share_one_module_and_patch_state(monkeypatch, tmp_path):
    import utils
    import hermes_cli.shared_utils as shared

    assert utils is shared
    assert utils._preserve_file_mode is shared._preserve_file_mode
    assert shared.logger.name == "utils"
    original = shared.atomic_replace
    calls = []

    def observed_replace(source, target):
        calls.append(target)
        return original(source, target)

    monkeypatch.setattr(utils, "atomic_replace", observed_replace)
    target = tmp_path / "state.json"
    shared.atomic_json_write(target, {"preserved": True})
    assert calls == [target]
    assert '"preserved": true' in target.read_text(encoding="utf-8")


def test_foreign_utils_cannot_intercept_fresh_first_party_imports(tmp_path):
    _fresh_python("""
        import importlib
        import sys
        import types
        foreign = types.ModuleType("utils")
        foreign.dependency_sentinel = object()
        sys.modules["utils"] = foreign
        shared = importlib.import_module("hermes_cli.shared_utils")
        for name in (
            "atomic_write_text", "atomic_roundtrip_yaml_update", "atomic_roundtrip_yaml_save",
            "fast_safe_load", "env_float", "base_url_origin", "model_forces_max_completion_tokens",
        ):
            assert callable(getattr(shared, name))
        for name in ("hermes_cli.auth", "hermes_cli.config", "agent.auxiliary_client",
                     "gateway.config", "tools.terminal_tool", "tools.skills_sync"):
            importlib.import_module(name)
        assert sys.modules["utils"] is foreign
        assert shared.base_url_origin("https://example.test/path") == ("https", "example.test", 443)
    """, tmp_path)


@pytest.mark.parametrize("foreign_utils", [False, True])
def test_hot_update_purges_only_owned_alias_and_reimports_canonical(tmp_path, foreign_utils):
    _fresh_python("""
        import importlib
        import sys
        import types
        import utils
        shared = importlib.import_module("hermes_cli.shared_utils")
        updater = importlib.import_module("hermes_cli.update_cmd")
        updater._m = lambda: types.SimpleNamespace(sys=sys)
        foreign = types.ModuleType("utils")
        if FOREIGN_UTILS:
            sys.modules["utils"] = foreign
        updater._purge_stale_hermes_modules()
        assert "hermes_cli.shared_utils" not in sys.modules
        if FOREIGN_UTILS:
            assert sys.modules["utils"] is foreign
        else:
            assert "utils" not in sys.modules
        fresh = importlib.import_module("hermes_cli.shared_utils")
        assert fresh is not shared
        assert callable(fresh.env_float)
        if not FOREIGN_UTILS:
            assert importlib.import_module("utils") is fresh
    """.replace("FOREIGN_UTILS", repr(foreign_utils)), tmp_path)


def test_hot_reload_refreshes_canonical_module_without_touching_foreign_utils(tmp_path):
    _fresh_python("""
        import importlib
        import sys
        import types
        shared = importlib.import_module("hermes_cli.shared_utils")
        updater = importlib.import_module("hermes_cli.update_cmd")
        updater._m = lambda: types.SimpleNamespace(sys=sys)
        foreign = types.ModuleType("utils")
        sys.modules["utils"] = foreign
        del shared.env_float
        updater._reload_updated_runtime_modules()
        assert shared.env_float("HERMES_UNSET_NAMESPACE_TEST", 1.5) == 1.5
        assert sys.modules["utils"] is foreign
    """, tmp_path)
