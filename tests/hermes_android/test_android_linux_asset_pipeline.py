import hashlib
import socket
import subprocess
import sys
import tarfile
import zipfile
from io import BytesIO
from pathlib import Path

import pytest

import scripts.prepare_android_linux_assets as linux_asset_script
from hermes_android.linux_assets import TermuxPackageRecord
from hermes_android.linux_assets import serializable_manifest
from hermes_android.linux_assets import write_manifest
from scripts.prepare_android_linux_assets import (
    create_bionic_llama_server_launcher,
    locked_packages,
    mirror_data_tar,
    patch_proot_distro_direct_execution,
    write_lock_file,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_prepare_android_linux_assets_script_exists_and_is_wired_into_gradle():
    script = (REPO_ROOT / "scripts/prepare_android_linux_assets.py").read_text(encoding="utf-8")
    native_script = (REPO_ROOT / "scripts/prepare_android_native_libs.py").read_text(encoding="utf-8")
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")

    assert "def prepare_assets" in script
    assert "resolve_dependency_closure" in script
    assert "prepareHermesAndroidLinuxAssets" in gradle
    assert "prepareHermesAndroidNativeLibs" in gradle
    assert 'inputs.file(repoRoot.resolve("scripts/prepare_android_linux_assets.py"))' in gradle
    assert 'inputs.file(repoRoot.resolve("hermes_android/linux_assets.py"))' in gradle
    assert "generated/hermes-linux-assets" in gradle
    assert "generated/hermes-native-libs" in gradle
    assert "termux_linux_assets.lock.json" in gradle
    assert "assets.srcDir" in gradle
    assert "jniLibs.srcDir" in gradle
    assert "useLegacyPackaging = true" in gradle
    assert "create_bionic_llama_server_launcher" in script
    assert "patch_android_spawn_needed_to_libc" in script
    assert "HERMES_TERMUX_MAIN_BASE_URLS" in script
    assert "libandroid-spawn.so" in script
    assert "libhermes_android_bash.so" in native_script
    assert "libhermes_android_llama_server.so" in native_script


def test_prepare_android_linux_assets_script_imports_from_android_workdir():
    result = subprocess.run(
        [sys.executable, str(REPO_ROOT / "scripts/prepare_android_linux_assets.py"), "--help"],
        cwd=REPO_ROOT / "android",
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    assert "Prepare Android Linux CLI assets" in result.stdout
    assert "--lock-file" in result.stdout
    assert "--check-mirrors" in result.stdout
    assert "--build-package-archive" in result.stdout


def test_committed_termux_lock_pins_immutable_package_archive():
    lock = linux_asset_script.load_lock_file(
        REPO_ROOT / "hermes_android/termux_linux_assets.lock.json"
    )
    archive = lock["package_archive"]

    assert archive["url"].startswith(
        "https://github.com/adybag14-cyber/hermes-agent/releases/download/v"
    )
    assert len(archive["sha256"]) == 64
    int(archive["sha256"], 16)


def test_prepare_android_linux_assets_uses_mirror_fallback(monkeypatch):
    calls = []

    def fake_download(url: str, attempts: int = 3) -> bytes:
        calls.append((url, attempts))
        if "packages.termux.dev" in url:
            raise RuntimeError("primary down")
        return b"payload"

    monkeypatch.setattr(linux_asset_script, "download_bytes", fake_download)
    monkeypatch.setattr(
        linux_asset_script,
        "configured_termux_main_base_urls",
        lambda: [
            "https://packages.termux.dev/apt/termux-main",
            "https://mirror.example/termux/termux-main",
        ],
    )

    assert linux_asset_script.download_termux_main_path("pool/main/bash.deb") == b"payload"
    assert calls == [
        ("https://packages.termux.dev/apt/termux-main/pool/main/bash.deb", 3),
        ("https://mirror.example/termux/termux-main/pool/main/bash.deb", 3),
    ]


def test_prepare_android_linux_assets_can_scope_downloads_to_ipv4(monkeypatch):
    families = []

    def fake_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
        families.append(family)
        return []

    monkeypatch.setenv("HERMES_FORCE_IPV4", "true")
    monkeypatch.setattr(linux_asset_script.socket, "getaddrinfo", fake_getaddrinfo)

    assert linux_asset_script.force_ipv4_downloads() is True
    with linux_asset_script.ipv4_only_dns(enabled=True):
        linux_asset_script.socket.getaddrinfo("mirror.example", 443)
    linux_asset_script.socket.getaddrinfo("mirror.example", 443)

    assert families == [socket.AF_INET, 0]


def test_prepare_android_linux_assets_defaults_to_ipv4_on_windows_with_opt_out(monkeypatch):
    monkeypatch.delenv("HERMES_FORCE_IPV4", raising=False)
    monkeypatch.setattr(linux_asset_script.os, "name", "nt")
    assert linux_asset_script.force_ipv4_downloads() is True

    monkeypatch.setenv("HERMES_FORCE_IPV4", "false")
    assert linux_asset_script.force_ipv4_downloads() is False


def test_prepare_android_linux_assets_prefers_verified_locked_archive(monkeypatch):
    package_payload = b"locked-deb-payload"
    archive_buffer = BytesIO()
    with zipfile.ZipFile(archive_buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("pool/main/p/proot/proot.deb", package_payload)
    archive_payload = archive_buffer.getvalue()
    lock_payload = {
        "package_archive": {
            "url": "https://example.invalid/termux-packages.zip",
            "sha256": hashlib.sha256(archive_payload).hexdigest(),
        }
    }
    package = TermuxPackageRecord(
        name="proot",
        version="1",
        filename="pool/main/p/proot/proot.deb",
        sha256=hashlib.sha256(package_payload).hexdigest(),
        depends=(),
    )

    monkeypatch.setattr(linux_asset_script, "download_bytes", lambda _url: archive_payload)
    monkeypatch.setattr(
        linux_asset_script,
        "download_termux_main_path",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("mirror fallback used")),
    )

    with linux_asset_script.locked_package_archive(lock_payload) as archive:
        assert linux_asset_script.download_locked_package(package, archive) == package_payload


def test_prepare_android_linux_asset_package_archive_is_deterministic(tmp_path, monkeypatch):
    package_payload = b"locked-deb-payload"
    package_sha256 = hashlib.sha256(package_payload).hexdigest()
    lock_payload = {
        "version": 1,
        "architectures": {
            "test-abi": {
                "termux_arch": "test-arch",
                "packages": [
                    {
                        "name": "proot",
                        "version": "1",
                        "filename": "pool/main/p/proot/proot.deb",
                        "sha256": package_sha256,
                        "depends": [],
                    }
                ],
            }
        },
    }
    monkeypatch.setattr(linux_asset_script, "ANDROID_TO_TERMUX_ARCH", {"test-abi": "test-arch"})
    monkeypatch.setattr(
        linux_asset_script,
        "download_termux_main_path",
        lambda filename, expected_sha256=None: package_payload,
    )
    first = tmp_path / "first.zip"
    second = tmp_path / "second.zip"

    first_sha256 = linux_asset_script.build_package_archive(lock_payload, first)
    second_sha256 = linux_asset_script.build_package_archive(lock_payload, second)

    assert first.read_bytes() == second.read_bytes()
    assert first_sha256 == second_sha256 == hashlib.sha256(first.read_bytes()).hexdigest()
    with zipfile.ZipFile(first) as archive:
        assert archive.namelist() == ["pool/main/p/proot/proot.deb"]
        assert archive.read("pool/main/p/proot/proot.deb") == package_payload


def test_prepare_android_linux_asset_lock_round_trips_packages(tmp_path):
    lock_file = tmp_path / "termux.lock.json"
    payload = {
        "version": 1,
        "architectures": {
            "arm64-v8a": {
                "termux_arch": "aarch64",
                "packages": [
                    {
                        "name": "bash",
                        "version": "5.3",
                        "filename": "pool/main/b/bash/bash_5.3_aarch64.deb",
                        "sha256": "deadbeef",
                        "depends": ["libandroid-support"],
                    }
                ],
            }
        },
    }

    write_lock_file(lock_file, payload)
    packages = locked_packages(linux_asset_script.load_lock_file(lock_file), "arm64-v8a", "aarch64")

    assert packages == [
        TermuxPackageRecord(
            name="bash",
            version="5.3",
            filename="pool/main/b/bash/bash_5.3_aarch64.deb",
            sha256="deadbeef",
            depends=("libandroid-support",),
        )
    ]


def test_prepare_android_linux_assets_supports_termux_mirror_override(monkeypatch):
    monkeypatch.setenv(
        "HERMES_TERMUX_MAIN_BASE_URLS",
        "https://one.example/termux-main; https://two.example/termux-main/; https://one.example/termux-main",
    )

    monkeypatch.delenv("HERMES_TERMUX_MAIN_BASE_URL", raising=False)
    urls = linux_asset_script.configured_termux_main_base_urls()
    assert urls[:2] == [
        "https://one.example/termux-main",
        "https://two.example/termux-main",
    ]
    assert len(urls) == len(set(urls))
    assert (
        linux_asset_script._termux_main_url("https://one.example/termux-main", linux_asset_script._packages_index_path("x86_64"))
        == "https://one.example/termux-main/dists/stable/main/binary-x86_64/Packages"
    )
    assert (
        linux_asset_script._termux_main_url("https://one.example/termux-main", "/pool/main/bash.deb")
        == "https://one.example/termux-main/pool/main/bash.deb"
    )
def test_android_linux_asset_json_writers_use_lf_newlines(tmp_path):
    lock_file = tmp_path / "termux.lock.json"
    manifest_file = tmp_path / "manifest.json"
    payload = {
        "version": 1,
        "architectures": {},
    }

    write_lock_file(lock_file, payload)
    write_manifest(manifest_file, {"android_abi": "x86_64", "packages": []})

    assert b"\r\n" not in lock_file.read_bytes()
    assert b"\r\n" not in manifest_file.read_bytes()


def test_linux_asset_manifest_normalizes_windows_link_targets():
    manifest = serializable_manifest(
        "arm64-v8a",
        packages=[],
        links=[
            {"path": "lib\\libreadline.so.8", "target": "lib\\libreadline.so.8.3"},
            {"path": "/bin\\sh", "target": "bin\\busybox"},
        ],
    )

    assert manifest["links"] == [
        {"path": "bin/sh", "target": "bin/busybox"},
        {"path": "lib/libreadline.so.8", "target": "lib/libreadline.so.8.3"},
    ]


def test_prepare_android_linux_assets_mirrors_absolute_termux_symlinks(tmp_path):
    archive = BytesIO()
    with tarfile.open(fileobj=archive, mode="w") as tar:
        directory = tarfile.TarInfo("./data/data/com.termux/files/usr/bin")
        directory.type = tarfile.DIRTYPE
        tar.addfile(directory)

        payload = b"#!/data/data/com.termux/files/usr/bin/bash\necho ok\n"
        file_info = tarfile.TarInfo("./data/data/com.termux/files/usr/bin/bzdiff")
        file_info.mode = 0o755
        file_info.size = len(payload)
        tar.addfile(file_info, BytesIO(payload))

        link_info = tarfile.TarInfo("./data/data/com.termux/files/usr/bin/bzcmp")
        link_info.type = tarfile.SYMTYPE
        link_info.linkname = "/data/data/com.termux/files/usr/bin/bzdiff"
        tar.addfile(link_info)

    archive.seek(0)
    prefix = tmp_path / "prefix"
    with tarfile.open(fileobj=archive, mode="r:") as tar:
        links = mirror_data_tar(tar, prefix)

    assert (prefix / "bin" / "bzdiff").read_text(encoding="utf-8") == "#!/usr/bin/env bash\necho ok\n"
    assert not (prefix / "bin" / "bzcmp").exists()
    assert links == [{"path": "bin/bzcmp", "target": "bin/bzdiff"}]


def test_prepare_android_linux_assets_creates_bionic_llama_server_copy(tmp_path):
    bin_dir = tmp_path / "prefix" / "bin"
    bin_dir.mkdir(parents=True)
    source = bin_dir / "llama-server"
    source.write_bytes(b"ELF...libandroid-spawn.so\0...")
    source.chmod(0o755)

    create_bionic_llama_server_launcher(tmp_path / "prefix")

    bionic = bin_dir / "llama-server-bionic"
    assert bionic.is_file()
    payload = bionic.read_bytes()
    assert b"libandroid-spawn.so\0" not in payload
    assert b"libc.so\0" in payload


def test_proot_distro_direct_exec_patch_is_exact_guarded_and_idempotent(tmp_path, monkeypatch):
    relative = "lib/python3.14/site-packages/proot_distro/commands/login/__init__.py"
    module = tmp_path / "prefix" / relative
    module.parent.mkdir(parents=True)
    source = (
        b"import os\nimport shutil\n"
        b"def command():\n"
        b'    proot_bin = shutil.which("proot") or "proot"\n'
    )
    updated = source.replace(
        linux_asset_script.PROOT_LOOKUP_EXPRESSION,
        linux_asset_script.PROOT_DIRECT_EXEC_REPLACEMENT,
    )
    module.write_bytes(source)
    monkeypatch.setattr(
        linux_asset_script,
        "PROOT_DISTRO_DIRECT_EXEC_PATCHES",
        {
            relative: {
                "source_sha256": hashlib.sha256(source).hexdigest(),
                "patched_sha256": hashlib.sha256(updated).hexdigest(),
            }
        },
    )

    assert patch_proot_distro_direct_execution(tmp_path / "prefix") == [relative]
    assert module.read_bytes() == updated
    assert updated.count(linux_asset_script.PROOT_DIRECT_EXEC_REPLACEMENT) == 1
    assert patch_proot_distro_direct_execution(tmp_path / "prefix") == [relative]
    assert module.read_bytes() == updated


def test_proot_distro_direct_exec_patch_rejects_unpinned_source(tmp_path, monkeypatch):
    relative = "lib/python3.14/site-packages/proot_distro/commands/login/__init__.py"
    module = tmp_path / "prefix" / relative
    module.parent.mkdir(parents=True)
    module.write_bytes(b"import os\n# unexpected upstream source\n")
    monkeypatch.setattr(
        linux_asset_script,
        "PROOT_DISTRO_DIRECT_EXEC_PATCHES",
        {
            relative: {
                "source_sha256": "0" * 64,
                "patched_sha256": "1" * 64,
            }
        },
    )

    with pytest.raises(RuntimeError, match="source hash changed"):
        patch_proot_distro_direct_execution(tmp_path / "prefix")


def test_android_linux_subsystem_recreates_windows_manifest_links():
    bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesLinuxSubsystemBridge.kt"
    ).read_text(encoding="utf-8")

    assert "normalizeAssetRelativePath(item.optString(\"path\"))" in bridge
    assert "normalizeAssetRelativePath(item.optString(\"target\"))" in bridge
    assert ".replace('\\\\', '/')" in bridge


def test_android_linux_subsystem_retries_after_app_update():
    bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesLinuxSubsystemBridge.kt"
    ).read_text(encoding="utf-8")

    assert 'state.optLong("app_version_code", -1L) != currentAppVersionCode' in bridge
    assert 'put("app_version_code", currentAppVersionCode)' in bridge
    assert 'state.optString("asset_manifest_sha256") != currentAssetFingerprint' in bridge
    assert 'put("asset_manifest_sha256", currentAssetFingerprint)' in bridge
    assert 'state.optInt("runtime_layout_version", 0) != RUNTIME_LAYOUT_VERSION' in bridge
    assert 'put("runtime_layout_version", RUNTIME_LAYOUT_VERSION)' in bridge
    assert 'state.optString("native_library_dir") != currentNativeLibraryDir' in bridge
    assert 'state.optString("execution_mode") == SYSTEM_SHELL_MODE' not in bridge
    assert "Embedded Linux assets unavailable" in bridge
    assert '"HERMES_ANDROID_SHELL" to SYSTEM_SHELL_PATH' in bridge
    assert '"HERMES_ANDROID_NATIVE_SHELL" to state.optString("shell_path")' in bridge
    assert '"HERMES_ANDROID_LINUX_BASH" to state.optString("shell_path").ifBlank { SYSTEM_SHELL_PATH }' in bridge
    assert "private fun appVersionCode(context: Context): Long" in bridge


def test_android_linux_subsystem_records_embedded_fallback_reason():
    bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesLinuxSubsystemBridge.kt"
    ).read_text(encoding="utf-8")
    llama = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/backend/LlamaCppServerController.kt"
    ).read_text(encoding="utf-8")

    assert "private data class ShellLaunchProbe" in bridge
    assert 'put("fallback_reason", fallbackReason.take(1200))' in bridge
    assert "llama.cpp executable is not available at $llamaServerPath" in llama


def test_android_gguf_launchers_use_extracted_prefix_directory():
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesLinuxSubsystemBridge.kt"
    ).read_text(encoding="utf-8")
    llama = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/backend/LlamaCppServerController.kt"
    ).read_text(encoding="utf-8")
    native_script = (REPO_ROOT / "scripts/prepare_android_native_libs.py").read_text(encoding="utf-8")

    assert "scripts/prepare_android_native_libs.py" in gradle
    assert "libhermes_android_bash.so" in native_script
    assert "libhermes_android_llama_server.so" in native_script
    assert "libhermes_android_llama_server_bionic_spawn.so" in native_script
    assert "llama-server-bionic" in bridge
    assert 'put("shell_path", bashPath)' in bridge
    assert 'put("native_llama_server_path", llamaServerPath)' in bridge
    assert 'put("bionic_llama_server_path", bionicLlamaServerPath)' in bridge
    assert 'put("native_library_dir", context.applicationInfo.nativeLibraryDir.orEmpty())' in bridge
    assert 'optString("native_llama_server_path").ifBlank { "llama-server" }' in llama
    assert 'optString("bionic_llama_server_path")' in llama
    assert 'execution_mode") == "android_system_shell"' in llama
    assert "selectLlamaServerPath(context, linuxState)" in llama
    assert "ANDROID_16K_PAGE_SIZE_BYTES" in llama
    assert ".readTimeout(750, TimeUnit.MILLISECONDS)" in llama
    assert "LLAMA_CPP_READY_CHECKS = 720" in llama
    assert "--no-warmup" in llama
