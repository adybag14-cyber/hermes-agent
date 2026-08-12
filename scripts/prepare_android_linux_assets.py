#!/usr/bin/env python3
from __future__ import annotations

import argparse
from contextlib import contextmanager
from hashlib import sha256
from io import BytesIO
import json
import os
import posixpath
import shutil
import socket
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from hermes_android.linux_assets import (
    ANDROID_LINUX_ASSET_ROOT,
    ANDROID_TO_TERMUX_ARCH,
    asset_manifest_path,
    asset_prefix_dir,
    load_data_tar_bytes_from_deb,
    normalize_text_shebang,
    open_data_tar,
    parse_packages_index,
    resolve_dependency_closure,
    ROOT_PACKAGES,
    serializable_manifest,
    strip_termux_prefix,
    TERMUX_MAIN_BASE_URL,
    verify_sha256,
    write_manifest,
    TermuxPackageRecord,
)

ANDROID_SPAWN_NEEDED = b"libandroid-spawn.so\0"
BIONIC_LIBC_NEEDED = b"libc.so\0"
BIONIC_LLAMA_SERVER_NAME = "llama-server-bionic"
PROOT_DIRECT_EXEC_ENV = "HERMES_ANDROID_PROOT_EXECUTABLE"
PROOT_LOOKUP_EXPRESSION = b'shutil.which("proot") or "proot"'
PROOT_DIRECT_EXEC_REPLACEMENT = (
    b'os.environ.get("' + PROOT_DIRECT_EXEC_ENV.encode("ascii") + b'") or ' + PROOT_LOOKUP_EXPRESSION
)
# proot-distro 5.4.0-1 is architecture-independent and is pinned by the
# package lock. Guard the two modules by both their original and resulting
# hashes so upstream layout/source drift fails the asset build loudly instead
# of silently restoring PATH-based execution.
PROOT_DISTRO_DIRECT_EXEC_PATCHES = {
    "lib/python3.14/site-packages/proot_distro/commands/login/__init__.py": {
        "source_sha256": "099b1dd1e7d4885c3e302c74aa30c7d544e623f271d9aadbc3bf7b7d16fee897",
        "patched_sha256": "15abb7deff3ba395358cdbd319b2d2d40c6e4fc267f95cf60f5b9a211ad11ada",
    },
    "lib/python3.14/site-packages/proot_distro/helpers/build_engine/run_step.py": {
        "source_sha256": "61195ff3c00a1f411687501f95934e0b619191d93f6651b84cc06b2cc7473c37",
        "patched_sha256": "7204f8b01f2ac94f506e913d034d252a427842ec013c79edc56c5cdb2aed3c67",
    },
}
DEFAULT_LOCK_FILE = REPO_ROOT / "hermes_android" / "termux_linux_assets.lock.json"
LOCK_FILE_VERSION = 1
# Prefer mirrors that still host historical lock-file .deb paths. packages.termux.dev
# and packages-cf frequently 404/403 from GitHub Actions IPs; keep multiple fallthroughs.
TERMUX_MAIN_FALLBACK_BASE_URLS = (
    "https://grimler.se/termux/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://mirror.iscas.ac.cn/termux/apt/termux-main",
    TERMUX_MAIN_BASE_URL,
    "https://termux.librehat.com/apt/termux-main",
    "https://mirror.rinarin.dev/termux/termux-main",
    "https://ftp.fau.de/termux/termux-main",
)


def force_ipv4_downloads() -> bool:
    configured = os.environ.get("HERMES_FORCE_IPV4")
    if configured is None:
        # Several Termux mirrors publish IPv6 records even when the Windows
        # host has no working IPv6 route. urllib can then spend minutes per
        # mirror in connect timeouts, so prefer the reliable family by default
        # on Windows while retaining an explicit opt-out.
        return os.name == "nt"
    return configured.strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


@contextmanager
def ipv4_only_dns(enabled: bool):
    """Scope urllib DNS resolution to IPv4 for hosts with broken IPv6 routes."""
    if not enabled:
        yield
        return

    original_getaddrinfo = socket.getaddrinfo

    def getaddrinfo_ipv4(host, port, family=0, type=0, proto=0, flags=0):
        return original_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

    socket.getaddrinfo = getaddrinfo_ipv4
    try:
        yield
    finally:
        socket.getaddrinfo = original_getaddrinfo


def download_bytes(url: str, attempts: int = 3) -> bytes:
    import time

    last_error: Exception | None = None
    # Cloudflare-backed Termux hosts reject bare urllib UAs from CI.
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        ),
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
    }
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers=headers)
            with ipv4_only_dns(force_ipv4_downloads()):
                with urllib.request.urlopen(request, timeout=120) as response:
                    payload = response.read()
                    if not payload:
                        last_error = RuntimeError("empty response body")
                        time.sleep(0.5 * (attempt + 1))
                        continue
                    return payload
        except Exception as exc:  # pragma: no cover - exercised by live smoke checks
            last_error = exc
            time.sleep(0.5 * (attempt + 1))
    raise RuntimeError(f"Failed to download {url}: {last_error}")


def configured_termux_main_base_urls() -> list[str]:
    configured = []
    for key in ("HERMES_TERMUX_MAIN_BASE_URLS", "HERMES_TERMUX_MAIN_BASE_URL"):
        raw = os.environ.get(key, "")
        configured.extend(item.strip() for item in raw.replace(";", ",").split(",") if item.strip())
    configured.extend(TERMUX_MAIN_FALLBACK_BASE_URLS)
    return list(dict.fromkeys(url.rstrip("/") for url in configured if url.strip()))


def _termux_main_url(base_url: str, relative_path: str) -> str:
    return f"{base_url.rstrip('/')}/{relative_path.lstrip('/')}"


def _packages_index_path(termux_arch: str) -> str:
    return f"dists/stable/main/binary-{termux_arch}/Packages"


def download_termux_main_path(relative_path: str, expected_sha256: str | None = None) -> bytes:
    errors: list[str] = []
    for base_url in configured_termux_main_base_urls():
        url = _termux_main_url(base_url, relative_path)
        try:
            payload = download_bytes(url)
            if expected_sha256:
                try:
                    verify_sha256(payload, expected_sha256)
                except ValueError as exc:
                    errors.append(f"{url}: {exc}")
                    continue
            return payload
        except Exception as exc:  # pragma: no cover - exercised by live release builds
            errors.append(f"{url}: {exc}")
    raise RuntimeError(f"Failed to download Termux path {relative_path}: {'; '.join(errors)}")


@contextmanager
def locked_package_archive(lock_payload: dict | None):
    """Open the immutable package bundle pinned by the lock, when configured."""
    archive_config = (lock_payload or {}).get("package_archive") or {}
    url = str(archive_config.get("url", "")).strip()
    expected_sha256 = str(archive_config.get("sha256", "")).strip()
    if not url or not expected_sha256:
        yield None
        return

    try:
        payload = download_bytes(url)
        verify_sha256(payload, expected_sha256)
        payload_stream = BytesIO(payload)
        archive = zipfile.ZipFile(payload_stream, "r")
        names = archive.namelist()
        duplicate_names = {name for name in names if names.count(name) > 1}
        if duplicate_names:
            archive.close()
            raise ValueError(
                "Duplicate entries in locked Termux package archive: "
                + ", ".join(sorted(duplicate_names))
            )
        sys.stderr.write(
            f"Using locked Termux package archive {url} ({len(names)} entries)\n"
        )
    except Exception as exc:  # pragma: no cover - live fallback path
        # Every package still has its own SHA-256 pin, so falling back to mirrors
        # remains byte-reproducible while keeping source builds recoverable if the
        # archive host is temporarily unavailable.
        sys.stderr.write(f"Locked Termux package archive unavailable ({url}): {exc}\n")
        yield None
        return

    with archive:
        yield archive


def download_locked_package(
    package: TermuxPackageRecord,
    archive: zipfile.ZipFile | None,
) -> bytes:
    if archive is not None:
        try:
            payload = archive.read(package.filename)
            verify_sha256(payload, package.sha256)
            return payload
        except (KeyError, ValueError) as exc:
            sys.stderr.write(
                f"Locked Termux package archive entry unavailable ({package.filename}): {exc}\n"
            )
    return download_termux_main_path(package.filename, expected_sha256=package.sha256)


def unique_locked_packages(lock_payload: dict) -> list[TermuxPackageRecord]:
    by_filename: dict[str, TermuxPackageRecord] = {}
    for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
        for package in locked_packages(lock_payload, android_abi, termux_arch):
            previous = by_filename.get(package.filename)
            if previous is not None and previous.sha256 != package.sha256:
                raise ValueError(
                    f"Conflicting SHA256 values for locked package {package.filename}: "
                    f"{previous.sha256} and {package.sha256}"
                )
            by_filename[package.filename] = package
    return [by_filename[name] for name in sorted(by_filename)]


def build_package_archive(lock_payload: dict, output_path: Path) -> str:
    """Create a deterministic ZIP of all lock-pinned .deb files."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    partial_path = output_path.with_name(f"{output_path.name}.partial")
    partial_path.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(partial_path, "w", compression=zipfile.ZIP_STORED) as archive:
            for package in unique_locked_packages(lock_payload):
                payload = download_termux_main_path(
                    package.filename,
                    expected_sha256=package.sha256,
                )
                info = zipfile.ZipInfo(package.filename, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, payload)
        partial_path.replace(output_path)
    finally:
        partial_path.unlink(missing_ok=True)
    return sha256(output_path.read_bytes()).hexdigest()


def read_packages_index(termux_arch: str) -> dict[str, TermuxPackageRecord]:
    return parse_packages_index(download_termux_main_path(_packages_index_path(termux_arch)).decode("utf-8", "ignore"))


def _package_record_to_json(record: TermuxPackageRecord) -> dict:
    return {
        "name": record.name,
        "version": record.version,
        "filename": record.filename,
        "sha256": record.sha256,
        "depends": list(record.depends),
    }


def _package_record_from_json(payload: dict) -> TermuxPackageRecord:
    return TermuxPackageRecord(
        name=str(payload["name"]),
        version=str(payload["version"]),
        filename=str(payload["filename"]),
        sha256=str(payload["sha256"]),
        depends=tuple(str(item) for item in payload.get("depends", [])),
    )


def build_lock_payload() -> dict:
    architectures = {}
    for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
        packages = resolve_dependency_closure(read_packages_index(termux_arch))
        architectures[android_abi] = {
            "termux_arch": termux_arch,
            "packages": [_package_record_to_json(package) for package in packages],
        }
    return {
        "version": LOCK_FILE_VERSION,
        "termux_main_base_url": TERMUX_MAIN_BASE_URL,
        "root_packages": list(ROOT_PACKAGES),
        "architectures": architectures,
    }


def write_lock_file(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(payload, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def load_lock_file(path: Path) -> dict | None:
    if not path.is_file():
        return None
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("version") != LOCK_FILE_VERSION:
        raise ValueError(f"Unsupported Termux asset lock file version in {path}")
    return payload


def locked_packages(lock_payload: dict, android_abi: str, termux_arch: str) -> list[TermuxPackageRecord]:
    arch_payload = lock_payload.get("architectures", {}).get(android_abi)
    if not arch_payload:
        raise KeyError(f"Termux asset lock file does not contain Android ABI {android_abi}")
    if arch_payload.get("termux_arch") != termux_arch:
        raise ValueError(f"Termux asset lock file maps {android_abi} to {arch_payload.get('termux_arch')}, not {termux_arch}")
    return [_package_record_from_json(item) for item in arch_payload.get("packages", [])]


def _termux_root(extracted_root: Path) -> Path:
    return extracted_root / "data" / "data" / "com.termux" / "files" / "usr"


def _normalize_link_target(source: Path, target: str, termux_root: Path) -> str | None:
    target_path = Path(target)
    if target_path.is_absolute():
        if str(target_path).startswith(str(termux_root)):
            return str(target_path.relative_to(termux_root))
        return None
    resolved = (source.parent / target_path).resolve(strict=False)
    try:
        return resolved.relative_to(termux_root).as_posix()
    except ValueError:
        return None


def mirror_extracted_tree(extracted_root: Path, staging_prefix: Path) -> list[dict]:
    termux_root = _termux_root(extracted_root)
    if not termux_root.exists():
        return []

    inode_first_paths: dict[tuple[int, int], str] = {}
    links: list[dict] = []

    for source in sorted(termux_root.rglob("*"), key=lambda item: item.relative_to(termux_root).as_posix()):
        relative = source.relative_to(termux_root)
        destination = staging_prefix / relative
        if source.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
            continue

        destination.parent.mkdir(parents=True, exist_ok=True)

        if source.is_symlink():
            normalized_target = _normalize_link_target(source, os.readlink(source), termux_root)
            if normalized_target:
                links.append({"path": relative.as_posix(), "target": normalized_target})
            continue

        stat = source.stat()
        inode_key = (stat.st_dev, stat.st_ino)
        first_path = inode_first_paths.get(inode_key)
        if first_path is not None:
            links.append({"path": relative.as_posix(), "target": first_path})
            continue
        inode_first_paths[inode_key] = relative.as_posix()

        payload = normalize_text_shebang(source.read_bytes())
        destination.write_bytes(payload)
        if relative.as_posix().startswith(("bin/", "libexec/")) or os.access(source, os.X_OK):
            destination.chmod(0o755)

    return links


def _archive_termux_relative(path: str) -> str | None:
    normalized = posixpath.normpath(str(path).replace("\\", "/"))
    relative = strip_termux_prefix(normalized)
    if relative is None:
        return None
    relative = posixpath.normpath(relative)
    if relative == ".":
        return ""
    if relative.startswith("../"):
        return None
    return relative


def _staging_destination(staging_prefix: Path, relative: str) -> Path:
    parts = [part for part in relative.split("/") if part and part != "."]
    if any(part == ".." for part in parts):
        raise ValueError(f"Unsafe archive member path: {relative!r}")
    destination = staging_prefix.joinpath(*parts)
    destination.resolve(strict=False).relative_to(staging_prefix.resolve(strict=False))
    return destination


def _normalize_archive_link_target(source_relative: str, target: str) -> str | None:
    direct = _archive_termux_relative(target)
    if direct:
        return direct
    if direct == "":
        return None

    normalized = str(target).replace("\\", "/")
    if normalized.startswith("/"):
        return None
    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(source_relative), normalized))
    if resolved == "." or resolved.startswith("../"):
        return None
    return resolved


def _normalize_archive_hardlink_target(source_relative: str, target: str) -> str | None:
    direct = _archive_termux_relative(target)
    if direct:
        return direct
    if direct == "":
        return None

    normalized = posixpath.normpath(str(target).replace("\\", "/").lstrip("./"))
    if normalized and normalized != "." and not normalized.startswith("../"):
        return normalized
    return _normalize_archive_link_target(source_relative, target)


def mirror_data_tar(data_tar: tarfile.TarFile, staging_prefix: Path) -> list[dict]:
    links: list[dict] = []
    staging_prefix.mkdir(parents=True, exist_ok=True)

    for member in sorted(data_tar.getmembers(), key=lambda item: item.name):
        relative = _archive_termux_relative(member.name)
        if relative is None:
            continue
        if relative == "":
            staging_prefix.mkdir(parents=True, exist_ok=True)
            continue

        destination = _staging_destination(staging_prefix, relative)

        if member.isdir():
            destination.mkdir(parents=True, exist_ok=True)
            continue

        if member.issym():
            target = _normalize_archive_link_target(relative, member.linkname)
            if target:
                links.append({"path": relative, "target": target})
            continue

        if member.islnk():
            target = _normalize_archive_hardlink_target(relative, member.linkname)
            if target:
                links.append({"path": relative, "target": target})
            continue

        if not member.isfile():
            continue

        file_obj = data_tar.extractfile(member)
        if file_obj is None:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        payload = normalize_text_shebang(file_obj.read())
        destination.write_bytes(payload)
        if relative.startswith(("bin/", "libexec/")) or member.mode & 0o111:
            destination.chmod(0o755)

    return links


def prune_staging_prefix(prefix_dir: Path) -> None:
    removable = [
        prefix_dir / "include",
        prefix_dir / "lib" / "pkgconfig",
        prefix_dir / "share" / "doc",
        prefix_dir / "share" / "info",
        prefix_dir / "share" / "man",
        prefix_dir / "share" / "zsh",
        prefix_dir / "share" / "LICENSES",
        prefix_dir / "var" / "cache",
    ]
    for path in removable:
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)


def patch_android_spawn_needed_to_libc(path: Path) -> bool:
    payload = path.read_bytes()
    if ANDROID_SPAWN_NEEDED not in payload:
        return False
    replacement = BIONIC_LIBC_NEEDED + (b"\0" * (len(ANDROID_SPAWN_NEEDED) - len(BIONIC_LIBC_NEEDED)))
    path.write_bytes(payload.replace(ANDROID_SPAWN_NEEDED, replacement))
    return True


def create_bionic_llama_server_launcher(prefix_dir: Path) -> None:
    source = prefix_dir / "bin" / "llama-server"
    if not source.is_file():
        return
    destination = prefix_dir / "bin" / BIONIC_LLAMA_SERVER_NAME
    shutil.copy2(source, destination)
    if patch_android_spawn_needed_to_libc(destination):
        destination.chmod(0o755)
    else:
        destination.unlink(missing_ok=True)


def patch_proot_distro_direct_execution(prefix_dir: Path) -> list[str]:
    """Route PRoot through the APK-native executable selected at runtime.

    Android 10+ can reject execve() of downloaded ELF files in writable app
    data. This deterministic build step changes only the two pinned
    proot-distro call sites that otherwise resolve ``proot`` from PATH. Runtime
    startup never edits the extracted Python sources.
    """

    patched: list[str] = []
    for relative_path, hashes in PROOT_DISTRO_DIRECT_EXEC_PATCHES.items():
        module = prefix_dir / relative_path
        if not module.is_file():
            raise RuntimeError(f"Pinned proot-distro module is missing: {relative_path}")

        payload = module.read_bytes()
        actual_sha256 = sha256(payload).hexdigest()
        source_sha256 = hashes["source_sha256"]
        patched_sha256 = hashes["patched_sha256"]
        if actual_sha256 == patched_sha256:
            if payload.count(PROOT_DIRECT_EXEC_REPLACEMENT) != 1:
                raise RuntimeError(
                    f"Patched proot-distro anchor count changed for {relative_path}"
                )
            patched.append(relative_path)
            continue
        if actual_sha256 != source_sha256:
            raise RuntimeError(
                "Pinned proot-distro source hash changed for "
                f"{relative_path}: expected {source_sha256}, got {actual_sha256}"
            )
        if payload.count(PROOT_LOOKUP_EXPRESSION) != 1:
            raise RuntimeError(
                f"Expected exactly one proot lookup anchor in {relative_path}"
            )
        if PROOT_DIRECT_EXEC_REPLACEMENT in payload:
            raise RuntimeError(f"Unexpected partial proot patch in {relative_path}")

        updated = payload.replace(
            PROOT_LOOKUP_EXPRESSION,
            PROOT_DIRECT_EXEC_REPLACEMENT,
            1,
        )
        updated_sha256 = sha256(updated).hexdigest()
        if updated_sha256 != patched_sha256:
            raise RuntimeError(
                "Pinned proot-distro patched hash changed for "
                f"{relative_path}: expected {patched_sha256}, got {updated_sha256}"
            )
        module.write_bytes(updated)
        patched.append(relative_path)
    return patched


def manifest_file_list(prefix_dir: Path) -> list[str]:
    return [
        path.relative_to(prefix_dir).as_posix()
        for path in sorted(prefix_dir.rglob("*"), key=lambda item: item.relative_to(prefix_dir).as_posix())
        if path.is_file()
    ]


def prepare_assets(output_dir: Path, lock_file: Path | None = DEFAULT_LOCK_FILE, refresh_lock_file: bool = False) -> None:
    lock_payload = None
    if lock_file is not None:
        if refresh_lock_file:
            lock_payload = build_lock_payload()
            write_lock_file(lock_file, lock_payload)
        else:
            lock_payload = load_lock_file(lock_file)

    with locked_package_archive(lock_payload) as package_archive:
        for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
            if lock_payload:
                packages = locked_packages(lock_payload, android_abi, termux_arch)
            else:
                records = read_packages_index(termux_arch)
                packages = resolve_dependency_closure(records)

            prefix_dir = asset_prefix_dir(output_dir, android_abi)
            if prefix_dir.exists():
                shutil.rmtree(prefix_dir)
            prefix_dir.mkdir(parents=True, exist_ok=True)

            links: list[dict] = []
            for package in packages:
                payload = download_locked_package(package, package_archive)
                data_bytes, data_name = load_data_tar_bytes_from_deb(payload)
                with open_data_tar(data_bytes, data_name) as tar:
                    links.extend(mirror_data_tar(tar, prefix_dir))

            prune_staging_prefix(prefix_dir)
            patch_proot_distro_direct_execution(prefix_dir)
            create_bionic_llama_server_launcher(prefix_dir)
            for extra_dir in [prefix_dir / "home", prefix_dir / "tmp"]:
                extra_dir.mkdir(parents=True, exist_ok=True)

            write_manifest(
                asset_manifest_path(output_dir, android_abi),
                serializable_manifest(
                    android_abi,
                    packages,
                    links=links,
                    files=manifest_file_list(prefix_dir),
                ),
            )


def check_termux_mirror_health(termux_arch: str = "x86_64") -> dict:
    import importlib.util

    harness_path = REPO_ROOT / "scripts" / "android_visual_harness.py"
    spec = importlib.util.spec_from_file_location("android_visual_harness", harness_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {harness_path}")
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    mirrors = harness.check_termux_package_mirrors(termux_arch=termux_arch)
    healthy = [item for item in mirrors if item["status"] == "ok"]
    return {
        "termux_arch": termux_arch,
        "healthy_count": len(healthy),
        "recommended_base_urls": [item["base_url"] for item in healthy],
        "mirrors": mirrors,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android Linux CLI assets for Hermes Android builds")
    parser.add_argument("--output-dir", required=True, help="Directory where generated assets should be written")
    parser.add_argument(
        "--lock-file",
        default=str(DEFAULT_LOCK_FILE),
        help="Pinned Termux package lock file used for reproducible release builds",
    )
    parser.add_argument("--refresh-lock-file", action="store_true", help="Refresh the pinned Termux package lock file")
    parser.add_argument("--lock-only", action="store_true", help="Only refresh the lock file, without extracting assets")
    parser.add_argument(
        "--build-package-archive",
        help="Create a deterministic ZIP containing every package pinned by the lock file",
    )
    parser.add_argument(
        "--check-mirrors",
        action="store_true",
        help="Probe configured Termux main mirrors and print a JSON health report",
    )
    parser.add_argument("--mirror-report", help="Optional path to write the mirror health JSON report")
    parser.add_argument("--termux-arch", default="x86_64", help="Termux arch used for mirror health probes")
    args = parser.parse_args()
    if args.check_mirrors:
        report = check_termux_mirror_health(termux_arch=args.termux_arch)
        encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.mirror_report:
            Path(args.mirror_report).expanduser().resolve().write_text(encoded, encoding="utf-8")
        else:
            sys.stdout.write(encoded)
        if report["healthy_count"] == 0:
            raise SystemExit(1)
        return
    output_dir = Path(args.output_dir).expanduser().resolve()
    lock_file = Path(args.lock_file).expanduser().resolve() if args.lock_file else None
    if args.build_package_archive:
        if lock_file is None:
            raise ValueError("--build-package-archive requires --lock-file")
        lock_payload = load_lock_file(lock_file)
        if lock_payload is None:
            raise FileNotFoundError(lock_file)
        archive_path = Path(args.build_package_archive).expanduser().resolve()
        archive_sha256 = build_package_archive(lock_payload, archive_path)
        sys.stdout.write(
            json.dumps(
                {
                    "path": str(archive_path),
                    "sha256": archive_sha256,
                    "bytes": archive_path.stat().st_size,
                },
                sort_keys=True,
            )
            + "\n"
        )
        return
    if args.lock_only:
        if lock_file is None:
            raise ValueError("--lock-only requires --lock-file")
        write_lock_file(lock_file, build_lock_payload())
        return
    asset_root = output_dir / ANDROID_LINUX_ASSET_ROOT
    if asset_root.exists():
        shutil.rmtree(asset_root)
    asset_root.mkdir(parents=True, exist_ok=True)
    prepare_assets(output_dir, lock_file=lock_file, refresh_lock_file=args.refresh_lock_file)


if __name__ == "__main__":
    main()
