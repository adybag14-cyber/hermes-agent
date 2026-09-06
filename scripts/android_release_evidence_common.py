"""Shared validation errors and exact-key checks for Android release evidence."""

from __future__ import annotations

from typing import Any, Mapping


class EvidenceError(ValueError):
    """Raised when release evidence fails closed."""


def _exact_keys(value: Mapping[str, Any], expected: set[str], context: str) -> None:
    if set(value) != expected:
        raise EvidenceError(
            f"{context} key set is invalid; missing={sorted(expected - set(value))}, "
            f"unexpected={sorted(set(value) - expected)}"
        )
