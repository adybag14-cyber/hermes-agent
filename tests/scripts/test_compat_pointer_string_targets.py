"""The compat gate detects literal patch targets without scanning virtualenvs."""

import importlib.util
import json
from pathlib import Path

import pytest


@pytest.mark.parametrize("patch_call", [
    'patch("legacy.facade.old_name")',
    'mock_patch("legacy.facade.old_name")',
    'mock.patch(target="legacy.facade.old_name")',
    'monkeypatch.setattr("legacy.facade.old_name", None)',
    'monkeypatch.delattr("legacy.facade.old_name")',
    'patch.dict("legacy.facade.old_name", {})',
])
def test_patch_strings_are_checked_but_data_and_environments_are_not(tmp_path, monkeypatch, capsys, patch_call):
    checker_path = Path(__file__).resolve().parents[2] / "scripts/check_compat_pointers.py"
    spec = importlib.util.spec_from_file_location("compat_string_checker", checker_path)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    manifest = tmp_path / "compat_manifest.json"
    manifest.write_text(json.dumps({"entries": [{"facade": "legacy.facade", "name": "old_name"}]}), encoding="utf-8")
    monkeypatch.setattr(checker, "ROOT", tmp_path)
    monkeypatch.setattr(checker, "MANIFEST", manifest)
    source = tmp_path / "example.py"
    imports = 'from unittest.mock import patch, patch as mock_patch\n'
    source.write_text(imports + patch_call + '\n', encoding="utf-8")
    ignored = tmp_path / ".venv/vendor.py"
    ignored.parent.mkdir()
    ignored.write_text('from legacy.facade import old_name\n', encoding="utf-8")
    assert checker.main() == 1
    output = capsys.readouterr().out
    assert "example.py:2" in output
    assert "vendor.py" not in output
    source.write_text(imports + 'patch("modern.module.function")\nexpected = ["legacy.facade.old_name"]\n', encoding="utf-8")
    assert checker.main() == 0
