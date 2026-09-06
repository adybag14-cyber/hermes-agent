#!/usr/bin/env python3
"""Inspect the built APK, not Gradle source text, for both runtime lanes and Python mode.

This checks packaging only. Native execution and model/SDK compatibility still
require the separate Android instrumentation gates.
"""
import argparse
from email.parser import BytesParser
import hashlib
import io
import json
from pathlib import Path
import zipfile

ABI_MACHINES = {"arm64-v8a": 183, "x86_64": 62}
NATIVE_PROGRAMS = ("bash", "llama_server", "llama_server_experimental")


def verify(apk: Path, *, chaquopy_lab: bool = False) -> dict:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        for abi, machine in ABI_MACHINES.items():
            for program in NATIVE_PROGRAMS:
                name = f"lib/{abi}/libhermes_android_{program}.so"
                with archive.open(name) as stream:
                    header = stream.read(20)
                if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != machine:
                    raise ValueError(f"Wrong native ELF architecture: {name}")
            if not json.loads(archive.read(f"assets/hermes-linux/{abi}/manifest.json")):
                raise ValueError(f"Empty Linux asset manifest: {abi}")
            with zipfile.ZipFile(io.BytesIO(archive.read(f"assets/chaquopy/requirements-{abi}.imy"))) as native:
                if not native.namelist():
                    raise ValueError(f"Empty Python ABI archive: {abi}")
        if not json.loads(archive.read("assets/hermes-experimental-llama/manifest.json")):
            raise ValueError("Missing experimental-lane manifest")
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/chaquopy/bootstrap.imy"))) as bootstrap:
            if not bootstrap.namelist():
                raise ValueError("Empty Python bootstrap")
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/chaquopy/requirements-common.imy"))) as requirements:
            metadata = {}
            for name in requirements.namelist():
                if name.endswith(".dist-info/METADATA"):
                    parsed = BytesParser().parsebytes(requirements.read(name))
                    key = parsed["Name"].lower().replace("_", "-")
                    if key in metadata:
                        raise ValueError(f"Duplicate Python distribution: {key}")
                    metadata[key] = parsed
            for name in ("anthropic", "fal-client"):
                package = metadata.get(name)
                if package is None:
                    raise ValueError(f"Missing Python distribution: {name}")
                placeholder = "placeholder" in package.get("Summary", "").lower()
                if placeholder == chaquopy_lab:
                    raise ValueError(f"Wrong Python dependency mode for {name}")
    with apk.open("rb") as stream:
        apk_sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"schema": "hermes-runtime-package-v1", "status": "passed",
            "chaquopy_lab": chaquopy_lab, "abis": list(ABI_MACHINES),
            "apk_sha256": apk_sha256,
            "runtime_execution_verified": False, "release_certified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--chaquopy-lab", action="store_true")
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, chaquopy_lab=args.chaquopy_lab), indent=2))


if __name__ == "__main__":
    main()
