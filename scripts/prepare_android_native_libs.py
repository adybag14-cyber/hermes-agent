#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import re
from pathlib import Path


ANDROID_ABIS = ("arm64-v8a", "x86_64")
NATIVE_EXECUTABLES = {
    "bin/bash": "libhermes_android_bash.so",
    "bin/llama-server": "libhermes_android_llama_server.so",
    "bin/llama-server-bionic": "libhermes_android_llama_server_bionic_spawn.so",
}
RUNTIME_LIBRARIES = {
    "libandroid-spawn.so": "libandroid-spawn.so",
    "libc++_shared.so": "libc++_shared.so",
    "libcrypto.so.3": "libcrypto.so",
    "libggml-base.so": "libggml-base.so",
    "libggml-cpu.so": "libggml-cpu.so",
    "libggml.so": "libggml.so",
    "libllama-common.so": "libllama-common.so",
    "libllama-server-impl.so": "libllama-server-impl.so",
    "libllama.so": "libllama.so",
    "libmtmd.so": "libmtmd.so",
    "libssl.so.3": "libssl.so",
    "libtalloc.so.2.4.3": "libtalloc.so",
}
NATIVE_EXECUTABLE_SEARCH_DIRS = ("bin", "libexec")


def native_executable_name(relative_path: str) -> str:
    existing = NATIVE_EXECUTABLES.get(relative_path)
    if existing:
        return existing
    safe = re.sub(r"[^0-9A-Za-z_]+", "_", relative_path.replace("\\", "/"))
    safe = safe.strip("_") or "command"
    return f"libhermes_exec_{safe}.so"


def patch_needed(path: Path, old_name: str, new_name: str) -> None:
    if not path.is_file():
        return
    old = old_name.encode("utf-8") + b"\0"
    new = new_name.encode("utf-8") + b"\0"
    if len(new) > len(old):
        raise ValueError(f"replacement {new_name!r} is longer than {old_name!r}")
    payload = path.read_bytes()
    if old not in payload:
        return
    payload = payload.replace(old, new + (b"\0" * (len(old) - len(new))))
    path.write_bytes(payload)


def copy_abi(linux_assets_dir: Path, output_dir: Path, abi: str) -> None:
    prefix_dir = linux_assets_dir / "hermes-linux" / abi / "prefix"
    abi_output = output_dir / abi
    abi_output.mkdir(parents=True, exist_ok=True)
    copied_destinations: set[str] = set()
    for source_root in NATIVE_EXECUTABLE_SEARCH_DIRS:
        root = prefix_dir / source_root
        if not root.is_dir():
            continue
        for source in sorted(path for path in root.rglob("*") if path.is_file()):
            source_relative = source.relative_to(prefix_dir).as_posix()
            destination_name = native_executable_name(source_relative)
            if destination_name in copied_destinations:
                continue
            destination = abi_output / destination_name
            shutil.copy2(source, destination)
            destination.chmod(0o755)
            copied_destinations.add(destination_name)
    lib_dir = prefix_dir / "lib"
    for source_name, destination_name in sorted(RUNTIME_LIBRARIES.items()):
        source = lib_dir / source_name
        if source.is_file():
            destination = abi_output / destination_name
            shutil.copy2(source, destination)
            destination.chmod(0o755)
    patch_needed(abi_output / "libllama-common.so", "libssl.so.3", "libssl.so")
    patch_needed(abi_output / "libllama-common.so", "libcrypto.so.3", "libcrypto.so")
    patch_needed(abi_output / "libllama-server-impl.so", "libssl.so.3", "libssl.so")
    patch_needed(abi_output / "libllama-server-impl.so", "libcrypto.so.3", "libcrypto.so")
    patch_needed(abi_output / "libssl.so", "libssl.so.3", "libssl.so")
    patch_needed(abi_output / "libssl.so", "libcrypto.so.3", "libcrypto.so")
    patch_needed(abi_output / "libcrypto.so", "libcrypto.so.3", "libcrypto.so")
    patch_needed(abi_output / "libhermes_exec_bin_proot.so", "libtalloc.so.2", "libtalloc.so")


def prepare_native_libs(linux_assets_dir: Path, output_dir: Path) -> None:
    if output_dir.exists():
        for item in output_dir.rglob("*"):
            if item.is_file():
                item.unlink()
        for item in sorted((p for p in output_dir.rglob("*") if p.is_dir()), reverse=True):
            item.rmdir()
    output_dir.mkdir(parents=True, exist_ok=True)
    for abi in ANDROID_ABIS:
        copy_abi(linux_assets_dir, output_dir, abi)


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android-packaged native launcher libraries")
    parser.add_argument("--linux-assets-dir", required=True, help="Generated Hermes Linux assets directory")
    parser.add_argument("--output-dir", required=True, help="Generated jniLibs output directory")
    args = parser.parse_args()
    prepare_native_libs(
        linux_assets_dir=Path(args.linux_assets_dir).expanduser().resolve(),
        output_dir=Path(args.output_dir).expanduser().resolve(),
    )


if __name__ == "__main__":
    main()
