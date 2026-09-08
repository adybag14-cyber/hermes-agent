#!/usr/bin/env python3
"""Require real Play-edition evidence independently of the full Android release matrix."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from pathlib import Path, PurePosixPath

try:
    from android_release_evidence_common import EvidenceError
except ModuleNotFoundError:
    from scripts.android_release_evidence_common import EvidenceError


MIN_VERSION = (0, 13, 156)
CASES = {
    "privacy-consent-lifecycle": (
        "decline_no_http", "accept_real_http", "request_has_no_tools", "revoke_reprompts",
        "background_cancelled", "voice_decline_no_permission_change",
    ),
    "report-submit-delete": (
        "preview_before_send", "cancel_no_receipt", "live_submission_confirmed",
        "in_app_deletion_confirmed", "synthetic_only",
    ),
    "six-language-privacy": (
        "persisted_language_matches_ui", "local_deletion_cancelled", "privacy_controls_visible",
    ),
}
MODEL_CHECKS = (
    "runtime_started", "startup_completion_verified", "chat_completion_nonempty", "input_safety_refusal",
    "stop_completed", "background_runtime_stopped", "python_not_started",
)
LANGUAGES = {"en", "zh", "es", "de", "pt", "fr"}
ENDPOINT = "https://hermes-content-reports.adybag14.workers.dev"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def expected_paths(models: list) -> set[PurePosixPath]:
    cases = set(CASES) | {f"model-{model.model_id}" for model in models}
    return {PurePosixPath(f"{case}.json") for case in cases} | {
        PurePosixPath(f"privacy-{language}.png") for language in LANGUAGES
    }


def validate_records(records: dict, source_digest: str, version_name: str, version_code: int, models: list) -> dict:
    expected = set(CASES) | {f"model-{model.model_id}" for model in models}
    require(set(records) == expected, "Play evidence cases are missing, duplicated or unexpected")
    require(re.fullmatch(r"[0-9a-f]{64}", source_digest) is not None, "Invalid source digest")
    identities = set()
    for case, record in records.items():
        require(record.get("schema") == "hermes-play-release-v1" and record.get("case") == case, f"Invalid schema/case: {case}")
        require(record.get("result") == "passed" and record.get("edition") == "play", f"Not passed Play evidence: {case}")
        require(record.get("source_digest") == source_digest, f"Stale source digest: {case}")
        require(record.get("version_name") == version_name and record.get("version_code") == version_code, f"Stale version: {case}")
        require(record.get("package") == "com.mobilefork.hermesagent" and record.get("build_variant") == "playDebug", f"Wrong installed variant: {case}")
        require(type(record.get("android_sdk")) is int and record["android_sdk"] >= 36 and record.get("page_size") == 16384, f"Requires Android 16 and real 16 KB pages: {case}")
        for field in ("candidate_apk_sha256", "instrumentation_apk_sha256"):
            require(re.fullmatch(r"[0-9a-f]{64}", str(record.get(field, ""))) is not None, f"Invalid installed APK identity: {case}")
        require(re.fullmatch(r"[a-z0-9][a-z0-9._-]{15,79}", str(record.get("evidence_run_id", ""))) is not None, f"Invalid run identity: {case}")
        require(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", str(record.get("device_boot_id", ""))) is not None, f"Invalid boot identity: {case}")
        require(str(record.get("device_serial", "")).startswith("emulator-") and bool(record.get("avd_name")), f"Missing emulator identity: {case}")
        require(bool(record.get("build_fingerprint")) and type(record.get("recorded_at_epoch_ms")) is int and record["recorded_at_epoch_ms"] > 0, f"Missing observed device/time: {case}")
        identities.add(tuple(record[key] for key in (
            "candidate_apk_sha256", "instrumentation_apk_sha256", "evidence_run_id", "device_boot_id", "avd_name",
        )))
        details = record.get("details", {})
        for check in CASES.get(case, MODEL_CHECKS):
            require(details.get(check) is True, f"Missing completed behavior {case}/{check}")
    require(len(identities) == 1, "Play evidence mixes different APKs, runs or device boots")
    require(records["report-submit-delete"]["details"].get("endpoint") == ENDPOINT, "Report was not sent to the private production service")
    languages = records["six-language-privacy"]["details"].get("languages", [])
    require(len(languages) == len(LANGUAGES) and set(languages) == LANGUAGES, "Six actual language switches are required")
    for model in models:
        detail = records[f"model-{model.model_id}"]["details"]
        require(detail.get("model_id") == model.model_id and detail.get("model_file") == model.file_name, "Wrong model artifact")
        require(detail.get("device_sha256") == model.sha256 and detail.get("device_bytes") == model.expected_bytes, "Wrong model bytes")
        require(detail.get("publisher_repository") == model.repository and detail.get("publisher_revision") == model.revision, "Wrong model publisher identity")
        require(detail.get("backend") == model.runtime, "Wrong model backend")
        require(type(detail.get("elapsed_ms")) is int and detail["elapsed_ms"] > 0 and detail.get("reply_characters", 0) > 0, "Missing measured real completion")
    return {"status": "passed", "edition": "play", "source_digest": source_digest, "cases": sorted(expected)}


def verify_directory(directory: Path, source_digest: str, version_name: str, version_code: int, models: list) -> dict:
    require(directory.is_dir() and not directory.is_symlink(), f"Missing regular Play evidence directory: {directory}")
    entries = list(directory.iterdir())
    require(all(path.is_file() and not path.is_symlink() for path in entries), "Play evidence layout must contain only regular files")
    actual = {PurePosixPath(path.name) for path in entries}
    expected = expected_paths(models)
    require(actual == expected, f"Play evidence layout mismatch; missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}")
    records = {path.stem: json.loads(path.read_text(encoding="utf-8")) for path in directory.glob("*.json")}
    result = validate_records(records, source_digest, version_name, version_code, models)
    screenshots = records["six-language-privacy"]["details"].get("screenshots", [])
    require(len(screenshots) == len(LANGUAGES), "Missing six language screenshots")
    expected_names = {f"privacy-{language.lower()}.png" for language in LANGUAGES}
    require({item.get("file") for item in screenshots} == expected_names, "Wrong language screenshots")
    for shot in screenshots:
        path = directory / shot["file"]
        require(path.is_file() and not path.is_symlink(), "Missing regular screenshot")
        data = path.read_bytes()
        require(len(data) == shot.get("bytes") and hashlib.sha256(data).hexdigest() == shot.get("sha256"), "Screenshot bytes changed")
        require(data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24, "Screenshot is not PNG")
        require(struct.unpack(">II", data[16:24]) == (shot.get("width"), shot.get("height")), "Screenshot dimensions differ")
    return result


def main() -> None:
    # Keep the reusable evidence contract independent of the Full manifest module.
    try:
        from android_release_evidence import load_registered_model_matrix
        from check_android_release_identity import validate_release_identity
    except ModuleNotFoundError:
        from scripts.android_release_evidence import load_registered_model_matrix
        from scripts.check_android_release_identity import validate_release_identity

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-digest", required=True)
    args = parser.parse_args()
    repo = args.repo_root.resolve()
    identity = validate_release_identity(repo, args.tag)
    models = load_registered_model_matrix(repo / "android/app/src/main/java/com/mobilefork/hermesagent/models/VerifiedLocalModelArtifacts.kt")
    result = verify_directory(repo / "android/release-evidence" / args.tag / "play", args.source_digest,
                              identity.version_name, identity.version_code, models)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
