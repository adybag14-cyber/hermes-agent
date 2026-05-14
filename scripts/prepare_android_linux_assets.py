#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import posixpath
import shutil
import sys
import tarfile
import urllib.request
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
    serializable_manifest,
    strip_termux_prefix,
    TERMUX_MAIN_BASE_URL,
    verify_sha256,
    write_manifest,
)

ANDROID_SPAWN_NEEDED = b"libandroid-spawn.so\0"
BIONIC_LIBC_NEEDED = b"libc.so\0"
BIONIC_LLAMA_SERVER_NAME = "llama-server-bionic"
DEFAULT_TERMUX_MAIN_BASE_URLS = (
    TERMUX_MAIN_BASE_URL,
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://mirror.mwt.me/termux/main",
    "https://termux.librehat.com/apt/termux-main",
)


def termux_main_base_urls() -> tuple[str, ...]:
    configured = os.environ.get("HERMES_TERMUX_MAIN_BASE_URLS", "")
    candidates = configured.replace(";", ",").split(",") if configured else DEFAULT_TERMUX_MAIN_BASE_URLS
    normalized: list[str] = []
    for candidate in candidates:
        value = candidate.strip().rstrip("/")
        if value and value not in normalized:
            normalized.append(value)
    return tuple(normalized)


def termux_packages_index_url(base_url: str, termux_arch: str) -> str:
    return f"{base_url}/dists/stable/main/binary-{termux_arch}/Packages"


def termux_package_url(base_url: str, filename: str) -> str:
    return f"{base_url}/{filename.lstrip('/')}"


def download_bytes(url: str, attempts: int = 3) -> bytes:
    last_error: Exception | None = None
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read()
        except Exception as exc:  # pragma: no cover - exercised by live smoke checks
            last_error = exc
    raise RuntimeError(f"Failed to download {url}: {last_error}")


def download_first_available(urls: list[str], attempts: int = 3) -> tuple[bytes, str]:
    errors: list[str] = []
    for url in urls:
        try:
            return download_bytes(url, attempts=attempts), url
        except Exception as exc:  # pragma: no cover - exercised by live smoke checks
            errors.append(f"{url}: {exc}")
    raise RuntimeError("Failed to download from all Termux mirrors: " + "; ".join(errors))


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


def prepare_assets(output_dir: Path) -> None:
    termux_base_urls = termux_main_base_urls()
    for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
        index_payload, index_url = download_first_available(
            [termux_packages_index_url(base_url, termux_arch) for base_url in termux_base_urls],
        )
        selected_base_url = index_url.split("/dists/", 1)[0]
        package_base_urls = tuple(
            dict.fromkeys((selected_base_url, *termux_base_urls))
        )
        records = parse_packages_index(index_payload.decode("utf-8", "ignore"))
        packages = resolve_dependency_closure(records)

        prefix_dir = asset_prefix_dir(output_dir, android_abi)
        if prefix_dir.exists():
            shutil.rmtree(prefix_dir)
        prefix_dir.mkdir(parents=True, exist_ok=True)

        links: list[dict] = []
        for package in packages:
            payload, _ = download_first_available(
                [termux_package_url(base_url, package.filename) for base_url in package_base_urls],
            )
            verify_sha256(payload, package.sha256)
            data_bytes, data_name = load_data_tar_bytes_from_deb(payload)
            with open_data_tar(data_bytes, data_name) as tar:
                links.extend(mirror_data_tar(tar, prefix_dir))

        prune_staging_prefix(prefix_dir)
        create_bionic_llama_server_launcher(prefix_dir)
        for extra_dir in [prefix_dir / "home", prefix_dir / "tmp"]:
            extra_dir.mkdir(parents=True, exist_ok=True)

        write_manifest(asset_manifest_path(output_dir, android_abi), serializable_manifest(android_abi, packages, links=links))


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android Linux CLI assets for Hermes Android builds")
    parser.add_argument("--output-dir", required=True, help="Directory where generated assets should be written")
    args = parser.parse_args()
    output_dir = Path(args.output_dir).expanduser().resolve()
    asset_root = output_dir / ANDROID_LINUX_ASSET_ROOT
    if asset_root.exists():
        shutil.rmtree(asset_root)
    asset_root.mkdir(parents=True, exist_ok=True)
    prepare_assets(output_dir)


if __name__ == "__main__":
    main()
