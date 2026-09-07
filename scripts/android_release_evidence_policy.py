"""Launch-review and physical upgrade acceptance policy."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any, Mapping

try:
    from android_release_evidence_common import EvidenceError, _exact_keys
except ModuleNotFoundError:  # Imported as a package rather than executed as a script.
    from scripts.android_release_evidence_common import EvidenceError, _exact_keys


REVIEWER_RE = re.compile(r"^[^\r\n]{2,120}$")
POLICY_UPDATE_VERSION = (0, 13, 154)


def physical_validation_waiver(tag: str) -> dict[str, Any] | None:
    """The release owner waived phone testing for this one stable release only."""
    if tag.strip() != "v0.13.154":
        return None
    return {
        "classification": "owner-waived-physical-validation",
        "release_tag": "v0.13.154",
        "physical_validation_performed": False,
        "authorization": "Explicit release-owner instruction on 2026-09-07 to skip phone validation and publish this release.",
        "scope": "this release only; the physical gate remains required for later releases",
    }


def record_physical_validation_waiver(manifest: dict[str, Any], tag: str) -> None:
    waiver = physical_validation_waiver(tag)
    if waiver is None:
        return
    manifest["physical_device_evidence"] = waiver
    manifest["contract"]["requires_one_physical_arm64_nanbeige_repair_record"] = False
    manifest["contract"]["physical_validation_waived_for_this_release"] = True
    manifest["summary"].update(physical_nanbeige_repair_count=0, physical_device_models=[])


def uses_upgrade_release_policy(tag: str) -> bool:
    match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)(?:-(?:alpha|beta|rc)(?:\.\d+)?)?", tag)
    if match is None:
        raise EvidenceError(f"Invalid Android release policy tag: {tag!r}")
    return tuple(int(part) for part in match.groups()) >= POLICY_UPDATE_VERSION


def physical_schema_for_tag(tag: str) -> str:
    revision = 2 if uses_upgrade_release_policy(tag) else 1
    return f"hermes-android-physical-nanbeige-repair-v{revision}"


def _reviewed_utc(value: Any, context: str) -> str:
    if not isinstance(value, str):
        raise EvidenceError(f"{context} must be a UTC timestamp string")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise EvidenceError(f"{context} must use YYYY-MM-DDTHH:MM:SSZ") from exc
    if parsed.year < 2026:
        raise EvidenceError(f"{context} predates the comprehensive release-evidence contract")
    return value


def validate_automatic_reconciliation(
    reconciliation: Mapping[str, Any], model_path: str, context: str,
    *, tag: str, release: Mapping[str, Any], model: Mapping[str, Any],
) -> None:
    reconciliation_context = f"{context}.automatic_reconciliation"
    if uses_upgrade_release_policy(tag):
        case = reconciliation.get("case")
        if case == "already-repaired-upgrade":
            validate_already_repaired_upgrade(reconciliation, model_path, release, model, reconciliation_context)
            return
        if case != "fresh-automatic-migration":
            raise EvidenceError(f"{reconciliation_context}.case must identify the observed migration or upgrade")
        reconciliation = {key: value for key, value in reconciliation.items() if key != "case"}
    _exact_keys(
        reconciliation,
        {
            "capture_route",
            "model_path",
            "trigger",
            "automatic",
            "exact_artifact_verified_before_reconciliation",
            "settings_before_runtime_lane",
            "required_runtime_lane",
            "settings_after_runtime_lane",
            "settings_save_succeeded",
            "persisted_before_runtime_launch",
            "runtime_launch_observed_after_persist",
            "visible_settings_runtime_lane",
            "visible_settings_matches_persisted_lane",
            "visible_settings_observed_after_ready",
            "user_reselected_lane",
        },
        reconciliation_context,
    )
    exact_reconciliation = {
        "capture_route": "app-managed-local-backend",
        "model_path": model_path,
        "trigger": "verified-artifact-prelaunch",
        "automatic": True,
        "exact_artifact_verified_before_reconciliation": True,
        "settings_before_runtime_lane": "stable",
        "required_runtime_lane": "turboquant",
        "settings_after_runtime_lane": "turboquant",
        "settings_save_succeeded": True,
        "persisted_before_runtime_launch": True,
        "runtime_launch_observed_after_persist": True,
        "visible_settings_runtime_lane": "turboquant",
        "visible_settings_matches_persisted_lane": True,
        "visible_settings_observed_after_ready": True,
        "user_reselected_lane": False,
    }
    for field, expected in exact_reconciliation.items():
        if reconciliation.get(field) != expected:
            raise EvidenceError(f"{reconciliation_context}.{field} must equal {expected!r}")


def validate_already_repaired_upgrade(
    record: Mapping[str, Any], model_path: str, release: Mapping[str, Any],
    model: Mapping[str, Any], context: str,
) -> None:
    expected = {
        "case": "already-repaired-upgrade",
        "capture_route": "app-ui-before-and-after-signed-in-place-upgrade",
        "model_path": model_path,
        "settings_before_runtime_lane": "turboquant",
        "required_runtime_lane": "turboquant",
        "settings_after_runtime_lane": "turboquant",
        "visible_settings_observed_before_install": True,
        "visible_settings_observed_after_ready": True,
        "fresh_migration_observed": False,
        "user_reselected_lane": False,
        "model_sha256_before": model["device_sha256"],
        "model_sha256_after": model["device_sha256"],
        "previous_apk_signer_sha256": release["candidate_apk_signer_sha256"],
        "install_argv": ["install", "-r", "--no-streaming"],
        "install_succeeded": True,
        "uninstall_performed": False,
        "data_cleared": False,
        "preferences_edited_outside_app": False,
    }
    _exact_keys(record, set(expected) | {
        "previous_version_name", "previous_version_code", "previous_apk_bytes", "previous_apk_sha256",
    }, context)
    for field, value in expected.items():
        if record.get(field) != value:
            raise EvidenceError(f"{context}.{field} must equal {value!r}")
    previous_name = record["previous_version_name"]
    match = re.fullmatch(r"0\.(\d+)\.(\d+)", str(previous_name))
    if match is None:
        raise EvidenceError(f"{context}.previous_version_name must identify the installed stable app")
    previous_code = record["previous_version_code"]
    expected_code = int(match[1]) * 10_000 + int(match[2]) * 100 + 90
    if type(previous_code) is not int or previous_code != expected_code or previous_code > release["version_code"]:
        raise EvidenceError(f"{context}.previous_version_code does not prove a same-version or forward upgrade")
    if type(record["previous_apk_bytes"]) is not int or record["previous_apk_bytes"] <= 0:
        raise EvidenceError(f"{context}.previous_apk_bytes must be positive")
    previous_sha = record["previous_apk_sha256"]
    if not isinstance(previous_sha, str) or re.fullmatch(r"[0-9a-f]{64}", previous_sha) is None:
        raise EvidenceError(f"{context}.previous_apk_sha256 must be lowercase SHA-256")
    if previous_sha == release["candidate_apk_sha256"]:
        raise EvidenceError(f"{context} must exercise an upgrade, not reinstall identical candidate bytes")


def validate_launch_visual_review(review: Mapping[str, Any], context: str, *, tag: str) -> bool:
    review_keys = {
        "status",
        "reviewer",
        "reviewed_at_utc",
        "decision",
        "notes",
        "method",
        "automated_pixel_certification",
    }
    _exact_keys(review, review_keys, f"{context}.visual_review")
    updated = uses_upgrade_release_policy(tag)
    if updated and review["status"] == "pending":
        expected_pending = {
            "status": "pending", "reviewer": None, "reviewed_at_utc": None,
            "decision": None, "notes": None, "method": "manual-frame-by-frame",
            "automated_pixel_certification": False,
        }
        if dict(review) != expected_pending:
            raise EvidenceError(f"{context}.visual_review pending record contains an unperformed review claim")
        return False
    if review["status"] != "reviewed" or review["decision"] != "pass":
        raise EvidenceError(f"{context} does not carry a passing completed human visual review")
    if not isinstance(review["reviewer"], str) or not REVIEWER_RE.fullmatch(review["reviewer"]):
        raise EvidenceError(f"{context}.visual_review.reviewer is invalid")
    _reviewed_utc(review["reviewed_at_utc"], f"{context}.visual_review.reviewed_at_utc")
    if not isinstance(review["notes"], str) or not review["notes"].strip() or len(review["notes"]) > 500:
        raise EvidenceError(f"{context}.visual_review.notes is invalid")
    methods = {"manual-frame-by-frame", "user-acceptance"} if updated else {"manual-frame-by-frame"}
    if review["method"] not in methods or review["automated_pixel_certification"] is not False:
        raise EvidenceError(f"{context}.visual_review improperly claims automated pixel certification")
    return True


def launch_review_manifest_contract(tag: str) -> dict[str, bool]:
    return {
        "requires_human_frame_by_frame_launch_theme_review": not uses_upgrade_release_policy(tag),
        "launch_theme_capture_does_not_self_certify_pixels": True,
    }


def physical_reconciliation_manifest_contract(tag: str) -> dict[str, Any]:
    if not uses_upgrade_release_policy(tag):
        return {
            "requires_automatic_persisted_turboquant_reconciliation_before_launch": True,
            "requires_visible_settings_to_match_reconciled_turboquant_lane": True,
        }
    return {
        "accepted_physical_upgrade_cases": ["fresh-automatic-migration", "already-repaired-upgrade"],
        "requires_observed_physical_upgrade_case_without_fabricated_migration": True,
        "requires_visible_settings_to_match_required_turboquant_lane": True,
    }
