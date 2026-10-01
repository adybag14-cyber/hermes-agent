from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class EvidenceItem:
    content: str
    source: str
    timestamp: float
    metadata: dict[str, str] | None = None


class TemporalRAG(Protocol):
    def retrieve(
        self,
        query: str,
        *,
        as_of: float | None = None,
        limit: int = 5,
    ) -> list[EvidenceItem]:
        ...


class InMemoryTemporalRAG:
    """Deterministic temporal evidence store used by ODYN and its tests."""

    def __init__(self) -> None:
        self._items: list[EvidenceItem] = []

    def add(
        self,
        content: str,
        *,
        source: str,
        timestamp: float,
        metadata: dict[str, str] | None = None,
    ) -> None:
        if not content.strip():
            raise ValueError("content must not be empty")
        if not source.strip():
            raise ValueError("source must not be empty")
        self._items.append(
            EvidenceItem(
                content=content,
                source=source,
                timestamp=float(timestamp),
                metadata=dict(metadata or {}),
            )
        )

    def retrieve(
        self,
        query: str,
        *,
        as_of: float | None = None,
        limit: int = 5,
    ) -> list[EvidenceItem]:
        if not query.strip():
            raise ValueError("query must not be empty")
        if limit <= 0:
            return []

        query_terms = {term.lower() for term in query.split() if term.strip()}
        candidates = [
            item for item in self._items
            if as_of is None or item.timestamp <= as_of
        ]

        def score(item: EvidenceItem) -> tuple[int, float]:
            haystack = f"{item.content} {item.source}".lower()
            overlap = sum(1 for term in query_terms if term in haystack)
            return overlap, item.timestamp

        return sorted(candidates, key=score, reverse=True)[:limit]
