"""Guard public distributions and build the private Android runtime wheel.

Ordinary wheels/sdists remain unsupported; Nix retains its existing opt-in.
HERMES_ANDROID_BUILD=1 permits only the resource-complete private wheel used
by Chaquopy. Editable installs and dynamic root-module discovery are preserved.
"""

import os
from pathlib import Path
import shutil
import stat

from setuptools import setup
from setuptools.command.sdist import sdist
from setuptools.command.build_py import build_py

_ROOT = os.path.dirname(os.path.abspath(__file__))

_IN_NIX_BUILD = os.environ.get("HERMES_NIX_BUILD") == "1"
_IN_ANDROID_BUILD = os.environ.get("HERMES_ANDROID_BUILD") == "1"
_ANDROID_RESOURCE_ROOTS = ("skills", "optional-skills", "locales", "optional-mcps")
_ANDROID_EXCLUDED_DIRS = frozenset({
    ".git", ".hg", ".svn", "__pycache__", ".pytest_cache", ".mypy_cache",
    ".ruff_cache", ".tox", ".nox", ".venv", "venv", "node_modules",
    "build", "dist", "target", ".artifacts", "release-evidence",
})
_ANDROID_EXCLUDED_SUFFIXES = (
    ".pyc", ".pyo", ".pyd", ".whl", ".egg", ".apk", ".aab", ".pftrace",
    ".profraw", ".profdata",
)

_BLOCK_MESSAGE = (
    "Building wheels or sdists for hermes-agent is not supported.\n"
    "Hermes is distributed via the shell installer, Docker image, or Nix.\n"
    "See: https://hermes-agent.nousresearch.com/docs/getting-started/installation\n"
    "\n"
    "If you are developing, use an editable install instead:\n"
    "  uv sync          # or: uv pip install -e .\n"
    "\n"
    "If you are building with Nix (uv2nix), this error should not fire —\n"
    "the Hermes Nix derivation sets HERMES_NIX_BUILD=1. If it does, file a bug."
)


def _check_android_asset_path(path: Path, root: Path) -> None:
    """Reject links, Windows junctions, and paths outside the source tree."""
    metadata = path.lstat()
    if path.is_symlink() or (
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    ):
        raise RuntimeError(f"Android runtime assets must not contain links: {path}")
    if not path.resolve(strict=True).is_relative_to(root):
        raise RuntimeError(f"Android runtime asset escapes its source tree: {path}")


def _android_runtime_assets(source_root: Path) -> list[tuple[Path, Path]]:
    """Inventory approved source resources; source archives need no Git metadata."""
    assets = []
    for resource_name in _ANDROID_RESOURCE_ROOTS:
        resource_root = source_root / resource_name
        if not resource_root.exists():
            raise RuntimeError(f"Android runtime resource tree is missing: {resource_name}")
        _check_android_asset_path(resource_root, source_root)
        if not resource_root.is_dir():
            raise RuntimeError(f"Android runtime resource tree is not a directory: {resource_name}")
        pending = [resource_root]
        while pending:
            directory = pending.pop()
            for path in sorted(directory.iterdir(), key=lambda item: item.name):
                name = path.name.lower()
                if (
                    name in _ANDROID_EXCLUDED_DIRS
                    or name.endswith((".egg-info", ".dist-info"))
                    or name.endswith(_ANDROID_EXCLUDED_SUFFIXES)
                    or name == ".coverage"
                ):
                    continue
                _check_android_asset_path(path, source_root)
                if path.is_dir():
                    pending.append(path)
                elif path.is_file():
                    assets.append((path, path.relative_to(source_root)))
                else:
                    raise RuntimeError(f"Android runtime asset is not a regular file: {path}")
    return sorted(assets, key=lambda item: item[1].as_posix())


def _android_build_root(source_root: Path, build_lib: str) -> Path:
    """Validate the lexical output path before following any filesystem links."""
    requested = Path(build_lib)
    if ".." in requested.parts:
        raise RuntimeError("Android resource output must use a separate build directory")
    output = requested if requested.is_absolute() else source_root / requested
    # Checking only resolve()'s result loses evidence that build/lib (or an
    # ancestor) is a junction into another checkout. Inspect every existing
    # lexical component before either build_py or resource cleanup can run.
    for component in (*reversed(output.parents), output):
        try:
            metadata = component.lstat()
        except FileNotFoundError:
            continue
        if component.is_symlink() or (
            getattr(metadata, "st_file_attributes", 0)
            & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
        ):
            raise RuntimeError(f"Android resource output must not contain links: {component}")
    resolved = output.resolve()
    if (
        resolved == Path(resolved.anchor)
        or source_root.is_relative_to(resolved)
        or (
            resolved.is_relative_to(source_root)
            and not resolved.is_relative_to(source_root / "build")
        )
        or (not resolved.is_relative_to(source_root) and not requested.is_absolute())
    ):
        raise RuntimeError("Android resource output must use a separate build directory")
    return resolved


class _AndroidRuntimeBuildPy(build_py):
    def run(self):
        source_root = Path(__file__).resolve().parent
        build_root = _android_build_root(source_root, self.build_lib)
        # Validate before modifying any resource output. Provenance belongs to
        # the source-archive boundary, not a Git invocation inside PEP 517.
        assets = _android_runtime_assets(source_root)
        super().run()
        _android_build_root(source_root, self.build_lib)
        for name in _ANDROID_RESOURCE_ROOTS:
            destination = build_root / name
            if destination.exists() or destination.is_symlink():
                _check_android_asset_path(destination, build_root)
                if not destination.is_dir():
                    raise RuntimeError(f"Android resource output is not a directory: {destination}")
                # This is exclusively an allowlisted directory under the
                # resolved setuptools build tree, never a source directory.
                if not self.dry_run:
                    shutil.rmtree(destination)
        self._android_asset_outputs = []
        for source, relative in assets:
            destination = build_root / relative
            self.mkpath(str(destination.parent))
            self.copy_file(str(source), str(destination))
            self._android_asset_outputs.append(str(destination))

    def get_outputs(self, include_bytecode=True):
        return super().get_outputs(include_bytecode) + getattr(self, "_android_asset_outputs", [])



class _GuardedSdist(sdist):
    def run(self, *args, **kwargs):
        if _IN_ANDROID_BUILD or not _IN_NIX_BUILD:
            raise RuntimeError(_BLOCK_MESSAGE)
        return super().run(*args, **kwargs)


cmdclass = {"sdist": _GuardedSdist}
if _IN_ANDROID_BUILD:
    cmdclass["build_py"] = _AndroidRuntimeBuildPy

# bdist_wheel is only available when the `wheel` package is installed.
# setuptools.build_meta.build_wheel() calls it internally, so the guard
# fires for all PEP 517 wheel build paths. Define the subclass only when
# the import succeeds — otherwise a None base class raises TypeError at
# class-definition time, before the cmdclass guard can run.
try:
    from setuptools.command.bdist_wheel import bdist_wheel

    class _GuardedBdistWheel(bdist_wheel):
        def run(self, *args, **kwargs):
            if not (_IN_NIX_BUILD or _IN_ANDROID_BUILD):
                raise RuntimeError(_BLOCK_MESSAGE)
            return super().run(*args, **kwargs)

    cmdclass["bdist_wheel"] = _GuardedBdistWheel
except ImportError:
    pass

# Root single-file modules (``run_agent``, ``hermes_state``, ``toolsets``...)
# are invisible to ``packages.find``: that finder sees only directories with an
# ``__init__.py``. The wheel build needs them on ``py_modules``, so derive the
# list from the source tree at build time. A static list in ``pyproject.toml``
# drifted each time the tree layout changed (missing modules broke installed
# wheels with ``ModuleNotFoundError``), so there is no list to maintain here.
# ``setup()`` kwargs merge with ``pyproject.toml``, and this file is the only
# legitimate wheel/sdist builder, so the derived value is the single source.
# Editable installs do not read it: ``build_editable`` never runs
# ``bdist_wheel``. The filter source (``nix/lib.nix`` ``pythonSrc``) keeps
# every root ``.py`` file, so the build sandbox sees the same set of files.
def _root_py_modules():
    try:
        names = os.listdir(_ROOT)
    except OSError:
        return []
    return sorted(
        name[:-3]
        for name in names
        if name.endswith(".py") and name != "setup.py"
    )


setup(cmdclass=cmdclass, py_modules=_root_py_modules())
