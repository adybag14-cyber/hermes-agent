from dataclasses import replace

import pytest

from scripts import android_release_evidence_policy as policy
from tests.hermes_android import test_android_performance_collector as fixtures


def test_release_coordinate_keeps_historical_contracts_and_binds_v158_to_its_runtime():
    prefix = "com.google.ai.edge.litertlm:litertlm-android:"
    for tag, sdk in (("v0.13.147", "0.16.0"), ("v0.13.148", "0.16.1"),
                     ("v0.13.153", "0.16.1"), ("v0.13.154", "0.17.0"),
                     ("v0.13.157", "0.17.0"), ("v0.13.158", "0.17.1")):
        assert policy.required_litertlm_coordinate(tag) == prefix + sdk
    with pytest.raises(policy.EvidenceError):
        policy.required_litertlm_coordinate("latest")


def test_collector_rejects_stale_runtime_coordinate_without_invalidating_older_evidence(tmp_path):
    fixtures._load_module("android_release_evidence", "scripts/android_release_evidence.py")
    collector = fixtures._load_module("android_collect_performance_evidence", "scripts/android_collect_performance_evidence.py")
    old = fixtures._config(collector, tmp_path)
    old.validate()
    current = replace(old, version_name="0.13.158", version_code=145890,
                      litertlm_coordinate="com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    current.validate()
    with pytest.raises(collector.CollectorError, match="release dependency"):
        replace(current, litertlm_coordinate=old.litertlm_coordinate).validate()


@pytest.mark.parametrize("version,coordinate", [
    ("0.13.157", "com.google.ai.edge.litertlm:litertlm-android:0.17.0"),
    ("0.13.158", "com.google.ai.edge.litertlm:litertlm-android:0.17.1"),
])
def test_serialized_payload_validation_receives_versioned_runtime_contract(tmp_path, monkeypatch, version, coordinate):
    release = fixtures._load_module("android_release_evidence", "scripts/android_release_evidence.py")
    collector = fixtures._load_module("android_collect_performance_evidence", "scripts/android_collect_performance_evidence.py")
    config = replace(fixtures._config(collector, tmp_path), version_name=version,
                     litertlm_coordinate=coordinate)
    observed = []

    def validate(*args, **kwargs):
        observed.append((args, kwargs))

    monkeypatch.setattr(release, "_validate_performance", validate)
    monkeypatch.setattr(collector, "_load_release_evidence_module", lambda: release)
    collector.ReleaseEvidencePayloadValidator().validate(
        tmp_path / "normalized.json", tmp_path / "host.json", tmp_path / "macro.json", (), config,
    )
    assert len(observed) == 1
    assert observed[0][1]["litertlm_coordinate"] == coordinate
    assert observed[0][0][3] == version
