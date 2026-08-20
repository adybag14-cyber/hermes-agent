from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from tests.hermes_android import test_android_release_evidence as legacy


REPO_ROOT = legacy.REPO_ROOT
V3_TAG = "v0.13.148"
V3_VERSION_NAME = "0.13.148"
V3_VERSION_CODE = 144_890
V3_RUN_ID = "release-v0.13.148-synthetic-run"
V3_LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.1"


@pytest.fixture(scope="module")
def evidence_module():
    return legacy._load_module()


@pytest.fixture
def artifacts(evidence_module):
    return legacy.artifacts.__wrapped__(evidence_module)


def _palette(scale: float = 1.0, shape: str = "rounded", light: bool = False) -> dict[str, str]:
    values = {
        "theme_primary": "#1565C0" if light else "#7AE7C7",
        "theme_secondary": "#8E24AA" if light else "#FFB86B",
        "theme_background": "#FAFBFF" if light else "#07110F",
        "theme_surface": "#FFFFFF" if light else "#0E1A17",
        "theme_surface_variant": "#EDF1FA" if light else "#172723",
        "card_shape": shape,
        "ui_font_scale": str(scale),
    }
    return values


def _metadata(profile: str, performance: dict, screenshot: Path, language: str, **palette) -> dict[str, str]:
    screen = performance["screen"]
    device = performance["device"]
    return {
        "profile": profile,
        "language": language,
        "theme_id": "custom-light" if palette["theme_background"] == "#FAFBFF" else "hermes",
        **palette,
        "screen_width_dp": str(screen["width_dp"]),
        "screen_height_dp": str(screen["height_dp"]),
        "system_font_scale": "1.0",
        "package_id": "com.mobilefork.hermesagent",
        "version_name": V3_VERSION_NAME,
        "version_code": str(V3_VERSION_CODE),
        "build_variant": "debug",
        "source_digest": legacy.SOURCE_DIGEST,
        "candidate_apk_sha256": legacy.UI_TARGET_SHA,
        "instrumentation_apk_sha256": legacy.UI_TEST_SHA,
        "evidence_run_id": V3_RUN_ID,
        "device_serial": device["serial"],
        "avd_name": device["avd_name"],
        "device_boot_id": device["boot_id"],
        "build_fingerprint": device["build_fingerprint"],
        "screenshot_sha256": hashlib.sha256(screenshot.read_bytes()).hexdigest(),
    }


def _write_capture(
    directory: Path,
    profile: str,
    performance: dict,
    identity: str,
    coverage_kind: str,
    language: str = "en",
    framework: bool = False,
    palette: dict[str, str] | None = None,
    page_id: str = "Hermes",
    sentinels: tuple[str, ...] | None = None,
) -> dict[str, str]:
    declared_sentinels = sentinels or (f"Visible {identity}",)
    slug = identity.replace(":", "-").replace("/", "-")
    artifact = f"headed-{V3_RUN_ID}-{profile}-{slug}"
    screenshot = directory / f"{artifact}.png"
    screenshot.write_bytes(f"synthetic-png:{profile}:{identity}".encode())
    metadata = _metadata(
        profile,
        performance,
        screenshot,
        language,
        **(palette or _palette()),
    )
    if framework:
        proof = directory / f"{artifact}-ui.xml"
        entries = "\n".join(
            f'    <entry key="{key}" value="{value}" />' for key, value in metadata.items()
        )
        proof.write_text(
            "\n".join(
                [
                    '<?xml version="1.0" encoding="utf-8"?>',
                    f'<hermes-ui-evidence artifact="{artifact}" evidence-identity="{identity}" '
                    f'coverage-kind="{coverage_kind}" page-id="{page_id}">',
                    "  <metadata>",
                    entries,
                    *(f'    <sentinel value="{sentinel}" />' for sentinel in declared_sentinels),
                    "  </metadata>",
                    "  <view-hierarchy>",
                    *(
                        f'    <node class="android.widget.TextView" visible="true" text="{sentinel}" />'
                        for sentinel in declared_sentinels
                    ),
                    "  </view-hierarchy>",
                    "</hermes-ui-evidence>",
                ]
            ),
            encoding="utf-8",
        )
    else:
        proof = directory / f"{artifact}-semantics.txt"
        header = {
            "evidence_type": "headed-ui-coverage-bound",
            "evidence_identity": identity,
            "artifact": artifact,
            "coverage_kind": coverage_kind,
            "page_id": page_id,
            **metadata,
        }
        proof.write_text(
            "\n".join(
                [
                    *(f"{key}={value}" for key, value in header.items()),
                    *(f"sentinel={sentinel}" for sentinel in declared_sentinels),
                    "",
                    "Node tree",
                    *(f"Text = '[{sentinel}]'" for sentinel in declared_sentinels),
                ],
            ),
            encoding="utf-8",
        )
    return {"identity": identity, "screenshot": screenshot.name, "proof": proof.name}


def _write_inventory(
    directory: Path,
    filename: str,
    coverage_kind: str,
    profile: str,
    performance: dict,
    captures: list[dict[str, str]],
) -> None:
    device = performance["device"]
    lines = [
        "evidence_type=headed-ui-coverage-inventory-bound",
        f"coverage_kind={coverage_kind}",
        f"profile={profile}",
        f"capture_count={len(captures)}",
        f"source_digest={legacy.SOURCE_DIGEST}",
        f"candidate_apk_sha256={legacy.UI_TARGET_SHA}",
        f"instrumentation_apk_sha256={legacy.UI_TEST_SHA}",
        f"evidence_run_id={V3_RUN_ID}",
        f"device_serial={device['serial']}",
        f"avd_name={device['avd_name']}",
        f"device_boot_id={device['boot_id']}",
    ]
    for index, capture in enumerate(captures, start=1):
        for field in ("identity", "screenshot", "proof"):
            lines.append(f"capture.{index}.{field}={capture[field]}")
    (directory / filename).write_text("\n".join(lines) + "\n", encoding="utf-8")


def _complete_captures(
    directory: Path,
    profile: str,
    performance: dict,
    source_contract,
) -> list[dict[str, str]]:
    captures = []
    captures.extend(
        _write_capture(
            directory,
            profile,
            performance,
            f"section:{section}",
            "app-section",
            page_id=section,
        )
        for section in source_contract.app_sections
    )
    captures.extend(
        _write_capture(
            directory,
            profile,
            performance,
            f"settings:{page}",
            "settings-subpage",
            page_id=f"Settings.{page}",
        )
        for page in source_contract.settings_pages
    )
    captures.extend(
        _write_capture(
            directory,
            profile,
            performance,
            f"device:{page}",
            "device-subpage",
            page_id=page,
        )
        for page in source_contract.device_pages
    )
    captures.extend(
        _write_capture(
            directory,
            profile,
            performance,
            f"appearance-preset:preset-{index}",
            "appearance-preset",
        )
        for index in range(1, 6)
    )
    for shape in ("square", "soft", "rounded"):
        captures.append(
            _write_capture(
                directory,
                profile,
                performance,
                f"shape:{shape}",
                "rendered-card-shape",
                palette=_palette(shape=shape),
            )
        )
    for label, scale in (("small", 0.9), ("default", 1.0), ("large", 1.2)):
        captures.append(
            _write_capture(
                directory,
                profile,
                performance,
                f"font:{label}:{int(scale * 100):03d}",
                "rendered-font-scale",
                palette=_palette(scale=scale),
            )
        )
    captures.append(
        _write_capture(
            directory,
            profile,
            performance,
            "appearance-custom-light",
            "custom-light-palette",
            palette=_palette(light=True),
        )
    )
    captures.extend(
        _write_capture(
            directory,
            profile,
            performance,
            f"framework:en:Framework{index}",
            "framework-view-activity",
            framework=True,
            palette=_palette(light=True),
        )
        for index in range(1, 5)
    )
    return captures


def _localized_captures(
    directory: Path,
    profile: str,
    performance: dict,
    source_contract,
) -> list[dict[str, str]]:
    captures = []
    for language in ("en", "zh", "es", "de", "pt", "fr"):
        captures.extend(
            _write_capture(
                directory,
                profile,
                performance,
                f"localized-model:{language}:{model_id}",
                "six-language-recommended-model",
                language=language,
                page_id=f"Settings.Models.{model_id}",
            )
            for model_id in source_contract.recommended_model_ids
        )
        if language != "en":
            captures.extend(
                _write_capture(
                    directory,
                    profile,
                    performance,
                    f"framework:{language}:Framework{index}",
                    "framework-view-activity",
                    language=language,
                    framework=True,
                )
                for index in range(1, 5)
            )
    return captures


def _write_launch_theme(
    root: Path,
    canonical_profile: str,
    profile: str,
    performance: dict,
    custom_capture: dict[str, str],
) -> None:
    directory = root / "launch-theme" / canonical_profile
    directory.mkdir(parents=True)
    ui_directory = root / "ui-coverage" / canonical_profile
    proof = ui_directory / custom_capture["proof"]
    palette = _palette(light=True)
    device = performance["device"]
    state_name = f"{canonical_profile}-persisted-palette.json"
    shared_preferences_sha = "9" * 64
    state = {
        "schema": "hermes-persisted-palette-state-v1",
        "identity": {
            "source_digest": legacy.SOURCE_DIGEST,
            "candidate_apk_sha256": legacy.UI_TARGET_SHA,
            "instrumentation_apk_sha256": legacy.UI_TEST_SHA,
            "evidence_run_id": V3_RUN_ID,
            "device_serial": device["serial"],
            "avd_name": device["avd_name"],
            "device_boot_id": device["boot_id"],
            "profile": profile,
        },
        "theme_id": "custom-light",
        "palette": {**palette, "ui_font_scale": float(palette["ui_font_scale"])},
        "shared_preferences_xml_sha256": shared_preferences_sha,
        "contains_only_filtered_palette_state": True,
    }
    state_path = directory / state_name
    state_path.write_text(json.dumps(state), encoding="utf-8")
    captures = []
    for label in ("cold-launcher-tap", "cold-deep-link"):
        video = directory / f"{canonical_profile}-{label}.mp4"
        video.write_bytes(b"\x00\x00\x00\x18ftypmp42" + label.encode())
        screenshot = directory / f"{canonical_profile}-{label}.png"
        screenshot.write_bytes(f"settled:{canonical_profile}:{label}".encode())
        activity = directory / f"{canonical_profile}-{label}.txt"
        activity.write_text(
            "mResumedActivity com.mobilefork.hermesagent/.MainActivity",
            encoding="utf-8",
        )
        captures.append(
            {
                "label": label,
                "launch_stdout": "Starting: Intent",
                "launch_stderr": "",
                "video": video.name,
                "video_sha256": hashlib.sha256(video.read_bytes()).hexdigest(),
                "settled_screenshot": screenshot.name,
                "settled_screenshot_sha256": hashlib.sha256(screenshot.read_bytes()).hexdigest(),
                "activity_dump": activity.name,
                "activity_dump_sha256": hashlib.sha256(activity.read_bytes()).hexdigest(),
                "automated_state_verdict": "main_activity_resumed_and_artifacts_decoded",
                "visual_splash_verdict": "manual_review_required",
            }
        )
    expected_kind = "phone" if canonical_profile == "phone-compact" else "tablet"
    screen = performance["screen"]
    manifest = {
        "schema": "hermes-host-launch-theme-evidence-v2",
        "identity": {
            "serial": device["serial"],
            "avd_name": device["avd_name"],
            "expected_profile": expected_kind,
            "evidence_run_id": V3_RUN_ID,
            "source_digest": legacy.SOURCE_DIGEST,
            "candidate_apk_sha256": legacy.UI_TARGET_SHA,
            "instrumentation_apk_sha256": legacy.UI_TEST_SHA,
            "observed_avd_name": device["avd_name"],
            "observed_profile": expected_kind,
            "width_dp": screen["width_dp"],
            "height_dp": screen["height_dp"],
            "sdk_int": device["android_sdk"],
            "build_fingerprint": device["build_fingerprint"],
            "device_boot_id": device["boot_id"],
            "installed_apk_path": "/data/app/hermes/base.apk",
            "installed_apk_sha256": legacy.UI_TARGET_SHA,
            "installed_instrumentation_apk_path": "/data/app/hermes-test/base.apk",
            "installed_instrumentation_apk_sha256": legacy.UI_TEST_SHA,
        },
        "palette": {
            "theme_id": "custom-light",
            "profile": profile,
            "proof_evidence_identity": "appearance-custom-light",
            "proof_sha256": hashlib.sha256(proof.read_bytes()).hexdigest(),
            **{**palette, "ui_font_scale": float(palette["ui_font_scale"])},
            "persisted_state_file": state_name,
            "persisted_state_file_sha256": hashlib.sha256(state_path.read_bytes()).hexdigest(),
            "shared_preferences_xml_sha256": shared_preferences_sha,
            "verified_against_persisted_app_state": True,
        },
        "captures": captures,
        "automated_verdict": "identity_bound_launch_capture_complete",
        "visual_review": {
            "status": "reviewed",
            "reviewer": "Synthetic Release Reviewer",
            "reviewed_at_utc": "2026-08-14T20:15:00Z",
            "decision": "pass",
            "notes": "Both synthetic launch lanes were reviewed frame by frame.",
            "method": "manual-frame-by-frame",
            "automated_pixel_certification": False,
        },
        "manual_acceptance": ["one", "two", "three", "four"],
    }
    (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def _issue_identity(performance: dict) -> dict:
    device = performance["device"]
    return {
        "release_source_digest": legacy.SOURCE_DIGEST,
        "candidate_apk_sha256": legacy.UI_TARGET_SHA,
        "instrumentation_apk_sha256": legacy.UI_TEST_SHA,
        "evidence_run_id": V3_RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "version_name": V3_VERSION_NAME,
        "version_code": V3_VERSION_CODE,
        "release_tag": V3_TAG,
        "build_variant": "debug",
        "lite_rt_lm_coordinate": V3_LITERTLM_COORDINATE,
        "device_serial": device["serial"],
        "avd_name": device["avd_name"],
        "device_boot_id": device["boot_id"],
        "device_model": device["model"],
        "build_fingerprint": device["build_fingerprint"],
        "android_sdk": device["android_sdk"],
        "supported_abis": device["supported_abis"],
        "profile": performance["profile"],
    }


def _write_issue8_evidence(root: Path, module, performance: dict) -> None:
    payload = {
        "schema": module.ISSUE8_EVIDENCE_SCHEMA,
        "issue_number": 8,
        "result": "pass",
        "overall_exit_code": 0,
        "evidence_source": "instrumentation",
        "instrumentation_method": module.ISSUE8_INSTRUMENTATION_METHOD,
        "release_identity": _issue_identity(performance),
        "direct_tool_routes": [
            {
                "prompt": "Run a command to tell me what time it is.",
                "tool_name": "terminal_tool",
                "tool_action": "date",
                "visible_tool_event": True,
                "visible_result_event": True,
                "visible_result_text": "Fri Aug 14 20:15:00 BST 2026",
                "executed_tool_calls": 1,
                "model_request_count": 0,
                "provider_network_request_count": 0,
            },
            {
                "prompt": "Check my device status",
                "tool_name": "android_device_diagnostics_tool",
                "tool_action": "status",
                "visible_tool_event": True,
                "visible_result_event": True,
                "visible_result_text": '{"status":"ready","available_system_actions":[]}',
                "executed_tool_calls": 1,
                "model_request_count": 0,
                "provider_network_request_count": 0,
            },
        ],
        "catalog_policy": {
            "evaluation_source": "production-mobile-catalog-policy",
            "model_id": module.ISSUE8_TWELVE_B_MODEL_ID,
            "repository": module.ISSUE8_TWELVE_B_REPOSITORY,
            "revision": module.ISSUE8_TWELVE_B_REVISION,
            "file_name": module.ISSUE8_TWELVE_B_FILE_NAME,
            "catalog_declared_bytes": module.ISSUE8_TWELVE_B_BYTES,
            "expected_sha256": module.ISSUE8_TWELVE_B_SHA256,
            "release_certified": False,
            "quick_start_eligible": False,
            "present_in_mobile_quick_catalog": False,
            "automatically_selected": False,
            "artifact_file_present": False,
        },
        "twelve_b_preflight": {
            "model_id": module.ISSUE8_TWELVE_B_MODEL_ID,
            "repository": module.ISSUE8_TWELVE_B_REPOSITORY,
            "revision": module.ISSUE8_TWELVE_B_REVISION,
            "file_name": module.ISSUE8_TWELVE_B_FILE_NAME,
            "catalog_declared_bytes": module.ISSUE8_TWELVE_B_BYTES,
            "model_bytes_evaluated": module.ISSUE8_TWELVE_B_BYTES,
            "expected_sha256": module.ISSUE8_TWELVE_B_SHA256,
            "backend": "litert-lm",
            "artifact_path": "",
            "artifact_file_present": False,
            "evaluation_source": "production-local-model-runtime-preflight",
            "memory_profile": {
                "source": "controlled-instrumentation-memory-snapshot",
                "classification": "nominal-16-gib",
                "total_bytes": module.NOMINAL_SIXTEEN_GIB_BYTES,
                "available_bytes": 10_000_000_000,
                "threshold_bytes": 500_000_000,
                "usable_available_bytes": 9_500_000_000,
                "low_memory": False,
            },
            "requested_context_tokens": 32_000,
            "effective_context_tokens": 2_048,
            "estimated_additional_bytes": 10_440_486_640,
            "preflight_allowed": False,
            "preflight_level": "blocked",
            "blocked_before_native_engine": True,
            "native_engine_start_attempted": False,
            "native_engine_started": False,
            "requires_app_restart": False,
            "reason": (
                "Only 9.5 GB usable RAM is available; this litert-lm start is estimated to need "
                "10.4 GB in addition to Android's reserve. Context is limited to 2048 tokens. "
                "Close memory-heavy apps or choose a smaller model."
            ),
        },
        "validation_errors": [],
    }
    directory = root / "issues"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "issue-8-tool-and-preflight.json").write_text(json.dumps(payload), encoding="utf-8")


def _command(command: str, stdout: str) -> dict:
    return {
        "command": command,
        "exit_code": 0,
        "stdout": stdout,
        "stderr": "",
        "sandbox_execution_mode": "proot_distro_qemu",
    }


def _native_route(route_path: str, file_name: str) -> dict:
    path = f"/data/app/hermes/lib/x86_64/{file_name}"
    return {
        "route_path": route_path,
        "path": path,
        "expected_file_name": file_name,
        "exists": True,
        "executable": True,
        "trusted": True,
    }


def _write_issue16_evidence(root: Path, module, performance: dict) -> None:
    proot_name = "libhermes_exec_bin_proot.so"
    proot_path = f"/data/app/hermes/lib/x86_64/{proot_name}"
    asset_sha = "7" * 64
    payload = {
        "schema": module.ISSUE16_EVIDENCE_SCHEMA,
        "issue_number": 16,
        "result": "pass",
        "overall_exit_code": 0,
        "release_identity": _issue_identity(performance),
        "packaged_runtime": {
            "packaged_asset_path": "hermes-linux/x86_64/manifest.json",
            "packaged_asset_sha256": asset_sha,
            "packaged_asset_skipped": False,
            "packaged_assets_present": True,
            "execution_mode": "embedded_termux",
            "uses_termux": True,
            "android_abi": "x86_64",
            "asset_manifest_sha256": asset_sha,
            "asset_refresh_error": "",
            "native_execution_route": "apk_native_library_direct",
            "proot_direct_exec_patch_ready": True,
            "host_printenv_command": "printenv HERMES_ANDROID_PROOT_EXECUTABLE",
            "host_printenv_exit_code": 0,
            "host_printenv_stdout": f"{proot_path}\n",
            "host_printenv_stderr": "",
            "proot_executable": proot_path,
            "trusted_native_routes": {
                "proot": _native_route(proot_path, proot_name),
                "qemu_user": _native_route(
                    "/data/app/hermes/lib/x86_64/libhermes_exec_bin_qemu_aarch64.so",
                    "libhermes_exec_bin_qemu_aarch64.so",
                ),
                "coreutils": _native_route(
                    "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/"
                    "x86_64/native-exec/bin/printenv",
                    "libhermes_exec_bin_coreutils.so",
                ),
            },
        },
        "sandbox": {
            "name": "hermes-debian-issue16-proof",
            "fresh_requested": True,
            "sandbox_existed_before": False,
            "deploy_exit_code": 0,
            "deployment_completed": True,
            "failed_phase": "",
            "sandbox_state": "ready",
            "sandbox_preserved_for_retry": False,
            "update_exit_code": 0,
            "update_command": (
                "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && "
                "DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install curl"
            ),
            "requested_timeout_seconds": 900,
            "guest_ca_bundle": {
                "exit_code": 0,
                "path": (
                    "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/prefix/"
                    "var/lib/proot-distro/containers/hermes-debian-issue16-proof/rootfs/"
                    "etc/ssl/certs/ca-certificates.crt"
                ),
                "source": "/apex/com.android.conscrypt/cacerts",
                "certificate_count": 140,
                "android_certificate_count": 140,
                "replaced_truncated_guest_bundle": False,
                "previous_certificate_count": 0,
                "sha256": "8" * 64,
            },
        },
        "guest_routing": {
            "expected_path": module.GUEST_ONLY_PATH,
            "observed_path": module.GUEST_ONLY_PATH,
            "path_command": "printf '%s\\n' \"$PATH\"",
            "path_exit_code": 0,
            "guest_only_path": True,
            "id_route": _command("command -v id", "/usr/bin/id\n"),
            "uname_route": _command("command -v uname", "/usr/bin/uname\n"),
            "curl_route": _command("command -v curl", "/usr/bin/curl\n"),
            "id_path": "/usr/bin/id",
            "uname_path": "/usr/bin/uname",
            "curl_path": "/usr/bin/curl",
        },
        "commands": {
            "id": _command("id", "uid=0(root) gid=0(root) groups=0(root)\n"),
            "uname": _command("uname -a", "Linux localhost 6.1.0 GNU/Linux\n"),
            "curl_version": _command("curl --version", "curl 8.14.1 libcurl/8.14.1\n"),
            "https": _command(
                "curl -fsS https://example.com/ >/dev/null && printf 'HTTPS_OK\\n'",
                "HTTPS_OK\n",
            ),
        },
        "cleanup": {
            "action": "uninstall",
            "exit_code": 0,
            "status_exit_code": 0,
            "agent_shell_enabled": False,
            "active_sandbox_name": "",
            "sandbox_name": "hermes-debian-issue16-proof",
            "sandbox_present": False,
            "sandbox_preserved": False,
            "sandbox_removed": True,
            "disposition": "sandbox_removed_stopped",
        },
        "validation_errors": [],
    }
    directory = root / "issues"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "issue-16-debian-sandbox.json").write_text(json.dumps(payload), encoding="utf-8")


def _write_v3_fixture(root: Path, module, artifacts) -> None:
    legacy._write_fixture(root, module, artifacts)
    source_contract = module.load_ui_evidence_source_contract(REPO_ROOT)
    historical = legacy._model_record(module.HISTORICAL_E4B_ARTIFACT)
    historical.update(
        {
            "accelerator": "cpu",
            "evidence_file": (
                "/data/user/0/com.mobilefork.hermesagent/files/hermes-model-evidence/"
                "litert-lm-gemma-4-E4B-it.litertlm-1780000000000.json"
            ),
            "details": {
                "health_backend": "litert-lm",
                "runtime_entrypoint": "on-device-backend-manager",
                "provisioning_method": "content-addressed-preprovisioned-preferred-download-record",
                "accelerator_attempts": ["cpu/standard: verified completion"],
                "requested_accelerator": "cpu",
                "gpu_attempted": False,
                "requested_speculative_decoding": "disabled",
                "speculative_decoding": False,
                "mtp_policy": "disabled: explicitly disabled by release evidence",
                "image_input_supported": False,
                "audio_input_supported": False,
                "clean_shutdown": True,
                "completion_characters": 24,
                "artifact_summary": "historical Gemma E4B exact custom-import artifact",
            },
        }
    )
    historical_path = root / Path(module.HISTORICAL_E4B_EVIDENCE_PATH.as_posix())
    historical_path.write_text(json.dumps(historical), encoding="utf-8")
    performances = {
        profile: json.loads((root / "performance" / f"{profile}.json").read_text(encoding="utf-8"))
        for profile in module.PROFILES
    }
    complete_by_profile = {}
    for canonical_profile, performance in performances.items():
        screen = performance["screen"]
        kind = "phone" if canonical_profile == "phone-compact" else "tablet"
        profile = f"{kind}-{screen['width_dp']}x{screen['height_dp']}dp"
        directory = root / "ui-coverage" / canonical_profile
        directory.mkdir(parents=True)
        complete = _complete_captures(directory, profile, performance, source_contract)
        complete_by_profile[canonical_profile] = (profile, complete)
        _write_inventory(
            directory,
            "complete-inventory.txt",
            "complete-current-profile",
            profile,
            performance,
            complete,
        )
        if canonical_profile == "phone-compact":
            localized = _localized_captures(directory, profile, performance, source_contract)
            _write_inventory(
                directory,
                "localized-inventory.txt",
                "six-language-and-framework-localization",
                profile,
                performance,
                localized,
            )
    for canonical_profile, performance in performances.items():
        profile, complete = complete_by_profile[canonical_profile]
        custom = next(capture for capture in complete if capture["identity"] == "appearance-custom-light")
        _write_launch_theme(root, canonical_profile, profile, performance, custom)
    _write_issue8_evidence(root, module, performances["phone-compact"])
    _write_issue16_evidence(root, module, performances["phone-compact"])


@pytest.fixture
def v3_root(tmp_path, monkeypatch, evidence_module, artifacts):
    monkeypatch.setattr(legacy, "TAG", V3_TAG)
    monkeypatch.setattr(legacy, "VERSION_NAME", V3_VERSION_NAME)
    monkeypatch.setattr(legacy, "VERSION_CODE", V3_VERSION_CODE)
    monkeypatch.setattr(legacy, "RUN_ID", V3_RUN_ID)
    monkeypatch.setattr(legacy, "LITERTLM_COORDINATE", V3_LITERTLM_COORDINATE)
    root = tmp_path / "release-evidence"
    _write_v3_fixture(root, evidence_module, artifacts)

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


def test_v147_policy_remains_v2_without_new_fixed_paths(evidence_module, artifacts):
    assert evidence_module.manifest_schema_for_tag("v0.13.147") == evidence_module.MANIFEST_SCHEMA_V2
    assert (
        evidence_module.litertlm_coordinate_for_tag("v0.13.147")
        == evidence_module.LEGACY_LITERTLM_COORDINATE
    )
    assert (
        evidence_module.litertlm_coordinate_for_tag("v0.13.148")
        == evidence_module.LITERTLM_COORDINATE
    )
    paths = evidence_module.expected_evidence_paths(artifacts, tag="v0.13.147")
    assert not any(path.parts[0] in {"ui-coverage", "launch-theme", "issues"} for path in paths)
    assert evidence_module.HISTORICAL_E4B_EVIDENCE_PATH not in paths


def test_committed_v147_evidence_remains_byte_for_byte_valid_under_v2(evidence_module, monkeypatch):
    evidence_root = REPO_ROOT / "android" / "release-evidence" / "v0.13.147"
    committed = json.loads((evidence_root / "manifest.json").read_text(encoding="utf-8"))
    def fast_committed_png_identity(path: Path):
        payload = path.read_bytes()
        return evidence_module.DecodedPng(
            int.from_bytes(payload[16:20], "big"),
            int.from_bytes(payload[20:24], "big"),
            hashlib.sha256(payload).hexdigest(),
            16,
        )

    # The PNG decoder's pixel-level behavior already has focused coverage in
    # the v2 suite. Avoid re-decoding twelve large historical PNGs through the
    # repository-wide 30-second per-test guard while retaining byte hashing.
    monkeypatch.setattr(evidence_module, "_decode_png", fast_committed_png_identity)
    artifacts = tuple(
        evidence_module.ArtifactSpec(**record)
        for record in committed["registered_model_matrix"]
    )
    source = evidence_module.SourceTreeIdentity(**committed["source_tree"])
    validated = evidence_module.validate_evidence_directory(
        evidence_root,
        artifacts,
        source.digest,
        "v0.13.147",
    )
    rebuilt = evidence_module.build_manifest(
        tag="v0.13.147",
        source=source,
        artifacts=artifacts,
        evidence=validated,
    )
    assert rebuilt == committed


def test_v148_validates_closed_comprehensive_ui_and_human_review_contract(
    v3_root, evidence_module, artifacts
):
    validated = evidence_module.validate_evidence_directory(
        v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG
    )
    assert validated.comprehensive_ui_capture_count >= 60
    assert validated.launch_theme_capture_count == 4
    assert validated.launch_theme_review_count == 2
    source = evidence_module.SourceTreeIdentity(
        algorithm=evidence_module.SOURCE_DIGEST_ALGORITHM,
        digest=legacy.SOURCE_DIGEST,
        file_count=123,
        git_object_format="sha1",
        excluded_prefix="android/release-evidence/",
    )
    manifest = evidence_module.build_manifest(
        tag=V3_TAG, source=source, artifacts=artifacts, evidence=validated
    )
    assert manifest["schema"] == evidence_module.MANIFEST_SCHEMA_V3
    assert manifest["contract"]["requires_comprehensive_ui_inventory_per_profile"] is True
    source_contract = evidence_module.load_ui_evidence_source_contract(REPO_ROOT)
    assert manifest["contract"]["required_app_sections"] == list(source_contract.app_sections)
    assert manifest["contract"]["required_nested_settings_pages"] == list(source_contract.settings_pages)
    assert manifest["contract"]["required_non_overview_device_pages"] == list(
        source_contract.device_pages
    )
    assert manifest["contract"]["required_recommended_model_ids"] == list(
        source_contract.recommended_model_ids
    )
    assert manifest["contract"]["requires_human_frame_by_frame_launch_theme_review"] is True
    assert manifest["contract"]["launch_theme_capture_does_not_self_certify_pixels"] is True
    assert manifest["summary"]["historical_issue8_model_count"] == 1
    assert manifest["summary"]["issue8_tool_and_preflight_count"] == 1
    assert manifest["summary"]["issue16_debian_sandbox_count"] == 1
    assert manifest["issue_evidence"]["issue_8"]["path"] == "issues/issue-8-tool-and-preflight.json"
    assert manifest["issue_evidence"]["issue_16"]["path"] == "issues/issue-16-debian-sandbox.json"
    assert manifest["historical_model_evidence"]["artifact"]["expected_bytes"] == 3_654_467_584
    assert manifest["historical_model_evidence"][
        "excluded_from_release_matrix_and_quick_recommendations"
    ] is True


@pytest.mark.parametrize(
    ("field_path", "invalid"),
    (
        (("publisher_revision",), "0" * 40),
        (("publisher_expected_bytes",), 3_654_467_583),
        (("expected_sha256",), "0" * 64),
        (("accelerator",), "gpu"),
        (("runtime_started",), False),
        (("health_ok",), False),
        (("completion_nonempty",), False),
        (("elapsed_ms",), 0),
        (("details", "runtime_entrypoint"), "direct-litert-proxy"),
        (("details", "requested_accelerator"), "auto"),
        (("details", "gpu_attempted"), True),
        (("details", "requested_speculative_decoding"), "auto"),
        (("details", "speculative_decoding"), True),
        (("details", "mtp_policy"), "enabled"),
        (("details", "image_input_supported"), True),
        (("details", "audio_input_supported"), True),
        (("details", "clean_shutdown"), False),
    ),
)
def test_v148_historical_e4b_record_is_exact_cpu_text_only_evidence(
    v3_root, evidence_module, artifacts, field_path, invalid
):
    path = v3_root / Path(evidence_module.HISTORICAL_E4B_EVIDENCE_PATH.as_posix())
    payload = json.loads(path.read_text(encoding="utf-8"))
    target = payload
    for field in field_path[:-1]:
        target = target[field]
    target[field_path[-1]] = invalid
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


def test_v148_rejects_pending_visual_review(v3_root, evidence_module, artifacts):
    path = v3_root / "launch-theme" / "phone-compact" / "manifest.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    payload["visual_review"].update(
        {"status": "pending", "reviewer": None, "reviewed_at_utc": None, "decision": None, "notes": None}
    )
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="passing completed human visual review"):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


def test_v148_rejects_palette_state_that_differs_from_rendered_proof(
    v3_root, evidence_module, artifacts
):
    path = v3_root / "launch-theme" / "tablet" / "manifest.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    payload["palette"]["theme_background"] = "#000000"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="rendered custom-light proof"):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


def test_v148_closed_layout_rejects_unreferenced_ui_artifact(v3_root, evidence_module, artifacts):
    extra = v3_root / "ui-coverage" / "tablet" / "unreferenced.png"
    extra.write_bytes(b"unreferenced")
    with pytest.raises(evidence_module.EvidenceError, match="layout mismatch"):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


@pytest.mark.parametrize(
    ("identity_prefix", "contract_field", "error_match"),
    (
        ("section:", "app_sections", "AppSection destinations"),
        ("settings:", "settings_pages", "nested Settings destinations"),
        ("device:", "device_pages", "non-Overview DevicePage destinations"),
    ),
)
def test_v148_rejects_complete_inventory_source_page_omission(
    v3_root,
    evidence_module,
    artifacts,
    identity_prefix,
    contract_field,
    error_match,
):
    directory = v3_root / "ui-coverage" / "phone-compact"
    inventory_path = directory / "complete-inventory.txt"
    inventory, captures = evidence_module._parse_ui_inventory(
        inventory_path,
        "synthetic-source-page-omission",
    )
    source_contract = evidence_module.load_ui_evidence_source_contract(REPO_ROOT)
    omitted_identity = f"{identity_prefix}{getattr(source_contract, contract_field)[-1]}"
    omitted = next(capture for capture in captures if capture["identity"] == omitted_identity)
    (directory / omitted["screenshot"]).unlink()
    (directory / omitted["proof"]).unlink()
    remaining = [capture for capture in captures if capture["identity"] != omitted["identity"]]
    performance = json.loads(
        (v3_root / "performance" / "phone-compact.json").read_text(encoding="utf-8")
    )
    _write_inventory(
        directory,
        inventory_path.name,
        inventory["coverage_kind"],
        inventory["profile"],
        performance,
        remaining,
    )

    with pytest.raises(evidence_module.EvidenceError, match=error_match):
        evidence_module.validate_evidence_directory(
            v3_root,
            artifacts,
            legacy.SOURCE_DIGEST,
            V3_TAG,
        )


@pytest.mark.parametrize(
    ("identity_prefix", "coverage_kind", "page_id_prefix", "error_match"),
    (
        ("section:", "app-section", "", "AppSection destinations"),
        ("settings:", "settings-subpage", "Settings.", "nested Settings destinations"),
        ("device:", "device-subpage", "", "non-Overview DevicePage destinations"),
    ),
)
def test_v148_rejects_complete_inventory_source_page_invention(
    v3_root,
    evidence_module,
    artifacts,
    identity_prefix,
    coverage_kind,
    page_id_prefix,
    error_match,
):
    directory = v3_root / "ui-coverage" / "phone-compact"
    inventory_path = directory / "complete-inventory.txt"
    inventory, captures = evidence_module._parse_ui_inventory(
        inventory_path,
        "synthetic-source-page-invention",
    )
    performance = json.loads(
        (v3_root / "performance" / "phone-compact.json").read_text(encoding="utf-8")
    )
    invented_name = "InventedReleasePage"
    captures.append(
        _write_capture(
            directory,
            inventory["profile"],
            performance,
            f"{identity_prefix}{invented_name}",
            coverage_kind,
            page_id=f"{page_id_prefix}{invented_name}",
        )
    )
    _write_inventory(
        directory,
        inventory_path.name,
        inventory["coverage_kind"],
        inventory["profile"],
        performance,
        captures,
    )

    with pytest.raises(evidence_module.EvidenceError, match=error_match):
        evidence_module.validate_evidence_directory(
            v3_root,
            artifacts,
            legacy.SOURCE_DIGEST,
            V3_TAG,
        )


@pytest.mark.parametrize("operation", ("omit", "invent"))
def test_v148_rejects_recommended_model_omission_or_invention(
    v3_root,
    evidence_module,
    artifacts,
    operation,
):
    directory = v3_root / "ui-coverage" / "phone-compact"
    inventory_path = directory / "localized-inventory.txt"
    inventory, captures = evidence_module._parse_ui_inventory(
        inventory_path,
        "synthetic-recommended-model-mismatch",
    )
    performance = json.loads(
        (v3_root / "performance" / "phone-compact.json").read_text(encoding="utf-8")
    )
    if operation == "omit":
        source_contract = evidence_module.load_ui_evidence_source_contract(REPO_ROOT)
        identity = f"localized-model:en:{source_contract.recommended_model_ids[-1]}"
        removed = next(capture for capture in captures if capture["identity"] == identity)
        (directory / removed["screenshot"]).unlink()
        (directory / removed["proof"]).unlink()
        captures = [capture for capture in captures if capture["identity"] != identity]
    else:
        invented_id = "invented-release-model"
        captures.append(
            _write_capture(
                directory,
                inventory["profile"],
                performance,
                f"localized-model:en:{invented_id}",
                "six-language-recommended-model",
                language="en",
                page_id=f"Settings.Models.{invented_id}",
            )
        )
    _write_inventory(
        directory,
        inventory_path.name,
        inventory["coverage_kind"],
        inventory["profile"],
        performance,
        captures,
    )

    with pytest.raises(evidence_module.EvidenceError, match="source-derived recommended models"):
        evidence_module.validate_evidence_directory(
            v3_root,
            artifacts,
            legacy.SOURCE_DIGEST,
            V3_TAG,
        )


def test_v148_rejects_declared_sentinel_absent_from_compose_proof_body(
    v3_root,
    evidence_module,
    artifacts,
):
    directory = v3_root / "ui-coverage" / "phone-compact"
    _, captures = evidence_module._parse_ui_inventory(
        directory / "complete-inventory.txt",
        "synthetic-sentinel-mismatch",
    )
    capture = next(item for item in captures if item["identity"].startswith("section:"))
    proof_path = directory / capture["proof"]
    header, separator, body = proof_path.read_text(encoding="utf-8").partition("\n\n")
    header_lines = header.splitlines()
    sentinel_index = next(index for index, line in enumerate(header_lines) if line.startswith("sentinel="))
    header_lines[sentinel_index] = "sentinel=Declared sentinel absent from proof body"
    proof_path.write_text("\n".join(header_lines) + separator + body, encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="sentinels absent from its proof body"):
        evidence_module.validate_evidence_directory(
            v3_root,
            artifacts,
            legacy.SOURCE_DIGEST,
            V3_TAG,
        )


def test_v148_rejects_declared_sentinel_absent_from_xml_proof_body(
    v3_root,
    evidence_module,
    artifacts,
):
    directory = v3_root / "ui-coverage" / "phone-compact"
    _, captures = evidence_module._parse_ui_inventory(
        directory / "complete-inventory.txt",
        "synthetic-xml-sentinel-mismatch",
    )
    capture = next(item for item in captures if item["identity"].startswith("framework:en:"))
    proof_path = directory / capture["proof"]
    text = proof_path.read_text(encoding="utf-8")
    sentinel_start = text.index('<sentinel value="') + len('<sentinel value="')
    sentinel_end = text.index('"', sentinel_start)
    text = (
        text[:sentinel_start]
        + "Declared XML sentinel absent from proof body"
        + text[sentinel_end:]
    )
    proof_path.write_text(text, encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="sentinels absent from its proof body"):
        evidence_module.validate_evidence_directory(
            v3_root,
            artifacts,
            legacy.SOURCE_DIGEST,
            V3_TAG,
        )


@pytest.mark.parametrize(
    ("inventory_name", "identity_prefix"),
    (
        ("complete-inventory.txt", "section:"),
        ("complete-inventory.txt", "settings:"),
        ("complete-inventory.txt", "device:"),
        ("localized-inventory.txt", "localized-model:en:"),
    ),
)
def test_v148_rejects_source_page_identity_with_wrong_proof_page_id(
    v3_root,
    evidence_module,
    artifacts,
    inventory_name,
    identity_prefix,
):
    directory = v3_root / "ui-coverage" / "phone-compact"
    _, captures = evidence_module._parse_ui_inventory(
        directory / inventory_name,
        "synthetic-page-id-mismatch",
    )
    capture = next(item for item in captures if item["identity"].startswith(identity_prefix))
    proof_path = directory / capture["proof"]
    lines = proof_path.read_text(encoding="utf-8").splitlines()
    page_index = next(index for index, line in enumerate(lines) if line.startswith("page_id="))
    lines[page_index] = "page_id=Invented.Relationship"
    proof_path.write_text("\n".join(lines), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="proof page ID"):
        evidence_module.validate_evidence_directory(
            v3_root,
            artifacts,
            legacy.SOURCE_DIGEST,
            V3_TAG,
        )


@pytest.mark.parametrize(
    "relative_path",
    (
        "issues/issue-8-tool-and-preflight.json",
        "issues/issue-16-debian-sandbox.json",
    ),
)
def test_v148_rejects_missing_fixed_issue_evidence(
    v3_root, evidence_module, artifacts, relative_path
):
    (v3_root / relative_path).unlink()
    with pytest.raises(evidence_module.EvidenceError, match="missing required fixed paths"):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


def _rewrite_json_field(path: Path, field_path: tuple[str | int, ...], value) -> None:
    payload = json.loads(path.read_text(encoding="utf-8"))
    target = payload
    for field in field_path[:-1]:
        target = target[field]
    target[field_path[-1]] = value
    path.write_text(json.dumps(payload), encoding="utf-8")


@pytest.mark.parametrize(
    ("relative_path", "field_path", "invalid"),
    (
        (
            "issues/issue-8-tool-and-preflight.json",
            ("release_identity", "candidate_apk_sha256"),
            "0" * 64,
        ),
        (
            "issues/issue-8-tool-and-preflight.json",
            ("release_identity", "evidence_run_id"),
            "release-v0.13.148-wrong-run",
        ),
        (
            "issues/issue-8-tool-and-preflight.json",
            ("release_identity", "device_boot_id"),
            "87654321-4321-4cba-8fed-ba0987654321",
        ),
        (
            "issues/issue-8-tool-and-preflight.json",
            ("release_identity", "profile"),
            "tablet",
        ),
        (
            "issues/issue-16-debian-sandbox.json",
            ("release_identity", "release_source_digest"),
            "0" * 64,
        ),
        (
            "issues/issue-16-debian-sandbox.json",
            ("release_identity", "instrumentation_apk_sha256"),
            "0" * 64,
        ),
        (
            "issues/issue-16-debian-sandbox.json",
            ("release_identity", "device_serial"),
            "emulator-9999",
        ),
        (
            "issues/issue-16-debian-sandbox.json",
            ("release_identity", "build_fingerprint"),
            "unbound/fingerprint",
        ),
    ),
)
def test_v148_issue_records_reject_identity_mismatches(
    v3_root, evidence_module, artifacts, relative_path, field_path, invalid
):
    _rewrite_json_field(v3_root / relative_path, field_path, invalid)
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


@pytest.mark.parametrize(
    ("field_path", "invalid"),
    (
        (("evidence_source",), "operator-normalized"),
        (("direct_tool_routes", 0, "prompt"), "What time is it?"),
        (("direct_tool_routes", 0, "visible_tool_event"), False),
        (("direct_tool_routes", 1, "visible_result_event"), False),
        (("direct_tool_routes", 0, "model_request_count"), 1),
        (("direct_tool_routes", 1, "provider_network_request_count"), 1),
        (("catalog_policy", "release_certified"), True),
        (("catalog_policy", "quick_start_eligible"), True),
        (("catalog_policy", "present_in_mobile_quick_catalog"), True),
        (("catalog_policy", "automatically_selected"), True),
        (("catalog_policy", "artifact_file_present"), True),
        (("twelve_b_preflight", "revision"), "0" * 40),
        (("twelve_b_preflight", "file_name"), "gemma-4-12B-it-gpu.litertlm"),
        (("twelve_b_preflight", "catalog_declared_bytes"), 6_547_589_311),
        (("twelve_b_preflight", "expected_sha256"), "0" * 64),
        (("twelve_b_preflight", "artifact_file_present"), True),
        (("twelve_b_preflight", "preflight_allowed"), True),
        (("twelve_b_preflight", "blocked_before_native_engine"), False),
        (("twelve_b_preflight", "native_engine_start_attempted"), True),
        (("twelve_b_preflight", "native_engine_started"), True),
        (("twelve_b_preflight", "memory_profile", "total_bytes"), 16_000_000_000),
    ),
)
def test_v148_issue8_rejects_mismatch_or_false_proof_fields(
    v3_root, evidence_module, artifacts, field_path, invalid
):
    path = v3_root / "issues" / "issue-8-tool-and-preflight.json"
    _rewrite_json_field(path, field_path, invalid)
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)


@pytest.mark.parametrize(
    ("field_path", "invalid"),
    (
        (("packaged_runtime", "packaged_asset_skipped"), True),
        (("packaged_runtime", "packaged_assets_present"), False),
        (("packaged_runtime", "trusted_native_routes", "proot", "trusted"), False),
        (("packaged_runtime", "trusted_native_routes", "qemu_user", "executable"), False),
        (("packaged_runtime", "trusted_native_routes", "coreutils", "trusted"), False),
        (("sandbox", "fresh_requested"), False),
        (("sandbox", "sandbox_existed_before"), True),
        (("sandbox", "deployment_completed"), False),
        (("sandbox", "update_exit_code"), 1),
        (("sandbox", "guest_ca_bundle", "certificate_count"), 0),
        (("guest_routing", "guest_only_path"), False),
        (("commands", "id", "exit_code"), 1),
        (("commands", "https", "stdout"), ""),
        (("cleanup", "agent_shell_enabled"), True),
        (("cleanup", "sandbox_present"), True),
        (("cleanup", "sandbox_removed"), False),
    ),
)
def test_v148_issue16_rejects_mismatch_or_false_proof_fields(
    v3_root, evidence_module, artifacts, field_path, invalid
):
    path = v3_root / "issues" / "issue-16-debian-sandbox.json"
    _rewrite_json_field(path, field_path, invalid)
    with pytest.raises(evidence_module.EvidenceError):
        evidence_module.validate_evidence_directory(v3_root, artifacts, legacy.SOURCE_DIGEST, V3_TAG)
