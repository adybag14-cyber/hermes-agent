import copy
import io
import json
import shlex
import shutil
import subprocess
import tarfile
import tomllib
import zipfile

import pytest

from scripts import build_android_mcp_wheels as mcp_build
from scripts import prepare_android_python_runtime as runtime


def test_mcp_builder_and_each_source_are_bound_to_the_runtime_lock():
    lock = runtime.load_lock()
    selected = runtime.pins(runtime.REQUIREMENTS.read_text())
    mcp_build.validate_sources(lock["mcp_native"], selected)
    for name in lock["mcp_native"]["packages"]:
        changed = copy.deepcopy(lock["mcp_native"])
        changed["packages"][name]["url"] = "https://untrusted.example/native.tar.gz"
        with pytest.raises(ValueError):
            mcp_build.validate_sources(changed, selected)
    changed = copy.deepcopy(lock["mcp_native"])
    changed["builder_sha256"] = "0" * 64
    with pytest.raises(ValueError):
        mcp_build.validate_sources(changed, selected)


def test_build_pin_does_not_change_runtime_metadata_or_source_code(tmp_path):
    metadata = tmp_path / "pyproject.toml"
    metadata.write_text('[build-system]\nrequires = ["maturin>=1.9,<2"]\nbuild-backend = "maturin"\n'
                        '[project]\nname = "rpds-py"\nversion = "0.30.0"\ndependencies = ["example>=1"]\n')
    project_before = tomllib.loads(metadata.read_text())["project"]
    mcp_build.pin_build_requirements(tmp_path, "rpds-py")
    result = tomllib.loads(metadata.read_text())
    assert result["project"] == project_before
    assert result["build-system"]["requires"] == mcp_build.BUILD_REQUIREMENTS["rpds-py"]


@pytest.mark.parametrize("name,kind", [("native/../escape", tarfile.REGTYPE),
                                       ("native/linked", tarfile.SYMTYPE), ("/native/absolute", tarfile.REGTYPE)])
def test_native_archive_rejects_escape_and_linked_build_inputs(tmp_path, name, kind):
    archive = tmp_path / "source.tar.gz"
    with tarfile.open(archive, "w:gz") as target:
        member = tarfile.TarInfo(name)
        member.type = kind
        member.size = 4 if kind == tarfile.REGTYPE else 0
        member.linkname = "/outside" if kind == tarfile.SYMTYPE else ""
        target.addfile(member, io.BytesIO(b"data") if member.isfile() else None)
    with pytest.raises(ValueError):
        mcp_build.extract(archive, tmp_path / "source", "native")


def test_static_library_license_addition_preserves_native_payload_and_records_every_file(tmp_path):
    wheel = tmp_path / "example-1.0-py3-none-any.whl"
    with zipfile.ZipFile(wheel, "w") as archive:
        archive.writestr("example/native.so", b"genuine compiled bytes")
        archive.writestr("example-1.0.dist-info/RECORD", "")
    mcp_build.attach_license(wheel, "LIBRARY-LICENSE.txt", b"original library license")
    with zipfile.ZipFile(wheel) as archive:
        assert archive.read("example/native.so") == b"genuine compiled bytes"
        assert archive.read("example-1.0.dist-info/licenses/LIBRARY-LICENSE.txt") == b"original library license"
        record = archive.read("example-1.0.dist-info/RECORD").decode()
        assert "example/native.so,sha256=" in record
        assert "licenses/LIBRARY-LICENSE.txt,sha256=" in record


def test_native_dependency_lookup_survives_android_target_overrides(tmp_path):
    prefix = tmp_path / "ffi-stage/hermes-mcp-ffi"
    configured = mcp_build.native_library_environment("cffi", tmp_path / "openssl", prefix, tmp_path / "ffi-stage")
    target_environment = {**configured, "PKG_CONFIG_LIBDIR": "/target/python/lib/pkgconfig", "CFLAGS": "-I/target/python/include"}
    assert target_environment["PKG_CONFIG_PATH"] == str(prefix / "lib/pkgconfig")
    assert target_environment["CFFI_FORCE_STATIC"] == str(prefix / "lib/libffi.a")
    assert target_environment["OPENSSL_DIR"] == str(tmp_path / "openssl")


def test_native_extensions_must_resolve_the_embedded_android_interpreter():
    for package, initializer in mcp_build.EXPECTED_INITIALIZERS.items():
        library = {"python_initializers": [initializer], "needed": ["libc.so", "libpython3.13.so"]}
        mcp_build.require_android_python_link({"native_libraries": [library]}, package)
        for missing in ([], ["libc.so"], ["libpython3.so"]):
            with pytest.raises(ValueError, match="directly link"):
                mcp_build.require_android_python_link({"native_libraries": [{**library, "needed": missing}]}, package)


@pytest.mark.linux_only
def test_rust_native_output_is_independent_of_nested_build_home(tmp_path):
    compiler = shutil.which("rustc")
    if compiler is None:
        pytest.skip("Native source-build regression requires the declared Rust toolchain")
    objects = []
    for layout in ("external", "nested"):
        build_home = tmp_path / layout / "home"
        work = (build_home if layout == "nested" else tmp_path / layout) / "work"
        source = work / "mcp-native" / "package"
        cargo_home = work / "cargo"
        source.mkdir(parents=True)
        dependency = cargo_home / "registry" / "dependency.rs"
        dependency.parent.mkdir(parents=True)
        dependency.write_text("pub fn provenance() -> &'static str { file!() }\n", encoding="utf-8")
        main = source / "lib.rs"
        main.write_text(f'#[path = {json.dumps(str(dependency))}] mod dependency;\n'
                        "#[no_mangle] pub fn source_path() -> &'static str { file!() }\n"
                        "#[no_mangle] pub fn dependency_path() -> &'static str { dependency::provenance() }\n",
                        encoding="utf-8")
        output = work / "fixture.o"
        subprocess.run([compiler, str(main), "--crate-type=lib", "--crate-name=remap_fixture", "--edition=2021",
                        "--emit=obj", "-C", "debuginfo=0", "-o", str(output),
                        *shlex.split(mcp_build.native_rust_flags(source, cargo_home, build_home))],
                       check=True, capture_output=True, text=True, timeout=30)
        objects.append(output.read_bytes())
    assert objects[0] == objects[1], "Build-home nesting must not change native source or dependency provenance"
