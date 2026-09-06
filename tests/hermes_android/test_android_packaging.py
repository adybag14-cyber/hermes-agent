import importlib.util
import marshal
from pathlib import Path
import re
import tomllib
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_chaquopy_normalizer():
    script_path = REPO_ROOT / "scripts/normalize_chaquopy_assets.py"
    spec = importlib.util.spec_from_file_location("normalize_chaquopy_assets", script_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module




def test_android_release_workflow_uses_hash_based_python_bytecode():
    workflow = (REPO_ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")

    assert 'SOURCE_DATE_EPOCH: "315532800"' in workflow


def test_chaquopy_asset_normalizer_removes_local_install_urls_and_canonicalizes_pyc():
    script = (REPO_ROOT / "scripts/normalize_chaquopy_assets.py").read_text(encoding="utf-8")

    assert 'name.endswith(".dist-info/direct_url.json")' in script
    assert "marshal.dumps(code, 2)" in script
    assert "PYC_UNCHECKED_HASH_HEADER" in script
    assert "zipfile.ZIP_STORED" in script


def test_chaquopy_pyc_normalizer_rewrites_invalidation_header():
    normalizer = _load_chaquopy_normalizer()
    code = compile("value = 1\n", "module.py", "exec")
    body = marshal.dumps(code, 2)
    magic = importlib.util.MAGIC_NUMBER
    timestamp_pyc = (
        magic
        + (0).to_bytes(4, "little")
        + (123).to_bytes(4, "little")
        + (10).to_bytes(4, "little")
        + body
    )
    checked_hash_pyc = magic + (3).to_bytes(4, "little") + b"12345678" + body

    normalized_timestamp = normalizer.normalize_pyc(timestamp_pyc)
    normalized_checked_hash = normalizer.normalize_pyc(checked_hash_pyc)

    assert normalized_timestamp == normalized_checked_hash
    assert normalized_timestamp[:4] == magic
    assert normalized_timestamp[4:8] == (1).to_bytes(4, "little")
    assert normalized_timestamp[8:16] == b"\0" * 8


def test_chaquopy_normalizer_writes_build_json_with_lf_newlines(tmp_path):
    normalizer = _load_chaquopy_normalizer()
    build_json = tmp_path / "build.json"
    build_json.write_text('{"b": 1, "a": 2}\n', encoding="utf-8")

    normalizer.normalize_build_json(build_json)

    payload = build_json.read_bytes()
    assert b"\r\n" not in payload
    assert payload == b'{\n    "a": 2,\n    "b": 1\n}\n'


def test_chaquopy_requirements_normalizer_canonicalizes_metadata_newlines(tmp_path):
    normalizer = _load_chaquopy_normalizer()
    requirements = tmp_path / "requirements-common.imy"
    with zipfile.ZipFile(requirements, "w") as archive:
        archive.writestr("demo-1.0.dist-info/METADATA", b"Name: demo\r\nVersion: 1.0\r\n")

    normalizer.normalize_requirements_imy(requirements)

    with zipfile.ZipFile(requirements) as archive:
        info = archive.getinfo("demo-1.0.dist-info/METADATA")
        assert archive.read("demo-1.0.dist-info/METADATA") == b"Name: demo\nVersion: 1.0\n"
        assert info.create_system == 3




def test_fdroid_updatecheck_data_uses_literal_version_code_for_future_tags():
    version_file = dict(
        line.split("=", 1)
        for line in (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.version")
        .read_text(encoding="utf-8")
        .splitlines()
        if line.strip()
    )
    version_name = version_file["versionName"]
    major, minor, patch = (int(part) for part in version_name.split("."))
    expected_code = major * 1_000_000 + minor * 10_000 + patch * 100 + 90
    template = (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template").read_text(encoding="utf-8")

    assert version_file == {
        "versionName": version_name,
        "versionCode": str(expected_code),
    }
    update_pattern_match = re.search(r"^UpdateCheckMode:\s+Tags\s+(.+)$", template, re.MULTILINE)
    assert update_pattern_match is not None
    update_pattern = update_pattern_match.group(1)
    assert re.match(update_pattern, f"v{version_name}")
    assert re.match(update_pattern, version_name) is None
    assert "UpdateCheckData: fdroid/com.mobilefork.hermesagent.version|versionCode=(\\d+)|.|versionName=(.*)" in template
    changelog = REPO_ROOT / f"fastlane/metadata/android/en-US/changelogs/{expected_code}.txt"
    assert changelog.is_file()
    assert len(changelog.read_text(encoding="utf-8")) <= 500


def test_android_anthropic_stub_matches_project_requirement_pin():
    pyproject = tomllib.loads((REPO_ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    stub_project = tomllib.loads(
        (REPO_ROOT / "android/pip-stubs/anthropic-stub/pyproject.toml").read_text(encoding="utf-8")
    )

    base_anthropic = next(
        dep for dep in pyproject["project"]["optional-dependencies"]["anthropic"]
        if dep.startswith("anthropic==")
    )
    assert base_anthropic == f"anthropic=={stub_project['project']['version']}"


def test_hy_memory_dependency_is_registered_as_lazy_optional_provider():
    from hermes_cli.config_defaults import DEFAULT_CONFIG
    from tools.lazy_deps import LAZY_DEPS
    from plugins.memory.hy_memory import HyMemoryProvider

    pyproject = tomllib.loads((REPO_ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    assert tuple(pyproject["project"]["optional-dependencies"]["hy-memory"]) == LAZY_DEPS["memory.hy_memory"]
    assert DEFAULT_CONFIG["memory"]["provider"] == HyMemoryProvider().name


def test_android_llama_server_native_dependencies_are_packaged():
    script = (REPO_ROOT / "scripts/prepare_android_native_libs.py").read_text(encoding="utf-8")

    assert '"bin/llama-server": "libhermes_android_llama_server.so"' in script
    assert '"libllama-server-impl.so": "libllama-server-impl.so"' in script
    assert 'patch_needed(abi_output / "libllama-server-impl.so", "libssl.so.3", "libssl.so")' in script
    assert 'patch_needed(abi_output / "libllama-server-impl.so", "libcrypto.so.3", "libcrypto.so")' in script


def test_runtime_service_enters_foreground_before_runtime_startup():
    service = (REPO_ROOT / "android/app/src/main/java/com/mobilefork/hermesagent/backend/HermesRuntimeService.kt").read_text(encoding="utf-8")
    start_body = service.split("private fun startOrRefreshForeground()", 1)[1].split("private fun buildNotification", 1)[0]

    assert start_body.index("promoteToForeground(runtime = null)") < start_body.index("HermesRuntimeManager.ensureStarted(")
    assert "override fun onCreate()" in service
    assert "promoteToForeground(runtime = null)" in service.split("override fun onCreate()", 1)[1].split("override fun onStartCommand", 1)[0]
    assert "ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service
    assert 'val notification = buildNotification(runtime)' in service


def test_android_floating_button_service_is_foreground_overlay():
    manifest = (REPO_ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    service = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesFloatingButtonService.kt"
    ).read_text(encoding="utf-8")
    system_bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesSystemControlBridge.kt"
    ).read_text(encoding="utf-8")
    store = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/data/DeviceCapabilityStore.kt"
    ).read_text(encoding="utf-8")

    assert 'android.permission.SYSTEM_ALERT_WINDOW' in manifest
    assert 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' in manifest
    assert 'android:name=".device.HermesFloatingButtonService"' in manifest
    assert 'android:foregroundServiceType="dataSync"' in manifest
    assert "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY" in service
    assert "ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service
    assert "FLAG_NOT_FOCUSABLE" in service
    assert "openHermes()" in service
    assert "startIfDesired" in service
    assert "isButtonVisible" in service
    assert "start_floating_button" in system_bridge
    assert "floating_button_running" in system_bridge
    assert "floating_button_visible" in system_bridge
    assert "KEY_FLOATING_BUTTON_ENABLED" in store


def test_android_launcher_uses_adaptive_icons():
    manifest = (REPO_ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    adaptive_icon = (REPO_ROOT / "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").read_text(encoding="utf-8")
    adaptive_round_icon = (
        REPO_ROOT / "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"
    ).read_text(encoding="utf-8")
    foreground = (REPO_ROOT / "android/app/src/main/res/drawable/ic_launcher_foreground.xml").read_text(encoding="utf-8")
    background = (REPO_ROOT / "android/app/src/main/res/drawable/ic_launcher_background.xml").read_text(encoding="utf-8")
    monochrome = (REPO_ROOT / "android/app/src/main/res/drawable/ic_launcher_monochrome.xml").read_text(encoding="utf-8")
    app_logo = (REPO_ROOT / "android/app/src/main/res/drawable/hermes_agent_fork_logo.xml").read_text(encoding="utf-8")

    assert 'android:icon="@mipmap/ic_launcher"' in manifest
    assert 'android:roundIcon="@mipmap/ic_launcher_round"' in manifest
    assert "<adaptive-icon" in adaptive_icon
    assert '<background android:drawable="@drawable/ic_launcher_background" />' in adaptive_icon
    assert '<foreground android:drawable="@drawable/ic_launcher_foreground" />' in adaptive_icon
    assert '<monochrome android:drawable="@drawable/ic_launcher_monochrome" />' in adaptive_icon
    assert adaptive_icon == adaptive_round_icon
    assert 'android:viewportWidth="108"' in foreground
    assert 'android:viewportHeight="108"' in foreground
    assert "#101827" in background
    assert 'android:fillColor="#FFFFFFFF"' in monochrome
    assert "FDIE" not in foreground
    assert 'android:fillColor="#FF000000"' not in monochrome
    assert "M54,21a33,33" in foreground
    assert "M28,82h52" not in app_logo
    assert "M34,86h5" not in app_logo


def test_android_anthropic_stub_warns_at_runtime():
    stub_init = (REPO_ROOT / "android/pip-stubs/anthropic-stub/anthropic/__init__.py").read_text(encoding="utf-8")

    assert "not available in the Hermes Android MVP build" in stub_init
    assert "OpenAI-compatible provider" in stub_init


def test_android_fal_client_stub_marks_image_generation_deferred():
    import pytest
    from toolsets import resolve_toolset

    stub_path = REPO_ROOT / "android/pip-stubs/fal-client-stub/fal_client/__init__.py"
    spec = importlib.util.spec_from_file_location("hermes_test_android_fal_stub", stub_path)
    stub = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stub)
    assert stub.__hermes_android_stub__ is True
    with pytest.raises(RuntimeError, match="Image generation is deferred"):
        stub.submit("test-model", arguments={})
    with pytest.raises(RuntimeError, match="Image generation is deferred"):
        stub.SyncClient()
    mobile_tools = set(resolve_toolset("hermes-android-app", include_registry=False))
    assert {"image_generate", "process"}.isdisjoint(mobile_tools)
    assert {"terminal", "android_device_status", "read_file", "write_file"} <= mobile_tools


def test_android_declares_shizuku_privileged_access_support():
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    manifest = (REPO_ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesPrivilegedAccessBridge.kt"
    ).read_text(encoding="utf-8")
    system_bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesSystemControlBridge.kt"
    ).read_text(encoding="utf-8")

    assert 'implementation("dev.rikka.shizuku:api:13.1.5")' in gradle
    assert 'implementation("dev.rikka.shizuku:provider:13.1.5")' in gradle
    assert 'moe.shizuku.manager.permission.API' in manifest
    assert 'moe.shizuku.manager.permission.API_V23' in manifest
    assert 'rikka.shizuku.ShizukuProvider' in manifest
    assert 'android:authorities="${applicationId}.shizuku"' in manifest
    assert "Shizuku.pingBinder()" in bridge
    assert "Shizuku.checkSelfPermission()" in bridge
    assert "Shizuku.requestPermission" in bridge
    assert "libshizuku.so" in bridge
    assert "open_wireless_debugging_settings" in bridge
    assert "open_shizuku_app" in bridge
    assert "privileged_access" in system_bridge


def test_android_release_tag_version_code_tracks_fdroid_semver():
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    version_file = dict(
        line.split("=", 1)
        for line in (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.version")
        .read_text(encoding="utf-8")
        .splitlines()
        if line.strip()
    )
    android_version = version_file["versionName"]
    major, minor, patch = (int(part) for part in android_version.split("."))
    expected_version_code = (major * 1_000_000) + (minor * 10_000) + (patch * 100) + 90

    assert "fun semverVersionCode(versionText: String): Int?" in gradle
    assert "return semverVersionCode(hermesVersionName()) ?: 1" in gradle
    assert "semverVersionCode(releaseTag)?.let { return it }" in gradle
    assert version_file == {
        "versionName": android_version,
        "versionCode": str(expected_version_code),
    }


def test_android_visual_harness_supports_wide_screenshots_and_clicks():
    harness = (REPO_ROOT / "scripts/android_visual_harness.py").read_text(encoding="utf-8")

    assert 'exec-out", "screencap", "-p"' in harness
    assert "out.write_bytes(proc.stdout)" in harness
    assert "proc.stdout.replace" not in harness
    assert '"input", "tap"' in harness
    assert '"swipe"' in harness
    assert '"text"' in harness
    assert '"wm", "size"' in harness
    assert '"wm", "density"' in harness
    assert "DEFAULT_READY_TEXT" in harness
    assert "wait_for_ui_text" in harness
    assert "scroll_until_text" in harness
    assert "check_termux_package_mirrors" in harness
    assert "tap-text" in harness
    assert "nav-section" in harness
    assert "check-termux-mirrors" in harness
    assert "No activities found" in harness
    assert "com.mobilefork.hermesagent" in harness
