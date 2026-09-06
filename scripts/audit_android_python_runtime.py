#!/usr/bin/env python3
"""Audit the prepared source builder's offline ABI view without importing wheels."""
import argparse
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--helpers", type=Path, required=True)
    parser.add_argument("--wheel-dir", type=Path, required=True)
    parser.add_argument("--requirements", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    # The caller downloaded and verified the complete pinned source archive.
    # These are build helpers, never modules imported from target Android wheels.
    sys.path.insert(0, str(args.helpers.resolve()))
    import assemble_wheelhouse
    import audit_wheels
    requirements, _ = assemble_wheelhouse.pinned_requirements(args.requirements)
    report = audit_wheels.audit_directory(args.wheel_dir, "3.13", args.abi)
    report["dependency_closure"] = assemble_wheelhouse.dependency_closure(
        args.wheel_dir, requirements, "3.13", args.abi)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    if not report["passed"]:
        raise ValueError("Android Python wheel audit failed: " + str(args.output))
    print(f"Android Python offline closure/ELF audit passed: {args.abi}")


if __name__ == "__main__":
    main()
