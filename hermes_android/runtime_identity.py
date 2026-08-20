"""Immutable identity checks for the embedded Hermes Android runtime."""

from __future__ import annotations

import os
import sys


def is_embedded_android_runtime() -> bool:
    """Recognize Chaquopy before the app has finished installing env markers."""
    return sys.platform == "android" or bool(
        os.getenv("HERMES_ANDROID_BOOTSTRAP", "").strip()
    )
