#!/usr/bin/env python3
"""Verify the actual Play APK manifest, payload exclusions and 16 KiB ELF LOAD alignment."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "com.mobilefork.hermesagent"
ABIS = {"arm64-v8a": 183, "x86_64": 62}
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
    "android.permission.ACCESS_NETWORK_STATE", "android.permission.VIBRATE",
    "android.permission.MODIFY_AUDIO_SETTINGS", f"{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
ALLOWED_COMPONENTS = {
    "activity": {f"{PACKAGE}.play.PlayActivity"},
    "service": set(),
    "receiver": {"androidx.profileinstaller.ProfileInstallReceiver"},
    "provider": {"androidx.core.content.FileProvider", "androidx.startup.InitializationProvider"},
}


def inspect_manifest(xml: str, *, version_name: str | None = None, version_code: int | None = None) -> dict:
    root = ET.fromstring(xml)
    if root.get("package") != PACKAGE:
        raise ValueError("Wrong Play package identity")
    sdk = root.find("uses-sdk")
    if sdk is None or int(sdk.get(ANDROID + "targetSdkVersion", "0")) < 36:
        raise ValueError("Play APK must target API 36 or newer")
    permissions = {node.get(ANDROID + "name", "") for node in
                   root.findall("uses-permission") + root.findall("uses-permission-sdk-23")}
    if not permissions <= ALLOWED_PERMISSIONS or "android.permission.INTERNET" not in permissions:
        raise ValueError(f"Unreviewed Play permissions: {sorted(permissions - ALLOWED_PERMISSIONS)}")
    app = root.find("application")
    if app is None or app.get(ANDROID + "allowBackup") != "false":
        raise ValueError("Play must disable app backup")
    distribution = [node.get(ANDROID + "value") for node in app.findall("meta-data")
                    if node.get(ANDROID + "name") == PACKAGE + ".DISTRIBUTION"]
    if distribution != ["play"]:
        raise ValueError("Artifact is not explicitly identified as the Play edition")
    for kind, allowed in ALLOWED_COMPONENTS.items():
        observed = {node.get(ANDROID + "name", "") for node in app.findall(kind)}
        if not observed <= allowed:
            raise ValueError(f"Unreviewed Play {kind}: {sorted(observed - allowed)}")
    if app.find("activity-alias") is not None:
        raise ValueError("Unreviewed Play activity alias")
    activities = app.findall("activity")
    if len(activities) != 1 or activities[0].get(ANDROID + "exported") != "true":
        raise ValueError("Play must expose exactly its foreground launcher activity")
    for receiver in app.findall("receiver"):
        if receiver.get(ANDROID + "permission") != "android.permission.DUMP":
            raise ValueError("Unexpected unprotected Play receiver")
    for provider in app.findall("provider"):
        if provider.get(ANDROID + "exported") != "false":
            raise ValueError("Play must not expose content providers")
    actual_name, actual_code = root.get(ANDROID + "versionName"), int(root.get(ANDROID + "versionCode", "0"))
    if version_name is not None and actual_name != version_name:
        raise ValueError("Wrong Play version name")
    if version_code is not None and actual_code != version_code:
        raise ValueError("Wrong Play version code")
    return {"package": PACKAGE, "edition": "play", "version_name": actual_name,
            "version_code": actual_code, "target_sdk": int(sdk.get(ANDROID + "targetSdkVersion")),
            "permissions": sorted(permissions)}


def inspect_elf(payload: bytes, machine: int) -> list[int]:
    if len(payload) < 64 or payload[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", payload, 18)[0] != machine:
        raise ValueError("Invalid ELF architecture/header")
    offset = struct.unpack_from("<Q", payload, 32)[0]
    size, count = struct.unpack_from("<HH", payload, 54)
    if size != 56 or count == 0 or offset + size * count > len(payload):
        raise ValueError("Invalid ELF program-header table")
    alignments = []
    for index in range(count):
        kind, _flags, file_offset, virtual, _physical, file_size, _memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", payload, offset + size * index)
        if kind != 1:
            continue
        if alignment < 16384 or alignment & (alignment - 1) or file_offset % alignment != virtual % alignment:
            raise ValueError(f"ELF LOAD segment is not 16 KiB compatible: alignment={alignment}")
        if file_offset + file_size > len(payload):
            raise ValueError("ELF LOAD segment exceeds file")
        alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF has no LOAD segments")
    return alignments


def inspect_payload(apk: Path, bundle: Path | None = None) -> dict:
    native = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK archive entries")
        for name in names:
            if name.startswith("assets/hermes-linux/") or "/libhermes_exec_" in name or name.endswith("/libhermes_android_bash.so"):
                raise ValueError(f"Full-edition executable payload in Play: {name}")
        for abi, machine in ABIS.items():
            required = f"lib/{abi}/libhermes_android_llama_server_experimental.so"
            if required not in names:
                raise ValueError(f"Missing packaged Play GGUF engine: {abi}")
            for name in names:
                if name.startswith(f"lib/{abi}/") and name.endswith(".so"):
                    alignments = inspect_elf(archive.read(name), machine)
                    native.append({"path": name, "load_alignment": min(alignments)})
            for name in names:
                if name.startswith("assets/chaquopy/") and abi in name and name.endswith(".imy"):
                    with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                        for member in nested.namelist():
                            if member.endswith(".so"):
                                alignments = inspect_elf(nested.read(member), machine)
                                native.append({"path": f"{name}!/{member}", "load_alignment": min(alignments)})
        if bundle is not None:
            with zipfile.ZipFile(bundle) as aab:
                bundle_payload = {name.removeprefix("base/") for name in aab.namelist()
                                  if name.startswith(("base/lib/", "base/assets/")) and not name.endswith("/")}
                apk_payload = {name for name in names if name.startswith(("lib/", "assets/")) and not name.endswith("/")}
                if bundle_payload != apk_payload:
                    raise ValueError("Play AAB and APK contain different runtime/asset inventories")
                for name in apk_payload:
                    if hashlib.sha256(archive.read(name)).digest() != hashlib.sha256(aab.read("base/" + name)).digest():
                        raise ValueError(f"Play AAB and APK payload bytes differ: {name}")
    return {"native_files": native, "bundle_payload_matched": bundle is not None}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apkanalyzer", required=True)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--version-name")
    parser.add_argument("--version-code", type=int)
    args = parser.parse_args()
    xml = subprocess.run([args.apkanalyzer, "manifest", "print", str(args.apk.resolve())],
                         check=True, capture_output=True, text=True, encoding="utf-8", timeout=120).stdout
    manifest = inspect_manifest(xml, version_name=args.version_name, version_code=args.version_code)
    payload = inspect_payload(args.apk, args.bundle)
    with args.apk.open("rb") as stream:
        apk_sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    print(json.dumps({"schema": "hermes-play-package-v1", "status": "passed", **manifest, **payload,
                      "apk_sha256": apk_sha256,
                      "runtime_execution_verified": False}, sort_keys=True))


if __name__ == "__main__":
    main()
