"""Tests for the holographic memory provider retrieval helpers."""

from __future__ import annotations

import numpy as np

from plugins.memory.holographic import holographic as hrr
from plugins.memory.holographic.retrieval import FactRetriever
from plugins.memory.holographic.store import MemoryStore


def test_batch_similarity_matches_scalar_similarity():
    target = hrr.encode_text("deploy checklist", dim=64)
    rows = [
        hrr.encode_text("deploy checklist", dim=64),
        hrr.encode_text("release checklist", dim=64),
        hrr.encode_text("garden watering", dim=64),
    ]
    matrix = np.vstack(rows)

    batch = hrr.batch_similarity(target, matrix)
    scalar = np.array([hrr.similarity(target, row) for row in rows], dtype=np.float64)

    assert np.allclose(batch, scalar)


def test_phase_bytes_to_matrix_round_trips_vectors():
    rows = [
        hrr.encode_text("alpha beta", dim=32),
        hrr.encode_text("gamma delta", dim=32),
    ]
    blobs = [hrr.phases_to_bytes(row) for row in rows]

    matrix = hrr.phase_bytes_to_matrix(blobs)

    assert matrix.shape == (2, 32)
    assert np.allclose(matrix[0], rows[0])
    assert np.allclose(matrix[1], rows[1])


def test_search_encodes_query_vector_once(tmp_path, monkeypatch):
    store = MemoryStore(db_path=tmp_path / "memory.db", hrr_dim=64)
    store.add_fact("Ada likes release automation", category="project", tags="release")
    store.add_fact("Ada maintains deployment checklists", category="project", tags="deploy")
    store.add_fact("Ada tracks rollout incidents", category="project", tags="ops")

    retriever = FactRetriever(store=store, hrr_weight=0.3, hrr_dim=64)
    original_encode_text = hrr.encode_text
    query = "Ada deploy"
    calls = {"query": 0}

    def wrapped_encode_text(text: str, dim: int = 1024):
        if text == query:
            calls["query"] += 1
        return original_encode_text(text, dim)

    monkeypatch.setattr(hrr, "encode_text", wrapped_encode_text)

    results = retriever.search(query, category="project", limit=2)

    assert results
    assert calls["query"] == 1