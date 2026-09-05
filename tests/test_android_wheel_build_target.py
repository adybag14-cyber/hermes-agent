"""Exercise the private Android wheel target through real PEP 517 hooks."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import zipfile

import pytest


PROJECT_ROOT = Path(__file__).resolve().parents[1]
RESOURCE_FILES = {
    "skills/sample/SKILL.md": "# Sample skill\n",
    "skills/sample/scripts/run.py": "print('sample')\n",
    "optional-skills/extra/SKILL.md": "# Optional skill\n",
    "locales/en.yaml": "greeting: hello\n",
    "optional-mcps/example/manifest.yaml": "name: example\n",
}
MODULE_FILES = {
    "hermes_android/__init__.py": "",
    "hermes_android/bootstrap.py": "def bootstrap(): return 'ready'\n",
    "hermes_cli/__init__.py": "",
    "hermes_cli/data/runtime.json": '{"runtime": "fixture"}\n',
    "gateway/__init__.py": "",
    "gateway/assets/status_phrases.yaml": "phrases: [ready]\n",
    "plugins/__init__.py": "",
    "plugins/example/__init__.py": "",
    "plugins/example/plugin.yaml": "name: example\n",
    "hermes_constants.py": "VERSION = 'fixture'\n",
    "iteration_limits.py": "DEFAULT = 90\n",
}


@pytest.fixture
def source_archive(tmp_path):
    root = tmp_path / "source-archive"
    root.mkdir()
    # Copy the implementation into a minimal source fixture; never execute
    # the project's real package discovery or read its source as an assertion.
    shutil.copy2(PROJECT_ROOT / "setup.py", root / "setup.py")
    (root / "pyproject.toml").write_text(
        '[build-system]\nrequires = ["setuptools", "wheel"]\n'
        'build-backend = "setuptools.build_meta"\n'
        '[project]\nname = "hermes-agent"\nversion = "0.0.0"\n'
        '[tool.setuptools]\npy-modules = ["hermes_constants", "iteration_limits"]\n'
        '[tool.setuptools.packages.find]\n'
        'include = ["hermes_android", "hermes_cli", "gateway", "plugins", "plugins.*"]\n'
        '[tool.setuptools.package-data]\n'
        'hermes_cli = ["data/*.json"]\n'
        'gateway = ["assets/**/*"]\n'
        'plugins = ["**/plugin.yaml", "**/plugin.yml"]\n',
        encoding="utf-8",
    )
    for relative, content in (MODULE_FILES | RESOURCE_FILES).items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")
    assert not (root / ".git").exists()
    return root


def _build(root, kind="wheel", *, android=None, nix=None, build_lib=None):
    env = os.environ.copy()
    for key in ("HERMES_ANDROID_BUILD", "HERMES_NIX_BUILD", "DIST_EXTRA_CONFIG"):
        env.pop(key, None)
    # A development-shell hint alone must still not permit distribution builds.
    env["NIX_BUILD_TOP"] = "/build/devshell"
    env["PYTHONUTF8"] = "1"
    env["PYTHONNOUSERSITE"] = "1"
    if android is not None:
        env["HERMES_ANDROID_BUILD"] = android
    if nix is not None:
        env["HERMES_NIX_BUILD"] = nix
    if build_lib is not None:
        extra_config = root.parent / "build-extra.cfg"
        extra_config.write_text(
            f"[build]\nbuild_lib = {build_lib}\n\n[build_py]\nbuild_lib = {build_lib}\n",
            encoding="utf-8",
        )
        env["DIST_EXTRA_CONFIG"] = str(extra_config)
    output = root.parent / "output"
    output.mkdir(exist_ok=True)
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            f"from setuptools.build_meta import build_{kind}; "
            f"build_{kind}({str(output)!r})",
        ],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=45,
        check=False,
    )
    return result, output


@pytest.mark.parametrize("kind", ["wheel", "sdist"])
@pytest.mark.parametrize("android", [None, "true"])
def test_ordinary_distribution_remains_blocked(source_archive, kind, android):
    result, output = _build(source_archive, kind, android=android)
    assert result.returncode != 0
    assert "Building wheels or sdists for hermes-agent is not supported" in result.stderr
    assert not list(output.iterdir())


@pytest.mark.parametrize("nix", [None, "1"])
def test_android_target_never_allows_sdist(source_archive, nix):
    result, output = _build(source_archive, "sdist", android="1", nix=nix)
    assert result.returncode != 0
    assert "Building wheels or sdists for hermes-agent is not supported" in result.stderr
    assert not list(output.iterdir())


@pytest.mark.parametrize("kind", ["wheel", "sdist"])
def test_nix_distribution_target_is_unchanged(source_archive, kind):
    result, output = _build(source_archive, kind, nix="1")
    assert result.returncode == 0, result.stdout + result.stderr
    if kind == "wheel":
        with zipfile.ZipFile(next(output.glob("*.whl"))) as archive:
            names = set(archive.namelist())
    else:
        with tarfile.open(next(output.glob("*.tar.gz"))) as archive:
            names = {name.split("/", 1)[1] for name in archive.getnames() if "/" in name}
    assert set(MODULE_FILES) <= names


def test_android_wheel_contains_runtime_modules_and_source_resources(source_archive):
    result, output = _build(source_archive, android="1")
    assert result.returncode == 0, result.stdout + result.stderr
    with zipfile.ZipFile(next(output.glob("*.whl"))) as archive:
        for relative, content in (MODULE_FILES | RESOURCE_FILES).items():
            assert archive.read(relative) == content.encode("utf-8")


def test_android_wheel_excludes_caches_artifacts_and_outside_roots(source_archive):
    excluded = [
        "skills/sample/__pycache__/cached.pyc",
        "skills/sample/node_modules/pkg/data.json",
        "skills/sample/build/intermediate.txt",
        "skills/sample/dist/archive.whl",
        "skills/sample/.artifacts/report.txt",
        "skills/sample/release-evidence/trace.txt",
        "skills/sample/metadata.egg-info/PKG-INFO",
        "skills/sample/old.apk",
        "optional-skills/extra/.venv/pyvenv.cfg",
        "locales/__pycache__/junk.pyc",
        "optional-mcps/example/.git/config",
        "secret.env",
        "android/release-evidence/trace.pftrace",
    ]
    for relative in excluded:
        path = source_archive / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("not a runtime source asset", encoding="utf-8")
    result, output = _build(source_archive, android="1")
    assert result.returncode == 0, result.stdout + result.stderr
    with zipfile.ZipFile(next(output.glob("*.whl"))) as archive:
        names = set(archive.namelist())
    assert set(RESOURCE_FILES) <= names
    assert not set(excluded) & names


def test_android_wheel_removes_stale_resource_output(source_archive):
    stale = source_archive / "build/lib/skills/deleted/SKILL.md"
    stale.parent.mkdir(parents=True)
    stale.write_text("deleted source from an older build", encoding="utf-8")
    result, output = _build(source_archive, android="1")
    assert result.returncode == 0, result.stdout + result.stderr
    with zipfile.ZipFile(next(output.glob("*.whl"))) as archive:
        assert "skills/deleted/SKILL.md" not in archive.namelist()


def test_android_wheel_rejects_missing_resource_tree(source_archive):
    shutil.rmtree(source_archive / "locales")
    result, _ = _build(source_archive, android="1")
    assert result.returncode != 0
    assert "Android runtime resource tree is missing: locales" in result.stderr


def test_android_wheel_refuses_source_tree_as_build_output(source_archive):
    result, _ = _build(source_archive, android="1", build_lib=source_archive)
    assert result.returncode != 0
    assert "Android resource output must use a separate build directory" in result.stderr
    assert (source_archive / "skills/sample/SKILL.md").read_text(encoding="utf-8") == RESOURCE_FILES["skills/sample/SKILL.md"]


def test_android_wheel_refuses_source_package_as_build_output(source_archive):
    victim = source_archive / "hermes_cli/skills/keep.txt"
    victim.parent.mkdir()
    victim.write_bytes(b"existing source-package resource")
    result, _ = _build(source_archive, android="1", build_lib=source_archive / "hermes_cli")
    assert result.returncode != 0
    assert "Android resource output must use a separate build directory" in result.stderr
    assert victim.read_bytes() == b"existing source-package resource"
    assert not (source_archive / "hermes_cli/hermes_android").exists()


def test_android_wheel_allows_explicit_unlinked_external_build_output(source_archive, tmp_path):
    result, output = _build(source_archive, android="1", build_lib=tmp_path / "external-output/lib")
    assert result.returncode == 0, result.stdout + result.stderr
    with zipfile.ZipFile(next(output.glob("*.whl"))) as archive:
        assert set(MODULE_FILES) | set(RESOURCE_FILES) <= set(archive.namelist())


@pytest.mark.parametrize("directory", [False, True])
def test_android_wheel_rejects_resource_symlinks(source_archive, tmp_path, directory):
    target = tmp_path / "outside"
    if directory:
        target.mkdir()
        (target / "secret.txt").write_text("outside", encoding="utf-8")
    else:
        target.write_text("outside", encoding="utf-8")
    link = source_archive / "skills/sample/external"
    try:
        link.symlink_to(target, target_is_directory=directory)
    except OSError as exc:
        if getattr(exc, "winerror", None) == 1314:
            pytest.skip("Windows requires symlink privilege; covered on native Linux")
        raise
    result, _ = _build(source_archive, android="1")
    assert result.returncode != 0
    assert "Android runtime assets must not contain links" in result.stderr


def test_android_wheel_rejects_output_symlink_without_touching_victim(source_archive, tmp_path):
    target = tmp_path / "other-checkout"
    victim = target / "skills/keep.txt"
    victim.parent.mkdir(parents=True)
    victim.write_bytes(b"other checkout's resource")
    link = source_archive / "build/lib"
    link.parent.mkdir()
    try:
        link.symlink_to(target, target_is_directory=True)
    except OSError as exc:
        if getattr(exc, "winerror", None) == 1314:
            pytest.skip("Windows requires symlink privilege; covered on native Linux")
        raise
    result, _ = _build(source_archive, android="1")
    assert result.returncode != 0
    assert "Android resource output must not contain links" in result.stderr
    assert victim.read_bytes() == b"other checkout's resource"
    assert not (target / "hermes_android").exists()


def test_editable_install_does_not_require_distribution_marker(source_archive):
    result, output = _build(source_archive, "editable")
    assert result.returncode == 0, result.stdout + result.stderr
    assert list(output.glob("*.whl"))


@pytest.mark.windows_only
def test_android_wheel_rejects_windows_resource_junction(source_archive, tmp_path):
    if sys.platform != "win32":
        pytest.skip("Windows junction creation is only available on Windows")
    target = tmp_path / "outside-junction"
    target.mkdir()
    secret = target / "secret.txt"
    secret.write_text("outside", encoding="utf-8")
    link = source_archive / "skills/sample/external-junction"
    create = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(link), str(target)],
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )
    assert create.returncode == 0, create.stdout + create.stderr
    result, _ = _build(source_archive, android="1")
    assert result.returncode != 0
    assert "Android runtime assets must not contain links" in result.stderr
    assert secret.read_text(encoding="utf-8") == "outside"


@pytest.mark.windows_only
@pytest.mark.parametrize("link_location", ["build", "build/lib"])
def test_android_wheel_rejects_output_junction_without_touching_victim(source_archive, tmp_path, link_location):
    if sys.platform != "win32":
        pytest.skip("Windows junction creation is only available on Windows")
    target = tmp_path / "other-checkout"
    effective_output = target / "lib" if link_location == "build" else target
    victim = effective_output / "skills/keep.txt"
    victim.parent.mkdir(parents=True)
    victim.write_bytes(b"other checkout's resource")
    link = source_archive / link_location
    link.parent.mkdir(parents=True, exist_ok=True)
    create = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(link), str(target)],
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )
    assert create.returncode == 0, create.stdout + create.stderr
    result, _ = _build(source_archive, android="1")
    assert result.returncode != 0
    assert "Android resource output must not contain links" in result.stderr
    assert victim.read_bytes() == b"other checkout's resource"
    assert not (effective_output / "hermes_android").exists()
