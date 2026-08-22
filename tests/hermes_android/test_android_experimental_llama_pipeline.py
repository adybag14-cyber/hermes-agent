import hashlib
import json
import tarfile
from pathlib import Path
from types import SimpleNamespace

import pytest

import scripts.prepare_android_experimental_llama_server as experimental


REPO_ROOT = Path(__file__).resolve().parents[2]
LOCK_FILE = REPO_ROOT / "hermes_android" / "experimental_llama_server.lock.json"


def test_experimental_llama_lock_pins_source_toolchain_and_capabilities():
    lock = experimental.load_lock_file(LOCK_FILE)

    source = lock["source"]
    assert source["repository"].startswith("https://github.com/")
    assert source["archive_url"].endswith(source["commit"])
    assert experimental.COMMIT_PATTERN.fullmatch(source["commit"])
    assert experimental.SHA256_PATTERN.fullmatch(source["archive_sha256"])
    assert source["archive_size_bytes"] > 0
    assert source["license"] == "MIT"
    assert lock["android"]["ndk_version"]
    assert lock["android"]["minimum_api"] == 24
    assert lock["android"]["abis"] == ["arm64-v8a", "x86_64"]
    assert lock["android"]["maximum_parallel_jobs"] == 12
    assert lock["android"]["minimum_load_alignment_bytes"] == 16384
    assert lock["toolchain"] == {
        "android_ndk_package": "ndk;29.0.14206865",
        "android_cmake_package": "cmake;3.31.6",
        "cmake_version": "3.31.6",
        "ninja_version": "1.12.1",
    }
    assert lock["build"]["cmake_defines"]["BUILD_SHARED_LIBS"] == "OFF"
    assert lock["build"]["cmake_defines"]["ANDROID_STL"] == "c++_static"
    assert lock["build"]["cmake_defines"]["LLAMA_BUILD_NUMBER"].isdigit()
    assert lock["build"]["cmake_defines"]["LLAMA_BUILD_COMMIT"] == lock["source"]["commit"]
    assert lock["build"]["source_date_epoch"] == 315532800
    assert lock["artifact"]["packaged_filename"] == "libhermes_android_llama_server_experimental.so"
    expected_licenses = {
        "LICENSE": (
            "hermes-experimental-llama/LICENSE.txt",
            1078,
            "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d",
        ),
        "licenses/LICENSE-jsonhpp": (
            "hermes-experimental-llama/licenses/LICENSE-jsonhpp.txt",
            1075,
            "c0d068392ea65358b798b8c165103560f06e9e3b38c4ab4e2d8810a7b931af86",
        ),
        "vendor/cpp-httplib/LICENSE": (
            "hermes-experimental-llama/licenses/LICENSE-cpp-httplib.txt",
            1075,
            "4b45cbe16d7b71b89ae6127e26e0d90a029198ca5e958ad8e3d0b8bbed364d8b",
        ),
    }
    assert {
        artifact["source_path"]: (
            artifact["packaged_asset_path"],
            artifact["size_bytes"],
            artifact["sha256"],
        )
        for artifact in lock["license_artifacts"]
    } == expected_licenses
    assert all(
        experimental.SHA256_PATTERN.fullmatch(artifact["sha256"])
        for artifact in lock["license_artifacts"]
    )
    assert "nanbeige" in lock["capabilities"]["model_architectures"]
    assert "q5_0" in lock["capabilities"]["kv_cache_types"]
    assert "q5_1" in lock["capabilities"]["kv_cache_types"]
    assert "q4_0" in lock["capabilities"]["kv_cache_types"]
    assert "turbo3" in lock["capabilities"]["kv_cache_types"]
    assert lock["capabilities"]["turbo_cache_requires_flash_attention"] is True


def test_experimental_llama_lock_rejects_more_than_twelve_workers(tmp_path):
    payload = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    payload["android"]["maximum_parallel_jobs"] = 13
    invalid = tmp_path / "invalid.lock.json"
    invalid.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="may not exceed 12"):
        experimental.load_lock_file(invalid)


def test_experimental_llama_lock_rejects_toolchain_package_version_drift(tmp_path):
    payload = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    payload["toolchain"]["android_ndk_package"] = "ndk;28.2.13676358"
    invalid = tmp_path / "invalid-toolchain.lock.json"
    invalid.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="android_ndk_package must equal"):
        experimental.load_lock_file(invalid)


def test_locked_cmake_and_ninja_resolve_as_one_exact_android_sdk_package(
    tmp_path,
    monkeypatch,
):
    lock = experimental.load_lock_file(LOCK_FILE)
    suffix = ".exe" if experimental.os.name == "nt" else ""
    tool_dir = tmp_path / "Sdk" / "cmake" / lock["toolchain"]["cmake_version"] / "bin"
    tool_dir.mkdir(parents=True)
    cmake = tool_dir / f"cmake{suffix}"
    ninja = tool_dir / f"ninja{suffix}"
    cmake.write_bytes(b"fixture")
    ninja.write_bytes(b"fixture")
    monkeypatch.setattr(experimental, "android_sdk_candidates", lambda: iter((tmp_path / "Sdk",)))
    monkeypatch.setattr(
        experimental,
        "command_version",
        lambda command: (
            "cmake version 3.31.6" if command.name == f"cmake{suffix}" else "1.12.1"
        ),
    )

    resolved = experimental.resolve_locked_cmake_and_ninja(lock, None, None)

    assert resolved == (cmake.resolve(), ninja.resolve(), "cmake version 3.31.6", "1.12.1")


def test_locked_cmake_and_ninja_reject_version_mismatch_before_build(tmp_path, monkeypatch):
    lock = experimental.load_lock_file(LOCK_FILE)
    cmake = tmp_path / "cmake"
    ninja = tmp_path / "ninja"
    cmake.write_bytes(b"fixture")
    ninja.write_bytes(b"fixture")
    monkeypatch.setattr(
        experimental,
        "command_version",
        lambda command: "cmake version 4.2.1" if command == cmake.resolve() else "1.12.1",
    )

    with pytest.raises(RuntimeError, match="locked CMake version mismatch"):
        experimental.resolve_locked_cmake_and_ninja(lock, str(cmake), str(ninja))


def test_verified_archive_cache_never_redownloads_matching_bytes(tmp_path, monkeypatch):
    payload = b"immutable source archive"
    archive = tmp_path / "source.tar.gz"
    archive.write_bytes(payload)
    monkeypatch.setattr(
        experimental.urllib.request,
        "urlopen",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("network used")),
    )

    result = experimental.download_verified_archive(
        "https://example.invalid/source.tar.gz",
        archive,
        len(payload),
        hashlib.sha256(payload).hexdigest(),
    )

    assert result == archive
    assert result.read_bytes() == payload


def test_source_archive_path_validation_rejects_traversal():
    member = tarfile.TarInfo("../../outside")

    with pytest.raises(RuntimeError, match="unsafe source archive member path"):
        experimental._safe_archive_member(member)


def test_configured_build_identity_must_match_locked_number_and_commit(tmp_path):
    lock = experimental.load_lock_file(LOCK_FILE)
    build_info = tmp_path / "common" / "build-info.cpp"
    build_info.parent.mkdir(parents=True)
    build_info.write_text(
        "int LLAMA_BUILD_NUMBER = "
        + lock["build"]["cmake_defines"]["LLAMA_BUILD_NUMBER"]
        + ";\nchar const * LLAMA_COMMIT = \""
        + lock["source"]["commit"]
        + "\";\n",
        encoding="utf-8",
    )

    experimental.verify_configured_build_identity(tmp_path, lock)
    build_info.write_text("int LLAMA_BUILD_NUMBER = 0;\n", encoding="utf-8")
    with pytest.raises(RuntimeError, match="build number does not match"):
        experimental.verify_configured_build_identity(tmp_path, lock)


def test_locked_licenses_are_hash_verified_and_packaged_at_declared_paths(tmp_path):
    payload = b"pinned project MIT license\n"
    third_party_payload = b"pinned dependency MIT license\n"
    source = tmp_path / "source"
    (source / "vendor" / "dependency").mkdir(parents=True)
    license_path = source / "LICENSE"
    license_path.write_bytes(payload)
    third_party_license_path = source / "vendor" / "dependency" / "LICENSE"
    third_party_license_path.write_bytes(third_party_payload)
    lock = {
        "license_artifacts": [
            {
                "source_path": "LICENSE",
                "packaged_asset_path": "licenses/project.txt",
                "size_bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            },
            {
                "source_path": "vendor/dependency/LICENSE",
                "packaged_asset_path": "licenses/dependency.txt",
                "size_bytes": len(third_party_payload),
                "sha256": hashlib.sha256(third_party_payload).hexdigest(),
            },
        ]
    }

    verified = experimental.verify_locked_licenses(source, lock)
    assert verified == [
        (lock["license_artifacts"][0], license_path),
        (lock["license_artifacts"][1], third_party_license_path),
    ]
    assets = tmp_path / "assets"
    experimental.package_locked_licenses(verified, assets)
    assert (assets / "licenses" / "project.txt").read_bytes() == payload
    assert (assets / "licenses" / "dependency.txt").read_bytes() == third_party_payload

    license_path.write_bytes(payload + b"tampered")
    with pytest.raises(RuntimeError, match="size does not match"):
        experimental.verify_locked_licenses(source, lock)


def test_experimental_llama_lock_rejects_duplicate_packaged_license_paths(tmp_path):
    payload = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    payload["license_artifacts"][1]["packaged_asset_path"] = payload["license_artifacts"][0][
        "packaged_asset_path"
    ]
    invalid = tmp_path / "duplicate-license.lock.json"
    invalid.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="duplicate packaged license path"):
        experimental.load_lock_file(invalid)


def test_committed_compatibility_patch_is_hash_bound_and_preserves_both_metadata_paths():
    lock = experimental.load_lock_file(LOCK_FILE)
    patch_record = lock["source_patches"][0]
    patch_path = experimental.resolve_repository_file(REPO_ROOT, patch_record["path"])
    patch_bytes = patch_path.read_bytes()
    payload = patch_bytes.decode("utf-8")
    attributes = (REPO_ROOT / ".gitattributes").read_text(encoding="utf-8").splitlines()

    assert patch_path.stat().st_size == patch_record["size_bytes"]
    assert experimental.sha256_file(patch_path) == patch_record["sha256"]
    assert b"\r" not in patch_bytes
    assert "hermes_android/patches/*.patch text eol=lf" in attributes
    assert {record["path"] for record in patch_record["files"]} == {"src/models/nanbeige.cpp"}
    assert payload.count("diff --git ") == 1
    assert 'ml.get_key("nanbeige.loop_count"' in payload
    assert "has_num_loops && has_legacy_loop_count" in payload
    assert "conflicting Nanbeige num_loops and loop_count metadata" in payload
    assert "candidate_n_layer_phys" in payload
    assert "ml.get_tensor_meta(last_candidate.c_str()) != nullptr" in payload
    assert "ml.get_tensor_meta(first_after_candidate.c_str()) == nullptr" in payload
    assert "ml.get_tensor_meta(last_metadata_layer.c_str()) == nullptr" in payload
    assert "matches neither a logical nor physical block_count layout" in payload
    assert "src/llama-arch.cpp" not in payload
    assert "src/llama-arch.h" not in payload


def test_locked_patch_applier_checks_preimage_and_postimage(tmp_path):
    repository_root = tmp_path / "repository"
    patch_path = repository_root / "patches" / "change.patch"
    patch_path.parent.mkdir(parents=True)
    patch_path.write_text(
        "diff --git a/source.txt b/source.txt\n"
        "--- a/source.txt\n"
        "+++ b/source.txt\n"
        "@@ -1 +1 @@\n"
        "-old\n"
        "+new\n",
        encoding="utf-8",
        newline="\n",
    )
    source_dir = tmp_path / "source"
    source_dir.mkdir()
    source_file = source_dir / "source.txt"
    source_file.write_text("old\n", encoding="utf-8", newline="\n")
    source_sha256 = experimental.sha256_file(source_file)
    patched_sha256 = hashlib.sha256(b"new\n").hexdigest()
    lock = {
        "build": {"source_date_epoch": 315532800},
        "source_patches": [
            {
                "path": "patches/change.patch",
                "size_bytes": patch_path.stat().st_size,
                "sha256": experimental.sha256_file(patch_path),
                "purpose": "fixture",
                "files": [
                    {
                        "path": "source.txt",
                        "source_sha256": source_sha256,
                        "patched_sha256": patched_sha256,
                    }
                ],
            }
        ]
    }

    evidence = experimental.apply_locked_source_patches(
        source_dir=source_dir,
        lock=lock,
        git=experimental.resolve_host_tool(None, "git"),
        environment=experimental.deterministic_build_environment(lock),
        repository_root=repository_root,
    )

    assert source_file.read_text(encoding="utf-8") == "new\n"
    assert evidence[0]["files"][0]["patched_sha256"] == patched_sha256


def test_android_elf_validation_requires_system_dependencies_and_16k_alignment(tmp_path, monkeypatch):
    binary = tmp_path / "llama-server"
    binary.write_bytes(b"\x7fELF" + b"\0" * 32)

    def fake_run(command, **_kwargs):
        option = command[1]
        if option == "-hW":
            stdout = "  Type: DYN (Position-Independent Executable file)\n  Machine: AArch64\n"
        elif option == "-dW":
            stdout = (
                " 0x0000000000000001 (NEEDED) Shared library: [libm.so]\n"
                " 0x0000000000000001 (NEEDED) Shared library: [libc.so]\n"
            )
        elif option == "-lW":
            stdout = (
                "  LOAD 0x000000 0x000000 0x000000 0x001000 0x001000 R E 0x4000\n"
                "  LOAD 0x004000 0x004000 0x004000 0x001000 0x001000 RW  0x4000\n"
            )
        else:
            raise AssertionError(command)
        return SimpleNamespace(stdout=stdout, stderr="", returncode=0)

    monkeypatch.setattr(experimental.subprocess, "run", fake_run)

    evidence = experimental.verify_android_elf(
        binary,
        abi="arm64-v8a",
        readelf=tmp_path / "llvm-readelf",
        allowed_needed={"libc.so", "libdl.so", "libm.so"},
        minimum_load_alignment=16384,
    )

    assert evidence["needed_libraries"] == ["libc.so", "libm.so"]
    assert evidence["load_alignments_bytes"] == [16384, 16384]


def test_android_elf_validation_rejects_unpinned_runtime_library(tmp_path, monkeypatch):
    binary = tmp_path / "llama-server"
    binary.write_bytes(b"\x7fELF" + b"\0" * 32)

    def fake_run(command, **_kwargs):
        option = command[1]
        outputs = {
            "-hW": "  Type: DYN\n  Machine: Advanced Micro Devices X86-64\n",
            "-dW": " 0x1 (NEEDED) Shared library: [libc++_shared.so]\n",
            "-lW": "  LOAD 0 0 0 0 0 R E 0x4000\n",
        }
        return SimpleNamespace(stdout=outputs[option], stderr="", returncode=0)

    monkeypatch.setattr(experimental.subprocess, "run", fake_run)

    with pytest.raises(RuntimeError, match="non-system or unpinned DT_NEEDED"):
        experimental.verify_android_elf(
            binary,
            abi="x86_64",
            readelf=tmp_path / "llvm-readelf",
            allowed_needed={"libc.so", "libdl.so", "libm.so"},
            minimum_load_alignment=16384,
        )


def test_builder_never_trusts_generated_outputs_as_their_own_attestation():
    script = (REPO_ROOT / "scripts/prepare_android_experimental_llama_server.py").read_text(
        encoding="utf-8"
    )
    build_body = script.split("def build_experimental_server(", 1)[1].split("\ndef main()", 1)[0]

    assert "output_is_current" not in build_body
    assert "every executed\n    # task rebuilds from the hash-locked source and patch" in build_body
    assert build_body.index("resolve_locked_cmake_and_ninja") < build_body.index(
        "download_verified_archive"
    )
    assert '"toolchain": lock["toolchain"]' in build_body


def test_packaged_manifest_excludes_the_ambient_git_banner():
    script = (REPO_ROOT / "scripts/prepare_android_experimental_llama_server.py").read_text(
        encoding="utf-8"
    )
    build_body = script.split("def build_experimental_server(", 1)[1].split("\ndef main()", 1)[0]
    manifest_body = build_body.split("        manifest = {", 1)[1].split(
        "        manifest_bytes =", 1
    )[0]

    assert 'print(f"Using host Git patch tool (diagnostic only): {git_version}")' in build_body
    assert '"git_version"' not in manifest_body
    assert "command_version(git)" not in manifest_body


def test_build_environment_uses_locked_source_date_epoch_not_ambient(monkeypatch):
    lock = experimental.load_lock_file(LOCK_FILE)
    monkeypatch.setenv("SOURCE_DATE_EPOCH", "2147483647")

    environment = experimental.deterministic_build_environment(lock)

    assert environment["SOURCE_DATE_EPOCH"] == str(lock["build"]["source_date_epoch"])
    assert environment["TZ"] == "UTC"
    assert environment["LC_ALL"] == "C"


def test_output_replacement_refuses_nonempty_unowned_directory(tmp_path):
    output = tmp_path / "jniLibs"
    output.mkdir()
    (output / "user-file").write_text("preserve", encoding="utf-8")
    staged = tmp_path / "staged"
    staged.mkdir()

    with pytest.raises(RuntimeError, match="refusing to replace non-empty unowned output"):
        experimental.replace_owned_output(staged, output)

    assert (output / "user-file").read_text(encoding="utf-8") == "preserve"


def test_gradle_and_bridge_wire_unique_experimental_server_without_replacing_stable_lane():
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    bridge = (
        REPO_ROOT
        / "android/app/src/main/java/com/mobilefork/hermesagent/device/HermesLinuxSubsystemBridge.kt"
    ).read_text(encoding="utf-8")
    stable_native_script = (REPO_ROOT / "scripts/prepare_android_native_libs.py").read_text(encoding="utf-8")

    assert "prepareHermesAndroidExperimentalLlamaServer" in gradle
    assert "scripts/prepare_android_experimental_llama_server.py" in gradle
    assert "hermes_android/patches/llama_cpp_e306_legacy_nanbeige_loop_count.patch" in gradle
    assert "inputs.file(hermesExperimentalLlamaPatchFile)" in gradle
    assert "generated/hermes-experimental-llama-libs" in gradle
    assert "generated/hermes-experimental-llama-assets" in gradle
    assert "assets.srcDir(generatedHermesExperimentalLlamaAssetsDir)" in gradle
    assert "jniLibs.srcDir(generatedHermesExperimentalLlamaLibsDir)" in gradle
    assert "onlyIf { !skipHermesAndroidLinuxAssets }" in gradle
    assert "dependsOn(prepareHermesAndroidExperimentalLlamaServer)" in gradle
    assert 'val hermesExperimentalLlamaNdkVersion = "29.0.14206865"' in gradle
    assert "ndkVersion = hermesExperimentalLlamaNdkVersion" in gradle
    assert 'caches/hermes-experimental-llama/source' in gradle
    assert "fun experimentalLlamaServerPath(context: Context): String" in bridge
    assert 'put("experimental_llama_server_path", experimentalLlamaServerPath)' in bridge
    assert 'put("experimental_llama_server_path", experimentalLlamaServerPath(context))' in bridge
    assert "libhermes_android_llama_server_experimental.so" not in stable_native_script
    assert '"bin/llama-server": "libhermes_android_llama_server.so"' in stable_native_script


def test_android_build_workflows_install_the_locked_native_toolchain():
    lock = experimental.load_lock_file(LOCK_FILE)
    locked_ndk_package = lock["toolchain"]["android_ndk_package"]
    locked_cmake_package = lock["toolchain"]["android_cmake_package"]
    debug_workflow = (REPO_ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
    release_workflow = (REPO_ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")

    assert locked_ndk_package in debug_workflow
    assert locked_ndk_package in release_workflow
    assert locked_cmake_package in debug_workflow
    assert locked_cmake_package in release_workflow
