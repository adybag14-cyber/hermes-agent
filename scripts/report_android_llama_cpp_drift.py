#!/usr/bin/env python3
"""Report Termux llama.cpp package drift without reclassifying compatibility."""

from __future__ import annotations

import argparse
import json
import re
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK_FILE = REPO_ROOT / "hermes_android" / "termux_linux_assets.lock.json"
LLAMA_POOL_PATH = "pool/main/l/llama-cpp/"
LLAMA_VERSION_PATTERN = re.compile(r"0\.0\.0-b(?P<build>\d+)-(?P<revision>\d+)")
LLAMA_FILENAME_PATTERN = re.compile(
    r"llama-cpp_(0\.0\.0-b\d+-\d+)_([0-9A-Za-z_+-]+)\.deb"
)
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


def _llama_version_key(version: str) -> tuple[int, int]:
    match = LLAMA_VERSION_PATTERN.fullmatch(version)
    if match is None:
        raise ValueError(f"Unsupported Termux llama-cpp version format: {version!r}")
    return int(match.group("build")), int(match.group("revision"))


def locked_llama_packages(lock: dict[str, Any]) -> tuple[str, dict[str, dict[str, str]]]:
    base_url = lock.get("termux_main_base_url")
    if not isinstance(base_url, str) or not base_url.startswith("https://"):
        raise ValueError("Lock must declare an HTTPS termux_main_base_url")

    architectures = lock.get("architectures")
    if not isinstance(architectures, dict) or not architectures:
        raise ValueError("Lock must declare at least one architecture")

    result: dict[str, dict[str, str]] = {}
    for abi, architecture in architectures.items():
        if not isinstance(abi, str) or not isinstance(architecture, dict):
            raise ValueError("Lock architecture entries must be objects keyed by Android ABI")
        termux_arch = architecture.get("termux_arch")
        packages = architecture.get("packages")
        if not isinstance(termux_arch, str) or not termux_arch:
            raise ValueError(f"Lock architecture {abi!r} has no termux_arch")
        if not isinstance(packages, list):
            raise ValueError(f"Lock architecture {abi!r} has no package list")

        matches = [package for package in packages if package.get("name") == "llama-cpp"]
        if len(matches) != 1:
            raise ValueError(
                f"Expected exactly one llama-cpp package for {abi}, found {len(matches)}"
            )
        package = matches[0]
        version = package.get("version")
        filename = package.get("filename")
        sha256 = package.get("sha256")
        if not isinstance(version, str):
            raise ValueError(f"llama-cpp package for {abi} has no version")
        _llama_version_key(version)
        expected_filename = f"llama-cpp_{version}_{termux_arch}.deb"
        if not isinstance(filename, str) or filename.rsplit("/", 1)[-1] != expected_filename:
            raise ValueError(
                f"llama-cpp filename for {abi} does not match its version and architecture"
            )
        if not isinstance(sha256, str) or SHA256_PATTERN.fullmatch(sha256) is None:
            raise ValueError(f"llama-cpp package for {abi} has no exact SHA-256")
        result[abi] = {
            "termux_arch": termux_arch,
            "pinned": version,
            "filename": filename,
            "sha256": sha256,
        }

    return base_url.rstrip("/"), result


def latest_index_versions(index_html: str, termux_arches: set[str]) -> dict[str, str]:
    candidates: dict[str, set[str]] = {architecture: set() for architecture in termux_arches}
    for version, architecture in LLAMA_FILENAME_PATTERN.findall(index_html):
        if architecture in candidates:
            candidates[architecture].add(version)

    missing = sorted(architecture for architecture, versions in candidates.items() if not versions)
    if missing:
        raise ValueError(
            "Termux llama-cpp index has no package for locked architecture(s): "
            + ", ".join(missing)
        )
    return {
        architecture: max(versions, key=_llama_version_key)
        for architecture, versions in candidates.items()
    }


def build_report(
    lock: dict[str, Any],
    index_html: str,
    *,
    index_source: str,
) -> dict[str, Any]:
    base_url, locked = locked_llama_packages(lock)
    upstream = latest_index_versions(
        index_html,
        {entry["termux_arch"] for entry in locked.values()},
    )
    architectures: dict[str, dict[str, Any]] = {}
    for abi, entry in sorted(locked.items()):
        latest = upstream[entry["termux_arch"]]
        architectures[abi] = {
            **entry,
            "latest_published": latest,
            "upstream_drift": entry["pinned"] != latest,
        }

    has_drift = any(entry["upstream_drift"] for entry in architectures.values())
    return {
        "artifact": "Termux llama-cpp",
        "architectures": architectures,
        "compatibility_authority": (
            "real-GGUF release evidence; this advisory report does not reclassify compatibility"
        ),
        "index_source": index_source,
        "pin_source": str(DEFAULT_LOCK_FILE),
        "repository": base_url,
        "status": "upstream-drift" if has_drift else "upstream-current",
        "upstream_drift": has_drift,
    }


def download_index(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Hermes-Android-llama-cpp-drift-report/1"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read()
    if not payload:
        raise RuntimeError(f"Empty Termux package index response from {url}")
    return payload.decode("utf-8")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock-file", default=str(DEFAULT_LOCK_FILE))
    parser.add_argument("--index-file", help="Use a local Termux directory index")
    parser.add_argument("--index-url", help="Override the authoritative Termux directory URL")
    args = parser.parse_args(argv)

    lock_file = Path(args.lock_file).expanduser().resolve()
    lock = json.loads(lock_file.read_text(encoding="utf-8"))
    base_url, _ = locked_llama_packages(lock)
    default_index_url = f"{base_url}/{LLAMA_POOL_PATH}"

    if args.index_file:
        index_path = Path(args.index_file).expanduser().resolve()
        index_source = str(index_path)
        index_html = index_path.read_text(encoding="utf-8")
    else:
        index_source = args.index_url or default_index_url
        index_html = download_index(index_source)

    report = build_report(lock, index_html, index_source=index_source)
    report["pin_source"] = str(lock_file)
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
