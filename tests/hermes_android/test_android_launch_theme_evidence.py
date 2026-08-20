from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "scripts" / "android_launch_theme_evidence.py"
SPEC = importlib.util.spec_from_file_location("android_launch_theme_evidence", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def valid_identity(**overrides):
    values = {
        "serial": "emulator-5554",
        "avd_name": "Hermes_API_35",
        "expected_profile": "phone",
        "evidence_run_id": "20260814-ui-theme-proof",
        "source_digest": "a" * 64,
        "candidate_apk_sha256": "b" * 64,
        "instrumentation_apk_sha256": "c" * 64,
    }
    values.update(overrides)
    return MODULE.EvidenceIdentity(**values)


def palette_values() -> dict[str, str | float]:
    return {
        "theme_primary": "#1565C0",
        "theme_secondary": "#8E24AA",
        "theme_background": "#FAFBFF",
        "theme_surface": "#FFFFFF",
        "theme_surface_variant": "#EDF1FA",
        "card_shape": "rounded",
        "ui_font_scale": 1.0,
    }


def write_palette_proof(path: Path, **overrides: str) -> Path:
    values = {
        "evidence_type": "headed-ui-coverage-bound",
        "evidence_identity": "appearance-custom-light",
        "artifact": "headed-run-profile-phone-411x891dp-theme-custom-light",
        "coverage_kind": "custom-light-palette",
        "page_id": "Hermes",
        "profile": "phone-411x891dp",
        "language": "en",
        "theme_id": "custom-light",
        **{key: str(value) for key, value in palette_values().items()},
        "source_digest": "a" * 64,
        "candidate_apk_sha256": "b" * 64,
        "instrumentation_apk_sha256": "c" * 64,
        "evidence_run_id": "20260814-ui-theme-proof",
        "device_serial": "emulator-5554",
        "avd_name": "Hermes_API_35",
        "device_boot_id": "12345678-1234-4abc-8def-1234567890ab",
    }
    values.update(overrides)
    path.write_text(
        "\n".join([*(f"{key}={value}" for key, value in values.items()), "sentinel=HermesChatInput", "", "Node tree"]),
        encoding="utf-8",
    )
    return path


def persisted_preferences_xml(**overrides: str) -> str:
    values = {key: str(value) for key, value in palette_values().items()}
    values.update(overrides)
    return "\n".join(
        [
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
            "<map>",
            f"  <string name=\"theme_primary_hex\">{values['theme_primary']}</string>",
            f"  <string name=\"theme_secondary_hex\">{values['theme_secondary']}</string>",
            f"  <string name=\"theme_background_hex\">{values['theme_background']}</string>",
            f"  <string name=\"theme_surface_hex\">{values['theme_surface']}</string>",
            f"  <string name=\"theme_surface_variant_hex\">{values['theme_surface_variant']}</string>",
            f"  <string name=\"theme_card_shape\">{values['card_shape']}</string>",
            f"  <float name=\"ui_font_scale\" value=\"{values['ui_font_scale']}\" />",
            "  <string name=\"provider\">unrelated-and-filtered</string>",
            "</map>",
        ]
    )


def test_identity_contract_rejects_unbound_inputs():
    valid_identity().validate()
    for field, invalid in (
        ("serial", "device"),
        ("avd_name", ""),
        ("expected_profile", "wide"),
        ("evidence_run_id", "short"),
        ("source_digest", "A" * 64),
        ("candidate_apk_sha256", "abc"),
        ("instrumentation_apk_sha256", "abc"),
    ):
        with pytest.raises(MODULE.EvidenceError):
            valid_identity(**{field: invalid}).validate()


def test_profile_parser_requires_one_exact_dp_profile():
    assert MODULE.parse_profile("config: mcc0-en-rUS-w411dp-h891dp-normal") == ("phone", 411, 891)
    assert MODULE.parse_profile("config: mcc0-en-rUS-w800dp-h1280dp-normal") == ("tablet", 800, 1280)
    with pytest.raises(MODULE.EvidenceError):
        MODULE.parse_profile("no dimensions")
    with pytest.raises(MODULE.EvidenceError):
        MODULE.parse_profile("w411dp-h891dp w800dp-h1280dp")


def test_palette_proof_and_persisted_state_must_match_exactly(tmp_path):
    proof = MODULE.load_palette_proof(write_palette_proof(tmp_path / "proof.txt"), valid_identity())
    observed = MODULE.parse_persisted_palette_xml(persisted_preferences_xml())
    MODULE.require_matching_palette(observed, proof)

    mismatched = MODULE.parse_persisted_palette_xml(
        persisted_preferences_xml(theme_background="#101010")
    )
    with pytest.raises(MODULE.EvidenceError, match="theme_background"):
        MODULE.require_matching_palette(mismatched, proof)


def test_palette_proof_is_bound_to_device_run_and_rendered_capture(tmp_path):
    valid = write_palette_proof(tmp_path / "valid.txt")
    proof = MODULE.load_palette_proof(valid, valid_identity())
    assert proof.evidence_identity == "appearance-custom-light"
    assert proof.palette.normalized() == palette_values()

    wrong_run = write_palette_proof(tmp_path / "wrong-run.txt", evidence_run_id="another-release-run-20260814")
    with pytest.raises(MODULE.EvidenceError, match="evidence_run_id"):
        MODULE.load_palette_proof(wrong_run, valid_identity())

    wrong_capture = write_palette_proof(tmp_path / "wrong-capture.txt", evidence_identity="section:Hermes")
    with pytest.raises(MODULE.EvidenceError, match="appearance-custom-light"):
        MODULE.load_palette_proof(wrong_capture, valid_identity())


def _write_review_fixture(root: Path) -> Path:
    def artifact(name: str, payload: bytes) -> tuple[str, str]:
        path = root / name
        path.write_bytes(payload)
        return name, hashlib.sha256(payload).hexdigest()

    palette_name, palette_sha = artifact("persisted-palette.json", b"{}\n")
    captures = []
    for label in ("cold-launcher-tap", "cold-deep-link"):
        video, video_sha = artifact(f"{label}.mp4", b"\x00\x00\x00\x18ftypmp42" + label.encode())
        screenshot, screenshot_sha = artifact(f"{label}.png", b"synthetic-png-for-review-contract")
        activity, activity_sha = artifact(f"{label}.txt", b"mResumedActivity com.mobilefork.hermesagent/.MainActivity")
        captures.append(
            {
                "label": label,
                "launch_stdout": "Starting: Intent",
                "launch_stderr": "",
                "video": video,
                "video_sha256": video_sha,
                "settled_screenshot": screenshot,
                "settled_screenshot_sha256": screenshot_sha,
                "activity_dump": activity,
                "activity_dump_sha256": activity_sha,
                "automated_state_verdict": "main_activity_resumed_and_artifacts_decoded",
                "visual_splash_verdict": "manual_review_required",
            }
        )
    manifest = {
        "schema": MODULE.MANIFEST_SCHEMA,
        "identity": {},
        "palette": {
            "verified_against_persisted_app_state": True,
            "persisted_state_file": palette_name,
            "persisted_state_file_sha256": palette_sha,
        },
        "captures": captures,
        "automated_verdict": "identity_bound_launch_capture_complete",
        "visual_review": {
            "status": "pending",
            "reviewer": None,
            "reviewed_at_utc": None,
            "decision": None,
            "notes": None,
            "method": "manual-frame-by-frame",
            "automated_pixel_certification": False,
        },
        "manual_acceptance": ["inspect pixels"],
    }
    path = root / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def test_review_command_persists_explicit_human_decision_without_pixel_self_certification(tmp_path):
    manifest_path = _write_review_fixture(tmp_path)
    MODULE.review(
        argparse.Namespace(
            manifest=manifest_path,
            reviewer="Release Reviewer",
            decision="pass",
            reviewed_at_utc="2026-08-14T20:15:00Z",
            notes="Both launch videos reviewed frame by frame.",
            replace_existing_review=False,
        )
    )
    reviewed = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert reviewed["visual_review"] == {
        "status": "reviewed",
        "reviewer": "Release Reviewer",
        "reviewed_at_utc": "2026-08-14T20:15:00Z",
        "decision": "pass",
        "notes": "Both launch videos reviewed frame by frame.",
        "method": "manual-frame-by-frame",
        "automated_pixel_certification": False,
    }
    with pytest.raises(MODULE.EvidenceError, match="already recorded"):
        MODULE.review(
            argparse.Namespace(
                manifest=manifest_path,
                reviewer="Second Reviewer",
                decision="pass",
                reviewed_at_utc="2026-08-14T20:20:00Z",
                notes="Attempted silent overwrite.",
                replace_existing_review=False,
            )
        )
