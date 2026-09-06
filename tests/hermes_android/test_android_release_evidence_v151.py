from __future__ import annotations

import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

import pytest

from tests.hermes_android import test_android_release_evidence as legacy
from tests.hermes_android import test_android_release_evidence_v3 as v3


TAG = "v0.13.151"
VERSION_NAME = "0.13.151"
VERSION_CODE = 145_190
RUN_ID = "release-v0.13.151-synthetic-run"
SOURCE_DIGEST = legacy.SOURCE_DIGEST
CANDIDATE_SHA256 = "9" * 64
CANDIDATE_BYTES = 25_000_000
SERIAL_SHA256 = hashlib.sha256(b"adb-physical-phone-wireless-tls").hexdigest()


@pytest.fixture(scope="module")
def evidence_module():
    return legacy._load_module()


@pytest.fixture
def artifacts(evidence_module):
    return (evidence_module.NANBEIGE_REPAIR_ARTIFACT,)


def _physical_record(module) -> dict:
    model_path = (
        module.PHYSICAL_MODEL_PATH_ROOT
        + module.NANBEIGE_REPAIR_ARTIFACT.file_name.lower()
    )
    reply = module.PHYSICAL_ORDINARY_CHAT_EXPECTED_REPLY
    stopped = module.PHYSICAL_STOP_TERMINAL_MESSAGE
    stable_runtime_directory = (
        f"/data/local/tmp/hermes-{TAG}-{CANDIDATE_SHA256[:16]}-llama-stable"
    )
    stable_runtime_path = (
        f"{stable_runtime_directory}/{module.PHYSICAL_STABLE_RUNTIME_EXECUTABLE}"
    )
    runtime_closure = []
    for index, apk_entry in enumerate(module.PHYSICAL_STABLE_RUNTIME_APK_ENTRIES, start=1):
        file_name = Path(apk_entry).name
        file_bytes = 10_000 + index
        file_sha256 = hashlib.sha256(f"stable-runtime:{file_name}".encode()).hexdigest()
        runtime_closure.append(
            {
                "apk_entry": apk_entry,
                "file_name": file_name,
                "role": module.PHYSICAL_STABLE_RUNTIME_ROLES[file_name],
                "device_path": f"{stable_runtime_directory}/{file_name}",
                "dt_needed": list(
                    module.PHYSICAL_STABLE_RUNTIME_DT_NEEDED[file_name]
                ),
                "extracted_bytes": file_bytes,
                "extracted_sha256": file_sha256,
                "device_bytes": file_bytes,
                "device_sha256": file_sha256,
            }
        )
    runtime_closure_manifest_sha256 = hashlib.sha256(
        json.dumps(
            runtime_closure,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    ).hexdigest()
    command_environment = {
        "GGML_BACKEND_PATH": f"{stable_runtime_directory}/libggml-cpu.so",
        "HOME": f"{stable_runtime_directory}/home",
        "LANG": "C",
        "LC_ALL": "C",
        "LD_LIBRARY_PATH": stable_runtime_directory,
        "PATH": "/system/bin",
        "TMPDIR": f"{stable_runtime_directory}/tmp",
    }
    command_environment_sha256 = hashlib.sha256(
        json.dumps(
            command_environment,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    ).hexdigest()
    command_argv = [
        stable_runtime_path,
        "--model",
        model_path,
        "--host",
        "127.0.0.1",
        "--port",
        str(module.PHYSICAL_STABLE_RUNTIME_PORT),
    ]
    command_argv_sha256 = hashlib.sha256(
        json.dumps(
            command_argv,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    return {
        "schema": module.PHYSICAL_NANBEIGE_REPAIR_SCHEMA,
        "result": "passed",
        "evidence_complete": True,
        "recorded_at_epoch_ms": 1_787_500_000_000,
        "release_identity": {
            "release_source_digest": SOURCE_DIGEST,
            "release_tag": TAG,
            "package_id": module.PACKAGE_ID,
            "version_name": VERSION_NAME,
            "version_code": VERSION_CODE,
            "build_variant": "release",
            "candidate_artifact_name": f"hermes-agent-android-{TAG}-device-candidate.apk",
            "candidate_apk_bytes": CANDIDATE_BYTES,
            "candidate_apk_sha256": CANDIDATE_SHA256,
            "installed_base_apk_bytes": CANDIDATE_BYTES,
            "installed_base_apk_sha256": CANDIDATE_SHA256,
            "candidate_apk_signer_sha256": module.EXPECTED_RELEASE_SIGNER_SHA256,
            "installed_apk_signer_sha256": module.EXPECTED_RELEASE_SIGNER_SHA256,
            "source_binding_verified": True,
            "signer_verified": True,
        },
        "device_identity": {
            "physical_device": True,
            "adb_transport": "wireless-tls",
            "adb_serial_sha256": SERIAL_SHA256,
            "model": "Pixel 9 Pro XL",
            "manufacturer": "Google",
            "product": "komodo",
            "device": "komodo",
            "hardware": "zumapro",
            "build_fingerprint": "google/komodo/komodo:16/BP2A.260705.008/release-keys",
            "boot_id": "b03b9f70-d1e4-4f9a-8f4c-34c1b9d344e8",
            "android_sdk": 36,
            "primary_abi": "arm64-v8a",
            "supported_abis": ["arm64-v8a", "armeabi-v7a"],
            "ro_kernel_qemu": "0",
            "avd_name": "",
        },
        "model_identity": {
            "model_id": module.NANBEIGE_REPAIR_ARTIFACT.model_id,
            "publisher_repository": module.NANBEIGE_REPAIR_ARTIFACT.repository,
            "publisher_revision": module.NANBEIGE_REPAIR_ARTIFACT.revision,
            "file_name": module.NANBEIGE_REPAIR_ARTIFACT.file_name,
            "runtime": module.NANBEIGE_REPAIR_ARTIFACT.runtime,
            "required_runtime_lane": "turboquant",
            "expected_bytes": module.NANBEIGE_REPAIR_ARTIFACT.expected_bytes,
            "device_visible_bytes": module.NANBEIGE_REPAIR_ARTIFACT.expected_bytes,
            "expected_sha256": module.NANBEIGE_REPAIR_ARTIFACT.sha256,
            "device_sha256": module.NANBEIGE_REPAIR_ARTIFACT.sha256,
            "device_path": model_path,
            "content_addressed_verification_passed": True,
        },
        "stable_precondition": {
            "capture_route": "adb-shell-extracted-stable-runtime",
            "model_path": model_path,
            "source_candidate_apk_sha256": CANDIDATE_SHA256,
            "runtime_directory_path": stable_runtime_directory,
            "runtime_closure": runtime_closure,
            "runtime_closure_file_count": len(runtime_closure),
            "runtime_closure_total_bytes": sum(
                item["extracted_bytes"] for item in runtime_closure
            ),
            "runtime_closure_manifest_sha256": runtime_closure_manifest_sha256,
            "system_library_allowlist": list(
                module.PHYSICAL_STABLE_RUNTIME_SYSTEM_LIBRARIES
            ),
            "unresolved_non_system_dependencies": [],
            "command_executable_path": stable_runtime_path,
            "command_working_directory": stable_runtime_directory,
            "command_library_path": stable_runtime_directory,
            "command_model_path": model_path,
            "command_environment": command_environment,
            "command_environment_sha256": command_environment_sha256,
            "command_argv": command_argv,
            "selected_runtime_lane": "stable",
            "exact_artifact_verified": True,
            "runtime_process_spawned": True,
            "ready": False,
            "process_exit_code": 1,
            "failure_stage": "model-load",
            "unknown_model_architecture": "nanbeige",
            "error_message": "loading model: unknown model architecture: 'nanbeige'",
            "loader_error_absent": True,
            "command_argv_sha256": command_argv_sha256,
            "device_runtime_cleanup_verified": True,
        },
        "automatic_reconciliation": {
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
        },
        "readiness": {
            "capture_route": "app-managed-local-backend",
            "backend": "llama.cpp",
            "runtime_lane": "turboquant",
            "persisted_runtime_lane": "turboquant",
            "model_path": model_path,
            "controller_ready": True,
            "health_endpoint_ok": True,
            "completion_canary_nonempty": True,
            "completion_canary_visible_characters": 20,
            "ready_latency_ms": 31_250,
            "status_message": "llama.cpp TurboQuant lane is ready and serving locally",
        },
        "ordinary_chat": {
            "capture_route": "app-chat-ui",
            "language_tag": "en",
            "tool_mode": "general",
            "tools_available": True,
            "prompt": module.PHYSICAL_ORDINARY_CHAT_PROMPT,
            "prompt_requested_tool": False,
            "request_completed": True,
            "visible_reply": reply,
            "visible_reply_characters": len(reply),
            "visible_progress_observed": True,
            "progress_event_count": 4,
            "tool_call_count": 0,
            "tool_result_count": 0,
            "terminal_state": "completed",
            "completion_latency_ms": 42_500,
        },
        "stop_control": {
            "capture_route": "app-chat-ui",
            "language_tag": "en",
            "prompt": module.PHYSICAL_STOP_CHAT_PROMPT,
            "stop_button_visible": True,
            "visible_progress_observed_before_stop": True,
            "stop_requested": True,
            "stop_acknowledged": True,
            "model_request_cancelled": True,
            "terminal_state": "stopped",
            "visible_terminal_message": stopped,
            "visible_terminal_message_characters": len(stopped),
            "nonterminal_placeholder": False,
            "busy_after_stop": False,
            "stop_button_visible_after_stop": False,
            "stop_latency_ms": 240,
        },
        "validation_errors": [],
    }


def _write_physical_record(path: Path, module) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(_physical_record(module)), encoding="utf-8")


def _rewrite(path: Path, fields: tuple[str, ...], value) -> None:
    payload = json.loads(path.read_text(encoding="utf-8"))
    target = payload
    for field in fields[:-1]:
        target = target[field]
    target[fields[-1]] = value
    path.write_text(json.dumps(payload), encoding="utf-8")


@pytest.fixture
def physical_record_path(tmp_path, evidence_module):
    path = tmp_path / evidence_module.PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix()
    _write_physical_record(path, evidence_module)
    return path


def _validate_physical(evidence_module, path: Path, artifacts) -> None:
    evidence_module._validate_physical_nanbeige_repair_evidence(
        path,
        artifacts,
        SOURCE_DIGEST,
        VERSION_NAME,
        VERSION_CODE,
        TAG,
    )


def test_v151_policy_adds_one_fixed_physical_record_without_changing_v148_to_v150(
    evidence_module, artifacts
):
    for tag in ("v0.13.148", "v0.13.149", "v0.13.150"):
        assert evidence_module.requires_physical_nanbeige_repair_evidence(tag) is False
        assert (
            evidence_module.PHYSICAL_NANBEIGE_REPAIR_PATH
            not in evidence_module.expected_evidence_paths(artifacts, tag=tag)
        )
    assert evidence_module.requires_physical_nanbeige_repair_evidence(TAG) is True
    assert (
        evidence_module.PHYSICAL_NANBEIGE_REPAIR_PATH
        in evidence_module.expected_evidence_paths(artifacts, tag=TAG)
    )


def test_release_tag_requirement_rejects_lightweight_and_wrong_commit_tags(
    tmp_path, evidence_module
):
    repo = tmp_path / "repo"
    repo.mkdir()

    def git(*args: str) -> None:
        subprocess.run(
            ["git", *args],
            cwd=repo,
            check=True,
            capture_output=True,
            text=True,
        )

    git("init")
    git("config", "user.name", "Hermes Release Test")
    git("config", "user.email", "hermes-release-test@example.invalid")
    (repo / "source.txt").write_text("one\n", encoding="utf-8")
    git("add", "source.txt")
    git("commit", "-m", "source one")

    git("tag", TAG)
    with pytest.raises(evidence_module.EvidenceError, match="annotated tag object"):
        evidence_module.require_tag_points_to_head(repo, TAG)

    git("tag", "-d", TAG)
    git("tag", "-a", TAG, "-m", "Hermes release")
    evidence_module.require_tag_points_to_head(repo, TAG)

    (repo / "source.txt").write_text("two\n", encoding="utf-8")
    git("add", "source.txt")
    git("commit", "-m", "source two")
    with pytest.raises(evidence_module.EvidenceError, match="does not point"):
        evidence_module.require_tag_points_to_head(repo, TAG)


def _write_candidate_apk_binding_fixture(tmp_path: Path, module):
    apk_path = tmp_path / "candidate.apk"
    entry_payloads = {
        entry: f"signed-apk-entry:{index}:{entry}".encode("utf-8")
        for index, entry in enumerate(module.PHYSICAL_STABLE_RUNTIME_APK_ENTRIES, start=1)
    }
    with zipfile.ZipFile(apk_path, "w", compression=zipfile.ZIP_STORED) as archive:
        for entry, payload in entry_payloads.items():
            archive.writestr(entry, payload)
    apk_bytes = apk_path.stat().st_size
    apk_sha = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    record = {
        "release_identity": {
            "candidate_apk_bytes": apk_bytes,
            "candidate_apk_sha256": apk_sha,
        },
        "stable_precondition": {
            "runtime_closure": [
                {
                    "apk_entry": entry,
                    "extracted_bytes": len(payload),
                    "extracted_sha256": hashlib.sha256(payload).hexdigest(),
                }
                for entry, payload in entry_payloads.items()
            ],
        },
    }
    record_path = tmp_path / "physical.json"
    record_path.write_text(json.dumps(record), encoding="utf-8")
    return apk_path, record_path, record


def test_physical_candidate_runtime_closure_is_bound_to_exact_signed_apk_entries(
    tmp_path, evidence_module
):
    apk_path, record_path, record = _write_candidate_apk_binding_fixture(
        tmp_path,
        evidence_module,
    )
    apk_bytes, apk_sha, closure_files = evidence_module.verify_physical_candidate_apk_binding(
        apk_path,
        record_path,
    )
    assert apk_bytes == record["release_identity"]["candidate_apk_bytes"]
    assert apk_sha == record["release_identity"]["candidate_apk_sha256"]
    assert closure_files == len(evidence_module.PHYSICAL_STABLE_RUNTIME_APK_ENTRIES)


def test_physical_candidate_runtime_closure_rejects_apk_entry_hash_drift(
    tmp_path, evidence_module
):
    apk_path, record_path, record = _write_candidate_apk_binding_fixture(
        tmp_path,
        evidence_module,
    )
    target = evidence_module.PHYSICAL_STABLE_RUNTIME_APK_ENTRIES[0]
    replacement_apk = tmp_path / "candidate-with-drift.apk"
    with zipfile.ZipFile(apk_path, "r") as original, zipfile.ZipFile(
        replacement_apk,
        "w",
        compression=zipfile.ZIP_STORED,
    ) as replacement:
        for entry in evidence_module.PHYSICAL_STABLE_RUNTIME_APK_ENTRIES:
            payload = original.read(entry)
            if entry == target:
                payload = bytes((payload[0] ^ 0xFF,)) + payload[1:]
            replacement.writestr(entry, payload)
    record["release_identity"]["candidate_apk_bytes"] = replacement_apk.stat().st_size
    record["release_identity"]["candidate_apk_sha256"] = hashlib.sha256(
        replacement_apk.read_bytes()
    ).hexdigest()
    record_path.write_text(json.dumps(record), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="extracted_sha256"):
        evidence_module.verify_physical_candidate_apk_binding(replacement_apk, record_path)


def test_valid_physical_nanbeige_repair_record_binds_candidate_device_and_model(
    physical_record_path, evidence_module, artifacts
):
    validated = evidence_module._validate_physical_nanbeige_repair_evidence(
        physical_record_path,
        artifacts,
        SOURCE_DIGEST,
        VERSION_NAME,
        VERSION_CODE,
        TAG,
    )
    assert validated.candidate_apk_sha256 == CANDIDATE_SHA256
    assert validated.apk_signer_sha256 == evidence_module.EXPECTED_RELEASE_SIGNER_SHA256
    assert validated.device_model == "Pixel 9 Pro XL"
    assert validated.adb_serial_sha256 == SERIAL_SHA256


@pytest.mark.parametrize(
    ("fields", "value", "match"),
    [
        (("release_identity", "package_id"), "wrong.package", "package_id"),
        (("release_identity", "version_name"), "0.13.150", "version_name"),
        (("release_identity", "version_code"), 145_180, "version_code"),
        (("release_identity", "release_source_digest"), "8" * 64, "release_source_digest"),
        (("release_identity", "release_tag"), "v0.13.150", "release_tag"),
        (("release_identity", "candidate_apk_sha256"), "8" * 64, "installed_base_apk_sha256"),
        (
            ("release_identity", "candidate_apk_signer_sha256"),
            "8" * 64,
            "candidate_apk_signer_sha256",
        ),
        (("release_identity", "source_binding_verified"), False, "source_binding_verified"),
        (("release_identity", "signer_verified"), False, "signer_verified"),
    ],
)
def test_physical_record_rejects_wrong_release_or_candidate_identity(
    physical_record_path, evidence_module, artifacts, fields, value, match
):
    _rewrite(physical_record_path, fields, value)
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("fields", "value", "match"),
    [
        (("device_identity", "physical_device"), False, "physical_device"),
        (("device_identity", "model"), "sdk_gphone64_arm64", "emulator identity"),
        (("device_identity", "ro_kernel_qemu"), "1", "non-QEMU"),
        (("device_identity", "avd_name"), "Hermes_API_35", "non-QEMU"),
        (("device_identity", "primary_abi"), "x86_64", "primary_abi"),
        (("device_identity", "supported_abis"), ["x86_64"], "supported_abis"),
    ],
)
def test_physical_record_rejects_emulator_or_non_arm64_identity(
    physical_record_path, evidence_module, artifacts, fields, value, match
):
    _rewrite(physical_record_path, fields, value)
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("device_visible_bytes", 2_574_807_839),
        ("device_sha256", "8" * 64),
        ("device_path", "/sdcard/Download/Nanbeige4.2-3B-Q4_K_M.gguf"),
        ("file_name", "different.gguf"),
    ],
)
def test_physical_record_rejects_wrong_exact_nanbeige_bytes_sha_or_path(
    physical_record_path, evidence_module, artifacts, field, value
):
    _rewrite(physical_record_path, ("model_identity", field), value)
    with pytest.raises(evidence_module.EvidenceError, match=field):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("fields", "value", "match"),
    [
        (("stable_precondition", "capture_route"), "run-as-direct-stable-runtime", "capture_route"),
        (("stable_precondition", "source_candidate_apk_sha256"), "8" * 64, "source_candidate"),
        (("stable_precondition", "runtime_directory_path"), "/data/local/tmp/wrong", "runtime_directory"),
        (("stable_precondition", "runtime_closure_file_count"), 11, "runtime_closure_file_count"),
        (("stable_precondition", "runtime_closure_total_bytes"), 1, "runtime_closure_total_bytes"),
        (("stable_precondition", "runtime_closure_manifest_sha256"), "8" * 64, "canonical closure"),
        (("stable_precondition", "system_library_allowlist"), ["libc.so"], "system_library_allowlist"),
        (("stable_precondition", "unresolved_non_system_dependencies"), ["libmissing.so"], "unresolved"),
        (("stable_precondition", "command_executable_path"), "/data/local/tmp/wrong", "command_executable"),
        (("stable_precondition", "command_working_directory"), "/data/local/tmp/wrong", "command_working"),
        (("stable_precondition", "command_library_path"), "/data/local/tmp/wrong", "command_library"),
        (("stable_precondition", "command_model_path"), "/sdcard/wrong.gguf", "command_model_path"),
        (("stable_precondition", "command_environment", "PATH"), "/vendor/bin", "command_environment"),
        (("stable_precondition", "command_environment_sha256"), "8" * 64, "canonical environment"),
        (("stable_precondition", "command_argv", 6), "18082", "command_argv"),
        (("stable_precondition", "command_argv_sha256"), "8" * 64, "canonical command argv"),
        (("stable_precondition", "device_runtime_cleanup_verified"), False, "cleanup"),
        (("stable_precondition", "selected_runtime_lane"), "turboquant", "selected_runtime_lane"),
        (("stable_precondition", "ready"), True, "ready"),
        (("stable_precondition", "process_exit_code"), 0, "process_exit_code"),
        (("stable_precondition", "unknown_model_architecture"), "llama", "unknown_model_architecture"),
        (("stable_precondition", "error_message"), "failed to load model", "error_message"),
        (("stable_precondition", "loader_error_absent"), False, "loader_error_absent"),
        (
            ("stable_precondition", "error_message"),
            "CANNOT LINK EXECUTABLE: library libmissing.so not found; unknown model architecture: 'nanbeige'",
            "linker or loader",
        ),
        (("automatic_reconciliation", "automatic"), False, "automatic"),
        (("automatic_reconciliation", "exact_artifact_verified_before_reconciliation"), False, "exact_artifact"),
        (("automatic_reconciliation", "settings_after_runtime_lane"), "stable", "settings_after_runtime_lane"),
        (("automatic_reconciliation", "settings_save_succeeded"), False, "settings_save_succeeded"),
        (("automatic_reconciliation", "persisted_before_runtime_launch"), False, "persisted_before_runtime_launch"),
        (("automatic_reconciliation", "visible_settings_runtime_lane"), "stable", "visible_settings_runtime_lane"),
        (
            ("automatic_reconciliation", "visible_settings_matches_persisted_lane"),
            False,
            "visible_settings_matches_persisted_lane",
        ),
        (
            ("automatic_reconciliation", "visible_settings_observed_after_ready"),
            False,
            "visible_settings_observed_after_ready",
        ),
        (("automatic_reconciliation", "user_reselected_lane"), True, "user_reselected_lane"),
    ],
)
def test_physical_record_requires_stable_failure_and_automatic_persisted_reconciliation(
    physical_record_path, evidence_module, artifacts, fields, value, match
):
    _rewrite(physical_record_path, fields, value)
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("field", "value", "match"),
    [
        ("apk_entry", "lib/arm64-v8a/wrong.so", "runtime_closure APK entries"),
        ("file_name", "wrong.so", "file_name"),
        ("role", "unclassified", "role"),
        ("device_path", "/data/local/tmp/wrong.so", "device_path"),
        ("dt_needed", ["libmissing.so"], "dt_needed"),
        ("device_bytes", 1, "device_bytes"),
        ("device_sha256", "8" * 64, "device_sha256"),
        ("extracted_sha256", "not-a-sha", "extracted_sha256"),
    ],
)
def test_physical_record_requires_exact_hashed_stable_runtime_closure(
    physical_record_path, evidence_module, artifacts, field, value, match
):
    payload = json.loads(physical_record_path.read_text(encoding="utf-8"))
    payload["stable_precondition"]["runtime_closure"][0][field] = value
    physical_record_path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


def test_physical_record_rejects_missing_or_reordered_stable_runtime_closure(
    physical_record_path, evidence_module, artifacts
):
    payload = json.loads(physical_record_path.read_text(encoding="utf-8"))
    closure = payload["stable_precondition"]["runtime_closure"]
    closure[0], closure[1] = closure[1], closure[0]
    physical_record_path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="runtime_closure APK entries"):
        _validate_physical(evidence_module, physical_record_path, artifacts)

    _write_physical_record(physical_record_path, evidence_module)
    payload = json.loads(physical_record_path.read_text(encoding="utf-8"))
    payload["stable_precondition"]["runtime_closure"].pop()
    payload["stable_precondition"]["runtime_closure_file_count"] -= 1
    physical_record_path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="must contain exactly"):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("fields", "value", "match"),
    [
        (("readiness", "controller_ready"), False, "controller_ready"),
        (("readiness", "health_endpoint_ok"), False, "health_endpoint_ok"),
        (("readiness", "completion_canary_nonempty"), False, "completion_canary_nonempty"),
        (("readiness", "persisted_runtime_lane"), "stable", "persisted_runtime_lane"),
    ],
)
def test_physical_record_requires_app_managed_turboquant_readiness(
    physical_record_path, evidence_module, artifacts, fields, value, match
):
    _rewrite(physical_record_path, fields, value)
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("fields", "value", "match"),
    [
        (("ordinary_chat", "tool_call_count"), 1, "tool_call_count"),
        (("ordinary_chat", "tool_result_count"), 1, "tool_result_count"),
        (("ordinary_chat", "visible_reply"), "   ", "visible_reply"),
        (("ordinary_chat", "visible_reply"), "Hello!", "deterministic Nanbeige canary"),
        (
            ("ordinary_chat", "visible_reply"),
            "Hermes could not complete this reply. Review the error above and try again.",
            "deterministic Nanbeige canary",
        ),
        (
            ("ordinary_chat", "visible_reply"),
            "This reply was stopped by the user.",
            "deterministic Nanbeige canary",
        ),
        (("ordinary_chat", "visible_reply"), "<think>hidden</think>Hello", "think marker"),
        (("ordinary_chat", "visible_progress_observed"), False, "visible_progress_observed"),
        (("ordinary_chat", "progress_event_count"), 0, "progress_event_count"),
        (("ordinary_chat", "terminal_state"), "working", "terminal_state"),
    ],
)
def test_physical_record_requires_visible_tool_free_completed_ordinary_chat(
    physical_record_path, evidence_module, artifacts, fields, value, match
):
    _rewrite(physical_record_path, fields, value)
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.mark.parametrize(
    ("fields", "value", "match"),
    [
        (("stop_control", "stop_acknowledged"), False, "stop_acknowledged"),
        (("stop_control", "model_request_cancelled"), False, "model_request_cancelled"),
        (("stop_control", "terminal_state"), "working", "terminal_state"),
        (("stop_control", "visible_terminal_message"), "", "visible_terminal_message"),
        (("stop_control", "nonterminal_placeholder"), True, "nonterminal_placeholder"),
        (("stop_control", "busy_after_stop"), True, "busy_after_stop"),
    ],
)
def test_physical_record_requires_stop_to_leave_nonblank_terminal_message(
    physical_record_path, evidence_module, artifacts, fields, value, match
):
    _rewrite(physical_record_path, fields, value)
    with pytest.raises(evidence_module.EvidenceError, match=match):
        _validate_physical(evidence_module, physical_record_path, artifacts)


@pytest.fixture
def v151_root(tmp_path, monkeypatch, evidence_module, artifacts):
    for module in (legacy, v3):
        monkeypatch.setattr(module, "TAG" if module is legacy else "V3_TAG", TAG)
        monkeypatch.setattr(
            module,
            "VERSION_NAME" if module is legacy else "V3_VERSION_NAME",
            VERSION_NAME,
        )
        monkeypatch.setattr(
            module,
            "VERSION_CODE" if module is legacy else "V3_VERSION_CODE",
            VERSION_CODE,
        )
        monkeypatch.setattr(module, "RUN_ID" if module is legacy else "V3_RUN_ID", RUN_ID)
        monkeypatch.setattr(
            module,
            "LITERTLM_COORDINATE" if module is legacy else "V3_LITERTLM_COORDINATE",
            evidence_module.litertlm_coordinate_for_tag(TAG),
        )
    root = tmp_path / "release-evidence"
    v3._write_v3_fixture(root, evidence_module, artifacts)
    model_path = root / "models" / "nanbeige4.2-3b-q4-k-m.json"
    model_record = json.loads(model_path.read_text(encoding="utf-8"))
    model_record["details"].update(
        {
            "runtime_lane": "turboquant",
            "cache_type_k": "turbo3",
            "cache_type_v": "turbo3",
            "flash_attention": "on",
        }
    )
    model_path.write_text(json.dumps(model_record), encoding="utf-8")
    _write_physical_record(
        root / Path(evidence_module.PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix()),
        evidence_module,
    )

    def synthetic_decode(path: Path):
        canonical = "phone-compact" if "phone-compact" in path.parts else "tablet"
        performance = json.loads(
            (root / "performance" / f"{canonical}.json").read_text(encoding="utf-8")
        )
        screen = performance["screen"]
        return evidence_module.DecodedPng(
            screen["width_px"],
            screen["height_px"],
            hashlib.sha256(path.read_bytes()).hexdigest(),
            16,
        )

    monkeypatch.setattr(evidence_module, "_decode_png", synthetic_decode)
    return root


def test_v151_directory_and_manifest_include_exact_physical_repair_binding(
    v151_root, evidence_module, artifacts
):
    validated = evidence_module.validate_evidence_directory(
        v151_root,
        artifacts,
        SOURCE_DIGEST,
        TAG,
    )
    assert validated.physical_nanbeige_repair_count == 1
    assert validated.physical_candidate_apk_sha256 == CANDIDATE_SHA256
    source = evidence_module.SourceTreeIdentity(
        algorithm=evidence_module.SOURCE_DIGEST_ALGORITHM,
        digest=SOURCE_DIGEST,
        file_count=123,
        git_object_format="sha1",
        excluded_prefix="android/release-evidence/",
    )
    manifest = evidence_module.build_manifest(
        tag=TAG,
        source=source,
        artifacts=artifacts,
        evidence=validated,
    )
    assert manifest["contract"]["requires_one_physical_arm64_nanbeige_repair_record"] is True
    assert manifest["contract"]["required_stable_runtime_apk_entries"] == list(
        evidence_module.PHYSICAL_STABLE_RUNTIME_APK_ENTRIES
    )
    assert manifest["contract"]["required_stable_runtime_file_count"] == 12
    assert (
        manifest["contract"][
            "requires_final_signed_apk_runtime_closure_entry_hash_binding"
        ]
        is True
    )
    assert (
        manifest["contract"][
            "requires_source_candidate_runtime_closure_extraction_and_device_hash_match"
        ]
        is True
    )
    assert (
        manifest["contract"]["requires_stable_runtime_dependency_and_environment_binding"]
        is True
    )
    assert manifest["contract"]["requires_no_stable_runtime_linker_or_loader_error"] is True
    assert (
        manifest["contract"]["required_general_mode_prompt"]
        == evidence_module.PHYSICAL_ORDINARY_CHAT_PROMPT
    )
    assert (
        manifest["contract"]["required_general_mode_visible_reply"]
        == evidence_module.PHYSICAL_ORDINARY_CHAT_EXPECTED_REPLY
    )
    assert (
        manifest["contract"]["requires_visible_settings_to_match_reconciled_turboquant_lane"]
        is True
    )
    assert (
        manifest["physical_device_evidence"]["path"]
        == evidence_module.PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix()
    )
    assert manifest["tested_binaries"]["physical_candidate_apk_sha256"] == CANDIDATE_SHA256
    assert manifest["summary"]["physical_nanbeige_repair_count"] == 1


def test_v151_directory_fails_closed_when_fixed_physical_record_is_missing(
    v151_root, evidence_module, artifacts
):
    (v151_root / Path(evidence_module.PHYSICAL_NANBEIGE_REPAIR_PATH.as_posix())).unlink()
    with pytest.raises(evidence_module.EvidenceError, match="missing required fixed paths"):
        evidence_module.validate_evidence_directory(
            v151_root,
            artifacts,
            SOURCE_DIGEST,
            TAG,
        )
