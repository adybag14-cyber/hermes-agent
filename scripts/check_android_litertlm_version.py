#!/usr/bin/env python3
"""Fail CI when Hermes no longer pins Google's latest LiteRT-LM Android SDK."""

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
DEPENDENCY_PATTERN = re.compile(
    r'com\.google\.ai\.edge\.litertlm:litertlm-android:([^"\s]+)'
)


def declared_version(gradle_file: Path) -> str:
    versions = DEPENDENCY_PATTERN.findall(gradle_file.read_text(encoding="utf-8"))
    if len(versions) != 1:
        raise ValueError(
            f"Expected exactly one pinned litertlm-android dependency in {gradle_file}, found {versions}"
        )
    version = versions[0]
    if version in {"+", "latest.release", "latest.integration"} or version.endswith("+"):
        raise ValueError(f"LiteRT-LM dependency must use an exact version, got {version!r}")
    return version


def latest_release(metadata: bytes) -> str:
    root = ET.fromstring(metadata)
    release = (root.findtext("./versioning/release") or "").strip()
    latest = (root.findtext("./versioning/latest") or "").strip()
    version = release or latest
    if not version:
        raise ValueError("Google Maven metadata does not declare a latest release")
    return version


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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-file", default=str(DEFAULT_GRADLE_FILE))
    parser.add_argument("--metadata-file", help="Use local Maven metadata instead of the live Google URL")
    args = parser.parse_args()

    gradle_file = Path(args.gradle_file).expanduser().resolve()
    if args.metadata_file:
        metadata_source = str(Path(args.metadata_file).expanduser().resolve())
        metadata = Path(metadata_source).read_bytes()
    else:
        metadata_source = MAVEN_METADATA_URL
        metadata = download_metadata()

    declared = declared_version(gradle_file)
    latest = latest_release(metadata)
    result = {
        "artifact": "com.google.ai.edge.litertlm:litertlm-android",
        "declared": declared,
        "latest": latest,
        "matches_latest": declared == latest,
        "metadata_source": metadata_source,
    }
    print(json.dumps(result, sort_keys=True))
    if declared != latest:
        raise SystemExit(
            f"Hermes pins LiteRT-LM Android {declared}, but Google Maven release is {latest}"
        )


if __name__ == "__main__":
    main()
