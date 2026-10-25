import json

import pytest

from scripts.check_osv_severity import summarize
from scripts.prepare_memory_security_scan import source_pins


def project(chroma_ref="a" * 40):
    return {"project": {"optional-dependencies": {"hy-memory": [
        f"chromadb @ git+https://github.com/adybag14-cyber/chroma.git@{chroma_ref}",
        "hy-memory @ git+https://github.com/adybag14-cyber/Hy-Memory.git@" + "b" * 40,
    ]}}}


@pytest.mark.parametrize("reference", ["main", "a" * 39, "a" * 40 + "#subdirectory=other"])
def test_dependency_scan_rejects_mutable_or_modified_source(reference):
    with pytest.raises(ValueError, match="immutable reviewed chromadb"):
        source_pins(project(reference))


def test_dependency_scan_uses_exact_installer_commits():
    assert source_pins(project()) == {
        "chromadb": ("adybag14-cyber/chroma", "a" * 40),
        "hy-memory": ("adybag14-cyber/Hy-Memory", "b" * 40),
    }


def test_scan_gate_distinguishes_high_medium_and_unscored(tmp_path):
    rules = [{"id": name, "properties": properties} for name, properties in [
        ("high", {"security-severity": "7.0"}),
        ("medium", {"security-severity": "6.9"}),
        ("unscored", {}),
    ]]
    path = tmp_path / "scan.sarif"
    path.write_text(json.dumps({"version": "2.1.0", "runs": [{
        "tool": {"driver": {"rules": rules}},
        "results": [{"ruleId": rule["id"], "message": {"text": rule["id"]}} for rule in rules],
    }]}))
    result = summarize(path)
    assert result["findings"] == 3
    assert result["unscored"] == 1
    assert [finding["id"] for finding in result["high_critical"]] == ["high"]


@pytest.mark.parametrize("content", ["not json", '{}', '{"version":"2.1.0","runs":[]}'])
def test_scan_gate_does_not_treat_broken_evidence_as_clean(tmp_path, content):
    path = tmp_path / "scan.sarif"
    path.write_text(content)
    with pytest.raises(ValueError):
        summarize(path)


def test_scan_gate_requires_evidence_file(tmp_path):
    with pytest.raises(FileNotFoundError):
        summarize(tmp_path / "missing.sarif")
