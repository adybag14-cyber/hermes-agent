from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


def prefer_hermes_package_root() -> None:
    """Prefer Hermes' wheel root without replacing dependency modules.

    Shared helpers live in ``hermes_cli.shared_utils``, so a dependency's
    already-loaded top-level ``utils`` is unrelated and must stay untouched.
    """
    root = _hermes_package_root()
    if root:
        root_text = str(root)
        sys.path[:] = [item for item in sys.path if item != root_text]
        sys.path.insert(0, root_text)

def _hermes_package_root() -> Path | None:
    for module_name in ("hermes_cli", "hermes_android"):
        spec = importlib.util.find_spec(module_name)
        origin = getattr(spec, "origin", None)
        if origin:
            return Path(origin).resolve().parent.parent
    return None
