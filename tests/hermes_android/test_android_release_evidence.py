from __future__ import annotations

import importlib.util
import hashlib
import json
import struct
import subprocess
import sys
import zlib
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIGEST = "d" * 64
CANDIDATE_APK_SHA256 = "c" * 64
INSTRUMENTATION_APK_SHA256 = "e" * 64
EVIDENCE_RUN_ID = "release-v0.1.2-synthetic-run"
DEVICE_BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
TAG = "v0.1.2"
VERSION_NAME = "0.1.2"
VERSION_CODE = 10_290
QEMU_CIM_SCRIPT = (
    "$utf8 = [System.Text.UTF8Encoding]::new($false); "
    "[Console]::OutputEncoding = $utf8; $OutputEncoding = $utf8; "
    "@(Get-CimInstance Win32_Process | "
    "Where-Object { $_.Name -like 'qemu-system-*' -and $_.CommandLine } | "
    "Select-Object @{Name='pid';Expression={[int]$_.ProcessId}},"
    "@{Name='name';Expression={[string]$_.Name}},"
    "@{Name='command_line';Expression={[string]$_.CommandLine}}) | "
    "ConvertTo-Json -Compress"
)


def _load_module():
    script = REPO_ROOT / "scripts/android_release_evidence.py"
    spec = importlib.util.spec_from_file_location("android_release_evidence", script)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def evidence_module():
    return _load_module()


@pytest.fixture
def artifacts(evidence_module):
    return (
        evidence_module.ArtifactSpec(
            model_id="small-litert",
            repository="publisher/mobile-model",
            revision="1" * 40,
            file_name="small.litertlm",
            runtime="litert-lm",
            expected_bytes=123_456,
            sha256="a" * 64,
        ),
        evidence_module.ArtifactSpec(
            model_id="small-gguf",
            repository="publisher/gguf-model",
            revision="2" * 40,
            file_name="small.gguf",
            runtime="llama.cpp",
            expected_bytes=654_321,
            sha256="b" * 64,
        ),
    )


@pytest.fixture
def evidence_root(tmp_path):
    root = tmp_path / "release-evidence"
    root.mkdir()
    return root


def _chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def _png(width: int, height: int, marker: str) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    marker_seed = hashlib.sha256(marker.encode("utf-8")).digest()
    palette = [
        bytes(
            (
                (marker_seed[0] + index * 17) & 0xFF,
                (marker_seed[1] + index * 29) & 0xFF,
                (marker_seed[2] + index * 43) & 0xFF,
            )
        )
        for index in range(16)
    ]
    compressor = zlib.compressobj(level=9)
    compressed = bytearray()
    for y in range(height):
        row = b"".join(palette[(x // 8 + y // 8) % len(palette)] for x in range(width))
        compressed.extend(compressor.compress(b"\x00" + row))
    compressed.extend(compressor.flush())
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", bytes(compressed))
        + _chunk(b"IEND", b"")
    )


def _performance(profile: str) -> dict:
    compact = profile == "phone-compact"
    width_dp, height_dp = (360, 800) if compact else (800, 1280)
    settings_bounds = [0, 100, 360, 760] if compact else [120, 100, 800, 1240]
    swipe_coordinates = [180, 628, 180, 232] if compact else [460, 1012, 460, 328]
    emulator_command = (
        "D:/Android/Sdk/emulator/emulator.exe -avd Hermes_API_35 "
        "-gpu host -accel on -port 5566 -no-snapshot-load"
    )
    payload = {
        "schema": "hermes-android-performance-evidence-v1",
        "profile": profile,
        "release_source_digest": SOURCE_DIGEST,
        "candidate_apk_sha256": CANDIDATE_APK_SHA256,
        "instrumentation_apk_sha256": INSTRUMENTATION_APK_SHA256,
        "evidence_run_id": EVIDENCE_RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "version_name": VERSION_NAME,
        "version_code": VERSION_CODE,
        "build_variant": "debug",
        "litertlm_coordinate": "com.google.ai.edge.litertlm:litertlm-android:0.16.0",
        "device": {
            "serial": "emulator-5566",
            "avd_name": "Hermes_API_35",
            "boot_id": DEVICE_BOOT_ID,
            "model": "sdk_gphone64_x86_64",
            "build_fingerprint": "google/sdk_gphone64_x86_64/emu64xa:15/test/release-keys",
            "android_sdk": 35,
            "supported_abis": ["x86_64"],
            "hardware_acceleration": True,
            "acceleration_check": "WHPX is installed and usable.",
            "acceleration_check_exit_code": 0,
            "gpu_renderer": "Android Emulator OpenGL ES Translator (NVIDIA RTX)",
            "emulator_pid": 4242,
            "emulator_process_name": "qemu-system-x86_64.exe",
            "emulator_command": emulator_command,
            "emulator_command_sha256": hashlib.sha256(emulator_command.encode("utf-8")).hexdigest(),
        },
        "screen": {
            "width_px": width_dp,
            "height_px": height_dp,
            "width_dp": width_dp,
            "height_dp": height_dp,
            "density_dpi": 160,
            "font_scale": 1.0,
        },
        "launch": {
            "cold_total_ms": 1200,
            "cold_wait_ms": 1100,
            "warm_total_ms": 400,
            "warm_process_pid": 8123,
        },
        "frames": {
            "total_rendered": 120,
            "janky": 6,
            "janky_percent": 5.0,
            "p50_ms": 8.0,
            "p90_ms": 14.0,
            "p95_ms": 18.0,
            "p99_ms": 28.0,
        },
        "memory": {"total_pss_kb": 250_000, "total_rss_kb": 320_000},
        "collector": {
            "source_digest_algorithm": "sha256-git-tree-contents-v1",
            "source_file_count": 123,
            "git_object_format": "sha1",
            "candidate_apk_device_path": "/data/app/hermes/base.apk",
            "instrumentation_apk_device_path": "/data/app/hermes-test/base.apk",
            "ui_navigation_route": (
                "phone-drawer-settings" if compact else "tablet-rail-settings"
            ),
            "settings_scroll_bounds_px": settings_bounds,
            "gfx_swipe_coordinates": swipe_coordinates,
            "gfxinfo_exercise_rounds": 1,
        },
    }
    raw_bytes = _raw_performance_bytes(profile, payload)
    payload["raw_evidence"] = {
        "path": f"performance/{profile}.raw.json",
        "sha256": hashlib.sha256(raw_bytes).hexdigest(),
    }
    return payload


def _command(record_id: str, argv: list[str], stdout: str = "", stderr: str = "") -> dict:
    return {
        "id": record_id,
        "argv": argv,
        "exit_code": 0,
        "stdout": stdout,
        "stderr": stderr,
    }


def _ui_xml(
    resource_id: str,
    bounds: str,
    *,
    clickable: bool = False,
    scrollable: bool = False,
    enabled: bool = True,
    package: str = "com.mobilefork.hermesagent",
) -> str:
    return (
        f'<hierarchy rotation="0"><node package="{package}" '
        f'resource-id="{resource_id}" bounds="{bounds}" '
        f'enabled="{str(enabled).lower()}" '
        f'clickable="{str(clickable).lower()}" scrollable="{str(scrollable).lower()}"/>'
        "</hierarchy>"
    )


def _raw_performance(profile: str, performance: dict) -> dict:
    serial = performance["device"]["serial"]
    adb = "adb"
    targeted = [adb, "-s", serial]
    records: list[dict] = []

    def identity(phase: str) -> None:
        records.extend(
            (
                _command(
                    f"{phase}.adb.devices",
                    [adb, "devices", "-l"],
                    f"List of devices attached\n{serial} device product:sdk_phone\n",
                ),
                _command(f"{phase}.adb.get-serialno", [*targeted, "get-serialno"], f"{serial}\n"),
                _command(f"{phase}.adb.get-state", [*targeted, "get-state"], "device\n"),
                _command(
                    f"{phase}.device.getprop.avd_name",
                    [*targeted, "shell", "getprop", "ro.boot.qemu.avd_name"],
                    "Hermes_API_35\n",
                ),
                _command(
                    f"{phase}.device.getprop.build_fingerprint",
                    [*targeted, "shell", "getprop", "ro.build.fingerprint"],
                    "google/sdk_gphone64_x86_64/emu64xa:15/test/release-keys\n",
                ),
                _command(
                    f"{phase}.device.getprop.model",
                    [*targeted, "shell", "getprop", "ro.product.model"],
                    "sdk_gphone64_x86_64\n",
                ),
                _command(
                    f"{phase}.device.getprop.android_sdk",
                    [*targeted, "shell", "getprop", "ro.build.version.sdk"],
                    "35\n",
                ),
                _command(
                    f"{phase}.device.getprop.supported_abis",
                    [*targeted, "shell", "getprop", "ro.product.cpu.abilist"],
                    "x86_64\n",
                ),
                _command(
                    f"{phase}.device.boot_id",
                    [*targeted, "shell", "cat", "/proc/sys/kernel/random/boot_id"],
                    f"{DEVICE_BOOT_ID}\n",
                ),
                _command(
                    f"{phase}.device.settings.font_scale",
                    [*targeted, "shell", "settings", "get", "system", "font_scale"],
                    "1.0\n",
                ),
                _command(
                    f"{phase}.package.candidate.path",
                    [*targeted, "shell", "pm", "path", "com.mobilefork.hermesagent"],
                    "package:/data/app/hermes/base.apk\n",
                ),
                _command(
                    f"{phase}.package.candidate.sha256",
                    [*targeted, "shell", "sha256sum", "/data/app/hermes/base.apk"],
                    f"{CANDIDATE_APK_SHA256}  /data/app/hermes/base.apk\n",
                ),
                _command(
                    f"{phase}.package.instrumentation.path",
                    [*targeted, "shell", "pm", "path", "com.mobilefork.hermesagent.test"],
                    "package:/data/app/hermes-test/base.apk\n",
                ),
                _command(
                    f"{phase}.package.instrumentation.sha256",
                    [*targeted, "shell", "sha256sum", "/data/app/hermes-test/base.apk"],
                    f"{INSTRUMENTATION_APK_SHA256}  /data/app/hermes-test/base.apk\n",
                ),
                _command(
                    f"{phase}.package.version",
                    [*targeted, "shell", "dumpsys", "package", "com.mobilefork.hermesagent"],
                    f"Packages:\n  versionCode={VERSION_CODE} minSdk=24\n  versionName={VERSION_NAME}\n",
                ),
                _command(
                    f"{phase}.host.qemu_processes",
                    [
                        "powershell.exe",
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        QEMU_CIM_SCRIPT,
                    ],
                    json.dumps(
                        [
                            {
                                "pid": 4242,
                                "name": "qemu-system-x86_64.exe",
                                "command_line": performance["device"]["emulator_command"],
                            }
                        ],
                        separators=(",", ":"),
                    ),
                ),
            )
        )

    identity("initial")
    screen = performance["screen"]
    def ui_dump(phase: str) -> str:
        return f"/data/local/tmp/hermes-performance-ui-{phase}.xml"

    if profile == "phone-compact":
        navigation_records = [
            _command(
                "measure.ui.initial.remove",
                [*targeted, "shell", "rm", "-f", ui_dump("initial")],
            ),
            _command(
                "measure.ui.initial.dump",
                [*targeted, "shell", "uiautomator", "dump", ui_dump("initial")],
                f"UI hierchary dumped to: {ui_dump('initial')}\n",
            ),
            _command(
                "measure.ui.initial.cat",
                [*targeted, "shell", "cat", ui_dump("initial")],
                _ui_xml("HermesChatDrawerButton", "[10,10][110,110]", clickable=True),
            ),
            _command(
                "measure.ui.phone.drawer.tap",
                [*targeted, "shell", "input", "tap", "60", "60"],
            ),
            _command(
                "measure.ui.drawer.remove",
                [*targeted, "shell", "rm", "-f", ui_dump("drawer")],
            ),
            _command(
                "measure.ui.drawer.dump",
                [*targeted, "shell", "uiautomator", "dump", ui_dump("drawer")],
                f"UI hierchary dumped to: {ui_dump('drawer')}\n",
            ),
            _command(
                "measure.ui.drawer.cat",
                [*targeted, "shell", "cat", ui_dump("drawer")],
                _ui_xml("HermesNavSettings", "[0,200][360,300]", clickable=True),
            ),
            _command(
                "measure.ui.phone.settings.tap",
                [*targeted, "shell", "input", "tap", "180", "250"],
            ),
            _command(
                "measure.ui.settings.remove",
                [*targeted, "shell", "rm", "-f", ui_dump("settings")],
            ),
            _command(
                "measure.ui.settings.dump",
                [*targeted, "shell", "uiautomator", "dump", ui_dump("settings")],
                f"UI hierchary dumped to: {ui_dump('settings')}\n",
            ),
            _command(
                "measure.ui.settings.cat",
                [*targeted, "shell", "cat", ui_dump("settings")],
                _ui_xml(
                    "HermesSettingsContentList", "[0,100][360,760]", scrollable=True
                ),
            ),
        ]
    else:
        navigation_records = [
            _command(
                "measure.ui.initial.remove",
                [*targeted, "shell", "rm", "-f", ui_dump("initial")],
            ),
            _command(
                "measure.ui.initial.dump",
                [*targeted, "shell", "uiautomator", "dump", ui_dump("initial")],
                f"UI hierchary dumped to: {ui_dump('initial')}\n",
            ),
            _command(
                "measure.ui.initial.cat",
                [*targeted, "shell", "cat", ui_dump("initial")],
                _ui_xml("HermesRailSettings", "[10,100][110,220]", clickable=True),
            ),
            _command(
                "measure.ui.tablet.settings.tap",
                [*targeted, "shell", "input", "tap", "60", "160"],
            ),
            _command(
                "measure.ui.settings.remove",
                [*targeted, "shell", "rm", "-f", ui_dump("settings")],
            ),
            _command(
                "measure.ui.settings.dump",
                [*targeted, "shell", "uiautomator", "dump", ui_dump("settings")],
                f"UI hierchary dumped to: {ui_dump('settings')}\n",
            ),
            _command(
                "measure.ui.settings.cat",
                [*targeted, "shell", "cat", ui_dump("settings")],
                _ui_xml(
                    "HermesSettingsContentList", "[120,100][800,1240]", scrollable=True
                ),
            ),
        ]
    records.extend(
        (
            _command(
                "measure.emulator.accel-check",
                ["emulator", "-accel-check"],
                "WHPX is installed and usable.\n",
            ),
            _command(
                "measure.screen.wm_size",
                [*targeted, "shell", "wm", "size"],
                f"Physical size: {screen['width_px']}x{screen['height_px']}\n",
            ),
            _command(
                "measure.screen.wm_density",
                [*targeted, "shell", "wm", "density"],
                f"Physical density: {screen['density_dpi']}\n",
            ),
            _command(
                "measure.launch.force_stop",
                [*targeted, "shell", "am", "force-stop", "com.mobilefork.hermesagent"],
            ),
            _command(
                "measure.launch.cold",
                [
                    *targeted,
                    "shell",
                    "am",
                    "start",
                    "-W",
                    "-S",
                    "-n",
                    "com.mobilefork.hermesagent/.MainActivity",
                ],
                "Status: ok\n"
                "LaunchState: COLD\n"
                "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                "TotalTime: 1200\nWaitTime: 1100\n",
            ),
            _command(
                "measure.launch.pid_before_back",
                [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"],
                "8123\n",
            ),
            _command(
                "measure.launch.back",
                [*targeted, "shell", "input", "keyevent", "KEYCODE_BACK"],
            ),
            _command(
                "measure.launch.pid_after_back",
                [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"],
                "8123\n",
            ),
            _command(
                "measure.launch.warm",
                [
                    *targeted,
                    "shell",
                    "am",
                    "start",
                    "-W",
                    "-n",
                    "com.mobilefork.hermesagent/.MainActivity",
                ],
                "Status: ok\n"
                "LaunchState: WARM\n"
                "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                "TotalTime: 400\nWaitTime: 410\n",
            ),
            *navigation_records,
            _command(
                "measure.screen.am_config",
                [*targeted, "shell", "am", "get-config"],
                f"config: en-rUS-w{screen['width_dp']}dp-h{screen['height_dp']}dp-normal\n",
            ),
            _command(
                "measure.gpu.surfaceflinger",
                [*targeted, "shell", "dumpsys", "SurfaceFlinger"],
                "GLES: Google, Android Emulator OpenGL ES Translator (NVIDIA RTX), OpenGL ES 3.2\n",
            ),
            _command(
                "measure.activity.before_gfx",
                [*targeted, "shell", "dumpsys", "activity", "activities"],
                "topResumedActivity=ActivityRecord{abc u0 "
                "com.mobilefork.hermesagent/.MainActivity t1}\n",
            ),
            _command(
                "measure.gfx.reset",
                [*targeted, "shell", "dumpsys", "gfxinfo", "com.mobilefork.hermesagent", "reset"],
            ),
        )
    )
    x, bottom, _, top = performance["collector"]["gfx_swipe_coordinates"]
    records.extend(
        (
            _command(
                "measure.gfx.swipe.0001",
                [
                    *targeted,
                    "shell",
                    "input",
                    "swipe",
                    str(x),
                    str(bottom),
                    str(x),
                    str(top),
                    "180",
                ],
            ),
            _command(
                "measure.gfx.framestats.01",
                [
                    *targeted,
                    "shell",
                    "dumpsys",
                    "gfxinfo",
                    "com.mobilefork.hermesagent",
                    "framestats",
                ],
                "** Graphics info for pid 8123 [com.mobilefork.hermesagent] **\n"
                "Total frames rendered: 120\n"
                "Janky frames: 6 (5.00%)\n"
                "50th percentile: 8ms\n"
                "90th percentile: 14ms\n"
                "95th percentile: 18ms\n"
                "99th percentile: 28ms\n",
            ),
            _command(
                "measure.activity.after_gfx",
                [*targeted, "shell", "dumpsys", "activity", "activities"],
                "topResumedActivity=ActivityRecord{abc u0 "
                "com.mobilefork.hermesagent/.MainActivity t1}\n",
            ),
            _command(
                "measure.memory.meminfo",
                [*targeted, "shell", "dumpsys", "meminfo", "com.mobilefork.hermesagent"],
                "** MEMINFO in pid 8123 [com.mobilefork.hermesagent] **\n"
                " TOTAL PSS: 250000 TOTAL RSS: 320000 TOTAL SWAP PSS: 0\n",
            ),
            _command(
                "measure.process.pid_after_measurement",
                [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"],
                "8123\n",
            ),
        )
    )
    identity("final")
    return {
        "schema": "hermes-android-performance-raw-v1",
        "profile": profile,
        "release_source_digest": SOURCE_DIGEST,
        "candidate_apk_sha256": CANDIDATE_APK_SHA256,
        "instrumentation_apk_sha256": INSTRUMENTATION_APK_SHA256,
        "evidence_run_id": EVIDENCE_RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "version_name": VERSION_NAME,
        "version_code": VERSION_CODE,
        "build_variant": "debug",
        "litertlm_coordinate": "com.google.ai.edge.litertlm:litertlm-android:0.16.0",
        "records": records,
    }


def _raw_performance_bytes(profile: str, performance: dict) -> bytes:
    return (
        json.dumps(
            _raw_performance(profile, performance),
            indent=2,
            sort_keys=True,
            ensure_ascii=False,
        )
        + "\n"
    ).encode("utf-8")


def _model_record(artifact) -> dict:
    method = {
        "litert-lm": (
            "LiteRtLmModelMatrixInstrumentedTest#"
            "provisionedLiteRtLmModelLoadsAndAnswersLocally"
        ),
        "llama.cpp": (
            "LlamaCppModelMatrixInstrumentedTest#"
            "provisionedContentAddressedGgufStartsAndAnswers"
        ),
    }[artifact.runtime]
    return {
        "schema": "hermes-model-evidence-v1",
        "release_source_digest": SOURCE_DIGEST,
        "candidate_apk_sha256": CANDIDATE_APK_SHA256,
        "instrumentation_apk_sha256": INSTRUMENTATION_APK_SHA256,
        "evidence_run_id": EVIDENCE_RUN_ID,
        "package_id": "com.mobilefork.hermesagent",
        "version_name": VERSION_NAME,
        "version_code": VERSION_CODE,
        "build_variant": "debug",
        "litertlm_coordinate": "com.google.ai.edge.litertlm:litertlm-android:0.16.0",
        "result": "passed",
        "evidence_complete": True,
        "content_addressed": True,
        "backend": artifact.backend,
        "instrumentation_method": method,
        "model_id": artifact.model_id,
        "publisher_repository": artifact.repository,
        "publisher_revision": artifact.revision,
        "file_name": artifact.file_name,
        "device_path": f"/data/local/tmp/{artifact.file_name}",
        "publisher_expected_bytes": artifact.expected_bytes,
        "device_visible_bytes": artifact.expected_bytes,
        "expected_sha256": artifact.sha256,
        "device_sha256": artifact.sha256,
        "runtime_started": True,
        "health_ok": True,
        "completion_nonempty": True,
        "elapsed_ms": 3000,
        "accelerator": "gpu" if artifact.runtime == "litert-lm" else "cpu",
        "status_message": "completion verified",
        "device_model": "sdk_gphone64_x86_64",
        "device_serial": "emulator-5566",
        "avd_name": "Hermes_API_35",
        "device_boot_id": DEVICE_BOOT_ID,
        "build_fingerprint": "google/sdk_gphone64_x86_64/emu64xa:15/test/release-keys",
        "android_sdk": 35,
        "supported_abis": "x86_64",
        "recorded_at_epoch_ms": 1_780_000_000_000,
        "details": {"completion_characters": 2},
        "evidence_file": "/data/user/0/app/files/evidence.json",
    }


def _write_fixture(root: Path, evidence_module, artifacts) -> None:
    for profile in evidence_module.PROFILES:
        performance = _performance(profile)
        performance_path = root / "performance" / f"{profile}.json"
        performance_path.parent.mkdir(parents=True, exist_ok=True)
        performance_path.write_text(json.dumps(performance), encoding="utf-8")
        (performance_path.parent / f"{profile}.raw.json").write_bytes(
            _raw_performance_bytes(profile, performance)
        )
        screen = performance["screen"]
        for language in evidence_module.LANGUAGES:
            language_dir = root / "ui" / profile / language
            language_dir.mkdir(parents=True, exist_ok=True)
            (language_dir / "screen.png").write_bytes(
                _png(screen["width_px"], screen["height_px"], f"{profile}-{language}")
            )
            screenshot_sha256 = hashlib.sha256(
                (language_dir / "screen.png").read_bytes()
            ).hexdigest()
            (language_dir / "semantics.txt").write_text(
                "\n".join(
                    (
                        f"language={language}",
                        f"screen_width_dp={screen['width_dp']}",
                        f"screen_height_dp={screen['height_dp']}",
                        "font_scale=1.0",
                        f"release_source_digest={SOURCE_DIGEST}",
                        f"candidate_apk_sha256={CANDIDATE_APK_SHA256}",
                        f"instrumentation_apk_sha256={INSTRUMENTATION_APK_SHA256}",
                        f"evidence_run_id={EVIDENCE_RUN_ID}",
                        "package_id=com.mobilefork.hermesagent",
                        f"version_name={VERSION_NAME}",
                        f"version_code={VERSION_CODE}",
                        "build_variant=debug",
                        "litertlm_coordinate=com.google.ai.edge.litertlm:litertlm-android:0.16.0",
                        "device_serial=emulator-5566",
                        "avd_name=Hermes_API_35",
                        f"device_boot_id={DEVICE_BOOT_ID}",
                        "build_fingerprint=google/sdk_gphone64_x86_64/emu64xa:15/test/release-keys",
                        f"screenshot_sha256={screenshot_sha256}",
                        "",
                        (
                            "Tag: 'HermesPersistentNavigation'\n"
                            if profile == "tablet"
                            else "Tag: 'HermesShellDrawerButton'\n"
                        )
                        + "Tag: 'HermesDevicePageNavigation'\n"
                        + f"Text = '[{evidence_module.LOCALIZED_DEVICE_OVERVIEW[language]}]'\n"
                        + f"Semantics tree localized for {profile} in language {language}",
                    )
                ),
                encoding="utf-8",
            )
    models = root / "models"
    models.mkdir(parents=True, exist_ok=True)
    for artifact in artifacts:
        (models / f"{artifact.model_id}.json").write_text(
            json.dumps(_model_record(artifact)),
            encoding="utf-8",
        )


def _rewrite_raw_fixture(root: Path, profile: str, mutator) -> None:
    raw_path = root / "performance" / f"{profile}.raw.json"
    raw_payload = json.loads(raw_path.read_text(encoding="utf-8"))
    mutator(raw_payload)
    raw_bytes = (
        json.dumps(raw_payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    ).encode("utf-8")
    raw_path.write_bytes(raw_bytes)
    normalized_path = root / "performance" / f"{profile}.json"
    normalized = json.loads(normalized_path.read_text(encoding="utf-8"))
    normalized["raw_evidence"]["sha256"] = hashlib.sha256(raw_bytes).hexdigest()
    normalized_path.write_text(json.dumps(normalized), encoding="utf-8")


def _insert_warm_retry(
    raw: dict,
    *,
    initial_wait_ms: int = 7,
    retry_state: str = "WARM",
    retry_total_ms: int = 401,
    retry_wait_ms: int = 411,
) -> None:
    records = raw["records"]
    warm_index = next(
        index for index, item in enumerate(records) if item["id"] == "measure.launch.warm"
    )
    records[warm_index]["stdout"] = (
        "Status: ok\n"
        "LaunchState: UNKNOWN (0)\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\n"
        "TotalTime: 0\n"
        f"WaitTime: {initial_wait_ms}\n"
    )
    targeted = ["adb", "-s", "emulator-5566"]
    records[warm_index + 1 : warm_index + 1] = [
        _command(
            "measure.launch.retry.pid_before_back",
            [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"],
            "8123\n",
        ),
        _command(
            "measure.launch.retry.back",
            [*targeted, "shell", "input", "keyevent", "KEYCODE_BACK"],
        ),
        _command(
            "measure.launch.retry.pid_after_back",
            [*targeted, "shell", "pidof", "com.mobilefork.hermesagent"],
            "8123\n",
        ),
        _command(
            "measure.launch.retry.warm",
            [
                *targeted,
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "com.mobilefork.hermesagent/.MainActivity",
            ],
            "Status: ok\n"
            f"LaunchState: {retry_state}\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            f"TotalTime: {retry_total_ms}\n"
            f"WaitTime: {retry_wait_ms}\n",
        ),
    ]


def _set_normalized_warm_total(root: Path, profile: str, total_ms: int) -> None:
    normalized_path = root / "performance" / f"{profile}.json"
    normalized = json.loads(normalized_path.read_text(encoding="utf-8"))
    normalized["launch"]["warm_total_ms"] = total_ms
    normalized_path.write_text(json.dumps(normalized), encoding="utf-8")


def test_parser_tracks_variable_runtime_registry_entries_without_a_catalog_snapshot(evidence_module):
    source = """
object VerifiedLocalModelArtifacts {
  val releaseMatrix: List<Artifact> = listOf(
    Artifact(
      modelId = "future-gguf",
      repoId = "org/future",
      revision = "1111111111111111111111111111111111111111",
      fileName = "future.gguf",
      runtime = "llama.cpp",
      expectedBytes = 9_876_543L,
      sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      validationEvidence = "device",
      remoteManifestMatches = true,
    ),
    Artifact(
      modelId = "new-litert",
      repoId = "org/mobile",
      revision = "2222222222222222222222222222222222222222",
      fileName = "new.litertlm",
      runtime = "litert-lm",
      expectedBytes = 1_234L,
      sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      validationEvidence = "device",
      remoteManifestMatches = true,
    ),
  );
}
"""
    parsed = evidence_module.parse_registered_model_matrix(source)

    assert {artifact.model_id for artifact in parsed} == {"future-gguf", "new-litert"}
    assert {artifact.backend for artifact in parsed} == {"llama.cpp", "litert-lm"}
    assert next(artifact for artifact in parsed if artifact.model_id == "future-gguf").expected_bytes == 9_876_543


def test_source_digest_ignores_evidence_blobs_but_changes_with_source(evidence_module):
    base = [
        ("100644", "blob", "1" * 40, "android/app/source.kt"),
        ("100644", "blob", "2" * 40, "android/release-evidence/v0.1.0/manifest.json"),
    ]
    changed_evidence = [base[0], ("100644", "blob", "3" * 40, base[1][3])]
    changed_source = [("100644", "blob", "4" * 40, base[0][3]), base[1]]

    original = evidence_module.source_digest_from_entries(base, object_format="sha1")
    assert evidence_module.source_digest_from_entries(changed_evidence, object_format="sha1") == original
    assert evidence_module.source_digest_from_entries(changed_source, object_format="sha1").digest != original.digest
    assert original.file_count == 1


def test_bound_source_identity_rejects_dirty_or_untracked_build_inputs(
    evidence_module, tmp_path
):
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.name", "Evidence Test"], cwd=repo, check=True)
    source = repo / "source.txt"
    source.write_text("clean\n", encoding="utf-8")
    subprocess.run(["git", "add", "source.txt"], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-q", "-m", "fixture"], cwd=repo, check=True)

    evidence_module.require_clean_worktree(repo)
    clean = evidence_module.git_source_tree_identity(repo)
    assert evidence_module.HEX_64_RE.fullmatch(clean.digest)

    source.write_text("dirty\n", encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="clean worktree"):
        evidence_module.require_clean_worktree(repo)
    subprocess.run(["git", "restore", "source.txt"], cwd=repo, check=True)
    (repo / "untracked.txt").write_text("input\n", encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="clean worktree"):
        evidence_module.require_clean_worktree(repo)


def test_complete_synthetic_headed_device_matrix_validates_and_manifests(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    validated = evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )
    source = evidence_module.SourceTreeIdentity(
        algorithm=evidence_module.SOURCE_DIGEST_ALGORITHM,
        digest=SOURCE_DIGEST,
        file_count=100,
        git_object_format="sha1",
        excluded_prefix="android/release-evidence/",
    )
    manifest = evidence_module.build_manifest(
        tag=TAG,
        source=source,
        artifacts=artifacts,
        evidence=validated,
    )
    manifest_path = evidence_root / "manifest.json"
    evidence_module.write_manifest(manifest_path, manifest)

    evidence_module.verify_manifest(manifest_path, manifest)
    assert validated.ui_capture_count == 12
    assert validated.performance_record_count == 2
    assert validated.model_count == len(artifacts)
    assert manifest["source_tree"]["digest"] == SOURCE_DIGEST
    assert manifest["tested_binaries"] == {
        "candidate_apk_sha256": CANDIDATE_APK_SHA256,
        "instrumentation_apk_sha256": INSTRUMENTATION_APK_SHA256,
        "evidence_run_id": EVIDENCE_RUN_ID,
    }
    manifest_paths = {record["path"] for record in manifest["evidence"]["files"]}
    assert "performance/phone-compact.raw.json" in manifest_paths
    assert "performance/tablet.raw.json" in manifest_paths
    assert {item["model_id"] for item in manifest["registered_model_matrix"]} == {
        artifact.model_id for artifact in artifacts
    }


def test_missing_language_capture_is_rejected(evidence_root, evidence_module, artifacts):
    _write_fixture(evidence_root, evidence_module, artifacts)
    (evidence_root / "ui" / "tablet" / "fr" / "screen.png").unlink()

    with pytest.raises(evidence_module.EvidenceError, match="layout mismatch"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_unregistered_extra_model_record_is_rejected(evidence_root, evidence_module, artifacts):
    _write_fixture(evidence_root, evidence_module, artifacts)
    extra = evidence_root / "models" / "not-in-runtime-registry.json"
    extra.write_text(json.dumps(_model_record(artifacts[0])), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="unexpected"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("field", "replacement"),
    (
        ("publisher_revision", "3" * 40),
        ("device_visible_bytes", 1),
        ("device_sha256", "f" * 64),
        ("runtime_started", False),
        ("health_ok", False),
        ("completion_nonempty", False),
        ("elapsed_ms", 0),
    ),
)
def test_model_matrix_requires_exact_registered_bytes_and_real_completion(
    evidence_root, evidence_module, artifacts, field, replacement
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "models" / f"{artifacts[0].model_id}.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload[field] = replacement
    target.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match=field):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("mutator", "message"),
    (
        (lambda payload: payload["frames"].update(total_rendered=8), "at least 100"),
        (lambda payload: payload["memory"].update(total_pss_kb=0), "total_pss_kb"),
        (lambda payload: payload["device"].update(hardware_acceleration=False), "hardware_acceleration"),
        (lambda payload: payload["device"].update(gpu_renderer="SwiftShader"), "software renderer"),
        (
            lambda payload: payload["device"].update(
                gpu_renderer="Microsoft Basic Render Driver"
            ),
            "software renderer",
        ),
    ),
)
def test_performance_contract_rejects_non_certifying_records(
    evidence_root, evidence_module, artifacts, mutator, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "performance" / "phone-compact.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    mutator(payload)
    if payload["frames"]["total_rendered"] == 8:
        payload["frames"].update(janky=1, janky_percent=12.5)
    target.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_raw_transcript_missing_or_byte_tampered_is_rejected(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    raw_path = evidence_root / "performance" / "phone-compact.raw.json"
    raw_path.write_bytes(raw_path.read_bytes() + b" ")
    with pytest.raises(evidence_module.EvidenceError, match="sha256 does not match"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)

    _write_fixture(evidence_root, evidence_module, artifacts)
    raw_path.unlink()
    with pytest.raises(evidence_module.EvidenceError, match="layout mismatch"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_raw_metrics_cannot_diverge_after_hash_is_recomputed(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(item for item in raw["records"] if item["id"] == "measure.launch.warm")
        record["stdout"] = record["stdout"].replace("TotalTime: 400", "TotalTime: 401")

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="raw launch timings disagree"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize("retry_state", ("WARM", "HOT"))
def test_performance_raw_unknown_warm_launch_allows_one_pid_bound_positive_retry(
    evidence_root, evidence_module, artifacts, retry_state
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def add_retry(raw):
        _insert_warm_retry(raw, retry_state=retry_state)

    _rewrite_raw_fixture(evidence_root, "phone-compact", add_retry)
    _set_normalized_warm_total(evidence_root, "phone-compact", 401)

    evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("retry_stdout", "message"),
    [
        (
            "Status: ok\nLaunchState: UNKNOWN (0)\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 0\nWaitTime: 7\n",
            "launch state",
        ),
        (
            "Status: ok\nLaunchState: COLD\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\n",
            "launch state",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 0\nWaitTime: 10\n",
            "invalid launch",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 10\nWaitTime: 0\n",
            "invalid launch",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: -1\nWaitTime: 10\n",
            "one TotalTime",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 10\nWaitTime: -1\n",
            "one WaitTime",
        ),
    ],
)
def test_performance_retry_rejects_second_unknown_cold_or_nonpositive_timings(
    evidence_root, evidence_module, artifacts, retry_stdout, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        _insert_warm_retry(raw)
        record = next(
            item for item in raw["records"] if item["id"] == "measure.launch.retry.warm"
        )
        record["stdout"] = retry_stdout

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("output", "message"),
    [
        (
            "Status: ok\nStatus: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\n",
            "one Status",
        ),
        (
            "Status: ok\nStatus: failed\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\n",
            "one Status",
        ),
        (
            "Status: ok\nLaunchState: WARM\nLaunchState: COLD\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\n",
            "launch state",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nTotalTime: 402\nWaitTime: 411\n",
            "one TotalTime",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\nWaitTime: 412\n",
            "one WaitTime",
        ),
        (
            "Status: ok\nLaunchState: WARM\nTotalTime: 401\nWaitTime: 411\n",
            "intended Activity",
        ),
        (
            "Status: ok\nLaunchState: WARM\nActivity: com.example/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\n",
            "intended Activity",
        ),
        (
            "Status: ok\nLaunchState: WARM\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\n"
            "TotalTime: 401\nWaitTime: 411\n",
            "intended Activity",
        ),
    ],
)
def test_raw_start_parser_rejects_duplicate_conflicting_fields_or_wrong_activity(
    evidence_module, output, message
):
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module._raw_parse_start(output, {"WARM", "HOT"}, "synthetic.warm")


def test_performance_retry_rejects_initial_unknown_wait_over_bound(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    _rewrite_raw_fixture(
        evidence_root,
        "phone-compact",
        lambda raw: _insert_warm_retry(raw, initial_wait_ms=1_001),
    )

    with pytest.raises(evidence_module.EvidenceError, match="retry is only permitted"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    "unknown_stdout",
    [
        "Status: ok\nStatus: ok\nLaunchState: UNKNOWN (0)\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 0\nWaitTime: 7\n",
        (
            "Status: ok\nLaunchState: UNKNOWN (0)\nLaunchState: UNKNOWN (0)\n"
            "Activity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 0\nWaitTime: 7\n"
        ),
        "Status: ok\nLaunchState: UNKNOWN (0)\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\n"
        "TotalTime: 0\nTotalTime: 0\nWaitTime: 7\n",
        "Status: ok\nLaunchState: UNKNOWN (0)\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\n"
        "TotalTime: 0\nWaitTime: 7\nWaitTime: 7\n",
        "Status: ok extra\nLaunchState: UNKNOWN (0)\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 0\nWaitTime: 7\n",
        "Status: ok\nLaunchState: UNKNOWN OTHER\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 0\nWaitTime: 7\n",
        "Status: ok\nLaunchState: UNKNOWN (0)\nTotalTime: 0\nWaitTime: 7\n",
        "Status: ok\nLaunchState: UNKNOWN (0)\nActivity: com.example/.MainActivity\n"
        "TotalTime: 0\nWaitTime: 7\n",
        "Status: ok\nLaunchState: UNKNOWN (0)\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\nTotalTime: 0\nWaitTime: 7\n",
    ],
)
def test_performance_retry_rejects_malformed_or_duplicate_unknown_result(
    evidence_root, evidence_module, artifacts, unknown_stdout
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        _insert_warm_retry(raw)
        record = next(
            item for item in raw["records"] if item["id"] == "measure.launch.warm"
        )
        record["stdout"] = unknown_stdout

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="retry is only permitted"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("record_id", "stdout", "message"),
    [
        ("measure.launch.retry.pid_before_back", "", "stdout is blank"),
        ("measure.launch.retry.pid_before_back", "8124\n", "changed before bounded"),
        ("measure.launch.retry.pid_after_back", "", "stdout is blank"),
        ("measure.launch.retry.pid_after_back", "8124\n", "changed across bounded"),
    ],
)
def test_performance_retry_rejects_killed_or_replaced_pid(
    evidence_root, evidence_module, artifacts, record_id, stdout, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        _insert_warm_retry(raw)
        record = next(item for item in raw["records"] if item["id"] == record_id)
        record["stdout"] = stdout

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("record_id", "replacement"),
    [
        ("measure.launch.retry.pid_before_back", "com.example.unrelated"),
        ("measure.launch.retry.pid_after_back", "com.example.unrelated"),
        ("measure.launch.retry.back", "KEYCODE_HOME"),
    ],
)
def test_performance_retry_rejects_retargeted_pidof_or_back_command(
    evidence_root, evidence_module, artifacts, record_id, replacement
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        _insert_warm_retry(raw)
        record = next(item for item in raw["records"] if item["id"] == record_id)
        record["argv"][-1] = replacement

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="required live command"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_retry_rejects_extra_retry_record(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        _insert_warm_retry(raw)
        records = raw["records"]
        retry_index = next(
            index
            for index, item in enumerate(records)
            if item["id"] == "measure.launch.retry.warm"
        )
        records.insert(
            retry_index + 1,
            _command(
                "measure.launch.retry.extra",
                [
                    "adb",
                    "-s",
                    "emulator-5566",
                    "shell",
                    "pidof",
                    "com.mobilefork.hermesagent",
                ],
                "8123\n",
            ),
        )

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(
        evidence_module.EvidenceError,
        match="UI navigation/measurement command order",
    ):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("mutator", "message"),
    [
        (
            lambda record: record.update(
                stdout=record["stdout"].replace(
                    'package="com.mobilefork.hermesagent"', 'package="com.example.unrelated"'
                )
            ),
            "wrong package",
        ),
        (
            lambda record: record.update(
                stdout=record["stdout"].replace(
                    "</hierarchy>",
                    '<node package="com.mobilefork.hermesagent" '
                    'resource-id="HermesSettingsContentList" bounds="[0,100][360,760]" '
                    'enabled="true" clickable="false" scrollable="true"/></hierarchy>',
                )
            ),
            "exactly once",
        ),
        (
            lambda record: record["argv"].__setitem__(-1, "999"),
            "required live command",
        ),
    ],
)
def test_performance_raw_ui_navigation_tampering_is_rejected(
    evidence_root, evidence_module, artifacts, mutator, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record_id = (
            "measure.ui.phone.drawer.tap"
            if message == "required live command"
            else "measure.ui.settings.cat"
        )
        record = next(item for item in raw["records"] if item["id"] == record_id)
        mutator(record)

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("record_id", "mutator", "message"),
    [
        (
            "measure.ui.initial.remove",
            lambda record: record["argv"].__setitem__(
                -1, "/data/local/tmp/hermes-performance-ui-stale.xml"
            ),
            "required live command",
        ),
        (
            "measure.ui.initial.remove",
            lambda record: record.update(stdout="removed stale file\n"),
            "fresh-path removal produced output",
        ),
        (
            "measure.ui.initial.dump",
            lambda record: record["argv"].__setitem__(
                -1, "/data/local/tmp/hermes-performance-ui-stale.xml"
            ),
            "required live command",
        ),
        (
            "measure.ui.initial.dump",
            lambda record: record.update(stdout=""),
            "exact fresh success marker",
        ),
        (
            "measure.ui.initial.dump",
            lambda record: record.update(
                stdout=(
                    "UI hierarchy dumped to: "
                    "/data/local/tmp/hermes-performance-ui-initial.xml\n"
                )
            ),
            "exact fresh success marker",
        ),
        (
            "measure.ui.initial.dump",
            lambda record: record.update(
                stdout=(
                    "UI hierchary dumped to: "
                    "/data/local/tmp/hermes-performance-ui-stale.xml\n"
                )
            ),
            "exact fresh success marker",
        ),
        (
            "measure.ui.initial.dump",
            lambda record: record.update(stderr="ERROR: stale hierarchy\n"),
            "exact fresh success marker",
        ),
    ],
)
def test_raw_ui_dump_requires_fresh_phase_path_and_exact_success(
    evidence_root, evidence_module, artifacts, record_id, mutator, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(item for item in raw["records"] if item["id"] == record_id)
        mutator(record)

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("record_id", "mutator", "message"),
    [
        (
            "measure.ui.initial.cat",
            lambda record: record.update(
                stdout=_ui_xml(
                    "HermesChatDrawerButton", "[10,10][110,110]", clickable=True
                )
            ),
            "wrong-profile",
        ),
        (
            "measure.ui.tablet.settings.tap",
            lambda record: record["argv"].__setitem__(-1, "999"),
            "required live command",
        ),
        (
            "measure.ui.settings.cat",
            lambda record: record.update(stdout="<hierarchy><node"),
            "invalid XML",
        ),
    ],
)
def test_tablet_raw_navigation_rejects_wrong_profile_argv_or_xml(
    evidence_root, evidence_module, artifacts, record_id, mutator, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(item for item in raw["records"] if item["id"] == record_id)
        mutator(record)

    _rewrite_raw_fixture(evidence_root, "tablet", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("field", "replacement", "message"),
    [
        ("ui_navigation_route", "phone-drawer-settings", "navigation route disagrees"),
        ("settings_scroll_bounds_px", [121, 100, 800, 1240], "settings bounds disagree"),
        ("gfx_swipe_coordinates", [461, 1012, 461, 328], "swipe coordinates disagree"),
    ],
)
def test_tablet_normalized_navigation_cannot_diverge_from_raw_route_bounds_or_swipe(
    evidence_root, evidence_module, artifacts, field, replacement, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "performance" / "tablet.json"
    normalized = json.loads(target.read_text(encoding="utf-8"))
    normalized["collector"][field] = replacement
    target.write_text(json.dumps(normalized), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_raw_swipe_rejects_flipped_first_direction(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        swipe = next(
            item for item in raw["records"] if item["id"] == "measure.gfx.swipe.0001"
        )
        swipe["argv"][7], swipe["argv"][9] = swipe["argv"][9], swipe["argv"][7]

    _rewrite_raw_fixture(evidence_root, "tablet", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="unexpected swipe coordinates"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_raw_swipe_rejects_repeated_direction_within_round(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        records = raw["records"]
        first_index = next(
            index
            for index, item in enumerate(records)
            if item["id"] == "measure.gfx.swipe.0001"
        )
        repeated = json.loads(json.dumps(records[first_index]))
        repeated["id"] = "measure.gfx.swipe.0002"
        records.insert(first_index + 1, repeated)

    _rewrite_raw_fixture(evidence_root, "tablet", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="unexpected swipe coordinates"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("record_id", "stdout"),
    [
        ("measure.activity.before_gfx", "Activities:\n"),
        (
            "measure.activity.before_gfx",
            "topResumedActivity=ActivityRecord{abc u0 com.example/.MainActivity t1}\n",
        ),
        ("measure.activity.after_gfx", "Activities:\n"),
        (
            "measure.activity.after_gfx",
            "topResumedActivity=ActivityRecord{abc u0 com.example/.MainActivity t1}\n",
        ),
    ],
)
def test_raw_gfx_measurement_requires_hermes_foreground_before_and_after(
    evidence_root, evidence_module, artifacts, record_id, stdout
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(item for item in raw["records"] if item["id"] == record_id)
        record["stdout"] = stdout

    _rewrite_raw_fixture(evidence_root, "tablet", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="resumed Hermes MainActivity"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    "output",
    [
        "topResumedActivity=ActivityRecord{abc u0 "
        "com.mobilefork.hermesagent/.MainActivity t1}\n",
        "ResumedActivity: ActivityRecord{abc u0 "
        "com.mobilefork.hermesagent/.MainActivity t1}\n",
        (
            "topResumedActivity=ActivityRecord{abc u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
            "ResumedActivity: ActivityRecord{def u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
        ),
    ],
)
def test_raw_resumed_activity_parser_accepts_current_and_canonical_claims(
    evidence_module, output
):
    evidence_module._raw_require_resumed_activity(output, "synthetic.activity")


@pytest.mark.parametrize(
    "output",
    [
        (
            "topResumedActivity=ActivityRecord{abc u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
            "ResumedActivity: malformed\n"
        ),
        (
            "topResumedActivity=ActivityRecord{abc u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
            "ResumedActivity: ActivityRecord{def u0 com.example/.MainActivity t1}\n"
        ),
    ],
)
def test_raw_resumed_activity_parser_rejects_unparsed_or_non_hermes_claims(
    evidence_module, output
):
    with pytest.raises(evidence_module.EvidenceError, match="only resumed Hermes"):
        evidence_module._raw_require_resumed_activity(output, "synthetic.activity")


def test_raw_device_font_scale_cannot_drift(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(
            item
            for item in raw["records"]
            if item["id"] == "final.device.settings.font_scale"
        )
        record["stdout"] = "1.1\n"

    _rewrite_raw_fixture(evidence_root, "tablet", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="font_scale must equal"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_normalized_screen_font_scale_must_equal_one(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "performance" / "tablet.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload["screen"]["font_scale"] = 1.1
    target.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="font_scale must equal 1.0"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("settings_xml", "message"),
    [
        (
            _ui_xml(
                "HermesSettingsContentList",
                "[120,100][800,1240]",
                scrollable=True,
                enabled=False,
            ),
            "not enabled",
        ),
        (
            _ui_xml(
                "HermesSettingsContentList", "[120,100][800,1240]", scrollable=False
            ),
            "not scrollable",
        ),
        (
            _ui_xml(
                "HermesSettingsContentList", "[-1,100][800,1240]", scrollable=True
            ),
            "invalid bounds",
        ),
        (
            _ui_xml(
                "HermesSettingsContentList", "[800,100][120,1240]", scrollable=True
            ),
            "unsafe display bounds",
        ),
        (
            _ui_xml(
                "HermesSettingsContentList", "[120,100][120,1240]", scrollable=True
            ),
            "unsafe display bounds",
        ),
        (
            _ui_xml(
                "HermesSettingsContentList", "[120,100][800,1281]", scrollable=True
            ),
            "unsafe display bounds",
        ),
    ],
)
def test_tablet_raw_settings_target_rejects_disabled_non_scrollable_or_unsafe_bounds(
    evidence_root, evidence_module, artifacts, settings_xml, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "measure.ui.settings.cat"
        )
        record["stdout"] = settings_xml

    _rewrite_raw_fixture(evidence_root, "tablet", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize("after_pid", ("", "8124\n"))
def test_performance_raw_warm_launch_requires_pid_preserved_across_back(
    evidence_root, evidence_module, artifacts, after_pid
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "measure.launch.pid_after_back"
        )
        record["stdout"] = after_pid

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    message = "positive Hermes process PID" if not after_pid else "PID changed across KEYCODE_BACK"
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_raw_warm_launch_back_and_pid_argv_are_enforced(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def replace_back_with_home(raw):
        record = next(item for item in raw["records"] if item["id"] == "measure.launch.back")
        record["argv"][-1] = "KEYCODE_HOME"

    _rewrite_raw_fixture(evidence_root, "phone-compact", replace_back_with_home)
    with pytest.raises(evidence_module.EvidenceError, match="required live command"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)

    _write_fixture(evidence_root, evidence_module, artifacts)

    def retarget_pidof(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "measure.launch.pid_before_back"
        )
        record["argv"][-1] = "com.example.unrelated"

    _rewrite_raw_fixture(evidence_root, "phone-compact", retarget_pidof)
    with pytest.raises(evidence_module.EvidenceError, match="required live command"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_normalized_warm_pid_must_match_reparsed_transcript(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "performance" / "phone-compact.json"
    normalized = json.loads(target.read_text(encoding="utf-8"))
    normalized["launch"]["warm_process_pid"] = 9999
    target.write_text(json.dumps(normalized), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="process PID differs"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_raw_fixture_binds_gfx_memory_and_final_pid_to_warm_process():
    performance = _performance("phone-compact")
    raw = _raw_performance("phone-compact", performance)
    records = raw["records"]
    by_id = {record["id"]: record for record in records}

    assert by_id["initial.device.settings.font_scale"]["stdout"] == "1.0\n"
    assert by_id["final.device.settings.font_scale"]["stdout"] == "1.0\n"
    assert by_id["measure.activity.before_gfx"]["stdout"].startswith(
        "topResumedActivity="
    )
    assert by_id["measure.activity.after_gfx"]["stdout"].startswith(
        "topResumedActivity="
    )
    assert "Graphics info for pid 8123 [com.mobilefork.hermesagent]" in by_id[
        "measure.gfx.framestats.01"
    ]["stdout"]
    assert "MEMINFO in pid 8123 [com.mobilefork.hermesagent]" in by_id[
        "measure.memory.meminfo"
    ]["stdout"]
    assert by_id["measure.process.pid_after_measurement"]["stdout"] == "8123\n"
    memory_index = next(
        index for index, record in enumerate(records) if record["id"] == "measure.memory.meminfo"
    )
    assert [record["id"] for record in records[memory_index : memory_index + 2]] == [
        "measure.memory.meminfo",
        "measure.process.pid_after_measurement",
    ]


@pytest.mark.parametrize(
    ("record_id", "old", "new", "message"),
    [
        (
            "measure.gfx.framestats.01",
            "** Graphics info for pid 8123 [com.mobilefork.hermesagent] **\n",
            "",
            "process header",
        ),
        (
            "measure.gfx.framestats.01",
            "Graphics info for pid 8123",
            "Graphics info for pid 8124",
            "process header",
        ),
        (
            "measure.memory.meminfo",
            "** MEMINFO in pid 8123 [com.mobilefork.hermesagent] **\n",
            "",
            "process header",
        ),
        (
            "measure.memory.meminfo",
            "MEMINFO in pid 8123",
            "MEMINFO in pid 8124",
            "process header",
        ),
        ("measure.process.pid_after_measurement", "8123\n", "", "positive Hermes process PID"),
        (
            "measure.process.pid_after_measurement",
            "8123\n",
            "8124\n",
            "PID changed during measurement",
        ),
    ],
)
def test_raw_measurement_rejects_missing_or_mismatched_process_identity(
    evidence_root, evidence_module, artifacts, record_id, old, new, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(item for item in raw["records"] if item["id"] == record_id)
        record["stdout"] = record["stdout"].replace(old, new)

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize(
    ("record_id", "duplicate", "message"),
    [
        (
            "measure.gfx.framestats.01",
            "** Graphics info for pid 8123 [com.mobilefork.hermesagent] **\n",
            "process header",
        ),
        (
            "measure.gfx.framestats.01",
            "Total frames rendered: 120\n",
            "one Total frames rendered",
        ),
        (
            "measure.gfx.framestats.01",
            "Janky frames: 6 (5.00%)\n",
            "one janky-frame summary",
        ),
        (
            "measure.memory.meminfo",
            "** MEMINFO in pid 8123 [com.mobilefork.hermesagent] **\n",
            "process header",
        ),
        (
            "measure.memory.meminfo",
            " TOTAL PSS: 250000 TOTAL RSS: 320000 TOTAL SWAP PSS: 0\n",
            "raw meminfo disagrees",
        ),
    ],
)
def test_raw_measurement_rejects_duplicate_headers_or_metrics(
    evidence_root, evidence_module, artifacts, record_id, duplicate, message
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(item for item in raw["records"] if item["id"] == record_id)
        record["stdout"] += duplicate

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match=message):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


@pytest.mark.parametrize("field", ("versionName", "versionCode"))
def test_raw_identity_rejects_duplicate_installed_version_fields(
    evidence_root, evidence_module, artifacts, field
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def mutate(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "initial.package.version"
        )
        value = VERSION_NAME if field == "versionName" else VERSION_CODE
        record["stdout"] += f"  {field}={value}\n"

    _rewrite_raw_fixture(evidence_root, "phone-compact", mutate)
    with pytest.raises(evidence_module.EvidenceError, match="package version disagrees"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_raw_get_serialno_and_command_argv_are_enforced(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def substitute_serial(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "initial.adb.get-serialno"
        )
        record["stdout"] = "emulator-5588\n"

    _rewrite_raw_fixture(evidence_root, "phone-compact", substitute_serial)
    with pytest.raises(evidence_module.EvidenceError, match="get-serialno does not exactly match"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)

    _write_fixture(evidence_root, evidence_module, artifacts)

    def substitute_command(raw):
        record = next(
            item
            for item in raw["records"]
            if item["id"] == "initial.device.getprop.build_fingerprint"
        )
        record["argv"][-1] = "ro.product.brand"

    _rewrite_raw_fixture(evidence_root, "phone-compact", substitute_command)
    with pytest.raises(evidence_module.EvidenceError, match="required live command"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_performance_raw_ambiguous_qemu_inventory_is_rejected(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)

    def duplicate_qemu(raw):
        record = next(
            item for item in raw["records"] if item["id"] == "initial.host.qemu_processes"
        )
        processes = json.loads(record["stdout"])
        duplicate = dict(processes[0])
        duplicate["pid"] = duplicate["pid"] + 1
        processes.append(duplicate)
        record["stdout"] = json.dumps(processes, separators=(",", ":"))

    _rewrite_raw_fixture(evidence_root, "phone-compact", duplicate_qemu)
    with pytest.raises(evidence_module.EvidenceError, match="exactly one serial/AVD QEMU"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_reused_untranslated_ui_capture_is_rejected(evidence_root, evidence_module, artifacts):
    _write_fixture(evidence_root, evidence_module, artifacts)
    en = evidence_root / "ui" / "phone-compact" / "en"
    fr = evidence_root / "ui" / "phone-compact" / "fr"
    (fr / "screen.png").write_bytes((en / "screen.png").read_bytes())
    fr_semantics = (en / "semantics.txt").read_text(encoding="utf-8").replace(
        "language=en", "language=fr"
    )
    (fr / "semantics.txt").write_text(fr_semantics, encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="localized Device/Overview sentinel"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_phone_ui_requires_the_live_device_shell_drawer_tag(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    for language in evidence_module.LANGUAGES:
        semantics = evidence_root / "ui" / "phone-compact" / language / "semantics.txt"
        semantics.write_text(
            semantics.read_text(encoding="utf-8").replace(
                "Tag: 'HermesShellDrawerButton'",
                "Tag: 'HermesChatDrawerButton'",
            ),
            encoding="utf-8",
        )

    with pytest.raises(evidence_module.EvidenceError, match="compact drawer navigation"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_registry_parser_rejects_helper_or_variable_entries(evidence_module):
    source = """
object VerifiedLocalModelArtifacts {
  val releaseMatrix: List<Artifact> = listOf(
    Artifact(
      modelId = "literal",
      repoId = "org/literal",
      revision = "1111111111111111111111111111111111111111",
      fileName = "literal.gguf",
      runtime = "llama.cpp",
      expectedBytes = 123L,
      sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      validationEvidence = "device",
      remoteManifestMatches = true,
    ),
    helperArtifact,
  );
}
"""
    with pytest.raises(evidence_module.EvidenceError, match="literal Artifact"):
        evidence_module.parse_registered_model_matrix(source)

    chained = source.replace(
        "    helperArtifact,\n",
        "",
    ).replace(
        "  );\n}",
        "  )!!.let { it + helperArtifact };\n}",
    )
    with pytest.raises(evidence_module.EvidenceError, match="explicit semicolon"):
        evidence_module.parse_registered_model_matrix(chained)


def test_registry_rejects_platform_specific_or_traversing_artifact_names(evidence_module):
    base = evidence_module.ArtifactSpec(
        model_id="unsafe",
        repository="org/model",
        revision="1" * 40,
        file_name="safe.gguf",
        runtime="llama.cpp",
        expected_bytes=123,
        sha256="a" * 64,
    )
    for unsafe_name in ("folder/model.gguf", r"folder\model.gguf", "../model.gguf", "bad name.gguf"):
        with pytest.raises(evidence_module.EvidenceError, match="unsafe portable file name"):
            evidence_module._validate_artifact_spec(
                evidence_module.ArtifactSpec(**{**base.__dict__, "file_name": unsafe_name})
            )


def test_registry_parser_ignores_comment_and_string_decoys(evidence_module):
    decoy = '''
/*
object VerifiedLocalModelArtifacts {
  val releaseMatrix: List<Artifact> = listOf(Artifact(
    modelId = "decoy", repoId = "bad/decoy", revision = "1111111111111111111111111111111111111111",
    fileName = "decoy.gguf", runtime = "llama.cpp", expectedBytes = 1L,
    sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    validationEvidence = "none", remoteManifestMatches = false,
  ));
}
*/
object VerifiedLocalModelArtifacts {
  val text = "val releaseMatrix: List<Artifact> = listOf()"
  val releaseMatrix: List<Artifact> get() = emptyList()
}
'''
    with pytest.raises(evidence_module.EvidenceError, match="canonical literal|explicitly typed literal"):
        evidence_module.parse_registered_model_matrix(decoy)


def test_png_decoder_rejects_crc_valid_but_non_pixel_idat(evidence_module, tmp_path):
    path = tmp_path / "fake.png"
    ihdr = struct.pack(">IIBBBBB", 20, 20, 8, 2, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(b"not pixel scanlines"))
        + _chunk(b"IEND", b"")
    )
    with pytest.raises(evidence_module.EvidenceError, match="decoded byte count"):
        evidence_module._decode_png(path)


def test_png_decoder_rejects_hidden_rgb_under_transparency(evidence_module, tmp_path):
    path = tmp_path / "transparent.png"
    width = height = 20
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            rows.extend(((x * 13) & 0xFF, (y * 17) & 0xFF, ((x + y) * 19) & 0xFF, 0))
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(bytes(rows)))
        + _chunk(b"IEND", b"")
    )
    with pytest.raises(evidence_module.EvidenceError, match="non-opaque"):
        evidence_module._decode_png(path)


def test_performance_rejects_negative_accel_conflicting_flags_and_catastrophic_numbers(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "performance" / "phone-compact.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload["device"]["acceleration_check"] = "WHPX is NOT usable"
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="usable accelerator"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)

    payload["device"]["acceleration_check"] = "WHPX is installed and usable."
    payload["device"]["emulator_command"] += " -gpu swiftshader_indirect -accel off"
    payload["device"]["emulator_command_sha256"] = hashlib.sha256(
        payload["device"]["emulator_command"].encode("utf-8")
    ).hexdigest()
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="-gpu host and -accel on"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)

    payload = _performance("phone-compact")
    payload["launch"]["cold_total_ms"] = 999_999
    payload["launch"]["cold_wait_ms"] = 999_999
    payload["launch"]["warm_total_ms"] = 999_999
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="performance budget"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)

    payload = _performance("phone-compact")
    payload["memory"] = {"total_pss_kb": 600_000, "total_rss_kb": 700_000}
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(evidence_module.EvidenceError, match="release ceiling"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_model_record_must_match_a_measured_device_identity(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    target = evidence_root / "models" / f"{artifacts[0].model_id}.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    payload["device_model"] = "unmeasured-emulator"
    target.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="device model/API/ABI identity"):
        evidence_module.validate_evidence_directory(evidence_root, artifacts, SOURCE_DIGEST, TAG)


def test_manifest_verification_detects_evidence_or_source_tampering(
    evidence_root, evidence_module, artifacts
):
    _write_fixture(evidence_root, evidence_module, artifacts)
    validated = evidence_module.validate_evidence_directory(
        evidence_root, artifacts, SOURCE_DIGEST, TAG
    )
    source = evidence_module.SourceTreeIdentity(
        algorithm=evidence_module.SOURCE_DIGEST_ALGORITHM,
        digest="d" * 64,
        file_count=100,
        git_object_format="sha1",
        excluded_prefix="android/release-evidence/",
    )
    manifest = evidence_module.build_manifest(
        tag=TAG, source=source, artifacts=artifacts, evidence=validated
    )
    manifest_path = evidence_root / "manifest.json"
    evidence_module.write_manifest(manifest_path, manifest)
    tampered = json.loads(manifest_path.read_text(encoding="utf-8"))
    tampered["source_tree"]["digest"] = "e" * 64
    manifest_path.write_text(json.dumps(tampered), encoding="utf-8")

    with pytest.raises(evidence_module.EvidenceError, match="does not match"):
        evidence_module.verify_manifest(manifest_path, manifest)
