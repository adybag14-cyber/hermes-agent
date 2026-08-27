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
import stat
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


def _path_is_within(candidate: Path, root: Path) -> bool:
    try:
        common = os.path.commonpath((str(candidate.resolve()), str(root.resolve())))
    except ValueError:
        return False
    return os.path.normcase(common) == os.path.normcase(str(root.resolve()))


def resolve_host_cxx_compiler(
    ndk_dir: Path,
    environment: dict[str, str] | None = None,
) -> Path:
    search_path = (environment or os.environ).get("PATH", "")
    rejected: list[Path] = []
    for name in ("g++", "c++", "clang++"):
        located = shutil.which(name, path=search_path)
        if located is None:
            continue
        resolved = Path(located).resolve()
        cross_named = re.search(
            r"(?:aarch64|armv7a|i686|x86_64)-linux-android.*(?:clang\+\+|g\+\+)(?:\.exe)?$",
            resolved.name,
            flags=re.IGNORECASE,
        )
        if _path_is_within(resolved, ndk_dir) or cross_named:
            rejected.append(resolved)
            continue
        if not resolved.is_file():
            continue
        return resolved
    rejected_text = ", ".join(str(path) for path in rejected)
    detail = f"; rejected Android cross compiler(s): {rejected_text}" if rejected else ""
    raise RuntimeError(
        "a real host C++ compiler was not found on PATH; install g++ or provide c++/clang++"
        + detail
    )


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
    host_cxx_compiler: Path,
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
        f"-DHOST_CXX_COMPILER:FILEPATH={host_cxx_compiler}",
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
    verify_configured_host_cxx_compiler(build_dir, host_cxx_compiler)
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


def verify_configured_host_cxx_compiler(build_dir: Path, expected: Path) -> None:
    cache = build_dir / "CMakeCache.txt"
    if not cache.is_file():
        raise RuntimeError(f"CMake did not generate the expected cache file: {cache}")
    matches = re.findall(
        r"^HOST_CXX_COMPILER:([^=]+)=(.*)$",
        cache.read_text(encoding="utf-8"),
        flags=re.MULTILINE,
    )
    if len(matches) != 1:
        raise RuntimeError("CMake cache must contain exactly one HOST_CXX_COMPILER entry")
    cache_type, configured_value = matches[0]
    if cache_type != "FILEPATH":
        raise RuntimeError("CMake cache HOST_CXX_COMPILER entry must have FILEPATH type")
    configured = Path(configured_value.strip()).resolve()
    if configured != expected.resolve():
        raise RuntimeError(
            f"configured HOST_CXX_COMPILER mismatch: expected {expected.resolve()}, got {configured}"
        )


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


TreeIdentity = dict[str, tuple[str, int, int, str]]
TreeInodes = dict[str, tuple[int, int]]
TreeSnapshot = tuple[tuple[int, int], TreeIdentity, TreeInodes]


def _unsafe_link_or_reparse(path_stat: os.stat_result) -> bool:
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    attributes = getattr(path_stat, "st_file_attributes", 0)
    return stat.S_ISLNK(path_stat.st_mode) or bool(reparse_flag and attributes & reparse_flag)


def _lstat_or_none(path: Path) -> os.stat_result | None:
    try:
        return path.lstat()
    except FileNotFoundError:
        return None


def _read_regular_nofollow(path: Path, label: str) -> tuple[bytes, os.stat_result]:
    before = path.lstat()
    if _unsafe_link_or_reparse(before) or not stat.S_ISREG(before.st_mode):
        raise RuntimeError(f"{label} must be an ordinary non-link file: {path}")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NONBLOCK", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        opened = os.fstat(descriptor)
        if (
            _unsafe_link_or_reparse(opened)
            or not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)
        ):
            raise RuntimeError(f"{label} changed while being opened: {path}")
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        payload = b"".join(chunks)
        after = os.fstat(descriptor)
        if (
            (after.st_dev, after.st_ino) != (opened.st_dev, opened.st_ino)
            or after.st_size != opened.st_size
            or after.st_mtime_ns != opened.st_mtime_ns
            or after.st_ctime_ns != opened.st_ctime_ns
            or len(payload) != after.st_size
        ):
            raise RuntimeError(f"{label} changed while being read: {path}")
        final_path_stat = path.lstat()
        if (
            _unsafe_link_or_reparse(final_path_stat)
            or (final_path_stat.st_dev, final_path_stat.st_ino) != (after.st_dev, after.st_ino)
        ):
            raise RuntimeError(f"{label} changed after being read: {path}")
        return payload, after
    finally:
        os.close(descriptor)


def _validated_tree_snapshot(root: Path, label: str) -> TreeSnapshot:
    try:
        root_stat = root.lstat()
    except OSError as exc:
        raise RuntimeError(f"unable to inspect {label}: {root}") from exc
    if _unsafe_link_or_reparse(root_stat) or not stat.S_ISDIR(root_stat.st_mode):
        raise RuntimeError(f"{label} must be an ordinary non-link directory: {root}")
    root_inode = (root_stat.st_dev, root_stat.st_ino)
    identity: TreeIdentity = {
        ".": ("directory", stat.S_IMODE(root_stat.st_mode), 0, "")
    }
    inodes: TreeInodes = {".": root_inode}
    pending_directories = [(root, PurePosixPath(), root_inode)]
    while pending_directories:
        directory, relative_parent, expected_inode = pending_directories.pop()
        directory_stat = directory.lstat()
        if (
            _unsafe_link_or_reparse(directory_stat)
            or not stat.S_ISDIR(directory_stat.st_mode)
            or (directory_stat.st_dev, directory_stat.st_ino) != expected_inode
        ):
            raise RuntimeError(f"{label} directory changed during traversal: {directory}")
        with os.scandir(directory) as entries_handle:
            entries = sorted(entries_handle, key=lambda entry: entry.name)
        child_directories = []
        for entry in entries:
            candidate = Path(entry.path)
            relative_path = relative_parent / entry.name
            relative = relative_path.as_posix()
            # Native Windows DirEntry.stat() can expose zeroed file identities
            # for directories; lstat() is the cross-platform authority used by
            # every later inode/reparse comparison in this transaction.
            candidate_stat = candidate.lstat()
            if _unsafe_link_or_reparse(candidate_stat):
                raise RuntimeError(f"{label} contains a link or reparse point: {relative}")
            if stat.S_ISDIR(candidate_stat.st_mode):
                identity[relative] = (
                    "directory",
                    stat.S_IMODE(candidate_stat.st_mode),
                    0,
                    "",
                )
                child_directories.append(
                    (
                        candidate,
                        relative_path,
                        (candidate_stat.st_dev, candidate_stat.st_ino),
                    )
                )
                inodes[relative] = (candidate_stat.st_dev, candidate_stat.st_ino)
            elif stat.S_ISREG(candidate_stat.st_mode):
                payload, opened = _read_regular_nofollow(candidate, label)
                identity[relative] = (
                    "file",
                    stat.S_IMODE(opened.st_mode),
                    len(payload),
                    hashlib.sha256(payload).hexdigest(),
                )
                inodes[relative] = (opened.st_dev, opened.st_ino)
            else:
                raise RuntimeError(f"{label} contains a special file: {relative}")
        pending_directories.extend(reversed(child_directories))
    final_root_stat = root.lstat()
    if (
        _unsafe_link_or_reparse(final_root_stat)
        or not stat.S_ISDIR(final_root_stat.st_mode)
        or (final_root_stat.st_dev, final_root_stat.st_ino) != root_inode
        or stat.S_IMODE(final_root_stat.st_mode) != identity["."][1]
    ):
        raise RuntimeError(f"{label} root changed during validation: {root}")
    return root_inode, identity, inodes


def _inventory_relative_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise RuntimeError(f"{label} must be one canonical relative POSIX path")
    if "\x00" in value or "\\" in value or ":" in value:
        raise RuntimeError(f"{label} must be one canonical relative POSIX path")
    raw_parts = value.split("/")
    if any(part in {"", ".", ".."} for part in raw_parts):
        raise RuntimeError(f"{label} must be one canonical relative POSIX path")
    relative = PurePosixPath(value)
    if relative.is_absolute() or relative.as_posix() != value:
        raise RuntimeError(f"{label} must be one canonical relative POSIX path")
    return value


def _inventory_size(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError(f"{label} must be one nonnegative integer")
    return value


def _inventory_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise RuntimeError(f"{label} must be one lowercase SHA-256 digest")
    return value


def _manifest_closed_inventory(
    root: Path,
    ownership_manifest: Path,
    identity: TreeIdentity,
) -> None:
    manifest_relative = ownership_manifest.as_posix()
    manifest_entry = identity.get(manifest_relative)
    if manifest_entry is None or manifest_entry[0] != "file":
        raise RuntimeError(
            f"refusing to replace non-empty unowned output directory without "
            f"{ownership_manifest}: {root}"
        )
    try:
        manifest_bytes, _ = _read_regular_nofollow(
            root / ownership_manifest,
            "ownership manifest",
        )
        if (
            len(manifest_bytes),
            hashlib.sha256(manifest_bytes).hexdigest(),
        ) != (manifest_entry[2], manifest_entry[3]):
            raise RuntimeError(f"output ownership manifest changed during validation: {root}")
        manifest = json.loads(manifest_bytes)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"invalid output ownership manifest: {root}") from exc
    if not isinstance(manifest, dict):
        raise RuntimeError(f"invalid output ownership manifest object: {root}")
    if manifest.get("schema_version") != 1 or manifest.get("generated_by") != MANIFEST_GENERATOR:
        raise RuntimeError(f"unsupported or forged output ownership manifest: {root}")

    expected_files: dict[str, tuple[int, str] | None] = {manifest_relative: None}
    normalized_paths = {manifest_relative.casefold()}

    def add_record(record: Any, path_key: str, label: str) -> None:
        if not isinstance(record, dict):
            raise RuntimeError(f"{label} must be an object")
        path = _inventory_relative_path(record.get(path_key), f"{label}.{path_key}")
        normalized = path.casefold()
        if path in expected_files or normalized in normalized_paths:
            raise RuntimeError(f"duplicate or case-colliding output inventory path: {path}")
        expected_files[path] = (
            _inventory_size(record.get("size_bytes"), f"{label}.size_bytes"),
            _inventory_sha256(record.get("sha256"), f"{label}.sha256"),
        )
        normalized_paths.add(normalized)

    if ownership_manifest == Path(MANIFEST_NAME):
        artifacts = manifest.get("artifacts")
        if not isinstance(artifacts, dict) or not artifacts:
            raise RuntimeError("JNI ownership manifest lacks artifact inventory")
        for name, record in artifacts.items():
            if not isinstance(name, str) or not name:
                raise RuntimeError("JNI ownership manifest artifact names must be nonempty strings")
            add_record(record, "relative_path", f"artifacts[{name!r}]")
    elif ownership_manifest == Path(PACKAGED_MANIFEST_ASSET):
        licenses = manifest.get("license_artifacts")
        if not isinstance(licenses, list) or not licenses:
            raise RuntimeError("assets ownership manifest lacks license inventory")
        for index, record in enumerate(licenses):
            add_record(record, "packaged_asset_path", f"license_artifacts[{index}]")
    else:
        raise RuntimeError(f"unsupported ownership manifest location: {ownership_manifest}")

    expected_dirs = {"."}
    for path in expected_files:
        parent = PurePosixPath(path).parent
        while parent.as_posix() != ".":
            expected_dirs.add(parent.as_posix())
            parent = parent.parent
    actual_files = {path for path, record in identity.items() if record[0] == "file"}
    actual_dirs = {path for path, record in identity.items() if record[0] == "directory"}
    if actual_files != set(expected_files) or actual_dirs != expected_dirs:
        raise RuntimeError("owned output tree is not closed to its manifest inventory")
    for path, expected in expected_files.items():
        if expected is None:
            continue
        record = identity[path]
        if (record[2], record[3]) != expected:
            raise RuntimeError(f"owned output does not match manifest inventory: {path}")


def _owned_tree_snapshot(root: Path, ownership_manifest: Path, label: str) -> TreeSnapshot:
    snapshot = _validated_tree_snapshot(root, label)
    if set(snapshot[1]) != {"."}:
        _manifest_closed_inventory(root, ownership_manifest, snapshot[1])
    if _validated_tree_snapshot(root, label) != snapshot:
        raise RuntimeError(f"{label} changed while its ownership inventory was validated: {root}")
    return snapshot


def _assert_replaceable_output(
    output_dir: Path,
    ownership_manifest: Path = Path(MANIFEST_NAME),
) -> TreeSnapshot | None:
    if _lstat_or_none(output_dir) is None:
        return None
    return _owned_tree_snapshot(
        output_dir,
        ownership_manifest,
        "existing experimental llama output",
    )


def _plain_directory_chain_snapshot(path: Path, label: str) -> tuple[tuple[str, int, int], ...]:
    absolute = Path(os.path.abspath(os.fspath(path)))
    if not absolute.is_absolute():
        raise RuntimeError(f"{label} must be absolute: {path}")
    current = Path(absolute.anchor)
    records: list[tuple[str, int, int]] = []
    for part in absolute.parts[1:]:
        current /= part
        current_stat = current.lstat()
        if _unsafe_link_or_reparse(current_stat) or not stat.S_ISDIR(current_stat.st_mode):
            raise RuntimeError(f"{label} contains a link, reparse point, or non-directory: {current}")
        records.append((os.path.normcase(os.fspath(current)), current_stat.st_dev, current_stat.st_ino))
    return tuple(records)


def _set_windows_file_times_bound(
    descriptor: int,
    timestamps: tuple[int, int],
) -> None:
    import ctypes
    import msvcrt
    from ctypes import wintypes

    epoch_delta_100ns = 116_444_736_000_000_000

    def as_filetime(timestamp_ns: int) -> wintypes.FILETIME:
        ticks = timestamp_ns // 100 + epoch_delta_100ns
        if ticks < 0 or ticks > 0xFFFFFFFFFFFFFFFF:
            raise RuntimeError(f"timestamp is outside the Windows FILETIME range: {timestamp_ns}")
        return wintypes.FILETIME(ticks & 0xFFFFFFFF, ticks >> 32)

    access_time = as_filetime(timestamps[0])
    write_time = as_filetime(timestamps[1])
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_file_time = kernel32.SetFileTime
    set_file_time.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
    ]
    set_file_time.restype = wintypes.BOOL
    native_handle = msvcrt.get_osfhandle(descriptor)
    if native_handle == -1:
        raise OSError("unable to resolve the native Windows destination handle")
    if not set_file_time(
        wintypes.HANDLE(native_handle),
        None,
        ctypes.byref(access_time),
        ctypes.byref(write_time),
    ):
        raise ctypes.WinError(ctypes.get_last_error())


def _set_file_times_bound(
    path: Path,
    descriptor: int,
    source_stat: os.stat_result,
) -> None:
    timestamps = (source_stat.st_atime_ns, source_stat.st_mtime_ns)
    if os.utime in os.supports_fd:
        os.utime(descriptor, ns=timestamps)
        return

    opened = os.fstat(descriptor)
    path_before = path.lstat()
    if (
        _unsafe_link_or_reparse(opened)
        or not stat.S_ISREG(opened.st_mode)
        or _unsafe_link_or_reparse(path_before)
        or not stat.S_ISREG(path_before.st_mode)
        or (path_before.st_dev, path_before.st_ino) != (opened.st_dev, opened.st_ino)
    ):
        raise RuntimeError(f"copied output identity changed before timestamp update: {path}")

    if os.name == "nt":
        _set_windows_file_times_bound(descriptor, timestamps)
    elif os.utime in os.supports_follow_symlinks:
        os.utime(path, ns=timestamps, follow_symlinks=False)
    else:
        raise RuntimeError("platform lacks a descriptor-bound or no-follow timestamp API")

    path_after = path.lstat()
    descriptor_after = os.fstat(descriptor)
    if (
        _unsafe_link_or_reparse(path_after)
        or not stat.S_ISREG(path_after.st_mode)
        or (path_after.st_dev, path_after.st_ino)
        != (descriptor_after.st_dev, descriptor_after.st_ino)
        or (descriptor_after.st_dev, descriptor_after.st_ino)
        != (opened.st_dev, opened.st_ino)
    ):
        raise RuntimeError(f"copied output identity changed during timestamp update: {path}")


def _copy_validated_tree(source: Path, destination: Path, source_snapshot: TreeSnapshot) -> None:
    source_inode, source_identity, _ = source_snapshot
    if _validated_tree_snapshot(source, "staged experimental llama output") != source_snapshot:
        raise RuntimeError("staged experimental llama output changed before copying")
    destination.mkdir(mode=0o700)
    destination_stat = destination.lstat()
    if _unsafe_link_or_reparse(destination_stat) or not stat.S_ISDIR(destination_stat.st_mode):
        raise RuntimeError("incoming output root is not an ordinary directory")
    copied_directories: list[tuple[Path, int]] = []
    for relative, record in sorted(source_identity.items()):
        if relative == ".":
            continue
        source_path = source / PurePosixPath(relative)
        destination_path = destination / PurePosixPath(relative)
        if record[0] == "directory":
            destination_path.mkdir(mode=0o700)
            copied_directories.append((destination_path, record[1]))
            continue
        payload, source_stat = _read_regular_nofollow(source_path, "staged output file")
        if len(payload) != record[2] or hashlib.sha256(payload).hexdigest() != record[3]:
            raise RuntimeError(f"staged output file changed during copying: {relative}")
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        flags |= getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(destination_path, flags, 0o600)
        try:
            view = memoryview(payload)
            while view:
                written = os.write(descriptor, view)
                if written <= 0:
                    raise OSError(f"unable to finish writing copied output: {relative}")
                view = view[written:]
            os.fchmod(descriptor, record[1])
            _set_file_times_bound(destination_path, descriptor, source_stat)
        finally:
            os.close(descriptor)
    for directory, mode in reversed(copied_directories):
        os.chmod(directory, mode)
    os.chmod(destination, source_identity["."][1])
    final_source = _validated_tree_snapshot(source, "staged experimental llama output")
    if final_source[0] != source_inode or final_source != source_snapshot:
        raise RuntimeError("staged experimental llama output changed while being copied")


def _new_private_directory(parent: Path, prefix: str) -> tuple[Path, tuple[int, int]]:
    created = Path(tempfile.mkdtemp(prefix=prefix, dir=parent))
    created_inode: tuple[int, int] | None = None
    try:
        created_stat = created.lstat()
        created_inode = (created_stat.st_dev, created_stat.st_ino)
        if _unsafe_link_or_reparse(created_stat) or not stat.S_ISDIR(created_stat.st_mode):
            raise RuntimeError(f"unsafe private directory created: {created}")
        os.chmod(created, 0o700)
        final_stat = created.lstat()
        if (
            _unsafe_link_or_reparse(final_stat)
            or not stat.S_ISDIR(final_stat.st_mode)
            or (final_stat.st_dev, final_stat.st_ino) != created_inode
        ):
            raise RuntimeError(f"private directory identity changed during creation: {created}")
        return created, created_inode
    except BaseException:
        current = _lstat_or_none(created)
        if (
            created_inode is not None
            and current is not None
            and not _unsafe_link_or_reparse(current)
            and stat.S_ISDIR(current.st_mode)
            and (current.st_dev, current.st_ino) == created_inode
        ):
            try:
                created.rmdir()
            except OSError:
                pass
        raise


def _new_transaction_root(parent: Path, output_name: str) -> tuple[Path, tuple[int, int]]:
    return _new_private_directory(parent, f".{output_name}.transaction-")


def _read_descriptor_bytes(descriptor: int, label: str) -> tuple[bytes, os.stat_result]:
    opened = os.fstat(descriptor)
    if _unsafe_link_or_reparse(opened) or not stat.S_ISREG(opened.st_mode):
        raise RuntimeError(f"{label} is not an ordinary file")
    chunks = []
    while True:
        chunk = os.read(descriptor, 1024 * 1024)
        if not chunk:
            break
        chunks.append(chunk)
    payload = b"".join(chunks)
    final = os.fstat(descriptor)
    if (
        (final.st_dev, final.st_ino) != (opened.st_dev, opened.st_ino)
        or final.st_size != opened.st_size
        or final.st_mtime_ns != opened.st_mtime_ns
        or final.st_ctime_ns != opened.st_ctime_ns
        or len(payload) != final.st_size
    ):
        raise RuntimeError(f"{label} changed while being read")
    return payload, final


def _expected_tree_children(identity: TreeIdentity) -> dict[str, set[str]]:
    children = {
        relative: set()
        for relative, record in identity.items()
        if record[0] == "directory"
    }
    for relative in identity:
        if relative == ".":
            continue
        relative_path = PurePosixPath(relative)
        parent_relative = relative_path.parent.as_posix()
        parent_key = parent_relative if parent_relative != "." else "."
        children[parent_key].add(relative_path.name)
    return children


def _remove_validated_tree_posix(
    path: Path,
    expected: TreeSnapshot,
    parent_inode: tuple[int, int],
) -> None:
    directory_flags = (
        os.O_RDONLY
        | os.O_DIRECTORY
        | os.O_NOFOLLOW
        | getattr(os, "O_CLOEXEC", 0)
    )
    parent_descriptor = os.open(path.parent, directory_flags)
    directory_descriptors: dict[str, int] = {}
    file_descriptors: dict[str, tuple[int, os.stat_result]] = {}
    try:
        parent_stat = os.fstat(parent_descriptor)
        if (
            _unsafe_link_or_reparse(parent_stat)
            or not stat.S_ISDIR(parent_stat.st_mode)
            or (parent_stat.st_dev, parent_stat.st_ino) != parent_inode
        ):
            raise RuntimeError(f"transaction deletion parent identity changed: {path.parent}")
        root_descriptor = os.open(path.name, directory_flags, dir_fd=parent_descriptor)
        directory_descriptors["."] = root_descriptor
        root_stat = os.fstat(root_descriptor)
        if (
            _unsafe_link_or_reparse(root_stat)
            or not stat.S_ISDIR(root_stat.st_mode)
            or (root_stat.st_dev, root_stat.st_ino) != expected[0]
            or stat.S_IMODE(root_stat.st_mode) != expected[1]["."][1]
        ):
            raise RuntimeError(f"transaction deletion root identity changed: {path}")

        directory_records = sorted(
            (
                (relative, record)
                for relative, record in expected[1].items()
                if relative != "." and record[0] == "directory"
            ),
            key=lambda item: (len(PurePosixPath(item[0]).parts), item[0]),
        )
        for relative, record in directory_records:
            relative_path = PurePosixPath(relative)
            parent_relative = relative_path.parent.as_posix()
            parent_key = parent_relative if parent_relative != "." else "."
            descriptor = os.open(
                relative_path.name,
                directory_flags,
                dir_fd=directory_descriptors[parent_key],
            )
            descriptor_stat = os.fstat(descriptor)
            if (
                _unsafe_link_or_reparse(descriptor_stat)
                or not stat.S_ISDIR(descriptor_stat.st_mode)
                or stat.S_IMODE(descriptor_stat.st_mode) != record[1]
                or (descriptor_stat.st_dev, descriptor_stat.st_ino) != expected[2][relative]
            ):
                os.close(descriptor)
                raise RuntimeError(f"transaction deletion directory changed: {relative}")
            directory_descriptors[relative] = descriptor

        file_records = sorted(
            (
                (relative, record)
                for relative, record in expected[1].items()
                if record[0] == "file"
            ),
            key=lambda item: item[0],
        )
        for relative, record in file_records:
            relative_path = PurePosixPath(relative)
            parent_relative = relative_path.parent.as_posix()
            parent_key = parent_relative if parent_relative != "." else "."
            parent_fd = directory_descriptors[parent_key]
            file_flags = (
                os.O_RDONLY
                | os.O_NOFOLLOW
                | getattr(os, "O_NONBLOCK", 0)
                | getattr(os, "O_CLOEXEC", 0)
            )
            descriptor = os.open(relative_path.name, file_flags, dir_fd=parent_fd)
            try:
                payload, descriptor_stat = _read_descriptor_bytes(
                    descriptor,
                    f"transaction deletion file {relative}",
                )
                if (
                    stat.S_IMODE(descriptor_stat.st_mode),
                    len(payload),
                    hashlib.sha256(payload).hexdigest(),
                    (descriptor_stat.st_dev, descriptor_stat.st_ino),
                ) != (record[1], record[2], record[3], expected[2][relative]):
                    raise RuntimeError(f"transaction deletion file changed: {relative}")
                file_descriptors[relative] = (descriptor, descriptor_stat)
            except BaseException:
                os.close(descriptor)
                raise

        expected_children = _expected_tree_children(expected[1])
        for relative, descriptor in directory_descriptors.items():
            if set(os.listdir(descriptor)) != expected_children[relative]:
                raise RuntimeError(f"transaction deletion directory inventory changed: {relative}")

        for relative, _record in file_records:
            relative_path = PurePosixPath(relative)
            parent_relative = relative_path.parent.as_posix()
            parent_key = parent_relative if parent_relative != "." else "."
            parent_fd = directory_descriptors[parent_key]
            descriptor, descriptor_stat = file_descriptors.pop(relative)
            try:
                path_stat = os.stat(
                    relative_path.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    _unsafe_link_or_reparse(path_stat)
                    or not stat.S_ISREG(path_stat.st_mode)
                    or (path_stat.st_dev, path_stat.st_ino)
                    != (descriptor_stat.st_dev, descriptor_stat.st_ino)
                ):
                    raise RuntimeError(f"transaction deletion file path changed: {relative}")
                os.unlink(relative_path.name, dir_fd=parent_fd)
            finally:
                os.close(descriptor)

        for relative, record in reversed(directory_records):
            relative_path = PurePosixPath(relative)
            parent_relative = relative_path.parent.as_posix()
            parent_key = parent_relative if parent_relative != "." else "."
            parent_fd = directory_descriptors[parent_key]
            descriptor = directory_descriptors.pop(relative)
            try:
                descriptor_stat = os.fstat(descriptor)
                path_stat = os.stat(
                    relative_path.name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    _unsafe_link_or_reparse(path_stat)
                    or not stat.S_ISDIR(path_stat.st_mode)
                    or stat.S_IMODE(descriptor_stat.st_mode) != record[1]
                    or (descriptor_stat.st_dev, descriptor_stat.st_ino) != expected[2][relative]
                    or (path_stat.st_dev, path_stat.st_ino)
                    != (descriptor_stat.st_dev, descriptor_stat.st_ino)
                    or os.listdir(descriptor)
                ):
                    raise RuntimeError(f"transaction deletion directory path changed: {relative}")
            finally:
                os.close(descriptor)
            os.rmdir(relative_path.name, dir_fd=parent_fd)

        root_descriptor = directory_descriptors.pop(".")
        try:
            root_path_stat = os.stat(path.name, dir_fd=parent_descriptor, follow_symlinks=False)
            root_descriptor_stat = os.fstat(root_descriptor)
            if (
                _unsafe_link_or_reparse(root_path_stat)
                or not stat.S_ISDIR(root_path_stat.st_mode)
                or (root_descriptor_stat.st_dev, root_descriptor_stat.st_ino) != expected[0]
                or (root_path_stat.st_dev, root_path_stat.st_ino)
                != (root_descriptor_stat.st_dev, root_descriptor_stat.st_ino)
                or os.listdir(root_descriptor)
            ):
                raise RuntimeError(f"transaction deletion root path changed: {path}")
        finally:
            os.close(root_descriptor)
        os.rmdir(path.name, dir_fd=parent_descriptor)
    finally:
        for descriptor, _ in file_descriptors.values():
            try:
                os.close(descriptor)
            except OSError:
                pass
        for descriptor in directory_descriptors.values():
            try:
                os.close(descriptor)
            except OSError:
                pass
        os.close(parent_descriptor)


def _remove_validated_tree_windows(
    path: Path,
    expected: TreeSnapshot,
    parent_inode: tuple[int, int],
    parent_mode: int,
) -> bool:
    """Delete a bound Windows tree through retained directory/file handles.

    Native Windows has no ``dir_fd``/``openat`` support in :mod:`os`.  Relative
    ``NtCreateFile`` calls keep every child open beneath its retained parent
    handle with reparse processing disabled.  Deletion is then requested on the
    opened object itself, never by reconstructing a descendant pathname.
    """

    import ctypes
    from ctypes import wintypes

    class UnicodeString(ctypes.Structure):
        _fields_ = [
            ("Length", wintypes.USHORT),
            ("MaximumLength", wintypes.USHORT),
            ("Buffer", wintypes.LPWSTR),
        ]

    class ObjectAttributes(ctypes.Structure):
        _fields_ = [
            ("Length", wintypes.ULONG),
            ("RootDirectory", wintypes.HANDLE),
            ("ObjectName", ctypes.POINTER(UnicodeString)),
            ("Attributes", wintypes.ULONG),
            ("SecurityDescriptor", wintypes.LPVOID),
            ("SecurityQualityOfService", wintypes.LPVOID),
        ]

    class IoStatusUnion(ctypes.Union):
        _fields_ = [("Status", wintypes.LONG), ("Pointer", wintypes.LPVOID)]

    class IoStatusBlock(ctypes.Structure):
        _anonymous_ = ("result",)
        _fields_ = [("result", IoStatusUnion), ("Information", ctypes.c_size_t)]

    class ByHandleFileInformation(ctypes.Structure):
        _fields_ = [
            ("dwFileAttributes", wintypes.DWORD),
            ("ftCreationTime", wintypes.FILETIME),
            ("ftLastAccessTime", wintypes.FILETIME),
            ("ftLastWriteTime", wintypes.FILETIME),
            ("dwVolumeSerialNumber", wintypes.DWORD),
            ("nFileSizeHigh", wintypes.DWORD),
            ("nFileSizeLow", wintypes.DWORD),
            ("nNumberOfLinks", wintypes.DWORD),
            ("nFileIndexHigh", wintypes.DWORD),
            ("nFileIndexLow", wintypes.DWORD),
        ]

    class FileDispositionInformation(ctypes.Structure):
        _fields_ = [("DeleteFile", wintypes.BOOLEAN)]

    class FileId128(ctypes.Structure):
        _fields_ = [("Identifier", ctypes.c_ubyte * 16)]

    class FileIdInformation(ctypes.Structure):
        _fields_ = [
            ("VolumeSerialNumber", ctypes.c_ulonglong),
            ("FileId", FileId128),
        ]

    class FileIdExtdDirectoryInformation(ctypes.Structure):
        _fields_ = [
            ("NextEntryOffset", wintypes.ULONG),
            ("FileIndex", wintypes.ULONG),
            ("CreationTime", ctypes.c_longlong),
            ("LastAccessTime", ctypes.c_longlong),
            ("LastWriteTime", ctypes.c_longlong),
            ("ChangeTime", ctypes.c_longlong),
            ("EndOfFile", ctypes.c_longlong),
            ("AllocationSize", ctypes.c_longlong),
            ("FileAttributes", wintypes.ULONG),
            ("FileNameLength", wintypes.ULONG),
            ("EaSize", wintypes.ULONG),
            ("ReparsePointTag", wintypes.ULONG),
            ("FileId", FileId128),
            ("FileName", wintypes.WCHAR * 1),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    ntdll = ctypes.WinDLL("ntdll")
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    get_information = kernel32.GetFileInformationByHandle
    get_information.argtypes = [wintypes.HANDLE, ctypes.POINTER(ByHandleFileInformation)]
    get_information.restype = wintypes.BOOL
    get_information_ex = kernel32.GetFileInformationByHandleEx
    get_information_ex.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        wintypes.LPVOID,
        wintypes.DWORD,
    ]
    get_information_ex.restype = wintypes.BOOL
    read_file = kernel32.ReadFile
    read_file.argtypes = [
        wintypes.HANDLE,
        wintypes.LPVOID,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
        wintypes.LPVOID,
    ]
    read_file.restype = wintypes.BOOL
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD]
    set_information.restype = wintypes.BOOL
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    nt_create_file = ntdll.NtCreateFile
    nt_create_file.argtypes = [
        ctypes.POINTER(wintypes.HANDLE),
        wintypes.ULONG,
        ctypes.POINTER(ObjectAttributes),
        ctypes.POINTER(IoStatusBlock),
        wintypes.LPVOID,
        wintypes.ULONG,
        wintypes.ULONG,
        wintypes.ULONG,
        wintypes.ULONG,
        wintypes.LPVOID,
        wintypes.ULONG,
    ]
    nt_create_file.restype = wintypes.LONG
    ntstatus_to_dos_error = ntdll.RtlNtStatusToDosError
    ntstatus_to_dos_error.argtypes = [wintypes.LONG]
    ntstatus_to_dos_error.restype = wintypes.ULONG

    delete_access = 0x00010000
    synchronize = 0x00100000
    file_read_data = 0x00000001
    file_list_directory = 0x00000001
    file_traverse = 0x00000020
    file_read_attributes = 0x00000080
    file_share_read = 0x00000001
    open_existing = 3
    file_open = 1
    file_attribute_readonly = 0x00000001
    file_attribute_directory = 0x00000010
    file_attribute_reparse_point = 0x00000400
    file_flag_backup_semantics = 0x02000000
    file_flag_open_reparse_point = 0x00200000
    file_directory_file = 0x00000001
    file_synchronous_io_nonalert = 0x00000020
    file_non_directory_file = 0x00000040
    file_open_reparse_point = 0x00200000
    obj_dont_reparse = 0x00001000
    file_disposition_info = 4
    file_id_info = 0x12
    file_id_extd_directory_info = 0x13
    file_id_extd_directory_restart_info = 0x14
    error_no_more_files = 18
    invalid_handle_value = ctypes.c_void_p(-1).value

    def raise_last_error(label: str) -> None:
        error = ctypes.get_last_error()
        raise OSError(error, f"{label}: {ctypes.FormatError(error)}")

    def close_checked(handle: int, label: str) -> None:
        if not close_handle(wintypes.HANDLE(handle)):
            raise_last_error(f"unable to close {label}")

    def information(handle: int, label: str) -> ByHandleFileInformation:
        result = ByHandleFileInformation()
        if not get_information(wintypes.HANDLE(handle), ctypes.byref(result)):
            raise_last_error(f"unable to inspect {label}")
        return result

    def file_size(info: ByHandleFileInformation) -> int:
        return (int(info.nFileSizeHigh) << 32) | int(info.nFileSizeLow)

    def handle_identity(handle: int, label: str) -> tuple[int, int]:
        result = FileIdInformation()
        if not get_information_ex(
            wintypes.HANDLE(handle),
            file_id_info,
            ctypes.byref(result),
            ctypes.sizeof(result),
        ):
            raise_last_error(f"unable to query full identity for {label}")
        return (
            int(result.VolumeSerialNumber),
            int.from_bytes(bytes(result.FileId.Identifier), "little"),
        )

    def permission_mode(info: ByHandleFileInformation, is_directory: bool) -> int:
        mode = 0o555 if is_directory else 0o444
        if not info.dwFileAttributes & file_attribute_readonly:
            mode |= 0o222
        return mode

    def require_information(
        handle: int,
        label: str,
        *,
        is_directory: bool,
        expected_mode: int,
        expected_identity: tuple[int, int] | None = None,
    ) -> ByHandleFileInformation:
        info = information(handle, label)
        attributes = info.dwFileAttributes
        if attributes & file_attribute_reparse_point:
            raise RuntimeError(f"{label} became a reparse point")
        if attributes & file_attribute_readonly:
            raise RuntimeError(f"{label} became read-only")
        actual_directory = bool(attributes & file_attribute_directory)
        if actual_directory != is_directory or permission_mode(info, is_directory) != expected_mode:
            raise RuntimeError(f"{label} type or mode changed")
        if expected_identity is not None and handle_identity(handle, label) != expected_identity:
            raise RuntimeError(f"{label} file identity changed")
        return info

    def extended_path(value: Path) -> str:
        absolute = os.path.abspath(os.fspath(value))
        if absolute.startswith("\\\\?\\"):
            return absolute
        if absolute.startswith("\\\\"):
            return "\\\\?\\UNC\\" + absolute[2:]
        return "\\\\?\\" + absolute

    def open_root(
        value: Path,
        expected_identity: tuple[int, int],
        expected_mode: int,
    ) -> int:
        desired_access = (
            synchronize
            | file_list_directory
            | file_traverse
            | file_read_attributes
        )
        handle = create_file(
            extended_path(value),
            desired_access,
            file_share_read,
            None,
            open_existing,
            file_flag_backup_semantics | file_flag_open_reparse_point,
            None,
        )
        if handle == invalid_handle_value:
            raise_last_error(f"unable to open transaction cleanup parent {value}")
        handle_value = int(handle)
        try:
            require_information(
                handle_value,
                "transaction cleanup parent",
                is_directory=True,
                expected_mode=expected_mode,
                expected_identity=expected_identity,
            )
        except BaseException:
            close_handle(wintypes.HANDLE(handle_value))
            raise
        return handle_value

    def open_relative(parent_handle: int, name: str, is_directory: bool) -> int:
        if (
            not name
            or name in {".", ".."}
            or "\x00" in name
            or ":" in name
            or "\\" in name
            or "/" in name
        ):
            raise RuntimeError(f"unsafe transaction cleanup component: {name!r}")
        encoded_name = name.encode("utf-16-le")
        buffer = ctypes.create_string_buffer(encoded_name + b"\x00\x00")
        encoded_length = len(encoded_name)
        unicode_name = UnicodeString(
            encoded_length,
            encoded_length + 2,
            ctypes.cast(buffer, wintypes.LPWSTR),
        )
        object_attributes = ObjectAttributes(
            ctypes.sizeof(ObjectAttributes),
            wintypes.HANDLE(parent_handle),
            ctypes.pointer(unicode_name),
            obj_dont_reparse,
            None,
            None,
        )
        io_status = IoStatusBlock()
        handle = wintypes.HANDLE()
        desired_access = delete_access | synchronize | file_read_attributes
        desired_access |= file_list_directory | file_traverse if is_directory else file_read_data
        options = file_synchronous_io_nonalert | file_open_reparse_point
        options |= file_directory_file if is_directory else file_non_directory_file
        status = nt_create_file(
            ctypes.byref(handle),
            desired_access,
            ctypes.byref(object_attributes),
            ctypes.byref(io_status),
            None,
            0,
            file_share_read,
            file_open,
            options,
            None,
            0,
        )
        if status < 0:
            error = int(ntstatus_to_dos_error(status))
            raise OSError(error, f"unable to open bound cleanup component {name!r}: {ctypes.FormatError(error)}")
        if int(io_status.Information) != 1:
            close_handle(handle)
            raise RuntimeError(f"bound cleanup component was not opened as an existing object: {name!r}")
        return int(handle.value)

    def read_handle(handle: int, label: str) -> bytes:
        chunks = []
        buffer = ctypes.create_string_buffer(1024 * 1024)
        while True:
            read = wintypes.DWORD()
            if not read_file(
                wintypes.HANDLE(handle),
                buffer,
                len(buffer),
                ctypes.byref(read),
                None,
            ):
                raise_last_error(f"unable to read {label}")
            if read.value == 0:
                break
            chunks.append(buffer.raw[: read.value])
        return b"".join(chunks)

    def enumerate_directory(handle: int, label: str) -> dict[str, tuple[int, int]]:
        entries: dict[str, tuple[int, int]] = {}
        normalized_names: set[str] = set()
        information_class = file_id_extd_directory_restart_info
        file_name_offset = FileIdExtdDirectoryInformation.FileName.offset
        buffer_size = 64 * 1024
        while True:
            buffer = ctypes.create_string_buffer(buffer_size)
            if not get_information_ex(
                wintypes.HANDLE(handle),
                information_class,
                buffer,
                buffer_size,
            ):
                error = ctypes.get_last_error()
                if error == error_no_more_files:
                    break
                raise OSError(error, f"unable to enumerate {label}: {ctypes.FormatError(error)}")
            information_class = file_id_extd_directory_info
            offset = 0
            while True:
                if offset + file_name_offset > buffer_size:
                    raise RuntimeError(f"malformed directory enumeration for {label}")
                entry = FileIdExtdDirectoryInformation.from_buffer_copy(
                    buffer.raw[offset : offset + ctypes.sizeof(FileIdExtdDirectoryInformation)]
                )
                name_length = int(entry.FileNameLength)
                if name_length % 2:
                    raise RuntimeError(f"odd UTF-16 filename length while enumerating {label}")
                name_start = offset + file_name_offset
                name_end = name_start + name_length
                if name_end > buffer_size:
                    raise RuntimeError(f"directory filename escapes enumeration buffer for {label}")
                name = buffer.raw[name_start:name_end].decode("utf-16-le", errors="strict")
                if name not in {".", ".."}:
                    normalized = name.casefold()
                    if name in entries or normalized in normalized_names:
                        raise RuntimeError(f"duplicate or case-colliding directory entry in {label}: {name}")
                    if entry.FileAttributes & file_attribute_reparse_point:
                        raise RuntimeError(f"reparse point encountered while enumerating {label}: {name}")
                    entries[name] = (
                        int(entry.FileAttributes),
                        int.from_bytes(bytes(entry.FileId.Identifier), "little"),
                    )
                    normalized_names.add(normalized)
                next_offset = int(entry.NextEntryOffset)
                if next_offset == 0:
                    break
                if next_offset < file_name_offset or offset + next_offset >= buffer_size:
                    raise RuntimeError(f"malformed directory record offset while enumerating {label}")
                offset += next_offset
        return entries

    def mark_delete(handle: int, label: str) -> None:
        disposition = FileDispositionInformation(1)
        if not set_information(
            wintypes.HANDLE(handle),
            file_disposition_info,
            ctypes.byref(disposition),
            ctypes.sizeof(disposition),
        ):
            raise_last_error(f"unable to mark {label} for deletion")

    parent_handle: int | None = None
    directory_handles: dict[str, int] = {}
    file_handles: dict[str, int] = {}
    try:
        parent_handle = open_root(path.parent, parent_inode, parent_mode)
        root_handle = open_relative(parent_handle, path.name, True)
        directory_handles["."] = root_handle
        require_information(
            root_handle,
            "transaction deletion root",
            is_directory=True,
            expected_mode=expected[1]["."][1],
            expected_identity=expected[0],
        )

        directory_records = sorted(
            (
                (relative, record)
                for relative, record in expected[1].items()
                if relative != "." and record[0] == "directory"
            ),
            key=lambda item: (len(PurePosixPath(item[0]).parts), item[0]),
        )
        for relative, record in directory_records:
            relative_path = PurePosixPath(relative)
            parent_relative = relative_path.parent.as_posix()
            parent_key = parent_relative if parent_relative != "." else "."
            handle = open_relative(
                directory_handles[parent_key],
                relative_path.name,
                True,
            )
            try:
                require_information(
                    handle,
                    f"transaction deletion directory {relative}",
                    is_directory=True,
                    expected_mode=record[1],
                    expected_identity=expected[2][relative],
                )
            except BaseException:
                close_handle(wintypes.HANDLE(handle))
                raise
            directory_handles[relative] = handle

        file_records = sorted(
            (
                (relative, record)
                for relative, record in expected[1].items()
                if record[0] == "file"
            ),
            key=lambda item: item[0],
        )
        for relative, record in file_records:
            relative_path = PurePosixPath(relative)
            parent_relative = relative_path.parent.as_posix()
            parent_key = parent_relative if parent_relative != "." else "."
            handle = open_relative(
                directory_handles[parent_key],
                relative_path.name,
                False,
            )
            try:
                before = require_information(
                    handle,
                    f"transaction deletion file {relative}",
                    is_directory=False,
                    expected_mode=record[1],
                    expected_identity=expected[2][relative],
                )
                payload = read_handle(handle, f"transaction deletion file {relative}")
                after = require_information(
                    handle,
                    f"transaction deletion file {relative}",
                    is_directory=False,
                    expected_mode=record[1],
                    expected_identity=expected[2][relative],
                )
                if (
                    file_size(before) != file_size(after)
                    or file_size(after) != len(payload)
                    or hashlib.sha256(payload).hexdigest() != record[3]
                    or len(payload) != record[2]
                ):
                    raise RuntimeError(f"transaction deletion file changed: {relative}")
                file_handles[relative] = handle
            except BaseException:
                close_handle(wintypes.HANDLE(handle))
                raise

        expected_children = _expected_tree_children(expected[1])
        for relative, handle in directory_handles.items():
            entries = enumerate_directory(handle, f"transaction deletion directory {relative}")
            if set(entries) != expected_children[relative]:
                raise RuntimeError(f"transaction deletion directory inventory changed: {relative}")
            for name, (attributes, entry_file_id) in entries.items():
                child_relative = name if relative == "." else f"{relative}/{name}"
                record = expected[1][child_relative]
                is_directory = record[0] == "directory"
                if bool(attributes & file_attribute_directory) != is_directory:
                    raise RuntimeError(f"transaction child type changed: {child_relative}")
                if entry_file_id != expected[2][child_relative][1]:
                    raise RuntimeError(f"transaction child identity changed: {child_relative}")

        for relative, record in file_records:
            handle = file_handles.pop(relative)
            try:
                current = require_information(
                    handle,
                    f"transaction deletion file {relative}",
                    is_directory=False,
                    expected_mode=record[1],
                    expected_identity=expected[2][relative],
                )
                if file_size(current) != record[2]:
                    raise RuntimeError(f"transaction deletion file size changed: {relative}")
                mark_delete(handle, f"transaction deletion file {relative}")
            finally:
                close_checked(handle, f"transaction deletion file {relative}")

        for relative, record in reversed(directory_records):
            handle = directory_handles.pop(relative)
            try:
                require_information(
                    handle,
                    f"transaction deletion directory {relative}",
                    is_directory=True,
                    expected_mode=record[1],
                    expected_identity=expected[2][relative],
                )
                if enumerate_directory(handle, f"transaction deletion directory {relative}"):
                    raise RuntimeError(f"transaction deletion directory is not empty: {relative}")
                mark_delete(handle, f"transaction deletion directory {relative}")
            finally:
                close_checked(handle, f"transaction deletion directory {relative}")

        root_handle = directory_handles.pop(".")
        try:
            require_information(
                root_handle,
                "transaction deletion root",
                is_directory=True,
                expected_mode=expected[1]["."][1],
                expected_identity=expected[0],
            )
            if enumerate_directory(root_handle, "transaction deletion root"):
                raise RuntimeError("transaction deletion root is not empty")
            mark_delete(root_handle, "transaction deletion root")
        finally:
            close_checked(root_handle, "transaction deletion root")

        require_information(
            parent_handle,
            "transaction cleanup parent",
            is_directory=True,
            expected_mode=parent_mode,
            expected_identity=parent_inode,
        )
        close_checked(parent_handle, "transaction cleanup parent")
        parent_handle = None
        return False
    finally:
        for handle in file_handles.values():
            close_handle(wintypes.HANDLE(handle))
        for handle in directory_handles.values():
            close_handle(wintypes.HANDLE(handle))
        if parent_handle is not None:
            close_handle(wintypes.HANDLE(parent_handle))


def _remove_validated_tree_nofollow(
    path: Path,
    expected: TreeSnapshot,
    parent_inode: tuple[int, int],
    parent_mode: int,
) -> bool:
    if _validated_tree_snapshot(path, "transaction deletion tree") != expected:
        raise RuntimeError(f"transaction deletion tree changed; preserving {path}")
    if os.name == "posix":
        _remove_validated_tree_posix(path, expected, parent_inode)
        return False
    return _remove_validated_tree_windows(path, expected, parent_inode, parent_mode)


def _cleanup_transaction_tree(
    path: Path,
    parent: Path,
    prefix: str,
    inode: tuple[int, int],
) -> None:
    if path.parent != parent or not path.name.startswith(prefix):
        raise RuntimeError(f"refusing unsafe transaction cleanup: {path}")
    snapshot = _validated_tree_snapshot(path, "transaction cleanup tree")
    if snapshot[0] != inode:
        raise RuntimeError(f"transaction cleanup identity changed; preserving {path}")
    parent_stat = parent.lstat()
    if _unsafe_link_or_reparse(parent_stat) or not stat.S_ISDIR(parent_stat.st_mode):
        raise RuntimeError(f"transaction cleanup parent is unsafe; preserving {path}")
    if _validated_tree_snapshot(path, "transaction cleanup tree") != snapshot:
        raise RuntimeError(f"transaction cleanup tree changed; preserving {path}")
    _remove_validated_tree_nofollow(
        path,
        snapshot,
        (parent_stat.st_dev, parent_stat.st_ino),
        stat.S_IMODE(parent_stat.st_mode),
    )


def _validate_destination_topology(replacements: tuple[tuple[Path, Path, Path], ...]) -> None:
    staged_roots = [Path(os.path.abspath(os.fspath(staged))) for staged, _, _ in replacements]
    outputs = [Path(os.path.abspath(os.fspath(output))) for _, output, _ in replacements]
    normalized = [Path(os.path.normcase(os.fspath(output))) for output in outputs]
    for index, output in enumerate(normalized):
        for other in normalized[index + 1 :]:
            if output == other or output in other.parents or other in output.parents:
                raise RuntimeError("experimental llama output destinations must be distinct and non-overlapping")
    normalized_staged = [Path(os.path.normcase(os.fspath(staged))) for staged in staged_roots]
    for staged in normalized_staged:
        for output in normalized:
            if staged == output or staged in output.parents or output in staged.parents:
                raise RuntimeError("staged and published experimental llama outputs must not overlap")


def _assert_publication_lock(lock: dict[str, Any]) -> None:
    _assert_bound_directory(
        lock["path"],
        lock["inode"],
        "experimental llama publication lock",
    )
    descriptor = lock["descriptor"]
    descriptor_stat = os.fstat(descriptor)
    if (
        _unsafe_link_or_reparse(descriptor_stat)
        or not stat.S_ISREG(descriptor_stat.st_mode)
        or (descriptor_stat.st_dev, descriptor_stat.st_ino) != lock["token_inode"]
    ):
        raise RuntimeError(f"publication lock token descriptor identity changed: {lock['token_path']}")
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks = []
    while True:
        chunk = os.read(descriptor, 4096)
        if not chunk:
            break
        chunks.append(chunk)
    if b"".join(chunks) != lock["token"]:
        raise RuntimeError(f"publication lock token descriptor changed: {lock['token_path']}")
    final_descriptor_stat = os.fstat(descriptor)
    if (
        final_descriptor_stat.st_size != len(lock["token"])
        or (final_descriptor_stat.st_dev, final_descriptor_stat.st_ino) != lock["token_inode"]
    ):
        raise RuntimeError(f"publication lock token changed while being read: {lock['token_path']}")
    path_payload, path_stat = _read_regular_nofollow(
        lock["token_path"],
        "publication lock token",
    )
    if (
        (path_stat.st_dev, path_stat.st_ino) != lock["token_inode"]
        or path_payload != lock["token"]
    ):
        raise RuntimeError(f"publication lock token path identity changed: {lock['token_path']}")
    _assert_bound_directory(
        lock["path"],
        lock["inode"],
        "experimental llama publication lock",
    )


def _acquire_publication_locks(outputs: list[Path]) -> list[dict[str, Any]]:
    locks: list[dict[str, Any]] = []
    parents = sorted(
        {Path(os.path.abspath(os.fspath(output.parent))) for output in outputs},
        key=lambda path: os.path.normcase(os.fspath(path)),
    )
    try:
        for parent in parents:
            lock_path = parent / ".hermes-experimental-llama-publication.lock"
            try:
                lock_path.mkdir(mode=0o700)
            except FileExistsError as exc:
                raise RuntimeError(
                    f"another experimental llama publication is active: {lock_path}"
                ) from exc
            try:
                lock_stat = lock_path.lstat()
            except BaseException:
                try:
                    lock_path.rmdir()
                except OSError:
                    pass
                raise
            lock_inode = (lock_stat.st_dev, lock_stat.st_ino)
            lock: dict[str, Any] = {
                "path": lock_path,
                "inode": lock_inode,
                "token_path": lock_path / "owner.token",
                "token_inode": None,
                "token": os.urandom(32),
                "descriptor": None,
                "setup_complete": False,
            }
            locks.append(lock)
            if _unsafe_link_or_reparse(lock_stat) or not stat.S_ISDIR(lock_stat.st_mode):
                raise RuntimeError(f"unsafe experimental llama publication lock: {lock_path}")
            os.chmod(lock_path, 0o700)
            _assert_bound_directory(
                lock_path,
                lock_inode,
                "experimental llama publication lock",
            )
            flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
            flags |= getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(lock["token_path"], flags, 0o600)
            lock["descriptor"] = descriptor
            token_stat = os.fstat(descriptor)
            lock["token_inode"] = (token_stat.st_dev, token_stat.st_ino)
            view = memoryview(lock["token"])
            while view:
                written = os.write(descriptor, view)
                if written <= 0:
                    raise OSError(f"unable to write publication lock token: {lock['token_path']}")
                view = view[written:]
            os.fsync(descriptor)
            _assert_publication_lock(lock)
            lock["setup_complete"] = True
        return locks
    except BaseException as exc:
        release_errors = _release_publication_locks(locks)
        if release_errors:
            raise RuntimeError(
                f"{exc}; publication lock cleanup also failed: " + " | ".join(release_errors)
            ) from exc
        raise


def _release_publication_locks(locks: list[dict[str, Any]]) -> list[str]:
    errors = []
    for lock in reversed(locks):
        valid = False
        try:
            if lock.get("setup_complete"):
                _assert_publication_lock(lock)
            else:
                _assert_bound_directory(
                    lock["path"],
                    lock["inode"],
                    "incomplete experimental llama publication lock",
                )
            valid = True
        except BaseException as exc:
            errors.append(f"{lock['path']}: {exc}")
        descriptor = lock.get("descriptor")
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError as exc:
                errors.append(f"{lock['token_path']}: unable to close token descriptor: {exc}")
                valid = False
            lock["descriptor"] = None
        if not valid:
            continue
        try:
            token_stat = _lstat_or_none(lock["token_path"])
            if token_stat is not None:
                if (
                    lock.get("token_inode") is None
                    or _unsafe_link_or_reparse(token_stat)
                    or not stat.S_ISREG(token_stat.st_mode)
                    or (token_stat.st_dev, token_stat.st_ino) != lock["token_inode"]
                ):
                    raise RuntimeError("publication lock token changed before release")
                os.unlink(lock["token_path"])
            _assert_bound_directory(
                lock["path"],
                lock["inode"],
                "experimental llama publication lock",
            )
            lock["path"].rmdir()
        except BaseException as exc:
            errors.append(f"{lock['path']}: {exc}")
    return errors


def _assert_publication_locks(locks: list[dict[str, Any]]) -> None:
    for lock in locks:
        _assert_publication_lock(lock)


def _snapshot_matches_owned(
    path: Path,
    manifest: Path,
    expected: TreeSnapshot,
    label: str,
) -> bool:
    return _owned_tree_snapshot(path, manifest, label) == expected


def _assert_bound_directory(path: Path, inode: tuple[int, int], label: str) -> None:
    current = path.lstat()
    if (
        _unsafe_link_or_reparse(current)
        or not stat.S_ISDIR(current.st_mode)
        or (current.st_dev, current.st_ino) != inode
    ):
        raise RuntimeError(f"{label} identity changed: {path}")


def _assert_plan_paths_bound(plan: dict[str, Any]) -> None:
    if _plain_directory_chain_snapshot(
        plan["output"].parent,
        "experimental llama output parent",
    ) != plan["parent_chain"]:
        raise RuntimeError("experimental llama output parent changed during publication")
    _assert_bound_directory(
        plan["transaction"],
        plan["transaction_inode"],
        "experimental llama transaction root",
    )


def _rollback_publication(plans: list[dict[str, Any]]) -> list[str]:
    ambiguities: list[str] = []
    for plan in reversed(plans):
        output: Path = plan["output"]
        plan_errors: list[str] = []
        quarantine = plan["transaction"] / "quarantine"
        if plan["published"]:
            current = _lstat_or_none(output)
            if current is not None:
                try:
                    if not _snapshot_matches_owned(
                        output,
                        plan["manifest"],
                        plan["incoming_snapshot"],
                        "rollback published output",
                    ):
                        raise RuntimeError("published output identity changed")
                    if _lstat_or_none(quarantine) is not None:
                        raise RuntimeError("rollback quarantine path is unexpectedly occupied")
                    os.replace(output, quarantine)
                    if not _snapshot_matches_owned(
                        quarantine,
                        plan["manifest"],
                        plan["incoming_snapshot"],
                        "rollback quarantine",
                    ):
                        raise RuntimeError("quarantined output identity changed")
                    plan["published"] = False
                except BaseException as exc:
                    plan_errors.append(f"current output is ambiguous: {exc}")
            else:
                plan["published"] = False

        if plan["backup_moved"]:
            backup: Path = plan["backup"]
            try:
                if _lstat_or_none(backup) is None:
                    raise RuntimeError("captured backup is missing")
                if not _snapshot_matches_owned(
                    backup,
                    plan["manifest"],
                    plan["old_snapshot"],
                    "rollback backup",
                ):
                    raise RuntimeError("captured backup identity changed")
                if _lstat_or_none(output) is not None:
                    raise RuntimeError("refusing to overwrite an unexpected current output")
                os.replace(backup, output)
                if not _snapshot_matches_owned(
                    output,
                    plan["manifest"],
                    plan["old_snapshot"],
                    "restored output",
                ):
                    raise RuntimeError("restored output identity changed")
                plan["backup_moved"] = False
            except BaseException as exc:
                plan_errors.append(f"backup restore is ambiguous: {exc}")
        elif plan["old_snapshot"] is not None and _lstat_or_none(output) is None:
            plan_errors.append("original output disappeared without a restorable backup")

        if plan_errors:
            if (
                not plan["published"]
                and _lstat_or_none(output) is None
                and _lstat_or_none(quarantine) is not None
            ):
                try:
                    if not _snapshot_matches_owned(
                        quarantine,
                        plan["manifest"],
                        plan["incoming_snapshot"],
                        "rollback preserved quarantine",
                    ):
                        raise RuntimeError("preserved quarantine identity changed")
                    os.replace(quarantine, output)
                    if not _snapshot_matches_owned(
                        output,
                        plan["manifest"],
                        plan["incoming_snapshot"],
                        "rollback preserved output",
                    ):
                        raise RuntimeError("preserved output identity changed")
                    plan["published"] = True
                except BaseException as exc:
                    plan_errors.append(f"unable to retain the verified new output: {exc}")
            plan["preserve_transaction"] = True
            ambiguities.append(f"{output}: " + "; ".join(plan_errors))
    return ambiguities


def replace_owned_output(
    staged_output: Path,
    output_dir: Path,
    ownership_manifest: Path = Path(MANIFEST_NAME),
) -> None:
    replace_owned_outputs(((staged_output, output_dir, ownership_manifest),))


def replace_owned_outputs(
    replacements: tuple[tuple[Path, Path, Path], ...],
) -> None:
    if not replacements:
        raise RuntimeError("at least one experimental llama output replacement is required")
    _validate_destination_topology(replacements)
    plans: list[dict[str, Any]] = []
    locks: list[dict[str, Any]] = []
    committed = False
    pending_error: BaseException | None = None
    cleanup_errors: list[str] = []
    try:
        outputs = [Path(os.path.abspath(os.fspath(output))) for _, output, _ in replacements]
        for output in outputs:
            output.parent.mkdir(parents=True, exist_ok=True)
        parent_chains = {
            output: _plain_directory_chain_snapshot(
                output.parent,
                "experimental llama output parent",
            )
            for output in outputs
        }
        locks = _acquire_publication_locks(outputs)
        _assert_publication_locks(locks)

        for staged_raw, output_raw, manifest in replacements:
            staged = Path(os.path.abspath(os.fspath(staged_raw)))
            output = Path(os.path.abspath(os.fspath(output_raw)))
            staged_snapshot = _validated_tree_snapshot(
                staged,
                "staged experimental llama output",
            )
            _manifest_closed_inventory(staged, manifest, staged_snapshot[1])
            old_snapshot = _assert_replaceable_output(output, manifest)
            transaction, transaction_inode = _new_transaction_root(output.parent, output.name)
            plan: dict[str, Any] = {
                "output": output,
                "parent_chain": parent_chains[output],
                "manifest": manifest,
                "old_snapshot": old_snapshot,
                "transaction": transaction,
                "transaction_inode": transaction_inode,
                "incoming": transaction / "incoming",
                "incoming_snapshot": None,
                "backup": transaction / "backup",
                "backup_moved": False,
                "published": False,
                "preserve_transaction": False,
            }
            plans.append(plan)
            _copy_validated_tree(staged, plan["incoming"], staged_snapshot)
            incoming_snapshot = _validated_tree_snapshot(
                plan["incoming"],
                "copied experimental llama output",
            )
            _manifest_closed_inventory(plan["incoming"], manifest, incoming_snapshot[1])
            if incoming_snapshot[1] != staged_snapshot[1]:
                raise RuntimeError("copied experimental llama output differs in bytes or modes")
            plan["incoming_snapshot"] = incoming_snapshot

        for plan in plans:
            output = plan["output"]
            _assert_publication_locks(locks)
            if _plain_directory_chain_snapshot(
                output.parent,
                "experimental llama output parent",
            ) != parent_chains[output]:
                raise RuntimeError("experimental llama output parent changed before publication")
            if _assert_replaceable_output(output, plan["manifest"]) != plan["old_snapshot"]:
                raise RuntimeError("existing generated output changed before publication")
            transaction_snapshot = _validated_tree_snapshot(
                plan["transaction"],
                "experimental llama transaction root",
            )
            if transaction_snapshot[0] != plan["transaction_inode"]:
                raise RuntimeError("experimental llama transaction root identity changed")

        for plan in plans:
            if plan["old_snapshot"] is not None:
                _assert_publication_locks(locks)
                _assert_plan_paths_bound(plan)
                os.replace(plan["output"], plan["backup"])
                plan["backup_moved"] = True
                _assert_plan_paths_bound(plan)
                _assert_publication_locks(locks)
                if not _snapshot_matches_owned(
                    plan["backup"],
                    plan["manifest"],
                    plan["old_snapshot"],
                    "publication backup",
                ):
                    raise RuntimeError("publication backup differs from approved old output")

        for plan in plans:
            _assert_publication_locks(locks)
            _assert_plan_paths_bound(plan)
            os.replace(plan["incoming"], plan["output"])
            plan["published"] = True
            _assert_plan_paths_bound(plan)
            _assert_publication_locks(locks)
            if not _snapshot_matches_owned(
                plan["output"],
                plan["manifest"],
                plan["incoming_snapshot"],
                "published output",
            ):
                raise RuntimeError("published generated output failed live identity verification")

        for plan in plans:
            _assert_publication_locks(locks)
            if not _snapshot_matches_owned(
                plan["output"],
                plan["manifest"],
                plan["incoming_snapshot"],
                "committed output",
            ):
                raise RuntimeError("generated outputs changed before publication commit")
        _assert_publication_locks(locks)
        committed = True
    except BaseException as exc:
        pending_error = exc
        if not committed:
            ambiguities = _rollback_publication(plans)
            if ambiguities:
                pending_error = RuntimeError(
                    "ambiguous experimental llama publication rollback; preserved transaction data: "
                    + " | ".join(ambiguities)
                )
    finally:
        for plan in plans:
            if plan["preserve_transaction"]:
                continue
            transaction = plan["transaction"]
            if _lstat_or_none(transaction) is None:
                continue
            try:
                _cleanup_transaction_tree(
                    transaction,
                    plan["output"].parent,
                    f".{plan['output'].name}.transaction-",
                    plan["transaction_inode"],
                )
            except BaseException as exc:
                cleanup_errors.append(f"{transaction}: {exc}")
        cleanup_errors.extend(_release_publication_locks(locks))

    if pending_error is not None:
        if cleanup_errors:
            raise RuntimeError(
                f"{pending_error}; cleanup also failed: " + " | ".join(cleanup_errors)
            ) from pending_error
        raise pending_error
    if cleanup_errors:
        raise RuntimeError(
            "experimental llama outputs committed, but transaction cleanup failed; "
            "new outputs remain authoritative: " + " | ".join(cleanup_errors)
        )


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
    host_cxx_compiler = resolve_host_cxx_compiler(ndk_dir, environment)

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
                host_cxx_compiler=host_cxx_compiler,
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
        replace_owned_outputs(
            (
                (staged_output, output_dir, Path(MANIFEST_NAME)),
                (staged_assets, assets_output_dir, Path(PACKAGED_MANIFEST_ASSET)),
            )
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

    # Output destinations stay lexical: resolving them here would dereference an
    # existing symlink/junction before the no-follow publication checks see it.
    output_dir = Path(os.path.abspath(os.fspath(Path(args.output_dir).expanduser())))
    assets_output_dir = Path(
        os.path.abspath(os.fspath(Path(args.assets_output_dir).expanduser()))
    )
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
