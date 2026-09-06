from __future__ import annotations

import copy
import json

import pytest

from scripts import android_release_evidence_policy as policy
from scripts.android_release_evidence_common import EvidenceError
from tests.hermes_android import test_android_release_evidence_v151 as historical


@pytest.mark.parametrize("mutation", [None, "old-tag", "fresh-claim", "changed-model", "reselected", "cleared", "downgrade", "same-apk", "bad-signer"])
def test_already_repaired_case_binds_a_non_destructive_real_upgrade(tmp_path, monkeypatch, mutation):
    module = historical.legacy._load_module()
    tag, version, code = "v0.13.154", "0.13.154", 145490
    for name, value in (("TAG", tag), ("VERSION_NAME", version), ("VERSION_CODE", code)):
        monkeypatch.setattr(historical, name, value)
    record = historical._physical_record(module)
    record["schema"] = policy.physical_schema_for_tag(tag)
    upgrade = {
        "case": "already-repaired-upgrade",
        "capture_route": "app-ui-before-and-after-signed-in-place-upgrade",
        "model_path": record["model_identity"]["device_path"],
        "settings_before_runtime_lane": "turboquant",
        "required_runtime_lane": "turboquant",
        "settings_after_runtime_lane": "turboquant",
        "visible_settings_observed_before_install": True,
        "visible_settings_observed_after_ready": True,
        "fresh_migration_observed": False,
        "user_reselected_lane": False,
        "model_sha256_before": record["model_identity"]["device_sha256"],
        "model_sha256_after": record["model_identity"]["device_sha256"],
        "previous_version_name": "0.13.153",
        "previous_version_code": 145390,
        "previous_apk_bytes": 24_000_000,
        "previous_apk_sha256": "8" * 64,
        "previous_apk_signer_sha256": record["release_identity"]["candidate_apk_signer_sha256"],
        "install_argv": ["install", "-r", "--no-streaming"],
        "install_succeeded": True,
        "uninstall_performed": False,
        "data_cleared": False,
        "preferences_edited_outside_app": False,
    }
    changes = {
        "fresh-claim": {"fresh_migration_observed": True},
        "changed-model": {"model_sha256_after": "f" * 64},
        "reselected": {"user_reselected_lane": True},
        "cleared": {"data_cleared": True},
        "downgrade": {"previous_version_name": "0.13.155", "previous_version_code": 145590},
        "same-apk": {"previous_apk_sha256": record["release_identity"]["candidate_apk_sha256"]},
        "bad-signer": {"previous_apk_signer_sha256": "f" * 64},
    }
    upgrade.update(changes.get(mutation, {}))
    record["automatic_reconciliation"] = upgrade
    if mutation == "old-tag":
        # Historical releases must not silently acquire the new acceptance case.
        with pytest.raises(EvidenceError):
            policy.validate_automatic_reconciliation(upgrade, upgrade["model_path"], "physical", tag="v0.13.153", release=record["release_identity"], model=record["model_identity"])
        return
    path = tmp_path / "physical.json"
    path.write_text(json.dumps(record), encoding="utf-8")
    def validate():
        return module._validate_physical_nanbeige_repair_evidence(path, (module.NANBEIGE_REPAIR_ARTIFACT,), historical.SOURCE_DIGEST, version, code, tag)
    if mutation:
        with pytest.raises(EvidenceError):
            validate()
    else:
        result = validate()
        assert result.candidate_apk_sha256 == record["release_identity"]["candidate_apk_sha256"]
        assert result.model_device_path == upgrade["model_path"]


@pytest.mark.parametrize("state", ["pending", "user-approved", "failed", "fabricated-review", "pixel-claim"])
def test_manual_review_is_informational_without_erasing_failures_or_inventing_review(state):
    pending = {
        "status": "pending", "reviewer": None, "reviewed_at_utc": None,
        "decision": None, "notes": None, "method": "manual-frame-by-frame",
        "automated_pixel_certification": False,
    }
    review = copy.deepcopy(pending)
    if state in {"user-approved", "failed", "pixel-claim"}:
        review.update(status="reviewed", reviewer="Release owner", reviewed_at_utc="2026-09-06T22:00:00Z", decision="pass", notes="User accepted these recordings.", method="user-acceptance")
    if state == "failed":
        review["decision"] = "fail"
    if state == "fabricated-review":
        review["reviewer"] = "Unperformed review"
    if state == "pixel-claim":
        review["automated_pixel_certification"] = True
    if state in {"pending", "user-approved"}:
        assert policy.validate_launch_visual_review(review, "launch", tag="v0.13.154") is (state == "user-approved")
    else:
        with pytest.raises(EvidenceError):
            policy.validate_launch_visual_review(review, "launch", tag="v0.13.154")
    with pytest.raises(EvidenceError):
        policy.validate_launch_visual_review(pending, "launch", tag="v0.13.153")
    assert policy.launch_review_manifest_contract("v0.13.154")["requires_human_frame_by_frame_launch_theme_review"] is False
