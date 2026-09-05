#!/usr/bin/env python3
"""Build and verify the private Chaquopy wheel without cleaning the checkout."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from verify_android_runtime_wheel import check_unlinked_path, verify_wheel


def _publish_wheel(source: Path, destination: Path) -> None:
    """Replace one directory entry atomically, never write through a hardlink."""
    check_unlinked_path(destination)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as output, source.open("rb") as input_file:
            shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
        check_unlinked_path(destination)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def build_wheel(project_root: Path, wheel_dir: Path, *, expected_name: str | None = None) -> dict:
    project_root = check_unlinked_path(project_root)
    wheel_dir = check_unlinked_path(wheel_dir)
    wheel_dir.mkdir(parents=True, exist_ok=True)
    # Setuptools' in-tree build/lib can retain deleted modules, or be a
    # junction into another checkout. Use owned scratch output instead of
    # recursively deleting any caller-supplied/source-tree directory.
    with tempfile.TemporaryDirectory(prefix="hermes-android-wheel-") as temporary:
        scratch = check_unlinked_path(Path(temporary))
        metadata = scratch / "metadata"
        metadata.mkdir()
        library = (scratch / "build/lib").as_posix()
        binary_build = (scratch / "build/temp").as_posix()
        scripts = (scratch / "build/scripts").as_posix()
        distribution = (scratch / "bdist/wheel").as_posix()
        config = scratch / "setuptools.cfg"
        config.write_text(
            f"[build]\nbuild_base = {(scratch / 'build').as_posix()}\n"
            f"build_lib = {library}\nbuild_purelib = {library}\nbuild_platlib = {library}\n"
            f"build_temp = {binary_build}\nbuild_scripts = {scripts}\n"
            f"\n[build_py]\nbuild_lib = {library}\n"
            f"\n[build_ext]\nbuild_lib = {library}\nbuild_temp = {binary_build}\n"
            f"\n[build_scripts]\nbuild_dir = {scripts}\n"
            f"\n[bdist]\nbdist_base = {(scratch / 'bdist').as_posix()}\n"
            f"\n[bdist_wheel]\nbdist_dir = {distribution}\nskip_build = 0\n"
            f"\n[install]\nrecord = {(scratch / 'install-record.txt').as_posix()}\nuser = 0\n"
            f"\n[install_lib]\nbuild_dir = {library}\ninstall_dir = {distribution}\n"
            f"\n[install_egg_info]\ninstall_dir = {distribution}\n"
            f"\n[install_scripts]\nbuild_dir = {scripts}\ninstall_dir = {(scratch / 'installed-scripts').as_posix()}\n"
            f"\n[install_data]\ninstall_dir = {(scratch / 'installed-data').as_posix()}\nroot = {scratch.as_posix()}\n"
            f"\n[install_headers]\ninstall_dir = {(scratch / 'installed-headers').as_posix()}\n"
            f"\n[egg_info]\negg_base = {metadata.as_posix()}\n",
            encoding="utf-8",
        )
        environment = os.environ.copy()
        environment["HERMES_ANDROID_BUILD"] = "1"
        environment.pop("HERMES_NIX_BUILD", None)
        environment["DIST_EXTRA_CONFIG"] = str(config)
        # pip builds into scratch too, so an old final wheel cannot satisfy
        # validation when the build failed or emitted a different version.
        built = scratch / "wheels"
        subprocess.run(
            [sys.executable, "-m", "pip", "wheel", "--no-deps", "--wheel-dir", str(built), str(project_root)],
            cwd=project_root, env=environment, check=True,
        )
        wheels = list(built.glob("*.whl"))
        if len(wheels) != 1:
            raise ValueError("Android runtime build must produce exactly one wheel")
        if expected_name is not None and wheels[0].name != expected_name:
            raise ValueError(f"Android runtime wheel does not match Chaquopy's expected filename: {expected_name}")
        report = verify_wheel(project_root, wheels[0])
        destination = check_unlinked_path(wheel_dir / wheels[0].name)
        # Write only the verified artifact; retain a previous good artifact
        # unchanged on a failed build or failed inventory check.
        _publish_wheel(wheels[0], destination)
        report["wheel"] = str(destination)
        return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--wheel-dir", required=True, type=Path)
    parser.add_argument("--wheel-name", required=True)
    args = parser.parse_args()
    print(json.dumps(build_wheel(args.project_root, args.wheel_dir, expected_name=args.wheel_name), sort_keys=True))


if __name__ == "__main__":
    main()
