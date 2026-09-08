import pytest

from scripts import android_release_evidence as evidence
from scripts import android_release_evidence_policy as policy


def test_owner_waiver_is_limited_to_the_authorized_stable_releases():
    for tag in ("v0.13.154", "v0.13.155", "v0.13.156"):
        assert evidence.requires_physical_nanbeige_repair_evidence(tag) is False
    for tag in ("v0.13.151", "v0.13.153", "v0.13.154-rc.1", "v0.13.155-rc.1", "v0.13.156-rc.1", "v0.13.157", "v0.14.0"):
        assert evidence.requires_physical_nanbeige_repair_evidence(tag) is True
        assert policy.physical_validation_waiver(tag) is None


@pytest.mark.parametrize("tag", ["v0.13.154", "v0.13.155", "v0.13.156"])
def test_manifest_records_unperformed_validation_without_claiming_a_phone_pass(tag):
    manifest = {"contract": {}, "summary": {}}
    policy.record_physical_validation_waiver(manifest, tag)
    assert manifest["physical_device_evidence"]["physical_validation_performed"] is False
    assert manifest["physical_device_evidence"]["release_tag"] == tag
    assert manifest["summary"]["physical_nanbeige_repair_count"] == 0
    assert manifest["summary"]["physical_device_models"] == []
    assert manifest["contract"]["requires_one_physical_arm64_nanbeige_repair_record"] is False
    historical = {"contract": {}, "summary": {}}
    policy.record_physical_validation_waiver(historical, "v0.13.153")
    assert historical == {"contract": {}, "summary": {}}
