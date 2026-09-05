"""Validate real private-wheel artifacts and build-directory isolation."""

import base64
import csv
import importlib.util
import io
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile

import pytest

from tests.test_android_wheel_build_target import MODULE_FILES, RESOURCE_FILES, _build, source_archive


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"


def _load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verifier = _load("verify_android_runtime_wheel")


@pytest.fixture
def wheel(source_archive):
    result, output = _build(source_archive, android="1")
    assert result.returncode == 0, result.stdout + result.stderr
    return next(output.glob("*.whl"))


def _rewrite(wheel, *, remove=(), replace=None, add=None, fix_record=True):
    with zipfile.ZipFile(wheel) as archive:
        files = {name: archive.read(name) for name in archive.namelist()}
    for name in remove:
        files.pop(name)
    files.update(replace or {})
    files.update(add or {})
    record_name = next(name for name in files if name.endswith(".dist-info/RECORD"))
    if fix_record:
        record = io.StringIO(newline="")
        writer = csv.writer(record)
        for name, data in files.items():
            if name != record_name:
                digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")
                writer.writerow([name, f"sha256={digest}", str(len(data))])
        writer.writerow([record_name, "", ""])
        files[record_name] = record.getvalue().encode("utf-8")
    with zipfile.ZipFile(wheel, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)


def test_verifies_real_wheel_from_archive_without_git(source_archive, wheel):
    result = verifier.verify_wheel(source_archive, wheel)
    assert result["runtime_files"] == len(MODULE_FILES | RESOURCE_FILES)
    assert result["sha256"] == hashlib.sha256(wheel.read_bytes()).hexdigest()
    assert result["resource_roots"] == ["skills", "optional-skills", "locales", "optional-mcps"]


def test_dynamic_root_modules_follow_source_discovery_and_reject_deleted_siblings(source_archive):
    project = source_archive / "pyproject.toml"
    project.write_text(project.read_text(encoding="utf-8").replace(
        'py-modules = ["hermes_constants", "iteration_limits"]\n', ""
    ), encoding="utf-8")
    sibling = source_archive / "new_root_sibling.py"
    sibling.write_text("VALUE = 'current'\n", encoding="utf-8")
    built, output = _build(source_archive, android="1")
    assert built.returncode == 0, built.stdout + built.stderr
    wheel_path = next(output.glob("*.whl"))
    report = verifier.verify_wheel(source_archive, wheel_path)
    assert report["runtime_files"] == len(MODULE_FILES | RESOURCE_FILES) + 1
    with zipfile.ZipFile(wheel_path) as archive:
        assert archive.read(sibling.name) == sibling.read_bytes()
    sibling.unlink()
    with pytest.raises(ValueError, match="unexpected files"):
        verifier.verify_wheel(source_archive, wheel_path)


@pytest.mark.parametrize("missing", [
    "hermes_android/bootstrap.py", "iteration_limits.py", "hermes_cli/data/runtime.json",
    "gateway/assets/status_phrases.yaml", "plugins/example/plugin.yaml",
    "skills/sample/SKILL.md", "optional-skills/extra/SKILL.md", "locales/en.yaml",
    "optional-mcps/example/manifest.yaml",
])
def test_rejects_missing_runtime_inventory_even_with_valid_record(source_archive, wheel, missing):
    _rewrite(wheel, remove=[missing])
    with pytest.raises(ValueError, match="missing runtime files"):
        verifier.verify_wheel(source_archive, wheel)


def test_rejects_stale_source_contents_with_valid_record(source_archive, wheel):
    (source_archive / "hermes_android/bootstrap.py").write_text("changed = True\n", encoding="utf-8")
    with pytest.raises(ValueError, match="stale runtime content"):
        verifier.verify_wheel(source_archive, wheel)


def test_rejects_tampered_record(source_archive, wheel):
    _rewrite(wheel, replace={"hermes_android/bootstrap.py": b"tampered"}, fix_record=False)
    with pytest.raises(ValueError, match="RECORD hash/size mismatch"):
        verifier.verify_wheel(source_archive, wheel)


@pytest.mark.parametrize("extra", [
    "hermes_android/deleted_module.py", "skills/deleted/SKILL.md",
    "skills/__pycache__/cached.pyc", "hermes_cli/build/stale.json", "../outside.txt",
    ".",
    "deleted_module.py", "removed_package/__init__.py", "removed_package/helper.py",
    "plugins/example/stale-plugin.yaml", "plugins/removed/plugin.yaml",
    "unexpected.txt", "secrets.env", "hermes_cli/undeclared-data.bin",
    "hermes_agent-0.0.0.dist-info/unexpected.txt",
    "hermes_agent-0.0.0.dist-info/licenses/undeclared.txt",
])
def test_rejects_stale_or_unsafe_wheel_members(source_archive, wheel, extra):
    _rewrite(wheel, add={extra: b"unexpected"})
    with pytest.raises(ValueError, match="unexpected files|cache or artifact|unsafe member"):
        verifier.verify_wheel(source_archive, wheel)


def test_rejects_deleted_package_data_with_valid_record(source_archive, wheel):
    (source_archive / "plugins/example/plugin.yaml").unlink()
    with pytest.raises(ValueError, match="unexpected files"):
        verifier.verify_wheel(source_archive, wheel)


def test_preserves_declared_license_metadata_and_contents(source_archive):
    project = source_archive / "pyproject.toml"
    project.write_text(project.read_text(encoding="utf-8").replace(
        'version = "0.0.0"', 'version = "0.0.0"\nlicense-files = ["LICENSE", "licenses/*.txt"]'
    ), encoding="utf-8")
    (source_archive / "LICENSE").write_text("Fixture license\n", encoding="utf-8")
    (source_archive / "licenses").mkdir()
    (source_archive / "licenses/NOTICE.txt").write_text("Fixture notice\n", encoding="utf-8")
    result, output = _build(source_archive, android="1")
    assert result.returncode == 0, result.stdout + result.stderr
    wheel = next(output.glob("*.whl"))
    assert verifier.verify_wheel(source_archive, wheel)["runtime_files"] == len(MODULE_FILES | RESOURCE_FILES)
    (source_archive / "licenses/NOTICE.txt").write_text("Changed notice\n", encoding="utf-8")
    with pytest.raises(ValueError, match="license is missing or stale"):
        verifier.verify_wheel(source_archive, wheel)


def test_rejects_mismatched_distribution_metadata(source_archive, wheel):
    with zipfile.ZipFile(wheel) as archive:
        name = next(name for name in archive.namelist() if name.endswith(".dist-info/METADATA"))
        metadata = archive.read(name).replace(b"Version: 0.0.0", b"Version: 9.9.9")
    _rewrite(wheel, replace={name: metadata})
    with pytest.raises(ValueError, match="name/version"):
        verifier.verify_wheel(source_archive, wheel)


def test_rejects_duplicate_archive_members(source_archive, wheel):
    with pytest.warns(UserWarning, match="Duplicate name"):
        with zipfile.ZipFile(wheel, "a") as archive:
            archive.writestr("hermes_constants.py", b"duplicate")
    with pytest.raises(ValueError, match="duplicate ZIP members"):
        verifier.verify_wheel(source_archive, wheel)


@pytest.fixture
def builder(monkeypatch):
    monkeypatch.setitem(sys.modules, "verify_android_runtime_wheel", verifier)
    return _load("build_android_runtime_wheel")


def _real_backend_instead_of_pip(builder, monkeypatch, seen):
    real_run = subprocess.run

    def build_backend(command, *, cwd, env, check):
        assert command[:5] == [sys.executable, "-m", "pip", "wheel", "--no-deps"]
        assert env["HERMES_ANDROID_BUILD"] == "1"
        assert "HERMES_NIX_BUILD" not in env
        seen["config"] = Path(env["DIST_EXTRA_CONFIG"])
        output = Path(command[command.index("--wheel-dir") + 1])
        seen["scratch"] = output.parent
        output.mkdir()
        # Inspect the effective command objects immediately before execution,
        # after setuptools has merged configuration and finalized defaults.
        # Merely asserting that build_base is set would miss direct overrides.
        audited_build = f"""
from pathlib import Path
from setuptools import Distribution
scratch = Path({str(output.parent)!r}).resolve()
fields = {{
    'build': ['build_base', 'build_lib', 'build_purelib', 'build_platlib', 'build_temp', 'build_scripts'],
    'build_py': ['build_lib'],
    'build_ext': ['build_lib', 'build_temp'],
    'build_scripts': ['build_dir'],
    'bdist': ['bdist_base', 'dist_dir'],
    'bdist_wheel': ['bdist_dir', 'dist_dir'],
    'egg_info': ['egg_base', 'egg_info'],
    'dist_info': ['output_dir'],
    'install': ['root', 'install_lib', 'install_purelib', 'install_platlib', 'install_headers', 'install_scripts', 'install_data', 'record'],
    'install_lib': ['build_dir', 'install_dir'],
    'install_egg_info': ['install_dir'],
    'install_scripts': ['build_dir', 'install_dir'],
    'install_data': ['install_dir', 'root'],
    'install_headers': ['install_dir'],
}}
original_run = Distribution.run_command
def checked_run(self, command):
    obj = self.get_command_obj(command)
    obj.ensure_finalized()
    for field in fields.get(command, []):
        value = getattr(obj, field, None)
        if value is not None:
            assert Path(value).resolve().is_relative_to(scratch), (command, field, value, scratch)
    return original_run(self, command)
Distribution.run_command = checked_run
from setuptools.build_meta import build_wheel
build_wheel({str(output)!r})
"""
        result = real_run(
            [sys.executable, "-c", audited_build],
            cwd=cwd, env=env, check=check, capture_output=True, text=True, timeout=45,
        )
        seen["scratch_files"] = [p.relative_to(output.parent).as_posix() for p in output.parent.rglob("*")]
        return result

    monkeypatch.setattr(builder.subprocess, "run", build_backend)


def test_build_wrapper_uses_private_target_and_owned_scratch(source_archive, tmp_path, builder, monkeypatch):
    victim = source_archive / "build/lib/hermes_android/deleted_module.py"
    victim.parent.mkdir(parents=True)
    victim.write_bytes(b"pre-existing build output")
    seen = {}
    _real_backend_instead_of_pip(builder, monkeypatch, seen)
    monkeypatch.setenv("HERMES_NIX_BUILD", "1")
    result = builder.build_wheel(source_archive, tmp_path / "published-wheels")
    assert victim.read_bytes() == b"pre-existing build output"
    assert not seen["scratch"].exists()
    assert any(name.startswith("build/lib/") for name in seen["scratch_files"])
    assert any(name.startswith("metadata/hermes_agent.egg-info/") for name in seen["scratch_files"])
    with zipfile.ZipFile(result["wheel"]) as archive:
        assert "hermes_android/deleted_module.py" not in archive.namelist()


def test_build_wrapper_with_real_offline_pip_frontend(source_archive, tmp_path, builder, monkeypatch):
    pytest.importorskip("pip")
    # Backend requirements are already installed for the PEP 517 fixture
    # tests. Exercise pip itself offline, without mutating the host runtime.
    monkeypatch.setenv("PIP_NO_INDEX", "1")
    monkeypatch.setenv("PIP_NO_BUILD_ISOLATION", "0")
    monkeypatch.setenv("PIP_DISABLE_PIP_VERSION_CHECK", "1")
    monkeypatch.setenv("PIP_CACHE_DIR", str(tmp_path / "pip-cache"))
    result = builder.build_wheel(source_archive, tmp_path / "published-wheels")
    assert result["runtime_files"] == len(MODULE_FILES | RESOURCE_FILES)
    assert not (source_archive / "build").exists()
    assert not list(source_archive.glob("*.egg-info"))


@pytest.mark.parametrize("configured", [
    "[build]\nbuild_lib = {victim}\nbuild_purelib = {victim}\nbuild_platlib = {victim}\n",
    "[build_py]\nbuild_lib = {victim}\n",
    "[bdist]\nbdist_base = {victim}\n",
    "[bdist_wheel]\nbdist_dir = {victim}\n",
    "[install_lib]\nbuild_dir = {victim}\ninstall_dir = {victim}\n",
    "[install_egg_info]\ninstall_dir = {victim}\n",
    "[install]\nrecord = {victim}/record.txt\n",
    "[install]\nroot = {victim}\ninstall_lib = {victim}\ninstall_scripts = {victim}\ninstall_data = {victim}\ninstall_headers = {victim}\n",
    "[install_scripts]\nbuild_dir = {victim}\ninstall_dir = {victim}\n",
    "[install_data]\ninstall_dir = {victim}\nroot = {victim}\n",
    "[install_headers]\ninstall_dir = {victim}\n",
    "[build_ext]\nbuild_lib = {victim}\nbuild_temp = {victim}\n",
    "[build_scripts]\nbuild_dir = {victim}\n",
])
def test_existing_setuptools_output_configuration_cannot_escape_scratch(source_archive, tmp_path, builder, monkeypatch, configured):
    victim = tmp_path / "other-checkout"
    keep = victim / "skills/keep.txt"
    keep.parent.mkdir(parents=True)
    keep.write_bytes(b"other checkout's asset")
    (source_archive / "setup.cfg").write_text(configured.format(victim=victim.as_posix()), encoding="utf-8")
    seen = {}
    _real_backend_instead_of_pip(builder, monkeypatch, seen)
    result = builder.build_wheel(source_archive, tmp_path / "published-wheels")
    assert keep.read_bytes() == b"other checkout's asset"
    assert sorted(path.relative_to(victim).as_posix() for path in victim.rglob("*")) == ["skills", "skills/keep.txt"]
    assert Path(result["wheel"]).is_file()
    assert not seen["scratch"].exists()


@pytest.mark.windows_only
def test_build_wrapper_does_not_follow_existing_source_output_junction(source_archive, tmp_path, builder, monkeypatch):
    if sys.platform != "win32":
        pytest.skip("Windows junction test")
    other = tmp_path / "other-checkout"
    victim = other / "lib/skills/keep.txt"
    victim.parent.mkdir(parents=True)
    victim.write_bytes(b"other checkout")
    created = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(source_archive / "build"), str(other)],
        capture_output=True, text=True, timeout=10, check=False,
    )
    assert created.returncode == 0, created.stdout + created.stderr
    seen = {}
    _real_backend_instead_of_pip(builder, monkeypatch, seen)
    result = builder.build_wheel(source_archive, tmp_path / "published-wheels")
    assert victim.read_bytes() == b"other checkout"
    assert Path(result["wheel"]).is_file()
    assert not (other / "lib/hermes_android").exists()


@pytest.mark.windows_only
def test_build_wrapper_rejects_destination_junction_before_running_build(source_archive, tmp_path, builder, monkeypatch):
    if sys.platform != "win32":
        pytest.skip("Windows junction test")
    other = tmp_path / "other-output"
    other.mkdir()
    victim = other / "hermes_agent-0.0.0-py3-none-any.whl"
    victim.write_bytes(b"unrelated wheel")
    destination = tmp_path / "linked-output"
    created = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(destination), str(other)],
        capture_output=True, text=True, timeout=10, check=False,
    )
    assert created.returncode == 0, created.stdout + created.stderr
    monkeypatch.setattr(builder.subprocess, "run", lambda *_args, **_kwargs: pytest.fail("build must not start"))
    with pytest.raises(ValueError, match="must not contain links"):
        builder.build_wheel(source_archive, destination)
    assert victim.read_bytes() == b"unrelated wheel"


def test_failed_validation_preserves_previous_wheel(source_archive, tmp_path, builder, monkeypatch):
    destination = tmp_path / "published-wheels"
    destination.mkdir()
    previous = destination / "hermes_agent-0.0.0-py3-none-any.whl"
    previous.write_bytes(b"previous known-good wheel")
    seen = {}
    _real_backend_instead_of_pip(builder, monkeypatch, seen)
    monkeypatch.setattr(builder, "verify_wheel", lambda *_: (_ for _ in ()).throw(ValueError("bad inventory")))
    with pytest.raises(ValueError, match="bad inventory"):
        builder.build_wheel(source_archive, destination)
    assert previous.read_bytes() == b"previous known-good wheel"
    assert not seen["scratch"].exists()


def test_different_wheel_version_cannot_leave_chaquopy_consuming_stale_artifact(source_archive, tmp_path, builder, monkeypatch):
    destination = tmp_path / "published-wheels"
    destination.mkdir()
    previous = destination / "hermes_agent-previous-py3-none-any.whl"
    previous.write_bytes(b"previous wheel")
    seen = {}
    _real_backend_instead_of_pip(builder, monkeypatch, seen)
    with pytest.raises(ValueError, match="expected filename"):
        builder.build_wheel(source_archive, destination, expected_name=previous.name)
    assert previous.read_bytes() == b"previous wheel"
    assert list(destination.iterdir()) == [previous]


def test_wheel_publication_does_not_write_through_existing_hardlink(wheel, tmp_path, builder):
    victim = tmp_path / "unrelated-wheel.whl"
    victim.write_bytes(b"unrelated existing inode")
    destination = tmp_path / "published.whl"
    os.link(victim, destination)
    builder._publish_wheel(wheel, destination)
    assert victim.read_bytes() == b"unrelated existing inode"
    assert destination.read_bytes() == wheel.read_bytes()
    assert not destination.samefile(victim)


def test_failed_wheel_publication_preserves_previous_artifact(wheel, tmp_path, builder, monkeypatch):
    destination = tmp_path / "published.whl"
    destination.write_bytes(b"previous artifact")

    def fail_during_copy(_source, output):
        output.write(b"partial replacement")
        raise OSError("simulated full disk")

    monkeypatch.setattr(builder.shutil, "copyfileobj", fail_during_copy)
    with pytest.raises(OSError, match="simulated full disk"):
        builder._publish_wheel(wheel, destination)
    assert destination.read_bytes() == b"previous artifact"
    assert not list(tmp_path.glob(".published.whl.*.tmp"))
