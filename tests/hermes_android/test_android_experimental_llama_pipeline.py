import errno
import hashlib
import json
import os
import stat
import tarfile
import threading
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
    assert experimental.NONDETERMINISTIC_ELF_SECTIONS == (
        ".note.gnu.build-id",
        ".comment",
    )


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


def test_host_cxx_compiler_prefers_real_gxx_from_host_path(tmp_path, monkeypatch):
    ndk = tmp_path / "Sdk" / "ndk" / "29.0.14206865"
    host_bin = tmp_path / "host-bin"
    host_bin.mkdir()
    gxx = host_bin / "g++"
    cxx = host_bin / "c++"
    gxx.write_bytes(b"host g++")
    cxx.write_bytes(b"host c++")
    observed = []

    def fake_which(name, *, path):
        observed.append((name, path))
        return str({"g++": gxx, "c++": cxx}.get(name)) if name in {"g++", "c++"} else None

    monkeypatch.setattr(experimental.shutil, "which", fake_which)

    resolved = experimental.resolve_host_cxx_compiler(ndk, {"PATH": str(host_bin)})

    assert resolved == gxx.resolve()
    assert observed == [("g++", str(host_bin))]


def test_host_cxx_compiler_rejects_missing_host_tool(tmp_path, monkeypatch):
    monkeypatch.setattr(experimental.shutil, "which", lambda _name, *, path: None)

    with pytest.raises(RuntimeError, match=r"real host C\+\+ compiler was not found"):
        experimental.resolve_host_cxx_compiler(tmp_path / "ndk", {"PATH": "/empty"})


def test_host_cxx_compiler_rejects_ndk_only_cross_compiler(tmp_path, monkeypatch):
    ndk = tmp_path / "Sdk" / "ndk" / "29.0.14206865"
    cross = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
    cross.parent.mkdir(parents=True)
    cross.write_bytes(b"Android cross compiler")
    monkeypatch.setattr(
        experimental.shutil,
        "which",
        lambda name, *, path: str(cross) if name == "clang++" else None,
    )

    with pytest.raises(RuntimeError, match="rejected Android cross compiler"):
        experimental.resolve_host_cxx_compiler(ndk, {"PATH": str(cross.parent)})


def test_configured_host_cxx_compiler_cache_must_match_exact_path(tmp_path):
    compiler = tmp_path / "host bin" / "g++"
    compiler.parent.mkdir()
    compiler.write_bytes(b"host compiler")
    build_dir = tmp_path / "build"
    build_dir.mkdir()
    cache = build_dir / "CMakeCache.txt"
    cache.write_text(
        f"HOST_CXX_COMPILER:FILEPATH={compiler.resolve()}\n",
        encoding="utf-8",
    )

    experimental.verify_configured_host_cxx_compiler(build_dir, compiler)
    cache.write_text(
        f"HOST_CXX_COMPILER:FILEPATH={(tmp_path / 'wrong-g++').resolve()}\n",
        encoding="utf-8",
    )
    with pytest.raises(RuntimeError, match="configured HOST_CXX_COMPILER mismatch"):
        experimental.verify_configured_host_cxx_compiler(build_dir, compiler)

    for invalid_type in ("STRING", "UNINITIALIZED"):
        cache.write_text(
            f"HOST_CXX_COMPILER:{invalid_type}={compiler.resolve()}\n",
            encoding="utf-8",
        )
        with pytest.raises(RuntimeError, match="must have FILEPATH type"):
            experimental.verify_configured_host_cxx_compiler(build_dir, compiler)

    cache.write_text(
        f"HOST_CXX_COMPILER:FILEPATH={compiler.resolve()}\n"
        f"HOST_CXX_COMPILER:STRING={compiler.resolve()}\n",
        encoding="utf-8",
    )
    with pytest.raises(RuntimeError, match="exactly one HOST_CXX_COMPILER entry"):
        experimental.verify_configured_host_cxx_compiler(build_dir, compiler)


def test_cmake_configuration_maps_random_source_and_build_roots_after_locked_defines(
    tmp_path,
    monkeypatch,
):
    lock = experimental.load_lock_file(LOCK_FILE)
    source_dir = tmp_path / "random source root"
    build_dir = tmp_path / "random build root"
    source_dir.mkdir()
    build_info = build_dir / "common" / "build-info.cpp"
    build_info.parent.mkdir(parents=True)
    build_info.write_text(
        "int LLAMA_BUILD_NUMBER = "
        + lock["build"]["cmake_defines"]["LLAMA_BUILD_NUMBER"]
        + ";\nchar const * LLAMA_COMMIT = \""
        + lock["source"]["commit"]
        + "\";\n",
        encoding="utf-8",
    )
    binary = build_dir / "bin" / "llama-server"
    binary.parent.mkdir(parents=True)
    binary.write_bytes(b"fixture")
    host_cxx = tmp_path / "host bin" / "g++"
    host_cxx.parent.mkdir()
    host_cxx.write_bytes(b"host compiler")
    (build_dir / "CMakeCache.txt").write_text(
        f"HOST_CXX_COMPILER:FILEPATH={host_cxx.resolve()}\n",
        encoding="utf-8",
    )
    commands = []

    def fake_run(command, **_kwargs):
        commands.append(command)
        return SimpleNamespace(stdout="", stderr="", returncode=0)

    monkeypatch.setattr(experimental.subprocess, "run", fake_run)

    result = experimental.configure_and_build_abi(
        lock=lock,
        source_dir=source_dir,
        build_dir=build_dir,
        abi="arm64-v8a",
        ndk_dir=tmp_path / "ndk",
        cmake=tmp_path / "cmake",
        ninja=tmp_path / "ninja",
        host_cxx_compiler=host_cxx,
        jobs=12,
        environment=experimental.deterministic_build_environment(lock),
    )

    assert result == binary
    configure = commands[0]
    options = experimental.deterministic_compiler_path_map_options(source_dir, build_dir)
    assert options == tuple(
        f"{option}={local_root}={canonical_root}"
        for local_root, canonical_root in (
            (source_dir.resolve().as_posix(), experimental.CANONICAL_SOURCE_PREFIX),
            (build_dir.resolve().as_posix(), experimental.CANONICAL_BUILD_PREFIX),
        )
        for option in experimental.PATH_PREFIX_MAP_OPTIONS
    )
    rendered_flags = experimental.cmake_compiler_flags(options)
    c_flags = f"-DCMAKE_C_FLAGS={rendered_flags}"
    cxx_flags = f"-DCMAKE_CXX_FLAGS={rendered_flags}"
    assert configure.count(c_flags) == 1
    assert configure.count(cxx_flags) == 1
    host_definition = f"-DHOST_CXX_COMPILER:FILEPATH={host_cxx}"
    assert configure.count(host_definition) == 1
    assert ";" not in rendered_flags
    for option in options:
        assert option in rendered_flags
    last_locked_define = max(
        configure.index(f"-D{key}={value}")
        for key, value in lock["build"]["cmake_defines"].items()
    )
    assert configure.index(f"-DANDROID_NDK={tmp_path / 'ndk'}") < configure.index(
        host_definition
    ) < min(
        configure.index(f"-D{key}={value}")
        for key, value in lock["build"]["cmake_defines"].items()
    )
    assert last_locked_define < configure.index(c_flags) < configure.index(cxx_flags)


@pytest.mark.parametrize("separator", ["/", "\\"])
def test_local_path_leak_scan_rejects_case_and_separator_variants(tmp_path, separator):
    root = tmp_path / "Random Build Root"
    root.mkdir()
    leaked = str(root.resolve()).replace("\\", separator).replace("/", separator).upper()
    binary = tmp_path / "llama-server"
    binary.write_bytes(b"ELF fixture\0" + leaked.encode("utf-8") + b"\0")

    with pytest.raises(RuntimeError, match="non-reproducible local build path"):
        experimental.verify_no_local_path_leaks(binary, (root,))


def test_local_path_leak_scan_accepts_only_canonical_prefixes(tmp_path):
    binary = tmp_path / "llama-server"
    binary.write_bytes(
        (experimental.CANONICAL_SOURCE_PREFIX + "\0" + experimental.CANONICAL_BUILD_PREFIX).encode(
            "utf-8"
        )
    )

    experimental.verify_no_local_path_leaks(
        binary,
        (tmp_path / "random-source", tmp_path / "random-build"),
    )


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


def test_android_elf_normalization_removes_host_metadata_and_proves_absence(
    tmp_path,
    monkeypatch,
):
    binary = tmp_path / "llama-server"
    binary.write_bytes(b"\x7fELF fixture")
    strip = tmp_path / "llvm-strip"
    readelf = tmp_path / "llvm-readelf"
    commands = []

    def fake_run(command, **_kwargs):
        commands.append(command)
        if command[0] == str(readelf):
            return SimpleNamespace(
                stdout=(
                    "There are 3 section headers:\n"
                    "  [ 1] .text PROGBITS 00000000 000040 000010 00 AX 0 0 16\n"
                    "  [ 2] .rodata PROGBITS 00000000 000050 000010 00 A 0 0 1\n"
                ),
                stderr="",
                returncode=0,
            )
        return SimpleNamespace(stdout="", stderr="", returncode=0)

    monkeypatch.setattr(experimental.subprocess, "run", fake_run)

    removed = experimental.normalize_android_elf_metadata(
        binary,
        strip=strip,
        readelf=readelf,
        environment={"LC_ALL": "C"},
    )

    assert removed == (".note.gnu.build-id", ".comment")
    assert commands[0] == [
        str(strip),
        "--strip-unneeded",
        "--remove-section=.note.gnu.build-id",
        "--remove-section=.comment",
        str(binary),
    ]
    assert commands[1] == [str(readelf), "-SW", str(binary)]


@pytest.mark.parametrize("surviving", [".note.gnu.build-id", ".comment"])
def test_android_elf_normalization_rejects_surviving_host_metadata(
    tmp_path,
    monkeypatch,
    surviving,
):
    binary = tmp_path / "llama-server"
    binary.write_bytes(b"\x7fELF fixture")

    def fake_run(command, **_kwargs):
        if command[1:2] == ["-SW"]:
            return SimpleNamespace(
                stdout=f"  [ 7] {surviving} NOTE 00000000 000100 000020 00 A 0 0 4\n",
                stderr="",
                returncode=0,
            )
        return SimpleNamespace(stdout="", stderr="", returncode=0)

    monkeypatch.setattr(experimental.subprocess, "run", fake_run)

    with pytest.raises(RuntimeError, match="retains non-reproducible ELF section"):
        experimental.normalize_android_elf_metadata(
            binary,
            strip=tmp_path / "llvm-strip",
            readelf=tmp_path / "llvm-readelf",
            environment={"LC_ALL": "C"},
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
    assert '"source": CANONICAL_SOURCE_PREFIX' in manifest_body
    assert '"build": CANONICAL_BUILD_PREFIX' in manifest_body
    assert "source_dir" not in manifest_body
    assert "build_dir" not in manifest_body


def test_local_path_scan_runs_after_metadata_normalization_and_before_elf_attestation():
    script = (REPO_ROOT / "scripts/prepare_android_experimental_llama_server.py").read_text(
        encoding="utf-8"
    )
    build_body = script.split("def build_experimental_server(", 1)[1].split("\ndef main()", 1)[0]

    assert build_body.index("normalize_android_elf_metadata") < build_body.index(
        "verify_no_local_path_leaks"
    ) < build_body.index("verify_android_elf")
    assert '"removed_elf_sections": list(NONDETERMINISTIC_ELF_SECTIONS)' in build_body


def test_build_environment_uses_locked_source_date_epoch_not_ambient(monkeypatch):
    lock = experimental.load_lock_file(LOCK_FILE)
    monkeypatch.setenv("SOURCE_DATE_EPOCH", "2147483647")

    environment = experimental.deterministic_build_environment(lock)

    assert environment["SOURCE_DATE_EPOCH"] == str(lock["build"]["source_date_epoch"])
    assert environment["TZ"] == "UTC"
    assert environment["LC_ALL"] == "C"


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _write_owned_output(
    root: Path,
    marker: str,
    *,
    kind: str = "jni",
    exist_ok: bool = False,
) -> Path:
    root.mkdir(parents=True, exist_ok=exist_ok)
    payload = marker.encode("utf-8")
    if kind == "jni":
        relative = "arm64-v8a/libhermes_experimental_llama_server.so"
        manifest_relative = experimental.MANIFEST_NAME
        manifest = {
            "schema_version": 1,
            "generated_by": experimental.MANIFEST_GENERATOR,
            "artifacts": {
                "arm64-v8a": {
                    "relative_path": relative,
                    "size_bytes": len(payload),
                    "sha256": _sha256_bytes(payload),
                }
            },
        }
    elif kind == "assets":
        relative = "licenses/project.txt"
        manifest_relative = experimental.PACKAGED_MANIFEST_ASSET
        manifest = {
            "schema_version": 1,
            "generated_by": experimental.MANIFEST_GENERATOR,
            "license_artifacts": [
                {
                    "source_path": "LICENSE",
                    "packaged_asset_path": relative,
                    "size_bytes": len(payload),
                    "sha256": _sha256_bytes(payload),
                }
            ],
        }
    else:
        raise AssertionError(f"unsupported owned-output fixture kind: {kind}")
    artifact = root / relative
    artifact.parent.mkdir(parents=True)
    artifact.write_bytes(payload)
    if kind == "jni":
        artifact.chmod(0o755)
    manifest_path = root / manifest_relative
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return artifact


def _transaction_residue(parent: Path) -> list[Path]:
    candidates = [
        *parent.glob(".*.transaction-*"),
        *parent.glob(".*.cleanup-*"),
        parent / ".hermes-experimental-llama-publication.lock",
    ]
    return sorted(path for path in candidates if path.exists() or path.is_symlink())


def test_output_replacement_refuses_nonempty_unowned_directory(tmp_path):
    output = tmp_path / "jniLibs"
    output.mkdir()
    (output / "user-file").write_text("preserve", encoding="utf-8")
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")

    with pytest.raises(RuntimeError, match="refusing to replace non-empty unowned output"):
        experimental.replace_owned_output(staged, output)

    assert (output / "user-file").read_text(encoding="utf-8") == "preserve"
    assert not _transaction_residue(tmp_path)


def test_output_replacement_copies_to_destination_filesystem_before_atomic_swap(
    tmp_path,
    monkeypatch,
):
    staged = tmp_path / "source-filesystem" / "staged"
    binary = _write_owned_output(staged, "new native bytes")
    output = tmp_path / "destination-filesystem" / "jniLibs"
    output.parent.mkdir()
    _write_owned_output(output, "old")
    real_replace = experimental.os.replace

    def simulated_cross_device_replace(source, destination):
        if Path(source) == staged:
            raise OSError(errno.EXDEV, "simulated cross-device rename")
        return real_replace(source, destination)

    monkeypatch.setattr(experimental.os, "replace", simulated_cross_device_replace)

    experimental.replace_owned_output(staged, output)

    published = output / binary.relative_to(staged)
    assert published.read_bytes() == b"new native bytes"
    assert stat.S_IMODE(published.stat().st_mode) == 0o755
    assert staged.is_dir()
    assert not _transaction_residue(output.parent)


def test_bound_timestamp_update_uses_native_windows_descriptor_handle(tmp_path, monkeypatch):
    source = tmp_path / "source"
    source.write_bytes(b"source")
    os.utime(source, ns=(946684800000000000, 946684801000000000))
    destination = tmp_path / "destination"
    destination.write_bytes(b"destination")
    descriptor = os.open(
        destination,
        os.O_RDWR | getattr(os, "O_BINARY", 0),
    )
    calls = []

    def fake_windows_set_times(open_descriptor, timestamps):
        calls.append((open_descriptor, timestamps))

    monkeypatch.setattr(experimental.os, "name", "nt")
    monkeypatch.setattr(
        experimental.os,
        "utime",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("path utime called")),
    )
    monkeypatch.setattr(experimental, "_set_windows_file_times_bound", fake_windows_set_times)
    try:
        source_stat = source.stat()
        experimental._set_file_times_bound(destination, descriptor, source_stat)
    finally:
        os.close(descriptor)

    assert calls == [(descriptor, (source_stat.st_atime_ns, source_stat.st_mtime_ns))]
    assert destination.read_bytes() == b"destination"


def test_output_replacement_accepts_an_existing_empty_output(tmp_path):
    staged = tmp_path / "staged"
    artifact = _write_owned_output(staged, "new")
    output = tmp_path / "output"
    output.mkdir()

    experimental.replace_owned_output(staged, output)

    assert (output / artifact.relative_to(staged)).read_bytes() == b"new"
    assert not _transaction_residue(tmp_path)


@pytest.mark.parametrize("unsafe_kind", ["symlink", "fifo"])
def test_output_replacement_rejects_staged_link_and_special_file(
    tmp_path,
    unsafe_kind,
):
    staged = tmp_path / "staged"
    staged.mkdir()
    unsafe = staged / "unsafe"
    if unsafe_kind == "symlink":
        target = tmp_path / "target"
        target.write_text("target", encoding="utf-8")
        unsafe.symlink_to(target)
    else:
        if not hasattr(os, "mkfifo"):
            pytest.skip("POSIX FIFO support required")
        os.mkfifo(unsafe)

    with pytest.raises(RuntimeError, match="contains a (?:link or reparse point|special file)"):
        experimental.replace_owned_output(staged, tmp_path / "output")


def test_output_replacement_rejects_a_staged_root_symlink(tmp_path):
    real_staged = tmp_path / "real-staged"
    _write_owned_output(real_staged, "new")
    staged = tmp_path / "staged-link"
    staged.symlink_to(real_staged, target_is_directory=True)

    with pytest.raises(RuntimeError, match="must be an ordinary non-link directory"):
        experimental.replace_owned_output(staged, tmp_path / "output")


def test_output_replacement_rejects_broken_output_symlink(tmp_path):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    output = tmp_path / "output"
    output.symlink_to(tmp_path / "missing-target", target_is_directory=True)

    with pytest.raises(RuntimeError, match="must be an ordinary non-link directory"):
        experimental.replace_owned_output(staged, output)

    assert output.is_symlink()


def test_output_replacement_rejects_a_linked_output_ancestor(tmp_path):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    real_parent = tmp_path / "real-parent"
    real_parent.mkdir()
    linked_parent = tmp_path / "linked-parent"
    linked_parent.symlink_to(real_parent, target_is_directory=True)
    output = linked_parent / "output"

    with pytest.raises(RuntimeError, match="contains a link, reparse point, or non-directory"):
        experimental.replace_owned_output(staged, output)

    assert linked_parent.is_symlink()
    assert not output.exists()


def test_windows_reparse_attribute_is_treated_as_an_unsafe_link(monkeypatch):
    monkeypatch.setattr(
        experimental.stat,
        "FILE_ATTRIBUTE_REPARSE_POINT",
        0x400,
        raising=False,
    )
    fake_stat = SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0x400)

    assert experimental._unsafe_link_or_reparse(fake_stat)


def test_output_replacement_preserves_old_owned_output_on_copy_failure(tmp_path, monkeypatch):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    output = tmp_path / "output"
    old_artifact = _write_owned_output(output, "old")

    def fail_copy(_source, destination, _snapshot):
        destination.mkdir()
        (destination / "partial").write_text("partial", encoding="utf-8")
        raise OSError("copy failed")

    monkeypatch.setattr(experimental, "_copy_validated_tree", fail_copy)

    with pytest.raises(OSError, match="copy failed"):
        experimental.replace_owned_output(staged, output)

    assert old_artifact.read_bytes() == b"old"
    assert not _transaction_residue(tmp_path)


def test_second_preparation_failure_cleans_the_first_transaction(tmp_path, monkeypatch):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    _write_owned_output(staged_jni, "new-jni")
    _write_owned_output(staged_assets, "new-assets", kind="assets")
    output_jni = tmp_path / "generated-jni" / "jniLibs"
    output_assets = tmp_path / "generated-assets" / "assets"
    old_jni = _write_owned_output(output_jni, "old-jni")
    old_assets = _write_owned_output(output_assets, "old-assets", kind="assets")
    real_copy = experimental._copy_validated_tree
    calls = 0

    def fail_second_copy(source, destination, snapshot):
        nonlocal calls
        calls += 1
        if calls == 2:
            destination.mkdir()
            raise OSError("second copy failed")
        return real_copy(source, destination, snapshot)

    monkeypatch.setattr(experimental, "_copy_validated_tree", fail_second_copy)

    with pytest.raises(OSError, match="second copy failed"):
        experimental.replace_owned_outputs(
            (
                (staged_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                (staged_assets, output_assets, Path(experimental.PACKAGED_MANIFEST_ASSET)),
            )
        )

    assert old_jni.read_bytes() == b"old-jni"
    assert old_assets.read_bytes() == b"old-assets"
    assert not _transaction_residue(output_jni.parent)
    assert not _transaction_residue(output_assets.parent)


def test_output_replacement_restores_old_owned_output_on_swap_failure(tmp_path, monkeypatch):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    output = tmp_path / "output"
    old_artifact = _write_owned_output(output, "old")
    real_replace = experimental.os.replace

    def fail_incoming_swap(source, destination):
        if Path(source).name == "incoming" and Path(destination) == output:
            raise OSError("swap failed")
        return real_replace(source, destination)

    monkeypatch.setattr(experimental.os, "replace", fail_incoming_swap)

    with pytest.raises(OSError, match="swap failed"):
        experimental.replace_owned_output(staged, output)

    assert old_artifact.read_bytes() == b"old"
    assert not _transaction_residue(tmp_path)


def test_second_output_swap_failure_restores_both_owned_outputs(tmp_path, monkeypatch):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    _write_owned_output(staged_jni, "new-jni")
    _write_owned_output(staged_assets, "new-assets", kind="assets")
    output_jni = tmp_path / "generated-jni" / "jniLibs"
    output_assets = tmp_path / "generated-assets" / "assets"
    old_jni = _write_owned_output(output_jni, "old-jni")
    old_assets = _write_owned_output(output_assets, "old-assets", kind="assets")
    real_replace = experimental.os.replace

    def fail_second_publish(source, destination):
        if Path(source).name == "incoming" and Path(destination) == output_assets:
            raise OSError("second publish failed")
        return real_replace(source, destination)

    monkeypatch.setattr(experimental.os, "replace", fail_second_publish)

    with pytest.raises(OSError, match="second publish failed"):
        experimental.replace_owned_outputs(
            (
                (staged_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                (staged_assets, output_assets, Path(experimental.PACKAGED_MANIFEST_ASSET)),
            )
        )

    assert old_jni.read_bytes() == b"old-jni"
    assert old_assets.read_bytes() == b"old-assets"
    assert not _transaction_residue(output_jni.parent)
    assert not _transaction_residue(output_assets.parent)


def test_cleanup_failure_after_commit_never_rolls_back_verified_outputs(tmp_path, monkeypatch):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    new_jni = _write_owned_output(staged_jni, "new-jni")
    new_assets = _write_owned_output(staged_assets, "new-assets", kind="assets")
    output_jni = tmp_path / "generated-jni" / "jniLibs"
    output_assets = tmp_path / "generated-assets" / "assets"
    _write_owned_output(output_jni, "old-jni")
    _write_owned_output(output_assets, "old-assets", kind="assets")
    real_cleanup = experimental._cleanup_transaction_tree
    calls = 0

    def fail_second_cleanup(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("cleanup failed")
        return real_cleanup(*args, **kwargs)

    monkeypatch.setattr(experimental, "_cleanup_transaction_tree", fail_second_cleanup)

    with pytest.raises(RuntimeError, match="outputs committed, but transaction cleanup failed"):
        experimental.replace_owned_outputs(
            (
                (staged_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                (staged_assets, output_assets, Path(experimental.PACKAGED_MANIFEST_ASSET)),
            )
        )

    assert (output_jni / new_jni.relative_to(staged_jni)).read_bytes() == b"new-jni"
    assert (output_assets / new_assets.relative_to(staged_assets)).read_bytes() == b"new-assets"
    assert not (output_jni.parent / ".hermes-experimental-llama-publication.lock").exists()
    assert not (output_assets.parent / ".hermes-experimental-llama-publication.lock").exists()


def test_rollback_ambiguity_does_not_prevent_other_output_restoration(tmp_path, monkeypatch):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    new_jni = _write_owned_output(staged_jni, "new-jni")
    _write_owned_output(staged_assets, "new-assets", kind="assets")
    output_jni = tmp_path / "generated-jni" / "jniLibs"
    output_assets = tmp_path / "generated-assets" / "assets"
    _write_owned_output(output_jni, "old-jni")
    old_assets = _write_owned_output(output_assets, "old-assets", kind="assets")
    real_replace = experimental.os.replace

    def tamper_then_fail(source, destination):
        if Path(source).name == "incoming" and Path(destination) == output_assets:
            (output_jni / new_jni.relative_to(staged_jni)).write_bytes(b"tampered")
            raise OSError("second publish failed")
        return real_replace(source, destination)

    monkeypatch.setattr(experimental.os, "replace", tamper_then_fail)

    with pytest.raises(RuntimeError, match="ambiguous experimental llama publication rollback"):
        experimental.replace_owned_outputs(
            (
                (staged_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                (staged_assets, output_assets, Path(experimental.PACKAGED_MANIFEST_ASSET)),
            )
        )

    assert old_assets.read_bytes() == b"old-assets"
    assert (output_jni / new_jni.relative_to(staged_jni)).read_bytes() == b"tampered"
    assert list(output_jni.parent.glob(".jniLibs.transaction-*"))


def test_final_commit_sweep_detects_change_after_all_immediate_verifications(
    tmp_path,
    monkeypatch,
):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    new_jni = _write_owned_output(staged_jni, "new-jni")
    _write_owned_output(staged_assets, "new-assets", kind="assets")
    output_jni = tmp_path / "generated-jni" / "jniLibs"
    output_assets = tmp_path / "generated-assets" / "assets"
    _write_owned_output(output_jni, "old-jni")
    old_assets = _write_owned_output(output_assets, "old-assets", kind="assets")
    real_matches = experimental._snapshot_matches_owned
    published_checks = 0

    def tamper_after_immediate_checks(path, manifest, expected, label):
        nonlocal published_checks
        result = real_matches(path, manifest, expected, label)
        if label == "published output":
            published_checks += 1
            if published_checks == 2:
                (output_jni / new_jni.relative_to(staged_jni)).write_bytes(b"late-tamper")
        return result

    monkeypatch.setattr(experimental, "_snapshot_matches_owned", tamper_after_immediate_checks)

    with pytest.raises(RuntimeError, match="ambiguous experimental llama publication rollback"):
        experimental.replace_owned_outputs(
            (
                (staged_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                (staged_assets, output_assets, Path(experimental.PACKAGED_MANIFEST_ASSET)),
            )
        )

    assert published_checks == 2
    assert old_assets.read_bytes() == b"old-assets"
    assert (output_jni / new_jni.relative_to(staged_jni)).read_bytes() == b"late-tamper"


@pytest.mark.parametrize(
    "mutation",
    [
        "wrong-schema",
        "wrong-generator",
        "extra-file",
        "missing-file",
        "bad-size",
        "bad-digest",
        "duplicate-path",
        "case-collision",
        "escaping-path",
        "manifest-collision",
        "malformed-record",
    ],
)
def test_output_replacement_rejects_unclosed_or_malformed_owned_inventory(tmp_path, mutation):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    output = tmp_path / "output"
    artifact = _write_owned_output(output, "old")
    manifest_path = output / experimental.MANIFEST_NAME
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    record = manifest["artifacts"]["arm64-v8a"]
    if mutation == "wrong-schema":
        manifest["schema_version"] = 2
    elif mutation == "wrong-generator":
        manifest["generated_by"] = "forged"
    elif mutation == "extra-file":
        (output / "extra").write_text("extra", encoding="utf-8")
    elif mutation == "missing-file":
        artifact.unlink()
    elif mutation == "bad-size":
        record["size_bytes"] += 1
    elif mutation == "bad-digest":
        record["sha256"] = "0" * 64
    elif mutation == "duplicate-path":
        manifest["artifacts"]["x86_64"] = dict(record)
    elif mutation == "case-collision":
        duplicate = dict(record)
        duplicate["relative_path"] = record["relative_path"].upper()
        manifest["artifacts"]["x86_64"] = duplicate
    elif mutation == "escaping-path":
        record["relative_path"] = "../escape"
    elif mutation == "manifest-collision":
        record["relative_path"] = experimental.MANIFEST_NAME
    elif mutation == "malformed-record":
        manifest["artifacts"]["arm64-v8a"] = []
    if mutation not in {"extra-file", "missing-file"}:
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(RuntimeError):
        experimental.replace_owned_output(staged, output)

    assert output.is_dir()
    assert not _transaction_residue(tmp_path)


@pytest.mark.parametrize("nested_first", [True, False])
def test_output_replacement_rejects_same_or_nested_destinations(tmp_path, nested_first):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    _write_owned_output(staged_jni, "new-jni")
    _write_owned_output(staged_assets, "new-assets", kind="assets")
    output = tmp_path / "output"
    other = output / "nested" if nested_first else output
    first = output if nested_first else output

    with pytest.raises(RuntimeError, match="distinct and non-overlapping"):
        experimental.replace_owned_outputs(
            (
                (staged_jni, first, Path(experimental.MANIFEST_NAME)),
                (staged_assets, other, Path(experimental.PACKAGED_MANIFEST_ASSET)),
            )
        )


def test_output_replacement_rejects_staged_and_destination_overlap(tmp_path):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")

    with pytest.raises(RuntimeError, match="staged and published.*must not overlap"):
        experimental.replace_owned_output(staged, staged / "nested-output")


def test_transaction_cleanup_preserves_a_replaced_root(tmp_path):
    transaction, inode = experimental._new_transaction_root(tmp_path, "output")
    original = tmp_path / "original-transaction"
    transaction.rename(original)
    replacement = transaction
    replacement.mkdir()
    (replacement / "unrelated").write_text("preserve", encoding="utf-8")

    with pytest.raises(RuntimeError, match="identity changed; preserving"):
        experimental._cleanup_transaction_tree(
            replacement,
            tmp_path,
            ".output.transaction-",
            inode,
        )

    assert (replacement / "unrelated").read_text(encoding="utf-8") == "preserve"
    assert original.is_dir()


def test_transaction_root_creation_failure_removes_the_exact_empty_root(tmp_path, monkeypatch):
    real_chmod = experimental.os.chmod

    def fail_transaction_chmod(path, mode):
        if ".output.transaction-" in Path(path).name:
            raise OSError("transaction chmod failed")
        return real_chmod(path, mode)

    monkeypatch.setattr(experimental.os, "chmod", fail_transaction_chmod)

    with pytest.raises(OSError, match="transaction chmod failed"):
        experimental._new_transaction_root(tmp_path, "output")

    assert not list(tmp_path.glob(".output.transaction-*"))


def test_publication_lock_setup_failure_releases_the_created_lock(tmp_path, monkeypatch):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    output = tmp_path / "output"
    _write_owned_output(output, "old")
    real_chmod = experimental.os.chmod

    def fail_lock_chmod(path, mode):
        if Path(path).name == ".hermes-experimental-llama-publication.lock":
            raise OSError("lock chmod failed")
        return real_chmod(path, mode)

    monkeypatch.setattr(experimental.os, "chmod", fail_lock_chmod)

    with pytest.raises(OSError, match="lock chmod failed"):
        experimental.replace_owned_output(staged, output)

    assert not _transaction_residue(tmp_path)


def test_transaction_cleanup_does_not_use_recursive_path_deletion(tmp_path, monkeypatch):
    staged = tmp_path / "staged"
    artifact = _write_owned_output(staged, "new")
    output = tmp_path / "output"
    _write_owned_output(output, "old")
    monkeypatch.setattr(
        experimental.shutil,
        "rmtree",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("unsafe rmtree")),
    )

    experimental.replace_owned_output(staged, output)

    assert (output / artifact.relative_to(staged)).read_bytes() == b"new"
    assert not _transaction_residue(tmp_path)


@pytest.mark.skipif(os.name != "posix", reason="POSIX dirfd/openat regression")
def test_transaction_cleanup_never_follows_a_swapped_intermediate_directory(
    tmp_path,
    monkeypatch,
):
    holder = tmp_path / "holder"
    tree = holder / "tree"
    nested = tree / "nested"
    nested.mkdir(parents=True)
    (nested / "file").write_bytes(b"matching bytes")
    external = tmp_path / "external"
    external.mkdir()
    external_file = external / "file"
    external_file.write_bytes(b"matching bytes")
    expected = experimental._validated_tree_snapshot(tree, "captured cleanup tree")
    holder_stat = holder.lstat()
    holder_inode = (holder_stat.st_dev, holder_stat.st_ino)
    real_snapshot = experimental._validated_tree_snapshot
    swapped = False

    def swap_after_snapshot(path, label):
        nonlocal swapped
        result = real_snapshot(path, label)
        if Path(path) == tree and label == "transaction deletion tree" and not swapped:
            swapped = True
            nested.rename(tree / "captured-nested")
            nested.symlink_to(external, target_is_directory=True)
        return result

    monkeypatch.setattr(experimental, "_validated_tree_snapshot", swap_after_snapshot)

    with pytest.raises((OSError, RuntimeError)):
        experimental._remove_validated_tree_nofollow(
            tree,
            expected,
            holder_inode,
            stat.S_IMODE(holder_stat.st_mode),
        )

    assert swapped
    assert external_file.read_bytes() == b"matching bytes"
    assert (tree / "captured-nested/file").read_bytes() == b"matching bytes"


def test_main_keeps_output_link_lexical_for_publication_rejection(tmp_path, monkeypatch):
    target = tmp_path / "target"
    target.mkdir()
    output_link = tmp_path / "output-link"
    output_link.symlink_to(target, target_is_directory=True)
    assets_output = tmp_path / "assets"
    captured = {}

    def capture_build(**kwargs):
        captured.update(kwargs)

    monkeypatch.setattr(experimental, "build_experimental_server", capture_build)
    monkeypatch.setattr(
        experimental.sys,
        "argv",
        [
            "prepare_android_experimental_llama_server.py",
            "--output-dir",
            str(output_link),
            "--assets-output-dir",
            str(assets_output),
        ],
    )

    experimental.main()

    assert captured["output_dir"] == output_link.absolute()
    assert captured["output_dir"] != target.resolve()


def test_concurrent_publication_is_rejected_without_mixing_output_pairs(tmp_path, monkeypatch):
    staged_jni = tmp_path / "staged-jni"
    staged_assets = tmp_path / "staged-assets"
    new_jni = _write_owned_output(staged_jni, "new-jni")
    new_assets = _write_owned_output(staged_assets, "new-assets", kind="assets")
    competing_jni = tmp_path / "competing-jni"
    competing_assets = tmp_path / "competing-assets"
    _write_owned_output(competing_jni, "competing-jni")
    _write_owned_output(competing_assets, "competing-assets", kind="assets")
    output_jni = tmp_path / "generated-jni" / "jniLibs"
    output_assets = tmp_path / "generated-assets" / "assets"
    _write_owned_output(output_jni, "old-jni")
    _write_owned_output(output_assets, "old-assets", kind="assets")
    real_copy = experimental._copy_validated_tree
    first_copy_entered = threading.Event()
    release_first_copy = threading.Event()
    worker_errors = []

    def block_first_copy(source, destination, snapshot):
        result = real_copy(source, destination, snapshot)
        if Path(source) == staged_jni:
            first_copy_entered.set()
            assert release_first_copy.wait(timeout=10)
        return result

    monkeypatch.setattr(experimental, "_copy_validated_tree", block_first_copy)

    def publish_first_pair():
        try:
            experimental.replace_owned_outputs(
                (
                    (staged_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                    (staged_assets, output_assets, Path(experimental.PACKAGED_MANIFEST_ASSET)),
                )
            )
        except BaseException as exc:
            worker_errors.append(exc)

    worker = threading.Thread(target=publish_first_pair, daemon=True)
    worker.start()
    assert first_copy_entered.wait(timeout=10)
    try:
        with pytest.raises(RuntimeError, match="another experimental llama publication is active"):
            experimental.replace_owned_outputs(
                (
                    (competing_jni, output_jni, Path(experimental.MANIFEST_NAME)),
                    (
                        competing_assets,
                        output_assets,
                        Path(experimental.PACKAGED_MANIFEST_ASSET),
                    ),
                )
            )
    finally:
        release_first_copy.set()
    worker.join(timeout=20)

    assert not worker.is_alive()
    assert not worker_errors
    assert (output_jni / new_jni.relative_to(staged_jni)).read_bytes() == b"new-jni"
    assert (output_assets / new_assets.relative_to(staged_assets)).read_bytes() == b"new-assets"
    assert not _transaction_residue(output_jni.parent)
    assert not _transaction_residue(output_assets.parent)


def test_replaced_publication_lock_aborts_before_output_mutation(tmp_path, monkeypatch):
    staged = tmp_path / "staged"
    _write_owned_output(staged, "new")
    output = tmp_path / "output"
    old_artifact = _write_owned_output(output, "old")
    real_copy = experimental._copy_validated_tree

    def replace_lock_after_copy(source, destination, snapshot):
        result = real_copy(source, destination, snapshot)
        lock = output.parent / ".hermes-experimental-llama-publication.lock"
        (lock / "owner.token").unlink()
        lock.rmdir()
        lock.mkdir()
        (lock / "owner.token").write_bytes(b"forged")
        return result

    monkeypatch.setattr(experimental, "_copy_validated_tree", replace_lock_after_copy)

    with pytest.raises(RuntimeError, match="publication lock identity changed"):
        experimental.replace_owned_output(staged, output)

    assert old_artifact.read_bytes() == b"old"
    assert output.is_dir()




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
