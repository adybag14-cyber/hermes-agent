#!/usr/bin/env python3
"""Build the pinned experimental TurboQuant llama-server for Android.

This lane is deliberately separate from the stable Termux llama.cpp payload.
The source archive, Android NDK, build switches, output name, and advertised
capabilities all come from one committed lock file.  Before anything enters
jniLibs, this script verifies the archive hash, strips the executable, and
checks its ELF architecture, DT_NEEDED set, and 16 KiB LOAD alignment.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK_FILE = REPO_ROOT / "hermes_android" / "experimental_llama_server.lock.json"
MANIFEST_NAME = "hermes-experimental-llama-server-manifest.json"
PACKAGED_MANIFEST_ASSET = "hermes-experimental-llama/manifest.json"
MANIFEST_GENERATOR = "scripts/prepare_android_experimental_llama_server.py"
SUPPORTED_ABIS = {
    "arm64-v8a": "AArch64",
    "x86_64": "Advanced Micro Devices X86-64",
}
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
PACKAGED_NAME_PATTERN = re.compile(r"lib[0-9A-Za-z_]+\.so")
TOOL_VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){2}")
NEEDED_PATTERN = re.compile(r"\(NEEDED\).*\[([^\]]+)\]")
MAX_DOWNLOAD_ATTEMPTS = 3
DOWNLOAD_CHUNK_BYTES = 1024 * 1024
CANONICAL_SOURCE_PREFIX = "/usr/src/hermes-experimental-llama/source"
CANONICAL_BUILD_PREFIX = "/usr/src/hermes-experimental-llama/build"
PATH_PREFIX_MAP_OPTIONS = (
    "-ffile-prefix-map",
    "-fmacro-prefix-map",
    "-fdebug-prefix-map",
)
NONDETERMINISTIC_ELF_SECTIONS = (
    ".note.gnu.build-id",
    ".comment",
)


def canonical_json_bytes(payload: Any) -> bytes:
    return (json.dumps(payload, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode("utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(DOWNLOAD_CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a non-empty string")
    return value.strip()


def _require_positive_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def load_lock_file(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    lock = _require_mapping(payload, "lock file")
    if lock.get("schema_version") != 1:
        raise ValueError("experimental llama lock schema_version must be 1")

    source = _require_mapping(lock.get("source"), "source")
    commit = _require_string(source.get("commit"), "source.commit")
    if not COMMIT_PATTERN.fullmatch(commit):
        raise ValueError("source.commit must be one full lowercase Git commit SHA")
    archive_sha256 = _require_string(source.get("archive_sha256"), "source.archive_sha256")
    if not SHA256_PATTERN.fullmatch(archive_sha256):
        raise ValueError("source.archive_sha256 must be one lowercase SHA-256 digest")
    _require_string(source.get("repository"), "source.repository")
    _require_string(source.get("archive_url"), "source.archive_url")
    _require_positive_int(source.get("archive_size_bytes"), "source.archive_size_bytes")

    android = _require_mapping(lock.get("android"), "android")
    _require_string(android.get("ndk_version"), "android.ndk_version")
    _require_positive_int(android.get("minimum_api"), "android.minimum_api")
    maximum_jobs = _require_positive_int(android.get("maximum_parallel_jobs"), "android.maximum_parallel_jobs")
    if maximum_jobs > 12:
        raise ValueError("android.maximum_parallel_jobs may not exceed 12")
    _require_positive_int(android.get("minimum_load_alignment_bytes"), "android.minimum_load_alignment_bytes")
    abis = android.get("abis")
    if not isinstance(abis, list) or not abis or len(abis) != len(set(abis)):
        raise ValueError("android.abis must be a non-empty list without duplicates")
    unknown_abis = sorted(set(abis) - set(SUPPORTED_ABIS))
    if unknown_abis:
        raise ValueError(f"unsupported Android ABI(s): {', '.join(unknown_abis)}")
    allowed_needed = android.get("allowed_needed_libraries")
    if not isinstance(allowed_needed, list) or not allowed_needed or not all(
        isinstance(item, str) and item for item in allowed_needed
    ):
        raise ValueError("android.allowed_needed_libraries must be a non-empty string list")

    toolchain = _require_mapping(lock.get("toolchain"), "toolchain")
    android_ndk_package = _require_string(
        toolchain.get("android_ndk_package"),
        "toolchain.android_ndk_package",
    )
    expected_ndk_package = f"ndk;{android['ndk_version']}"
    if android_ndk_package != expected_ndk_package:
        raise ValueError(
            "toolchain.android_ndk_package must equal "
            f"{expected_ndk_package!r} for android.ndk_version"
        )
    cmake_version = _require_string(toolchain.get("cmake_version"), "toolchain.cmake_version")
    ninja_version = _require_string(toolchain.get("ninja_version"), "toolchain.ninja_version")
    for label, version in (
        ("toolchain.cmake_version", cmake_version),
        ("toolchain.ninja_version", ninja_version),
    ):
        if not TOOL_VERSION_PATTERN.fullmatch(version):
            raise ValueError(f"{label} must be one exact three-component version")
    android_cmake_package = _require_string(
        toolchain.get("android_cmake_package"),
        "toolchain.android_cmake_package",
    )
    expected_cmake_package = f"cmake;{cmake_version}"
    if android_cmake_package != expected_cmake_package:
        raise ValueError(
            "toolchain.android_cmake_package must equal "
            f"{expected_cmake_package!r} for toolchain.cmake_version"
        )

    build = _require_mapping(lock.get("build"), "build")
    if _require_string(build.get("generator"), "build.generator") != "Ninja":
        raise ValueError("only the pinned Ninja generator is supported")
    _require_string(build.get("configuration"), "build.configuration")
    source_date_epoch = _require_positive_int(build.get("source_date_epoch"), "build.source_date_epoch")
    if source_date_epoch > 2_147_483_647:
        raise ValueError("build.source_date_epoch must fit a portable signed 32-bit timestamp")
    defines = _require_mapping(build.get("cmake_defines"), "build.cmake_defines")
    if not defines or not all(isinstance(key, str) and isinstance(value, str) for key, value in defines.items()):
        raise ValueError("build.cmake_defines must contain string keys and values")
    if defines.get("LLAMA_BUILD_COMMIT") != commit:
        raise ValueError("LLAMA_BUILD_COMMIT must equal source.commit")
    _require_positive_int(int(defines.get("LLAMA_BUILD_NUMBER", "0")), "LLAMA_BUILD_NUMBER")

    source_patches = lock.get("source_patches")
    if not isinstance(source_patches, list) or not source_patches:
        raise ValueError("source_patches must contain at least one pinned compatibility patch")
    seen_patch_paths: set[str] = set()
    for index, patch in enumerate(source_patches):
        record = _require_mapping(patch, f"source_patches[{index}]")
        patch_path = PurePosixPath(_require_string(record.get("path"), f"source_patches[{index}].path"))
        if patch_path.is_absolute() or any(part in {"", ".", ".."} for part in patch_path.parts):
            raise ValueError(f"source_patches[{index}].path must be one safe relative path")
        if patch_path.as_posix() in seen_patch_paths:
            raise ValueError(f"duplicate source patch path: {patch_path}")
        seen_patch_paths.add(patch_path.as_posix())
        _require_positive_int(record.get("size_bytes"), f"source_patches[{index}].size_bytes")
        patch_sha256 = _require_string(record.get("sha256"), f"source_patches[{index}].sha256")
        if not SHA256_PATTERN.fullmatch(patch_sha256):
            raise ValueError(f"source_patches[{index}].sha256 must be one lowercase SHA-256 digest")
        _require_string(record.get("purpose"), f"source_patches[{index}].purpose")
        files = record.get("files")
        if not isinstance(files, list) or not files:
            raise ValueError(f"source_patches[{index}].files must be a non-empty list")
        seen_files: set[str] = set()
        for file_index, file_record_raw in enumerate(files):
            file_record = _require_mapping(
                file_record_raw,
                f"source_patches[{index}].files[{file_index}]",
            )
            relative = PurePosixPath(
                _require_string(
                    file_record.get("path"),
                    f"source_patches[{index}].files[{file_index}].path",
                )
            )
            if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
                raise ValueError("source patch file paths must be safe relative paths")
            if relative.as_posix() in seen_files:
                raise ValueError(f"duplicate source patch file path: {relative}")
            seen_files.add(relative.as_posix())
            for digest_name in ("source_sha256", "patched_sha256"):
                digest = _require_string(
                    file_record.get(digest_name),
                    f"source_patches[{index}].files[{file_index}].{digest_name}",
                )
                if not SHA256_PATTERN.fullmatch(digest):
                    raise ValueError(f"{digest_name} must be one lowercase SHA-256 digest")

    artifact = _require_mapping(lock.get("artifact"), "artifact")
    packaged_name = _require_string(artifact.get("packaged_filename"), "artifact.packaged_filename")
    if not PACKAGED_NAME_PATTERN.fullmatch(packaged_name):
        raise ValueError("artifact.packaged_filename must be one safe lib*.so basename")
    _require_string(artifact.get("server_identity"), "artifact.server_identity")

    license_artifacts = lock.get("license_artifacts")
    if not isinstance(license_artifacts, list) or not license_artifacts:
        raise ValueError("license_artifacts must be a non-empty list")
    seen_license_sources: set[str] = set()
    seen_packaged_licenses: set[str] = set()
    for index, license_artifact_raw in enumerate(license_artifacts):
        license_artifact = _require_mapping(
            license_artifact_raw,
            f"license_artifacts[{index}]",
        )
        license_source_path = PurePosixPath(
            _require_string(
                license_artifact.get("source_path"),
                f"license_artifacts[{index}].source_path",
            )
        )
        packaged_asset_path = PurePosixPath(
            _require_string(
                license_artifact.get("packaged_asset_path"),
                f"license_artifacts[{index}].packaged_asset_path",
            )
        )
        for path, label in (
            (license_source_path, f"license_artifacts[{index}].source_path"),
            (packaged_asset_path, f"license_artifacts[{index}].packaged_asset_path"),
        ):
            if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
                raise ValueError(f"{label} must be one safe relative path")
        source_key = license_source_path.as_posix()
        packaged_key = packaged_asset_path.as_posix()
        if source_key in seen_license_sources:
            raise ValueError(f"duplicate license source path: {source_key}")
        if packaged_key in seen_packaged_licenses:
            raise ValueError(f"duplicate packaged license path: {packaged_key}")
        seen_license_sources.add(source_key)
        seen_packaged_licenses.add(packaged_key)
        license_sha256 = _require_string(
            license_artifact.get("sha256"),
            f"license_artifacts[{index}].sha256",
        )
        if not SHA256_PATTERN.fullmatch(license_sha256):
            raise ValueError(
                f"license_artifacts[{index}].sha256 must be one lowercase SHA-256 digest"
            )
        _require_positive_int(
            license_artifact.get("size_bytes"),
            f"license_artifacts[{index}].size_bytes",
        )

    capabilities = _require_mapping(lock.get("capabilities"), "capabilities")
    cache_types = capabilities.get("kv_cache_types")
    if not isinstance(cache_types, list) or "turbo3" not in cache_types:
        raise ValueError("capabilities.kv_cache_types must include turbo3")
    if "nanbeige" not in capabilities.get("model_architectures", []):
        raise ValueError("capabilities.model_architectures must include nanbeige")
    return lock


def archive_matches(path: Path, expected_size: int, expected_sha256: str) -> bool:
    return path.is_file() and path.stat().st_size == expected_size and sha256_file(path) == expected_sha256


def download_verified_archive(
    url: str,
    destination: Path,
    expected_size: int,
    expected_sha256: str,
    attempts: int = MAX_DOWNLOAD_ATTEMPTS,
) -> Path:
    if archive_matches(destination, expected_size, expected_sha256):
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".partial")
    partial.unlink(missing_ok=True)
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Hermes-Android-pinned-builder/1"})
            digest = hashlib.sha256()
            size = 0
            with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
                while True:
                    chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                    if not chunk:
                        break
                    output.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
            actual_sha256 = digest.hexdigest()
            if size != expected_size:
                raise RuntimeError(f"archive size mismatch: expected {expected_size}, got {size}")
            if actual_sha256 != expected_sha256:
                raise RuntimeError(
                    f"archive SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}"
                )
            os.replace(partial, destination)
            return destination
        except (OSError, RuntimeError, urllib.error.URLError) as exc:
            last_error = exc
            partial.unlink(missing_ok=True)
            if attempt < attempts:
                time.sleep(min(2 ** (attempt - 1), 4))
    raise RuntimeError(f"failed to download pinned source archive after {attempts} attempt(s): {last_error}")


def _safe_archive_member(member: tarfile.TarInfo) -> None:
    path = PurePosixPath(member.name)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise RuntimeError(f"unsafe source archive member path: {member.name!r}")
    if member.ischr() or member.isblk() or member.isfifo():
        raise RuntimeError(f"unsupported special source archive member: {member.name!r}")
    if member.issym() or member.islnk():
        target = PurePosixPath(member.linkname)
        if target.is_absolute():
            raise RuntimeError(f"absolute source archive link target: {member.name!r}")
        combined = path.parent.joinpath(target)
        normalized: list[str] = []
        for part in combined.parts:
            if part == "..":
                if not normalized:
                    raise RuntimeError(f"escaping source archive link target: {member.name!r}")
                normalized.pop()
            elif part not in {"", "."}:
                normalized.append(part)


def extract_verified_source(archive_path: Path, destination: Path, commit: str) -> Path:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive_path, mode="r:gz") as archive:
        members = archive.getmembers()
        for member in members:
            _safe_archive_member(member)
        archive.extractall(destination, members=members, filter="data")
    expected = destination / f"llama-cpp-turboquant-{commit}"
    if not (expected / "CMakeLists.txt").is_file():
        roots = sorted(path for path in destination.iterdir() if path.is_dir())
        if len(roots) != 1 or not (roots[0] / "CMakeLists.txt").is_file():
            raise RuntimeError("pinned source archive did not contain exactly one llama.cpp source root")
        expected = roots[0]
    if not (expected / "src" / "models" / "nanbeige.cpp").is_file():
        raise RuntimeError("pinned experimental source is missing Nanbeige model support")
    return expected


def verify_locked_licenses(
    source_dir: Path,
    lock: dict[str, Any],
) -> list[tuple[dict[str, Any], Path]]:
    verified: list[tuple[dict[str, Any], Path]] = []
    for license_artifact in lock["license_artifacts"]:
        source_path = license_artifact["source_path"]
        license_path = source_dir.joinpath(*PurePosixPath(source_path).parts)
        if not license_path.is_file():
            raise RuntimeError(f"pinned source archive is missing license notice {source_path}")
        if license_path.stat().st_size != license_artifact["size_bytes"]:
            raise RuntimeError(f"pinned source license notice size does not match the lock: {source_path}")
        if sha256_file(license_path) != license_artifact["sha256"]:
            raise RuntimeError(f"pinned source license notice SHA-256 does not match the lock: {source_path}")
        verified.append((license_artifact, license_path))
    return verified


def package_locked_licenses(
    verified_licenses: list[tuple[dict[str, Any], Path]],
    assets_output_dir: Path,
) -> None:
    for license_artifact, license_source in verified_licenses:
        packaged_license = assets_output_dir.joinpath(
            *PurePosixPath(license_artifact["packaged_asset_path"]).parts
        )
        packaged_license.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(license_source, packaged_license)


def resolve_repository_file(repository_root: Path, relative_path: str) -> Path:
    root = repository_root.resolve()
    candidate = root.joinpath(*PurePosixPath(relative_path).parts).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise RuntimeError(f"locked repository path escapes the source checkout: {relative_path}") from exc
    return candidate


def apply_locked_source_patches(
    *,
    source_dir: Path,
    lock: dict[str, Any],
    git: Path,
    environment: dict[str, str],
    repository_root: Path = REPO_ROOT,
) -> list[dict[str, Any]]:
    applied: list[dict[str, Any]] = []
    for patch_record in lock["source_patches"]:
        patch_path = resolve_repository_file(repository_root, patch_record["path"])
        if not patch_path.is_file():
            raise RuntimeError(f"locked compatibility patch is missing: {patch_path}")
        if patch_path.stat().st_size != patch_record["size_bytes"]:
            raise RuntimeError(f"compatibility patch size does not match the lock: {patch_record['path']}")
        if sha256_file(patch_path) != patch_record["sha256"]:
            raise RuntimeError(f"compatibility patch SHA-256 does not match the lock: {patch_record['path']}")
        for file_record in patch_record["files"]:
            source_file = source_dir.joinpath(*PurePosixPath(file_record["path"]).parts)
            if not source_file.is_file() or sha256_file(source_file) != file_record["source_sha256"]:
                raise RuntimeError(f"compatibility patch preimage changed: {file_record['path']}")
        subprocess.run(
            [str(git), "apply", "--check", "--whitespace=error-all", str(patch_path)],
            cwd=source_dir,
            check=True,
            env=environment,
        )
        subprocess.run(
            [str(git), "apply", "--whitespace=error-all", str(patch_path)],
            cwd=source_dir,
            check=True,
            env=environment,
        )
        file_evidence: list[dict[str, Any]] = []
        for file_record in patch_record["files"]:
            source_file = source_dir.joinpath(*PurePosixPath(file_record["path"]).parts)
            actual_sha256 = sha256_file(source_file) if source_file.is_file() else ""
            if actual_sha256 != file_record["patched_sha256"]:
                raise RuntimeError(f"compatibility patch postimage changed: {file_record['path']}")
            file_evidence.append(
                {
                    "path": file_record["path"],
                    "source_sha256": file_record["source_sha256"],
                    "patched_sha256": actual_sha256,
                }
            )
        applied.append(
            {
                "path": patch_record["path"],
                "sha256": patch_record["sha256"],
                "purpose": patch_record["purpose"],
                "files": file_evidence,
            }
        )
    return applied


def _parse_local_properties_sdk(path: Path) -> Path | None:
    if not path.is_file():
        return None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if not raw_line.startswith("sdk.dir="):
            continue
        value = raw_line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
        if value:
            return Path(value).expanduser()
    return None


def android_sdk_candidates() -> Iterable[Path]:
    sdk_roots: list[Path] = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(variable, "").strip()
        if value:
            sdk_roots.append(Path(value))
    local_sdk = _parse_local_properties_sdk(REPO_ROOT / "android" / "local.properties")
    if local_sdk is not None:
        sdk_roots.append(local_sdk)
    if os.name == "nt":
        local_app_data = os.environ.get("LOCALAPPDATA", "").strip()
        if local_app_data:
            sdk_roots.append(Path(local_app_data) / "Android" / "Sdk")
    else:
        sdk_roots.extend((Path.home() / "Android" / "Sdk", Path("/opt/android-sdk")))

    seen: set[Path] = set()
    for sdk_root in sdk_roots:
        resolved = sdk_root.expanduser().resolve()
        if resolved not in seen:
            seen.add(resolved)
            yield resolved


def ndk_candidates(explicit: Path | None, version: str) -> Iterable[Path]:
    if explicit is not None:
        yield explicit
    for variable in ("HERMES_ANDROID_NDK_ROOT", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(variable, "").strip()
        if value:
            yield Path(value)
    for sdk_root in android_sdk_candidates():
        yield sdk_root / "ndk" / version


def _ndk_revision(ndk_dir: Path) -> str:
    properties = ndk_dir / "source.properties"
    if not properties.is_file():
        return ""
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("Pkg.Revision"):
            return line.split("=", 1)[-1].strip()
    return ""


def resolve_ndk(explicit: Path | None, version: str) -> Path:
    seen: set[Path] = set()
    for candidate in ndk_candidates(explicit, version):
        resolved = candidate.expanduser().resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        if _ndk_revision(resolved) == version and (
            resolved / "build" / "cmake" / "android.toolchain.cmake"
        ).is_file():
            return resolved
    searched = ", ".join(str(path) for path in seen) or "no candidate paths"
    raise RuntimeError(f"Android NDK {version} was not found; searched: {searched}")


def _host_prebuilt_name() -> str:
    machine = platform.machine().lower()
    if os.name == "nt":
        return "windows-x86_64"
    if sys.platform == "darwin":
        return "darwin-arm64" if machine in {"arm64", "aarch64"} else "darwin-x86_64"
    return "linux-x86_64"


def resolve_ndk_tool(ndk_dir: Path, tool_name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    preferred = ndk_dir / "toolchains" / "llvm" / "prebuilt" / _host_prebuilt_name() / "bin" / f"{tool_name}{suffix}"
    if preferred.is_file():
        return preferred
    matches = sorted((ndk_dir / "toolchains" / "llvm" / "prebuilt").glob(f"*/bin/{tool_name}{suffix}"))
    if len(matches) != 1:
        raise RuntimeError(f"could not resolve exactly one NDK {tool_name} executable")
    return matches[0]


def resolve_host_tool(explicit: str | None, name: str) -> Path:
    candidate = explicit or shutil.which(name)
    if not candidate:
        raise RuntimeError(f"required host tool {name!r} was not found on PATH")
    resolved = Path(candidate).expanduser().resolve()
    if not resolved.is_file():
        raise RuntimeError(f"required host tool {name!r} does not exist at {resolved}")
    return resolved


def command_version(command: Path) -> str:
    result = subprocess.run(
        [str(command), "--version"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return (result.stdout or result.stderr).splitlines()[0].strip()


def resolve_locked_cmake_and_ninja(
    lock: dict[str, Any],
    explicit_cmake: str | None,
    explicit_ninja: str | None,
) -> tuple[Path, Path, str, str]:
    if bool(explicit_cmake) != bool(explicit_ninja):
        raise RuntimeError("--cmake and --ninja must be supplied together")

    toolchain = lock["toolchain"]
    cmake_version = toolchain["cmake_version"]
    ninja_version = toolchain["ninja_version"]
    if explicit_cmake and explicit_ninja:
        cmake = resolve_host_tool(explicit_cmake, "cmake")
        ninja = resolve_host_tool(explicit_ninja, "ninja")
    else:
        suffix = ".exe" if os.name == "nt" else ""
        searched: list[Path] = []
        resolved_pair: tuple[Path, Path] | None = None
        for sdk_root in android_sdk_candidates():
            bin_dir = sdk_root / "cmake" / cmake_version / "bin"
            cmake_candidate = bin_dir / f"cmake{suffix}"
            ninja_candidate = bin_dir / f"ninja{suffix}"
            searched.extend((cmake_candidate, ninja_candidate))
            if cmake_candidate.is_file() and ninja_candidate.is_file():
                resolved_pair = (cmake_candidate.resolve(), ninja_candidate.resolve())
                break
        if resolved_pair is None:
            rendered = ", ".join(str(path) for path in searched) or "no Android SDK roots"
            raise RuntimeError(
                f"Android SDK package {toolchain['android_cmake_package']} was not found; "
                f"searched: {rendered}"
            )
        cmake, ninja = resolved_pair

    actual_cmake = command_version(cmake)
    actual_ninja = command_version(ninja)
    expected_cmake = f"cmake version {cmake_version}"
    if actual_cmake != expected_cmake:
        raise RuntimeError(
            f"locked CMake version mismatch: expected {expected_cmake!r}, got {actual_cmake!r}"
        )
    if actual_ninja != ninja_version:
        raise RuntimeError(
            f"locked Ninja version mismatch: expected {ninja_version!r}, got {actual_ninja!r}"
        )
    return cmake, ninja, actual_cmake, actual_ninja


def deterministic_build_environment(lock: dict[str, Any]) -> dict[str, str]:
    environment = os.environ.copy()
    # Ambient SOURCE_DATE_EPOCH must not mutate a hash-locked Android candidate.
    # The value is part of the reviewed build contract and is validated on load.
    environment["SOURCE_DATE_EPOCH"] = str(lock["build"]["source_date_epoch"])
    environment["TZ"] = "UTC"
    environment["LC_ALL"] = "C"
    return environment


def deterministic_compiler_path_map_options(source_dir: Path, build_dir: Path) -> tuple[str, ...]:
    mappings = (
        (source_dir.resolve().as_posix(), CANONICAL_SOURCE_PREFIX),
        (build_dir.resolve().as_posix(), CANONICAL_BUILD_PREFIX),
    )
    options: list[str] = []
    for local_root, canonical_root in mappings:
        # Clang's prefix-map spelling uses '=' as its delimiter. Fail before
        # configuration rather than silently generate an ambiguous mapping.
        if "=" in local_root or any(character in local_root for character in "\0\r\n"):
            raise RuntimeError(f"local compiler path cannot be represented safely: {local_root!r}")
        options.extend(
            f"{option}={local_root}={canonical_root}" for option in PATH_PREFIX_MAP_OPTIONS
        )
    return tuple(options)


def cmake_compiler_flags(options: Iterable[str]) -> str:
    arguments = list(options)
    # CMAKE_<LANG>_FLAGS is a command-line string fragment, not a CMake list.
    # Quote for the build host's native shell so roots containing whitespace or
    # shell metacharacters remain one Clang argument without semicolon joining.
    if os.name == "nt":
        return subprocess.list2cmdline(arguments)
    return shlex.join(arguments)


def verify_no_local_path_leaks(binary: Path, roots: Iterable[Path]) -> None:
    payload = binary.read_bytes().lower()
    for root in roots:
        resolved = root.resolve()
        native = str(resolved)
        spellings = {
            native,
            resolved.as_posix(),
            native.replace("\\", "/"),
            native.replace("/", "\\"),
        }
        for spelling in spellings:
            for encoding in ("utf-8", "utf-16-le", "utf-16-be"):
                needle = spelling.encode(encoding).lower()
                if needle and needle in payload:
                    raise RuntimeError(
                        f"{binary} embeds non-reproducible local build path {spelling!r}"
                    )


def normalize_android_elf_metadata(
    binary: Path,
    *,
    strip: Path,
    readelf: Path,
    environment: dict[str, str],
) -> tuple[str, ...]:
    """Remove host-derived, non-loadable ELF metadata and prove it is absent.

    LLD calculates the GNU build ID before ``llvm-strip --strip-unneeded``.
    Inputs which are discarded by that strip can therefore leave different
    build-ID bytes in otherwise identical final Android executables.  Compiler
    comments are also non-loadable host metadata.  Remove both with the exact
    NDK tool and fail before packaging if either section survives.
    """

    command = [str(strip), "--strip-unneeded"]
    command.extend(
        f"--remove-section={section}" for section in NONDETERMINISTIC_ELF_SECTIONS
    )
    command.append(str(binary))
    subprocess.run(command, check=True, env=environment)

    result = subprocess.run(
        [str(readelf), "-SW", str(binary)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=environment,
    )
    section_names = set(
        re.findall(r"^\s*\[\s*\d+\]\s+(\S+)", result.stdout, flags=re.MULTILINE)
    )
    surviving = sorted(section_names.intersection(NONDETERMINISTIC_ELF_SECTIONS))
    if surviving:
        raise RuntimeError(
            f"{binary} retains non-reproducible ELF section(s): {', '.join(surviving)}"
        )
    return NONDETERMINISTIC_ELF_SECTIONS


def configure_and_build_abi(
    *,
    lock: dict[str, Any],
    source_dir: Path,
    build_dir: Path,
    abi: str,
    ndk_dir: Path,
    cmake: Path,
    ninja: Path,
    jobs: int,
    environment: dict[str, str],
) -> Path:
    android = lock["android"]
    build = lock["build"]
    toolchain = ndk_dir / "build" / "cmake" / "android.toolchain.cmake"
    command = [
        str(cmake),
        "-S",
        str(source_dir),
        "-B",
        str(build_dir),
        "-G",
        build["generator"],
        f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        f"-DCMAKE_BUILD_TYPE={build['configuration']}",
        f"-DANDROID_ABI={abi}",
        f"-DANDROID_PLATFORM=android-{android['minimum_api']}",
        f"-DANDROID_NDK={ndk_dir}",
    ]
    command.extend(f"-D{key}={value}" for key, value in sorted(build["cmake_defines"].items()))
    path_map_flags = cmake_compiler_flags(
        deterministic_compiler_path_map_options(source_dir, build_dir)
    )
    command.extend(
        (
            f"-DCMAKE_C_FLAGS={path_map_flags}",
            f"-DCMAKE_CXX_FLAGS={path_map_flags}",
        )
    )
    subprocess.run(command, check=True, env=environment)
    verify_configured_build_identity(build_dir, lock)
    subprocess.run(
        [str(cmake), "--build", str(build_dir), "--target", "llama-server", "--parallel", str(jobs)],
        check=True,
        env=environment,
    )
    binary = build_dir / "bin" / "llama-server"
    if not binary.is_file():
        raise RuntimeError(f"llama-server build did not produce {binary}")
    return binary


def verify_configured_build_identity(build_dir: Path, lock: dict[str, Any]) -> None:
    build_info = build_dir / "common" / "build-info.cpp"
    if not build_info.is_file():
        raise RuntimeError(f"CMake did not generate the expected build identity file: {build_info}")
    payload = build_info.read_text(encoding="utf-8")
    expected_number = lock["build"]["cmake_defines"]["LLAMA_BUILD_NUMBER"]
    expected_commit = lock["source"]["commit"]
    if f"int LLAMA_BUILD_NUMBER = {expected_number};" not in payload:
        raise RuntimeError("configured llama-server build number does not match the lock")
    if f'char const * LLAMA_COMMIT = "{expected_commit}";' not in payload:
        raise RuntimeError("configured llama-server commit identity does not match the lock")


def verify_android_elf(
    binary: Path,
    *,
    abi: str,
    readelf: Path,
    allowed_needed: set[str],
    minimum_load_alignment: int,
) -> dict[str, Any]:
    if binary.read_bytes()[:4] != b"\x7fELF":
        raise RuntimeError(f"{binary} is not an ELF executable")
    header = subprocess.run(
        [str(readelf), "-hW", str(binary)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    expected_machine = SUPPORTED_ABIS[abi]
    if expected_machine not in header:
        raise RuntimeError(f"{binary} does not report the expected {expected_machine} ELF machine")
    type_line = next((line.strip() for line in header.splitlines() if line.strip().startswith("Type:")), "")
    if "DYN" not in type_line:
        raise RuntimeError(f"{binary} is not a position-independent Android executable: {type_line}")

    dynamic = subprocess.run(
        [str(readelf), "-dW", str(binary)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    needed = sorted(set(NEEDED_PATTERN.findall(dynamic)))
    unexpected = sorted(set(needed) - allowed_needed)
    if unexpected:
        raise RuntimeError(f"{binary} has non-system or unpinned DT_NEEDED entries: {', '.join(unexpected)}")

    program_headers = subprocess.run(
        [str(readelf), "-lW", str(binary)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    alignments: list[int] = []
    for line in program_headers.splitlines():
        columns = line.split()
        if not columns or columns[0] != "LOAD":
            continue
        try:
            alignments.append(int(columns[-1], 0))
        except ValueError as exc:
            raise RuntimeError(f"could not parse ELF LOAD alignment from {line!r}") from exc
    if not alignments:
        raise RuntimeError(f"{binary} has no ELF LOAD program headers")
    inadequate = [alignment for alignment in alignments if alignment < minimum_load_alignment]
    if inadequate:
        rendered = ", ".join(hex(value) for value in alignments)
        raise RuntimeError(
            f"{binary} is not {minimum_load_alignment}-byte page aligned; LOAD alignments: {rendered}"
        )
    return {
        "elf_machine": expected_machine,
        "elf_type": type_line.removeprefix("Type:").strip(),
        "needed_libraries": needed,
        "load_alignments_bytes": alignments,
    }


def _assert_replaceable_output(output_dir: Path, ownership_manifest: Path = Path(MANIFEST_NAME)) -> None:
    if not output_dir.exists():
        return
    if not output_dir.is_dir():
        raise RuntimeError(f"experimental llama output is not a directory: {output_dir}")
    if not any(output_dir.iterdir()):
        return
    manifest_path = output_dir / ownership_manifest
    if not manifest_path.is_file():
        raise RuntimeError(
            f"refusing to replace non-empty unowned output directory without {ownership_manifest}: {output_dir}"
        )
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"refusing to replace output with an invalid ownership manifest: {output_dir}") from exc
    if manifest.get("generated_by") != MANIFEST_GENERATOR:
        raise RuntimeError(f"refusing to replace output not owned by {MANIFEST_GENERATOR}: {output_dir}")


def replace_owned_output(
    staged_output: Path,
    output_dir: Path,
    ownership_manifest: Path = Path(MANIFEST_NAME),
) -> None:
    _assert_replaceable_output(output_dir, ownership_manifest)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    if output_dir.exists():
        shutil.rmtree(output_dir)
    os.replace(staged_output, output_dir)


def build_experimental_server(
    *,
    output_dir: Path,
    assets_output_dir: Path,
    lock_file: Path,
    cache_dir: Path,
    explicit_ndk: Path | None,
    explicit_cmake: str | None,
    explicit_ninja: str | None,
    explicit_git: str | None,
    requested_jobs: int,
) -> dict[str, Any]:
    lock = load_lock_file(lock_file)
    lock_sha256 = sha256_file(lock_file)

    # Generated outputs and their generated manifest are one mutable trust domain.
    # Never accept them as an attestation of their own integrity: every executed
    # task rebuilds from the hash-locked source and patch. Gradle remains free to
    # skip the whole task when its declared inputs and outputs are unchanged.

    android = lock["android"]
    maximum_jobs = android["maximum_parallel_jobs"]
    if requested_jobs <= 0:
        raise ValueError("--jobs must be positive")
    jobs = min(requested_jobs, maximum_jobs, 12)
    ndk_dir = resolve_ndk(explicit_ndk, android["ndk_version"])
    cmake, ninja, actual_cmake_version, actual_ninja_version = resolve_locked_cmake_and_ninja(
        lock,
        explicit_cmake,
        explicit_ninja,
    )
    git = resolve_host_tool(explicit_git, "git")
    git_version = command_version(git)
    print(f"Using host Git patch tool (diagnostic only): {git_version}")
    strip = resolve_ndk_tool(ndk_dir, "llvm-strip")
    readelf = resolve_ndk_tool(ndk_dir, "llvm-readelf")
    environment = deterministic_build_environment(lock)

    source = lock["source"]
    archive_name = f"llama-cpp-turboquant-{source['commit']}.tar.gz"
    archive_path = cache_dir / archive_name
    download_verified_archive(
        source["archive_url"],
        archive_path,
        source["archive_size_bytes"],
        source["archive_sha256"],
    )

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    # The extracted codeload archive has no .git directory. Keep CMake's source
    # tree outside the Hermes checkout so Git cannot walk upward and stamp the
    # unrelated Hermes commit into ggml's compiled provenance.
    with tempfile.TemporaryDirectory(prefix="hermes-experimental-llama-build-") as raw_work:
        work_dir = Path(raw_work)
        source_dir = extract_verified_source(archive_path, work_dir / "source", source["commit"])
        verified_licenses = verify_locked_licenses(source_dir, lock)
        environment["GIT_CEILING_DIRECTORIES"] = str(source_dir.parent.resolve())
        applied_patches = apply_locked_source_patches(
            source_dir=source_dir,
            lock=lock,
            git=git,
            environment=environment,
        )
        staged_output = work_dir / "jniLibs"
        staged_assets = work_dir / "assets"
        artifacts: dict[str, dict[str, Any]] = {}
        for abi in android["abis"]:
            print(f"Configuring pinned experimental llama-server for {abi}...")
            build_dir = work_dir / "build" / abi
            binary = configure_and_build_abi(
                lock=lock,
                source_dir=source_dir,
                build_dir=build_dir,
                abi=abi,
                ndk_dir=ndk_dir,
                cmake=cmake,
                ninja=ninja,
                jobs=jobs,
                environment=environment,
            )
            normalize_android_elf_metadata(
                binary,
                strip=strip,
                readelf=readelf,
                environment=environment,
            )
            verify_no_local_path_leaks(binary, (work_dir, source_dir, build_dir))
            elf = verify_android_elf(
                binary,
                abi=abi,
                readelf=readelf,
                allowed_needed=set(android["allowed_needed_libraries"]),
                minimum_load_alignment=android["minimum_load_alignment_bytes"],
            )
            destination = staged_output / abi / lock["artifact"]["packaged_filename"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(binary, destination)
            destination.chmod(0o755)
            artifacts[abi] = {
                "relative_path": destination.relative_to(staged_output).as_posix(),
                "size_bytes": destination.stat().st_size,
                "sha256": sha256_file(destination),
                **elf,
            }

        manifest = {
            "schema_version": 1,
            "generated_by": MANIFEST_GENERATOR,
            "lock_sha256": lock_sha256,
            "server_identity": lock["artifact"]["server_identity"],
            "source": lock["source"],
            "source_patches": applied_patches,
            "toolchain": lock["toolchain"],
            "android": {
                "ndk_version": android["ndk_version"],
                "minimum_api": android["minimum_api"],
                "minimum_load_alignment_bytes": android["minimum_load_alignment_bytes"],
                "allowed_needed_libraries": android["allowed_needed_libraries"],
            },
            "build": {
                "configuration": lock["build"]["configuration"],
                "generator": lock["build"]["generator"],
                "cmake_defines": lock["build"]["cmake_defines"],
                "parallel_jobs": jobs,
                "source_date_epoch": environment["SOURCE_DATE_EPOCH"],
                "cmake_version": actual_cmake_version,
                "ninja_version": actual_ninja_version,
                "compiler_path_prefixes": {
                    "source": CANONICAL_SOURCE_PREFIX,
                    "build": CANONICAL_BUILD_PREFIX,
                },
                "removed_elf_sections": list(NONDETERMINISTIC_ELF_SECTIONS),
            },
            "capabilities": lock["capabilities"],
            "license_artifacts": lock["license_artifacts"],
            "artifacts": artifacts,
        }
        manifest_bytes = canonical_json_bytes(manifest)
        (staged_output / MANIFEST_NAME).write_bytes(manifest_bytes)
        packaged_manifest = staged_assets / PACKAGED_MANIFEST_ASSET
        packaged_manifest.parent.mkdir(parents=True, exist_ok=True)
        packaged_manifest.write_bytes(manifest_bytes)
        package_locked_licenses(verified_licenses, staged_assets)
        replace_owned_output(staged_output, output_dir)
        replace_owned_output(
            staged_assets,
            assets_output_dir,
            ownership_manifest=Path(PACKAGED_MANIFEST_ASSET),
        )
    print(f"Prepared pinned experimental llama-server jniLibs: {output_dir}")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the pinned experimental TurboQuant llama-server for Android")
    parser.add_argument("--output-dir", required=True, help="Generated jniLibs output directory")
    parser.add_argument("--assets-output-dir", required=True, help="Generated Android assets output directory")
    parser.add_argument("--lock-file", default=str(DEFAULT_LOCK_FILE), help="Pinned experimental source/build lock")
    parser.add_argument("--cache-dir", help="Verified source archive cache directory")
    parser.add_argument("--ndk-dir", help="Exact Android NDK directory; otherwise discover the locked revision")
    parser.add_argument("--cmake", help="CMake executable override")
    parser.add_argument("--ninja", help="Ninja executable override")
    parser.add_argument("--git", help="Git executable override used only to apply pinned source patches")
    parser.add_argument("--jobs", type=int, default=12, help="Parallel compile jobs (hard-capped by the lock and at 12)")
    args = parser.parse_args()

    output_dir = Path(args.output_dir).expanduser().resolve()
    assets_output_dir = Path(args.assets_output_dir).expanduser().resolve()
    lock_file = Path(args.lock_file).expanduser().resolve()
    cache_dir = (
        Path(args.cache_dir).expanduser().resolve()
        if args.cache_dir
        else output_dir.parent / "hermes-experimental-llama-cache"
    )
    explicit_ndk = Path(args.ndk_dir).expanduser().resolve() if args.ndk_dir else None
    build_experimental_server(
        output_dir=output_dir,
        assets_output_dir=assets_output_dir,
        lock_file=lock_file,
        cache_dir=cache_dir,
        explicit_ndk=explicit_ndk,
        explicit_cmake=args.cmake,
        explicit_ninja=args.ninja,
        explicit_git=args.git,
        requested_jobs=args.jobs,
    )


if __name__ == "__main__":
    main()
