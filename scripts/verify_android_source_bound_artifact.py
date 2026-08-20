#!/usr/bin/env python3
"""Verify that an Android APK/AAB embeds one exact committed source digest."""

from __future__ import annotations

import argparse
import re
import zipfile
from pathlib import Path


SHA256_RE = re.compile(r"[0-9a-f]{64}")


class SourceBindingError(RuntimeError):
    """Raised when a release artifact does not carry the expected binding."""


def _dex_entry_names(archive: zipfile.ZipFile) -> list[str]:
    return sorted(
        name
        for name in archive.namelist()
        if name.endswith(".dex")
        and (name.startswith("classes") or name.startswith("base/dex/classes"))
    )


def verify_source_binding(artifact: Path, source_digest: str) -> list[str]:
    normalized_digest = source_digest.strip().lower()
    if not SHA256_RE.fullmatch(normalized_digest):
        raise SourceBindingError("source digest must be one lowercase SHA-256")
    if not artifact.is_file():
        raise SourceBindingError(f"Android artifact does not exist: {artifact}")

    expected = normalized_digest.encode("ascii")
    with zipfile.ZipFile(artifact) as archive:
        dex_entries = _dex_entry_names(archive)
        if not dex_entries:
            raise SourceBindingError(f"Android artifact contains no application DEX: {artifact}")
        dex_payloads = [archive.read(name) for name in dex_entries]

    if not any(expected in payload for payload in dex_payloads):
        raise SourceBindingError(
            f"Android artifact does not embed expected source digest {normalized_digest}: {artifact}"
        )
    if any(b"unbound" in payload for payload in dex_payloads):
        raise SourceBindingError(f"Android artifact still embeds an unbound source identity: {artifact}")
    return dex_entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--source-digest", required=True)
    args = parser.parse_args()
    entries = verify_source_binding(args.artifact, args.source_digest)
    print(f"sourceBinding=verified artifact={args.artifact} dexEntries={','.join(entries)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
