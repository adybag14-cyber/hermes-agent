#!/usr/bin/env python3
"""Build the official MCP SDK's missing native Android dependencies from pinned source.

The existing Chaquopy fork remains unchanged. These builds reuse its verified
cibuildwheel driver, wheel/ELF auditor and reproducible-wheel normalizer.
"""
from __future__ import annotations

import base64
import csv
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import tomllib
import urllib.parse
import urllib.request
import zipfile

NATIVE_PACKAGES = {"cffi", "cryptography", "rpds-py"}
LIBRARIES = {"openssl", "libffi"}
TARGETS = {
    "arm64-v8a": ("arm64_v8a", "aarch64-linux-android", "android-arm64"),
    "x86_64": ("x86_64", "x86_64-linux-android", "android-x86_64"),
}
EXPECTED_INITIALIZERS = {"cffi": "PyInit__cffi_backend", "cryptography": "PyInit__rust", "rpds-py": "PyInit_rpds"}
BUILD_REQUIREMENTS = {
    "cffi": ["setuptools==80.9.0"],
    "cryptography": ["maturin==1.15.0", "cffi==2.0.0", "setuptools==80.9.0"],
    "rpds-py": ["maturin==1.15.0"],
}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_sources(config: dict, selected: dict[str, str]) -> None:
    if (set(config.get("packages", {})) != NATIVE_PACKAGES or set(config.get("libraries", {})) != LIBRARIES
            or config.get("builder_sha256") != sha256(Path(__file__))):
        raise ValueError("MCP source-builder identity or native source set differs from its lock")
    for package, record in (config["packages"] | config["libraries"]).items():
        url = urllib.parse.urlsplit(record["url"])
        allowed = (url.scheme == "https" and not url.query and not url.fragment and not url.username and (
            (package in NATIVE_PACKAGES and url.netloc == "files.pythonhosted.org" and url.path.startswith("/packages/"))
            or (package == "openssl" and url.netloc == "github.com" and url.path.startswith("/openssl/openssl/releases/download/"))
            or (package == "libffi" and url.netloc == "github.com" and url.path.startswith("/libffi/libffi/releases/download/"))))
        if (not allowed or not re.fullmatch(r"[A-Za-z0-9_.+-]+\.tar\.gz", record["filename"])
                or Path(url.path).name != record["filename"]
                or not re.fullmatch(r"[A-Za-z0-9_.+-]+", record["source_directory"])
                or not re.fullmatch(r"[0-9a-f]{64}", record["sha256"])
                or not 0 < record["bytes"] < 64 * 1024 * 1024):
            raise ValueError("Invalid MCP native source archive identity")
        if package in NATIVE_PACKAGES and selected.get(package) != record["version"]:
            raise ValueError("MCP source version does not match the selected runtime requirement")


def download(record: dict, destination: Path) -> None:
    if destination.exists():
        if destination.is_symlink() or destination.stat().st_size != record["bytes"] or sha256(destination) != record["sha256"]:
            raise ValueError("Existing MCP source archive differs from its immutable lock")
        return
    request = urllib.request.Request(record["url"], headers={"User-Agent": "Hermes-Android-source-build/1"})
    with urllib.request.urlopen(request, timeout=90) as incoming, destination.open("xb") as outgoing:
        total = 0
        while chunk := incoming.read(1024 * 1024):
            total += len(chunk)
            if total > record["bytes"]:
                raise ValueError("MCP source download exceeds its locked byte count")
            outgoing.write(chunk)
    if total != record["bytes"] or sha256(destination) != record["sha256"]:
        raise ValueError("MCP source archive checksum mismatch")


def extract(archive: Path, output: Path, prefix: str) -> None:
    output.mkdir()
    seen = set()
    total = 0
    with tarfile.open(archive) as incoming:
        for member in incoming:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or "\\" in member.name or ".." in member.name.split("/")
                    or not path.parts or path.parts[0] != prefix):
                raise ValueError("Unsafe MCP source archive path")
            if member.isdir():
                continue
            relative = Path(*path.parts[1:])
            total += member.size
            if (not member.isfile() or relative in seen or member.size > 32 * 1024 * 1024
                    or total > 1024 * 1024 * 1024):
                raise ValueError("Linked, duplicate or oversized MCP source archive member")
            seen.add(relative)
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            with incoming.extractfile(member) as source, destination.open("xb") as target:
                shutil.copyfileobj(source, target)
            destination.chmod(0o755 if member.mode & 0o111 else 0o644)


def pin_build_requirements(source: Path, package: str) -> None:
    path = source / "pyproject.toml"
    original = path.read_text(encoding="utf-8")
    metadata = tomllib.loads(original)
    expected_backend = "setuptools.build_meta" if package == "cffi" else "maturin"
    if metadata["build-system"]["build-backend"] != expected_backend:
        raise ValueError("Unexpected native Python build backend")
    start = original.index("[build-system]")
    end_match = re.search(r"(?m)^\[", original[start + len("[build-system]"):])
    end = len(original) if end_match is None else start + len("[build-system]") + end_match.start()
    section, count = re.subn(r"(?ms)^requires\s*=\s*\[.*?\]", "requires = " + json.dumps(BUILD_REQUIREMENTS[package]),
                             original[start:end], count=1)
    if count != 1:
        raise ValueError("Native source build requirements were not unambiguous")
    updated = original[:start] + section + original[end:]
    after = tomllib.loads(updated)
    before = dict(metadata)
    before["build-system"] = {**metadata["build-system"], "requires": BUILD_REQUIREMENTS[package]}
    if after != before:
        raise ValueError("Build-requirements pin changed unrelated source metadata")
    path.write_text(updated, encoding="utf-8")


def attach_license(wheel: Path, name: str, license_bytes: bytes) -> None:
    with zipfile.ZipFile(wheel) as source:
        files = {item.filename: source.read(item) for item in source.infolist() if not item.is_dir()}
    records = [path for path in files if path.endswith(".dist-info/RECORD")]
    if len(records) != 1 or not license_bytes:
        raise ValueError("Missing wheel RECORD or static-library license")
    dist_info = records[0].rsplit("/", 1)[0]
    target = f"{dist_info}/licenses/{name}"
    if target in files:
        raise ValueError("Static-library license already exists in original wheel")
    files[target] = license_bytes
    record = io.StringIO(newline="")
    writer = csv.writer(record, lineterminator="\n")
    for path, payload in sorted(files.items()):
        value = base64.urlsafe_b64encode(hashlib.sha256(payload).digest()).rstrip(b"=").decode()
        writer.writerow((path, "", "") if path == records[0] else (path, "sha256=" + value, len(payload)))
    files[records[0]] = record.getvalue().encode()
    temporary = wheel.with_suffix(".licensed")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as target_archive:
        for path, payload in sorted(files.items()):
            entry = zipfile.ZipInfo(path, (1980, 2, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | 0o644) << 16
            target_archive.writestr(entry, payload)
    temporary.replace(wheel)


def native_library_environment(package: str, openssl_prefix: Path, ffi_prefix: Path, ffi_stage: Path) -> dict[str, str]:
    # cibuildwheel's Android target setup replaces PKG_CONFIG_LIBDIR and CFLAGS.
    # PKG_CONFIG_PATH is the supported additional search path which survives that setup.
    result = {"OPENSSL_STATIC": "1", "OPENSSL_DIR": str(openssl_prefix), "PKG_CONFIG_ALLOW_CROSS": "1",
              "PKG_CONFIG_PATH": str(ffi_prefix / "lib/pkgconfig"), "PKG_CONFIG_SYSROOT_DIR": str(ffi_stage)}
    if package == "cffi":
        result["CFFI_FORCE_STATIC"] = str(ffi_prefix / "lib/libffi.a")
    return result


def configure_cryptography_android_headers(source: Path) -> None:
    # cryptography 50 assumes PYO3_CROSS_LIB_DIR ends in lib/pythonX.Y;
    # cibuildwheel Android correctly supplies prefix/lib instead. Adapt only
    # this build-time path calculation, not cryptographic or protocol code.
    path = source / "src/rust/cryptography-cffi/build.rs"
    original = path.read_text(encoding="utf-8")
    old = '''        let py_ver = lib
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("python3");
        let prefix = lib
            .parent()
            .and_then(|p| p.parent())
            .expect("PYO3_CROSS_LIB_DIR has unexpected layout");'''
    new = '''        let prefix = lib.parent()
            .expect("PYO3_CROSS_LIB_DIR has unexpected layout");
        let py_ver = "python3.13";'''
    if original.count(old) != 1:
        raise ValueError("Cryptography cross-header source differs from the reviewed build adaptation")
    path.write_text(original.replace(old, new, 1), encoding="utf-8")


def configure_cryptography_android_link(source: Path) -> None:
    # PyO3's abi3 build selects the generic libpython3 shim. Chaquopy loads
    # libpython with Android's local linker namespace, so every extension needs
    # a direct dependency on the real interpreter, as cffi and rpds already do.
    path = source / "src/rust/build.rs"
    original = path.read_text(encoding="utf-8")
    marker = "fn main() {"
    if original.count(marker) != 1:
        raise ValueError("Cryptography linker build script differs from its reviewed source")
    link = '''
    assert_eq!(env::var("CARGO_CFG_TARGET_OS").as_deref(), Ok("android"));
    let python_lib = env::var("PYO3_CROSS_LIB_DIR")
        .expect("Android Python target library directory is required");
    assert!(std::path::Path::new(&python_lib).join("libpython3.13.so").is_file());
    println!("cargo:rustc-link-search=native={python_lib}");
    println!("cargo:rustc-link-lib=dylib=python3.13");
'''
    path.write_text(original.replace(marker, marker + link, 1), encoding="utf-8")


def require_android_python_link(audit: dict, package: str) -> None:
    extensions = [library for library in audit["native_libraries"]
                  if EXPECTED_INITIALIZERS[package] in library["python_initializers"]]
    if not extensions or any("libpython3.13.so" not in library["needed"] for library in extensions):
        raise ValueError("Native MCP extension must directly link the Android Python 3.13 interpreter")


def native_rust_flags(source: Path, cargo_home: Path, build_home: Path) -> str:
    flags = "-C link-arg=-Wl,--build-id=none -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
    # rustc uses the last matching prefix. Keep broad HOME first so a CI work
    # directory nested under it cannot override the source/Cargo normalization.
    for root, replacement in ((build_home, "/hermes-build-home"), (cargo_home, "/hermes-cargo"),
                              (source, "/hermes-source")):
        flags += " --remap-path-prefix=" + str(root) + "=" + replacement
    return flags


def build(config: dict, *, work: Path, wheel_dir: Path, helpers: Path, python: Path, environment: dict) -> dict:
    # Only verified source helpers may supply the build runner and artifact auditor.
    sys.path.insert(0, str(helpers))
    import audit_wheels
    import build_native
    import reproducible_wheel

    work.mkdir()
    downloads = work / "downloads"
    downloads.mkdir()
    for record in (config["packages"] | config["libraries"]).values():
        download(record, downloads / record["filename"])
    ndk = Path(environment["ANDROID_HOME"]) / "ndk/27.3.13750724"
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    if not (toolchain / "clang").is_file():
        raise ValueError("MCP source build requires the declared NDK 27.3.13750724")
    report = {"schema": "hermes-android-mcp-native-build-v1", "runtime_tested": False, "abis": {}}
    base_env = {key: value for key, value in environment.items() if key not in {
        "CC", "CXX", "AR", "RANLIB", "STRIP", "CFLAGS", "CPPFLAGS", "LDFLAGS", "OPENSSL_DIR", "PKG_CONFIG_PATH"}}
    base_env.update(ANDROID_NDK_ROOT=str(ndk), PATH=str(toolchain) + os.pathsep + base_env["PATH"])
    for abi, (platform_abi, triple, openssl_target) in TARGETS.items():
        target = work / abi
        target.mkdir()
        sources = {}
        for name, record in (config["packages"] | config["libraries"]).items():
            sources[name] = target / name
            extract(downloads / record["filename"], sources[name], record["source_directory"])

        def run(command, label, *, cwd, env=base_env, timeout=1200):
            # The verified helper owns this command's process group and timeout cleanup.
            build_native.run_logged(["bash", "-c", 'cd "$1" || exit; shift; exec "$@"', "mcp-build", str(cwd), *map(str, command)],
                                    env, timeout, target / (label + ".log"))

        openssl = sources["openssl"]
        run(["perl", "Configure", openssl_target, "-D__ANDROID_API__=24", "no-shared", "no-tests", "no-apps", "no-docs",
             "no-module", "no-filenames", "--prefix=/hermes-mcp-runtime", "--openssldir=/hermes-mcp-runtime/ssl"],
            "openssl-configure", cwd=openssl)
        run(["make", "-j12", "build_libs"], "openssl-build", cwd=openssl)
        openssl_stage = target / "openssl-stage"
        run(["make", "install_dev", "DESTDIR=" + str(openssl_stage)], "openssl-install", cwd=openssl)
        openssl_prefix = openssl_stage / "hermes-mcp-runtime"
        ffi = sources["libffi"]
        ffi_env = base_env | {"CC": str(toolchain / (triple + "24-clang")), "AR": str(toolchain / "llvm-ar"),
                              "RANLIB": str(toolchain / "llvm-ranlib"), "STRIP": str(toolchain / "llvm-strip"),
                              "CFLAGS": "-O2 -fPIC -ffile-prefix-map=" + str(ffi) + "=/hermes-libffi"}
        run(["./configure", "--host=" + triple, "--prefix=/hermes-mcp-ffi", "--libdir=/hermes-mcp-ffi/lib",
             "--disable-shared", "--enable-static", "--with-pic", "--disable-docs"], "libffi-configure", cwd=ffi, env=ffi_env)
        run(["make", "-j12"], "libffi-build", cwd=ffi, env=ffi_env)
        ffi_stage = target / "libffi-stage"
        run(["make", "install", "DESTDIR=" + str(ffi_stage)], "libffi-install", cwd=ffi, env=ffi_env)
        ffi_prefix = ffi_stage / "hermes-mcp-ffi"
        audited = []
        for package in sorted(NATIVE_PACKAGES):
            source = sources[package]
            pin_build_requirements(source, package)
            if package == "cryptography":
                configure_cryptography_android_headers(source)
                configure_cryptography_android_link(source)
            output = target / (package + "-wheels")
            output.mkdir()
            c_flags = "-O2 -fPIC -ffile-prefix-map=" + str(source) + "=/hermes-source -I" + str(ffi_prefix / "include")
            linker_flags = "-L" + str(ffi_prefix / "lib") + " -Wl,--exclude-libs,ALL -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
            rust_flags = native_rust_flags(source, Path(base_env["CARGO_HOME"]), Path.home())
            build_vars = {"ANDROID_API_LEVEL": "24", "MATURIN_PEP517_ARGS": "--locked", "RUSTFLAGS": rust_flags,
                          "CFLAGS": c_flags, "LDFLAGS": linker_flags,
                          **native_library_environment(package, openssl_prefix, ffi_prefix, ffi_stage)}
            build_env = base_env | {"CIBW_BUILD_FRONTEND": "build", "CIBW_TEST_SKIP": "*", "CIBW_BUILD_VERBOSITY": "1",
                                   "CIBW_ENVIRONMENT_ANDROID": " ".join(key + "=" + shlex.quote(value) for key, value in build_vars.items())}
            command = [str(python), "-m", "cibuildwheel", str(source), "--only", "cp313-android_" + platform_abi,
                       "--config-file", str(helpers / "cibuildwheel.toml"), "--output-dir", str(output)]
            build_native.run_logged(command, build_env, 3600, target / (package + ".log"))
            wheels = list(output.glob("*.whl"))
            if len(wheels) != 1:
                raise ValueError("Native MCP build must produce exactly one target wheel per package/ABI")
            wheel = wheels[0]
            original = audit_wheels.audit_wheel(wheel, "3.13", abi)
            require_android_python_link(original, package)
            reproducible_wheel.normalize(wheel, source)
            if package == "cryptography":
                attach_license(wheel, "OPENSSL-LICENSE.txt", (openssl / "LICENSE.txt").read_bytes())
            if package == "cffi":
                attach_license(wheel, "LIBFFI-LICENSE.txt", (ffi / "LICENSE").read_bytes())
            final = audit_wheels.audit_directory(output, "3.13", abi, {package})
            if not final["passed"]:
                raise ValueError("Native MCP ELF/dependency audit failed: " + json.dumps(final["errors"]))
            audited.extend(final["wheels"])
            shutil.copyfile(wheel, wheel_dir / wheel.name)
        report["abis"][abi] = audited
    return report
