#!/usr/bin/env python3
"""Check the stable release pin or resolve Google's published LiteRT-LM SDKs."""

from __future__ import annotations

import argparse
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GRADLE_FILE = REPO_ROOT / "android" / "app" / "build.gradle.kts"
MAVEN_METADATA_URL = (
    "https://dl.google.com/dl/android/maven2/"
    "com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml"
)
STABLE_VERSION_PATTERN = re.compile(
    r'val\s+liteRtLmStableVersion\s*=\s*"([^"\s]+)"'
)
DEPENDENCY_PATTERN = re.compile(
    r'implementation\(\s*"com\.google\.ai\.edge\.litertlm:'
    r'litertlm-android:\$liteRtLmVersion"\s*\)'
)
EXACT_VERSION_PATTERN = re.compile(r"\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.]+)?")
STABLE_EXACT_VERSION_PATTERN = re.compile(r"\d+\.\d+\.\d+")


def declared_version(gradle_file: Path) -> str:
    gradle_text = gradle_file.read_text(encoding="utf-8")
    versions = STABLE_VERSION_PATTERN.findall(gradle_text)
    if len(versions) != 1:
        raise ValueError(
            f"Expected exactly one liteRtLmStableVersion pin in {gradle_file}, found {versions}"
        )
    version = versions[0]
    if not EXACT_VERSION_PATTERN.fullmatch(version):
        raise ValueError(f"LiteRT-LM dependency must use an exact version, got {version!r}")
    if len(DEPENDENCY_PATTERN.findall(gradle_text)) != 1:
        raise ValueError(
            "Expected litertlm-android to consume the validated $liteRtLmVersion exactly once"
        )
    return version


def latest_release(metadata: bytes) -> str:
    root = ET.fromstring(metadata)
    release = (root.findtext("./versioning/release") or "").strip()
    latest = (root.findtext("./versioning/latest") or "").strip()
    version = release or latest
    if not version:
        raise ValueError("Google Maven metadata does not declare a latest release")
    if not EXACT_VERSION_PATTERN.fullmatch(version):
        raise ValueError(
            f"Google Maven metadata must declare one exact release version, got {version!r}"
        )
    return version


def latest_stable_release(metadata: bytes) -> str:
    root = ET.fromstring(metadata)
    candidates = {
        value.strip()
        for value in (
            root.findtext("./versioning/release") or "",
            root.findtext("./versioning/latest") or "",
            *(node.text or "" for node in root.findall("./versioning/versions/version")),
        )
        if STABLE_EXACT_VERSION_PATTERN.fullmatch(value.strip())
    }
    if not candidates:
        raise ValueError("Google Maven metadata does not declare a stable release")
    return max(candidates, key=lambda value: tuple(int(part) for part in value.split(".")))


def download_metadata(url: str = MAVEN_METADATA_URL) -> bytes:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Hermes-Android-LiteRT-LM-version-check/1"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read()
    if not payload:
        raise RuntimeError(f"Empty Maven metadata response from {url}")
    return payload


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-file", default=str(DEFAULT_GRADLE_FILE))
    parser.add_argument("--metadata-file", help="Use local Maven metadata instead of the live Google URL")
    output_mode = parser.add_mutually_exclusive_group()
    output_mode.add_argument(
        "--print-latest",
        action="store_true",
        help="Print Google's exact published-latest version without comparing the release pin",
    )
    output_mode.add_argument(
        "--print-latest-stable",
        action="store_true",
        help="Print Google's newest stable version without comparing the release pin",
    )
    output_mode.add_argument(
        "--print-declared",
        action="store_true",
        help="Print the exact Hermes release pin without downloading Maven metadata",
    )
    args = parser.parse_args(argv)

    gradle_file = Path(args.gradle_file).expanduser().resolve()
    if args.print_declared:
        print(declared_version(gradle_file))
        return

    if args.metadata_file:
        metadata_source = str(Path(args.metadata_file).expanduser().resolve())
        metadata = Path(metadata_source).read_bytes()
    else:
        metadata_source = MAVEN_METADATA_URL
        metadata = download_metadata()

    published_latest = latest_release(metadata)
    latest_stable = latest_stable_release(metadata)
    if args.print_latest:
        print(published_latest)
        return
    if args.print_latest_stable:
        print(latest_stable)
        return

    declared = declared_version(gradle_file)
    result = {
        "artifact": "com.google.ai.edge.litertlm:litertlm-android",
        "declared": declared,
        "latest": latest_stable,
        "matches_latest": declared == latest_stable,
        "latest_stable": latest_stable,
        "matches_latest_stable": declared == latest_stable,
        "published_latest": published_latest,
        "published_latest_is_prerelease": published_latest != latest_stable,
        "metadata_source": metadata_source,
    }
    print(json.dumps(result, sort_keys=True))
    if declared != latest_stable:
        raise SystemExit(
            f"Hermes pins LiteRT-LM Android {declared}, but Google's latest stable "
            f"release is {latest_stable} (published latest: {published_latest})"
        )


if __name__ == "__main__":
    main()
