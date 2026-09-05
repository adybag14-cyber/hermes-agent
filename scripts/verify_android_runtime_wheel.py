#!/usr/bin/env python3
"""Verify the private Android wheel against its staged source archive."""

import argparse
import base64
import csv
from email.parser import BytesParser
import fnmatch
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tomllib
import zipfile


RESOURCE_ROOTS = ("skills", "optional-skills", "locales", "optional-mcps")
EXCLUDED_DIRS = frozenset({
    ".git", ".hg", ".svn", "__pycache__", ".pytest_cache", ".mypy_cache",
    ".ruff_cache", ".tox", ".nox", ".venv", "venv", "node_modules",
    "build", "dist", "target", ".artifacts", "release-evidence",
})
EXCLUDED_SUFFIXES = (
    ".pyc", ".pyo", ".pyd", ".whl", ".egg", ".apk", ".aab", ".pftrace",
    ".profraw", ".profdata",
)


def check_unlinked_path(path: Path) -> Path:
    """Check lexical ancestors, including Windows junctions, before resolving."""
    path = path.absolute()
    if ".." in path.parts:
        raise ValueError(f"Path must not contain parent traversal: {path}")
    for component in (*reversed(path.parents), path):
        try:
            metadata = component.lstat()
        except FileNotFoundError:
            continue
        if component.is_symlink() or (
            getattr(metadata, "st_file_attributes", 0)
            & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
        ):
            raise ValueError(f"Android wheel paths must not contain links: {component}")
    return path.resolve()


def _excluded(name: str) -> bool:
    name = name.lower()
    return (
        name in EXCLUDED_DIRS or name == ".coverage"
        or name.endswith((".egg-info", ".dist-info"))
        or name.endswith(EXCLUDED_SUFFIXES)
    )


def _source_files(root: Path, relative: str) -> list[str]:
    directory = root / relative
    if not directory.is_dir():
        raise ValueError(f"Android runtime source directory is missing: {relative}")
    check_unlinked_path(directory)
    files = []
    pending = [directory]
    while pending:
        for path in sorted(pending.pop().iterdir()):
            if _excluded(path.name):
                continue
            check_unlinked_path(path)
            if path.is_dir():
                pending.append(path)
            elif path.is_file():
                files.append(path.relative_to(root).as_posix())
            else:
                raise ValueError(f"Android runtime source is not a regular file: {path}")
    return files


def _glob_match(parts: tuple[str, ...], pattern: tuple[str, ...]) -> bool:
    if not pattern:
        return not parts
    if pattern[0] == "**":
        return _glob_match(parts, pattern[1:]) or (
            bool(parts) and _glob_match(parts[1:], pattern)
        )
    return bool(parts) and fnmatch.fnmatchcase(parts[0], pattern[0]) and _glob_match(parts[1:], pattern[1:])


def source_inventory(root: Path) -> tuple[dict, set[str]]:
    """Use the package declarations, never Git metadata or imported app code."""
    root = check_unlinked_path(root)
    project = tomllib.loads((root / "pyproject.toml").read_text(encoding="utf-8"))
    config = project["tool"]["setuptools"]
    modules = config.get("py-modules")
    if modules is None:
        # setup.py derives root modules when TOML does not override them.
        # Inspect source paths only; importing setup.py would execute a build.
        modules = sorted(path.stem for path in root.glob("*.py") if path.name != "setup.py")
    required = {f"{name.replace('.', '/')}.py" for name in modules}
    packages = config.get("packages", {})
    if isinstance(packages, list):
        includes, excludes = packages, []
    else:
        discovery = packages.get("find", {})
        if discovery.get("where", ["."]) != ["."]:
            raise ValueError("Android runtime verifier supports repository-root packages only")
        includes, excludes = discovery.get("include", ["*"]), discovery.get("exclude", [])
    package_files = []
    # Walk only top-level directories selected by a package pattern. Namespace
    # subpackages are supported without importing any application module.
    for path in sorted(root.iterdir()):
        if _excluded(path.name) or not path.is_dir():
            continue
        if any(fnmatch.fnmatchcase(path.name, pattern.split(".", 1)[0]) for pattern in includes):
            package_files.extend(_source_files(root, path.name))
    discovered = set()
    for relative in package_files:
        path = PurePosixPath(relative)
        package = ".".join(path.parent.parts)
        if any(fnmatch.fnmatchcase(package, pattern) for pattern in includes) and not any(
            fnmatch.fnmatchcase(package, pattern) for pattern in excludes
        ):
            discovered.add(package)
            if path.suffix == ".py":
                required.add(relative)
    for package, patterns in config.get("package-data", {}).items():
        targets = discovered if package in ("", "*") else {package}
        for target in targets:
            prefix = target.replace(".", "/") + "/"
            for relative in package_files:
                if relative.startswith(prefix) and any(
                    _glob_match(tuple(relative[len(prefix):].split("/")), tuple(pattern.split("/")))
                    for pattern in patterns
                ):
                    required.add(relative)
    for relative in RESOURCE_ROOTS:
        required.update(_source_files(root, relative))
    for relative in required:
        path = check_unlinked_path(root / relative)
        if not path.is_file():
            raise ValueError(f"Declared Android runtime source is missing: {relative}")
    return project["project"], required


def _normalized_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _license_files(root: Path, project: dict) -> dict[str, Path]:
    patterns = project.get("license-files", ["LICEN[CS]E*", "COPYING*", "NOTICE*", "AUTHORS*"])
    legacy_license = project.get("license")
    if isinstance(legacy_license, dict) and "file" in legacy_license:
        patterns = [*patterns, legacy_license["file"]]
    files = {}
    for pattern in patterns:
        path = PurePosixPath(pattern)
        if path.is_absolute() or ".." in path.parts or "\\" in pattern or ":" in pattern:
            raise ValueError(f"Unsafe Android wheel license pattern: {pattern}")
        for source in root.glob(pattern):
            source = check_unlinked_path(source)
            if source.is_file():
                files[source.relative_to(root).as_posix()] = source
    return files


def verify_wheel(root: Path, wheel: Path) -> dict:
    root = check_unlinked_path(root)
    project, required = source_inventory(root)
    wheel = check_unlinked_path(wheel)
    with zipfile.ZipFile(wheel) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Android wheel contains duplicate ZIP members")
        if any(stat.S_ISLNK(member.external_attr >> 16) for member in archive.infolist()):
            raise ValueError("Android wheel must not contain symbolic links")
        for name in names:
            path = PurePosixPath(name)
            if not name or not path.parts or "\\" in name or path.is_absolute() or ".." in path.parts or ":" in name:
                raise ValueError(f"Android wheel contains an unsafe member: {name}")
            # The one distribution metadata directory is allowed below; nested
            # distribution metadata, caches, and build artifacts are not.
            parts = path.parts[1:] if path.parts[0].endswith(".dist-info") else path.parts
            if any(_excluded(part) for part in parts):
                raise ValueError(f"Android wheel contains a cache or artifact: {name}")
        metadata_names = [name for name in names if name.count("/") == 1 and name.endswith(".dist-info/METADATA")]
        if len(metadata_names) != 1:
            raise ValueError("Android wheel must contain exactly one distribution")
        info = metadata_names[0].rsplit("/", 1)[0]
        metadata_roots = {PurePosixPath(name).parts[0] for name in names if PurePosixPath(name).parts[0].endswith(".dist-info")}
        if metadata_roots != {info} or not {f"{info}/WHEEL", f"{info}/RECORD"}.issubset(names):
            raise ValueError("Android wheel distribution metadata is incomplete or ambiguous")
        metadata = BytesParser().parsebytes(archive.read(metadata_names[0]))
        if _normalized_name(metadata["Name"] or "") != _normalized_name(project["name"]) or metadata["Version"] != project["version"]:
            raise ValueError("Android wheel name/version does not match the staged source")
        wheel_metadata = BytesParser().parsebytes(archive.read(f"{info}/WHEEL"))
        if wheel_metadata["Root-Is-Purelib"] != "true" or wheel_metadata.get_all("Tag") != ["py3-none-any"]:
            raise ValueError("Android runtime wheel must be pure Python (py3-none-any)")
        record_name = f"{info}/RECORD"
        rows = list(csv.reader(io.StringIO(archive.read(record_name).decode("utf-8"))))
        if any(len(row) != 3 for row in rows) or len({row[0] for row in rows}) != len(rows):
            raise ValueError("Android wheel RECORD is malformed or duplicated")
        record = {row[0]: row[1:] for row in rows}
        file_names = {name for name in names if not name.endswith("/")}
        if set(record) != file_names or record[record_name] != ["", ""]:
            raise ValueError("Android wheel RECORD inventory does not match the archive")
        for name, (digest, size) in record.items():
            if name == record_name:
                continue
            data = archive.read(name)
            expected = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")
            if digest != f"sha256={expected}" or size != str(len(data)):
                raise ValueError(f"Android wheel RECORD hash/size mismatch: {name}")
        missing = sorted(required - file_names)
        if missing:
            raise ValueError(f"Android wheel is missing runtime files: {', '.join(missing[:8])}")
        for name in sorted(required):
            if archive.read(name) != (root / name).read_bytes():
                raise ValueError(f"Android wheel contains stale runtime content: {name}")
        licenses = _license_files(root, project)
        if set(metadata.get_all("License-File", [])) != set(licenses):
            raise ValueError("Android wheel license inventory does not match the staged source")
        license_members = {f"{info}/licenses/{name}": source for name, source in licenses.items()}
        for name, source in license_members.items():
            if name not in file_names or archive.read(name) != source.read_bytes():
                raise ValueError(f"Android wheel license is missing or stale: {name}")
        # Do not infer ownership from files which happen to remain in the
        # source: a deleted top-level module, entire package, or package-data
        # file is still unexpected, even when its RECORD entry is valid.
        allowed_metadata = {f"{info}/{name}" for name in (
            "METADATA", "WHEEL", "RECORD", "entry_points.txt", "top_level.txt",
        )}
        allowed_files = required | allowed_metadata | set(license_members)
        unexpected = sorted(file_names - allowed_files)
        if unexpected:
            raise ValueError(f"Android wheel contains unexpected files: {', '.join(unexpected[:8])}")
        allowed_directories = {
            str(parent) + "/" for name in allowed_files
            for parent in PurePosixPath(name).parents if str(parent) != "."
        }
        if any(name.endswith("/") and name not in allowed_directories for name in names):
            raise ValueError("Android wheel contains unexpected directories")
    return {
        "wheel": str(wheel), "sha256": hashlib.sha256(wheel.read_bytes()).hexdigest(),
        "runtime_files": len(required), "archive_files": len(file_names),
        "resource_roots": list(RESOURCE_ROOTS),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--wheel", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(verify_wheel(args.project_root, args.wheel), sort_keys=True))


if __name__ == "__main__":
    main()
