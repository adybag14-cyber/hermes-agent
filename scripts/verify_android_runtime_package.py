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


def verify(apk: Path, *, chaquopy_lab: bool = False, legacy_stubs: bool = False,
           python_bundle: Path | None = None) -> dict:
    if legacy_stubs and (chaquopy_lab or python_bundle is not None):
        raise ValueError("Legacy placeholder inspection cannot certify a genuine SDK bundle")
    receipt = None
    if python_bundle is not None:
        try:
            from . import prepare_android_python_runtime as runtime
        except ImportError:
            import prepare_android_python_runtime as runtime
        receipt = runtime.verify(python_bundle)
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
                if not legacy_stubs:
                    for package in ("jiter", "pydantic_core"):
                        members = [name for name in native.namelist()
                                   if name.startswith(package + "/") and name.endswith(".so")]
                        if not members:
                            raise ValueError(f"Missing genuine native Python dependency: {abi}/{package}")
                        for name in members:
                            header = native.read(name)[:20]
                            if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != machine:
                                raise ValueError(f"Wrong Python native ELF architecture: {abi}/{name}")
        if not json.loads(archive.read("assets/hermes-experimental-llama/manifest.json")):
            raise ValueError("Missing experimental-lane manifest")
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/chaquopy/bootstrap.imy"))) as bootstrap:
            if not bootstrap.namelist():
                raise ValueError("Empty Python bootstrap")
        if receipt is not None and hashlib.sha256(archive.read("assets/chaquopy/bootstrap.imy")).hexdigest() != receipt["bootstrap_sha256"]:
            raise ValueError("Packaged bootstrap differs from the source-built bundle")
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
                if placeholder != legacy_stubs:
                    raise ValueError(f"Wrong Python dependency mode for {name}")
            if python_bundle is not None:
                expected = runtime.pins((python_bundle / "requirements.txt").read_text(encoding="utf-8"))
                for name, version in expected.items():
                    if name not in metadata or metadata[name]["Version"] != version:
                        raise ValueError(f"Packaged SDK version differs from the source bundle: {name}")
    with apk.open("rb") as stream:
        apk_sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"schema": "hermes-runtime-package-v1", "status": "passed",
            "chaquopy_lab": chaquopy_lab, "abis": list(ABI_MACHINES),
            "python_dependency_mode": "legacy-stubs" if legacy_stubs else "genuine-sdks",
            "source_bundle_verified": receipt is not None,
            "apk_sha256": apk_sha256,
            "runtime_execution_verified": False, "release_certified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--chaquopy-lab", action="store_true")
    parser.add_argument("--legacy-stubs", action="store_true",
                        help="Explicit historical APK inspection only; never a current-release gate")
    parser.add_argument("--python-bundle", type=Path,
                        help="Verify embedded bootstrap bytes and every SDK pin against the source-built bundle")
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, chaquopy_lab=args.chaquopy_lab,
                            legacy_stubs=args.legacy_stubs, python_bundle=args.python_bundle), indent=2))


if __name__ == "__main__":
    main()
