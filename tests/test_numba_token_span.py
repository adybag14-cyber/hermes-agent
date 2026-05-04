from __future__ import annotations

from environments import numba_token_span


def test_find_token_span_prefers_last_match() -> None:
    full_tokens = [1, 2, 3, 2, 3, 4]
    sub_tokens = [2, 3]
    assert numba_token_span.find_token_span(full_tokens, sub_tokens) == 3


def test_find_token_span_returns_none_when_missing() -> None:
    assert numba_token_span.find_token_span([1, 2, 3], [4, 5]) is None


def test_find_token_span_returns_none_for_empty_inputs() -> None:
    assert numba_token_span.find_token_span([], [1]) is None
    assert numba_token_span.find_token_span([1, 2], []) is None


def test_find_token_span_falls_back_to_python(monkeypatch) -> None:
    monkeypatch.setattr(numba_token_span, "NUMBA_TOKEN_SPAN_AVAILABLE", False)
    full_tokens = list(range(800)) + [10, 11, 12]
    sub_tokens = [10, 11, 12]
    assert numba_token_span.find_token_span(full_tokens, sub_tokens) == 800


def test_find_token_span_uses_prepared_full_tokens() -> None:
    full_tokens = list(range(900)) + [10, 11, 12]
    prepared = numba_token_span.prepare_token_span_full(full_tokens)
    match = numba_token_span.find_token_span(
        full_tokens,
        [10, 11, 12],
        prepared_full_tokens=prepared,
    )
    assert match == 900


def test_numba_availability_flag_is_bool() -> None:
    assert isinstance(numba_token_span.NUMBA_TOKEN_SPAN_AVAILABLE, bool)
