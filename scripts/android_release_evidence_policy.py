"""Launch-review and physical upgrade acceptance policy."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any, Mapping

try:
    from scripts.android_release_evidence_common import EvidenceError, _exact_keys
except ModuleNotFoundError:  # Direct script execution.
    from android_release_evidence_common import EvidenceError, _exact_keys


REVIEWER_RE = re.compile(r"^[^\r\n]{2,120}$")


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
) -> None:
    reconciliation_context = f"{context}.automatic_reconciliation"
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


def validate_launch_visual_review(review: Mapping[str, Any], context: str) -> None:
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
    if review["status"] != "reviewed" or review["decision"] != "pass":
        raise EvidenceError(f"{context} does not carry a passing completed human visual review")
    if not isinstance(review["reviewer"], str) or not REVIEWER_RE.fullmatch(review["reviewer"]):
        raise EvidenceError(f"{context}.visual_review.reviewer is invalid")
    _reviewed_utc(review["reviewed_at_utc"], f"{context}.visual_review.reviewed_at_utc")
    if not isinstance(review["notes"], str) or not review["notes"].strip() or len(review["notes"]) > 500:
        raise EvidenceError(f"{context}.visual_review.notes is invalid")
    if review["method"] != "manual-frame-by-frame" or review["automated_pixel_certification"] is not False:
        raise EvidenceError(f"{context}.visual_review improperly claims automated pixel certification")


def launch_review_manifest_contract() -> dict[str, bool]:
    return {
        "requires_human_frame_by_frame_launch_theme_review": True,
        "launch_theme_capture_does_not_self_certify_pixels": True,
    }


def physical_reconciliation_manifest_contract() -> dict[str, bool]:
    return {
        "requires_automatic_persisted_turboquant_reconciliation_before_launch": True,
        "requires_visible_settings_to_match_reconciled_turboquant_lane": True,
    }
