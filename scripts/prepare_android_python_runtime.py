#!/usr/bin/env python3
"""Source-build missing Android Python dependencies into an external build cache.

No custom prebuilt wheel/bootstrap downloads are accepted. The pinned Chaquopy
fork builds jiter, pydantic-core, pure msgpack and the Python bootstrap from
verified source. Other wheels come only from PyPI/Chaquopy with committed hashes.
This preparation is build evidence, never device/runtime certification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import venv

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "hermes_android/python_runtime.lock.json"
REQUIREMENTS = ROOT / "requirements-android-chaquopy.txt"
SCHEMA = "hermes-chaquopy-source-consumer-v1"
CUSTOM = {"jiter", "pydantic-core", "msgpack"}
ABIS = {"arm64-v8a": "arm64_v8a", "x86_64": "x86_64"}
BUILD_PINS = (
    "pip==26.2.1", "cibuildwheel==4.2.0", "build==1.3.0", "maturin==1.15.0",
    "setuptools==80.9.0", "wheel==0.45.1", "packaging==26.3", "bashlex==0.18",
    "bracex==3.0.1", "certifi==2026.7.22", "dependency-groups==1.3.2",
    "filelock==3.32.5", "humanize==4.16.0", "platformdirs==4.11.7",
    "pyproject-hooks==1.2.0",
)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical_name(value):
    return re.sub(r"[-_.]+", "-", value).lower()


def pins(text):
    result = {}
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([A-Za-z0-9_.!+]+)", line)
        if not match or canonical_name(match[1]) in result:
            raise ValueError("Requirements must have one exact, unique pin per package")
        result[canonical_name(match[1])] = match[2]
    return result


def inventory(root):
    files = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Linked or escaping Python bundle input")
        if path.is_file() and path != root / "consumer.json":
            files.append({"path": path.relative_to(root).as_posix(),
                          "bytes": path.stat().st_size, "sha256": digest(path)})
    return files


def load_lock(path=LOCK, requirements=REQUIREMENTS):
    lock = json.loads(path.read_text(encoding="utf-8"))
    if (lock.get("schema_version") != 1 or lock.get("python") != "3.13"
            or lock.get("bootstrap_version") != "17.0.1"):
        raise ValueError("Unsupported Android Python source lock")
    source = lock["source"]
    if (not re.fullmatch(r"[0-9a-f]{40}", source["commit"])
            or source["archive_url"] != "https://codeload.github.com/adybag14-cyber/chaquopy/tar.gz/" + source["commit"]
            or not re.fullmatch(r"[0-9a-f]{64}", source["archive_sha256"])
            or not 0 < source["archive_size_bytes"] < 64 * 1024 * 1024):
        raise ValueError("Chaquopy source must be an immutable, checksum-bound archive")
    selected = pins(requirements.read_text(encoding="utf-8"))
    wheel_packages = set()
    names = set()
    for wheel in lock["official_wheels"]:
        name = wheel["filename"]
        if (not re.fullmatch(r"[A-Za-z0-9_.!+-]+\.whl", name) or name in names
                or not re.fullmatch(r"[0-9a-f]{64}", wheel["sha256"])
                or not 0 < wheel["bytes"] < 64 * 1024 * 1024):
            raise ValueError("Invalid/duplicate official wheel identity")
        package, version = name.split("-")[:2]
        package = canonical_name(package)
        if package in CUSTOM or selected.get(package) != version:
            raise ValueError("Official wheel does not match the selected requirements")
        names.add(name)
        wheel_packages.add(package)
    if wheel_packages != set(selected) - CUSTOM or not CUSTOM <= set(selected):
        raise ValueError("Official wheel lock does not cover the complementary package set")
    return lock


def verify(root, *, lock_file=LOCK, requirements=REQUIREMENTS):
    lock = load_lock(lock_file, requirements)
    receipt = json.loads((root / "consumer.json").read_text(encoding="utf-8"))
    if (receipt.get("schema") != SCHEMA or receipt.get("python") != lock["python"]
            or receipt.get("source_lock_sha256") != digest(lock_file)
            or receipt.get("hermes_requirements_sha256") != digest(requirements)
            or receipt.get("fork_commit") != lock["source"]["commit"]
            or receipt.get("runtime_tested") is not False
            or receipt.get("bootstrap_version") != lock["bootstrap_version"]
            or receipt.get("files") != inventory(root)):
        raise ValueError("Python source bundle no longer matches committed inputs or its closed inventory")
    bootstrap = root / "maven/com/chaquo/python/runtime/bootstrap" / lock["bootstrap_version"]
    bootstrap = bootstrap / f"bootstrap-{lock['bootstrap_version']}-{lock['python']}.imy"
    if digest(bootstrap) != receipt["bootstrap_sha256"]:
        raise ValueError("Source-built bootstrap changed")
    return receipt


def extract_source(archive, output, prefix):
    """Extract only selected regular source files; never trust tar link targets."""
    output.mkdir()
    seen = set()
    with tarfile.open(archive) as source:
        for member in source:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != prefix:
                raise ValueError("Unsafe Chaquopy archive member")
            relative = PurePosixPath(*path.parts[1:])
            name = relative.as_posix()
            selected = (name.startswith("compat/hermes/")
                        or name.startswith("product/runtime/src/main/python/")
                        or name in {"VERSION.txt", "product/runtime/build.gradle",
                                    "product/buildSrc/src/main/java/com/chaquo/python/internal/Common.java"})
            if not selected or member.isdir():
                continue
            if not member.isfile() or name in seen or member.size > 16 * 1024 * 1024:
                raise ValueError("Linked, duplicated or oversized Chaquopy source member")
            seen.add(name)
            destination = output / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as incoming, destination.open("xb") as outgoing:
                shutil.copyfileobj(incoming, outgoing)


def download_source(record, destination):
    request = urllib.request.Request(record["archive_url"], headers={"User-Agent": "Hermes-source-build/1"})
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("xb") as output:
        total = 0
        while block := response.read(1024 * 1024):
            total += len(block)
            if total > record["archive_size_bytes"]:
                raise ValueError("Chaquopy source archive exceeds locked size")
            output.write(block)
    if total != record["archive_size_bytes"] or digest(destination) != record["archive_sha256"]:
        raise ValueError("Chaquopy source archive checksum/size mismatch")


def run(command, *, cwd, env, timeout=3600):
    print("Running " + " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=env, check=True, timeout=timeout)


def official_requirements(lock, requirements):
    lines = []
    for package, version in sorted(pins(requirements).items()):
        if package in CUSTOM:
            continue
        hashes = sorted({item["sha256"] for item in lock["official_wheels"]
                         if canonical_name(item["filename"].split("-")[0]) == package})
        lines.append(f"{package}=={version} " + " ".join("--hash=sha256:" + value for value in hashes))
    return "\n".join(lines) + "\n"


def prepare(output, work, *, lock_file=LOCK, requirements=REQUIREMENTS):
    lock = load_lock(lock_file, requirements)
    if output.exists():
        return verify(output, lock_file=lock_file, requirements=requirements)
    if sys.platform != "linux" or sys.version_info[:2] != (3, 13):
        raise ValueError("Source preparation requires Linux Python 3.13; Windows uses the pinned buildserver container")
    if work.exists() or work.is_relative_to(output) or output.is_relative_to(work):
        raise ValueError("Use distinct new work/output directories; failed work is retained")
    work.mkdir(parents=True)
    source_archive = work / "chaquopy-source.tar.gz"
    download_source(lock["source"], source_archive)
    source = work / "chaquopy"
    extract_source(source_archive, source, "chaquopy-" + lock["source"]["commit"])
    helpers = source / "compat/hermes"
    environment = {name: value for name, value in os.environ.items()
                   if not name.endswith(("_TOKEN", "_SECRET", "_PASSWORD", "_API_KEY"))
                   and not name.startswith(("PIP_", "CIBW_"))}
    environment.update(PYTHONHASHSEED="0", SOURCE_DATE_EPOCH=str(lock["build"]["source_date_epoch"]),
                       CARGO_HOME=str(work / "cargo"), RUSTUP_HOME=str(work / "rustup"),
                       RUSTUP_TOOLCHAIN=lock["build"]["rust_version"],
                       CARGO_BUILD_JOBS=str(lock["build"]["maximum_parallel_jobs"]),
                       PIP_CONFIG_FILE=os.devnull)
    sdk = Path(environment.get("ANDROID_HOME", environment.get("ANDROID_SDK_ROOT", "/opt/android-sdk")))
    ndk = sdk / "ndk" / lock["build"]["native_ndk_version"] / "source.properties"
    if not ndk.is_file():
        raise ValueError("Install declared native Python NDK " + lock["build"]["native_ndk_version"])
    environment["ANDROID_HOME"] = environment["ANDROID_SDK_ROOT"] = str(sdk)
    drivers = work / "drivers"
    venv.create(drivers, with_pip=True)
    python = drivers / "bin/python"
    run([python, "-m", "pip", "install", "--index-url", "https://pypi.org/simple", "--no-deps", *BUILD_PINS],
        cwd=source, env=environment, timeout=600)
    run(["rustup", "toolchain", "install", lock["build"]["rust_version"], "--profile", "minimal",
         "--target", "aarch64-linux-android", "--target", "x86_64-linux-android"], cwd=source, env=environment)
    stage = work / "bundle"
    wheels = stage / "wheels"
    wheels.mkdir(parents=True)
    for package in ("jiter", "pydantic-core"):
        for abi in ABIS:
            result = work / f"{package}-{abi}"
            run([python, helpers / "build_native.py", "--package", package, "--python", "3.13",
                 "--abi", abi, "--build-only", "--source-cache", work / f"source-{package}-{abi}",
                 "--output", result], cwd=source, env=environment, timeout=3900)
            for wheel in (result / "wheels").glob("*.whl"):
                shutil.copyfile(wheel, wheels / wheel.name)
    run([python, helpers / "prepare_sources.py", "prepare", "--package", "msgpack",
         "--dest", work / "source-msgpack"], cwd=source, env=environment, timeout=180)
    run([python, "-m", "build", "--no-isolation", "--wheel", "--outdir", wheels,
         work / "source-msgpack/msgpack-1.2.2"], cwd=source,
        env=environment | {"MSGPACK_PUREPYTHON": "1"}, timeout=600)
    bootstrap = work / "bootstrap"
    run([python, helpers / "build_bootstrap.py", "--python", "3.13", "--output", bootstrap],
        cwd=source, env=environment, timeout=180)
    shutil.copytree(bootstrap / "maven", stage / "maven")
    bootstrap_receipt = json.loads((bootstrap / "receipt.json").read_text(encoding="utf-8"))
    shutil.copyfile(bootstrap / "receipt.json", stage / "bootstrap-receipt.json")
    official = work / "official-requirements.txt"
    official.write_text(official_requirements(lock, requirements.read_text(encoding="utf-8")), encoding="utf-8")
    shutil.copyfile(requirements, stage / "requirements.txt")
    for abi, platform_abi in ABIS.items():
        run([python, "-m", "pip", "download", "--no-deps", "--require-hashes", "--only-binary=:all:",
             "--platform", "android_24_" + platform_abi, "--python-version", "3.13",
             "--implementation", "cp", "--abi", "cp313", "--index-url", "https://pypi.org/simple",
             "--extra-index-url", "https://chaquo.com/pypi-13.1", "--dest", wheels, "-r", official],
            cwd=source, env=environment, timeout=900)
        run([python, "-m", "pip", "download", "--no-index", "--only-binary=:all:",
             "--platform", "android_24_" + platform_abi, "--python-version", "3.13",
             "--implementation", "cp", "--abi", "cp313", "--find-links", wheels,
             "--dest", work / ("closure-" + abi), "-r", requirements], cwd=source, env=environment, timeout=180)
        run([python, ROOT / "scripts/audit_android_python_runtime.py", "--helpers", helpers,
             "--wheel-dir", work / ("closure-" + abi), "--requirements", requirements,
             "--abi", abi, "--output", work / ("closure-" + abi + ".json")],
            cwd=source, env=environment, timeout=180)
    for item in lock["official_wheels"]:
        path = wheels / item["filename"]
        if not path.is_file() or path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
            raise ValueError("Official wheel differs from committed bytes: " + item["filename"])
    # These diagnostics are added to the debug Python source set only.
    (stage / "python").mkdir()
    for name in ("runtime_smoke.py", "hermes_runtime_smoke.py"):
        shutil.copyfile(helpers / name, stage / "python" / name)
    receipt = {"schema": SCHEMA, "python": lock["python"], "bootstrap_version": lock["bootstrap_version"],
               "bootstrap_sha256": bootstrap_receipt["archive_sha256"],
               "source_lock_sha256": digest(lock_file), "hermes_requirements_sha256": digest(requirements),
               "fork_commit": lock["source"]["commit"], "runtime_tested": False, "files": inventory(stage)}
    (stage / "consumer.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    verify(stage, lock_file=lock_file, requirements=requirements)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(stage, output)
    return verify(output, lock_file=lock_file, requirements=requirements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "verify"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if args.command == "verify":
        receipt = verify(output)
    else:
        if args.work_dir is None:
            parser.error("prepare requires --work-dir (new directory, retained on failure)")
        receipt = prepare(output, args.work_dir.resolve())
    print(json.dumps({"schema": receipt["schema"], "files": len(receipt["files"]),
                      "bootstrap_sha256": receipt["bootstrap_sha256"], "runtime_tested": False}))


if __name__ == "__main__":
    main()
