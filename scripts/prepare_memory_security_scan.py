#!/usr/bin/env python3
"""Fetch dependency lockfiles from the exact reviewed HY/Chroma source pins."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import tomllib
from urllib.request import urlopen


SOURCES = {
    "chromadb": ("adybag14-cyber/chroma", ("Cargo.lock",)),
    "hy-memory": ("adybag14-cyber/Hy-Memory", ("uv.lock", "memhub/apps/core/uv.lock")),
}


def source_pins(project: dict) -> dict[str, tuple[str, str]]:
    requirements = project["project"]["optional-dependencies"]["hy-memory"]
    pins = {}
    for name, (repository, _) in SOURCES.items():
        pattern = rf"{re.escape(name)} @ git\+https://github\.com/{re.escape(repository)}\.git@([0-9a-f]{{40}})"
        matches = [re.fullmatch(pattern, value) for value in requirements]
        commits = [match.group(1) for match in matches if match]
        if len(commits) != 1:
            raise ValueError(f"Expected one immutable reviewed {name} requirement")
        pins[name] = (repository, commits[0])
    return pins


def prepare(project_path: Path, output_dir: Path) -> None:
    pins = source_pins(tomllib.loads(project_path.read_text(encoding="utf-8")))
    output_dir.mkdir(parents=True, exist_ok=False)
    records = []
    for name, (repository, commit) in pins.items():
        for relative in SOURCES[name][1]:
            url = f"https://raw.githubusercontent.com/{repository}/{commit}/{relative}"
            with urlopen(url, timeout=60) as response:
                content = response.read(16 * 1024 * 1024 + 1)
            if len(content) > 16 * 1024 * 1024:
                raise ValueError(f"Oversized lockfile: {url}")
            document = tomllib.loads(content.decode("utf-8"))
            if not isinstance(document.get("package"), list) or not document["package"]:
                raise ValueError(f"Missing package inventory: {url}")
            destination = output_dir / name / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
            records.append({"repository": repository, "commit": commit, "path": relative,
                            "sha256": hashlib.sha256(content).hexdigest(),
                            "packages": len(document["package"])})
    (output_dir / "source-provenance.json").write_text(
        json.dumps(records, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Prepared {len(records)} lockfiles from immutable dependency commits")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path("pyproject.toml"))
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.project, args.output_dir)
