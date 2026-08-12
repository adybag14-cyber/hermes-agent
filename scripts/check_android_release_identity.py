#!/usr/bin/env python3
"""Fail closed when an Android release tag disagrees with source/F-Droid identity."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ReleaseIdentity:
    version_name: str
    version_code: int


def _single_match(pattern: str, text: str, label: str) -> str:
    matches = re.findall(pattern, text, flags=re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"Expected one {label}, found {matches!r}")
    return matches[0]


def _semver_version_code(version: str) -> int:
    match = re.fullmatch(
        r"(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(?:[.-]?(\d+))?)?",
        version,
    )
    if match is None:
        raise ValueError(f"Android release version must be SemVer, got {version!r}")
    major, minor, patch = (int(match.group(index)) for index in range(1, 4))
    prerelease = (match.group(4) or "").lower()
    prerelease_sequence = min(int(match.group(5) or "0"), 9)
    rank = {"alpha": 1, "beta": 2, "rc": 3, "": 9}.get(prerelease, 4)
    return major * 1_000_000 + minor * 10_000 + patch * 100 + rank * 10 + prerelease_sequence


def validate_release_identity(repo_root: Path, tag: str) -> ReleaseIdentity:
    normalized_tag = tag.strip()
    fdroid_text = (repo_root / "fdroid/com.mobilefork.hermesagent.version").read_text(encoding="utf-8")
    fdroid_version = _single_match(r"^versionName=(.+)$", fdroid_text, "F-Droid versionName")
    fdroid_code = int(_single_match(r"^versionCode=(\d+)$", fdroid_text, "F-Droid versionCode"))
    template_text = (repo_root / "fdroid/com.mobilefork.hermesagent.yml.template").read_text(encoding="utf-8")
    template_current_version = _single_match(
        r"^CurrentVersion:\s*(\S+)$", template_text, "F-Droid template CurrentVersion"
    )
    template_current_code = int(
        _single_match(r"^CurrentVersionCode:\s*(\d+)$", template_text, "F-Droid template CurrentVersionCode")
    )
    if not normalized_tag.startswith("v"):
        raise ValueError(f"Android release tag must start with v, got {normalized_tag!r}")
    tag_version = normalized_tag.removeprefix("v")
    _semver_version_code(tag_version)
    expected_tag = f"v{fdroid_version}"
    versions = {
        "tag": tag_version,
        "F-Droid version": fdroid_version,
        "F-Droid template": template_current_version,
    }
    if normalized_tag != expected_tag or len(set(versions.values())) != 1:
        details = ", ".join(f"{key}={value}" for key, value in versions.items())
        raise ValueError(f"Android release identity mismatch; expected tag {expected_tag}. {details}")

    computed_code = _semver_version_code(tag_version)
    codes = {"computed": computed_code, "F-Droid version": fdroid_code, "F-Droid template": template_current_code}
    if len(set(codes.values())) != 1:
        details = ", ".join(f"{key}={value}" for key, value in codes.items())
        raise ValueError(f"Android versionCode mismatch. {details}")
    return ReleaseIdentity(version_name=tag_version, version_code=computed_code)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    identity = validate_release_identity(args.repo_root.resolve(), args.tag)
    print(f"versionName={identity.version_name}")
    print(f"versionCode={identity.version_code}")


if __name__ == "__main__":
    main()
