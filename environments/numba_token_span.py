from __future__ import annotations

from typing import List, Optional

try:
    import numpy as np
except Exception:  # pragma: no cover - optional dependency
    np = None

try:
    from numba import njit
except Exception:  # pragma: no cover - optional dependency
    njit = None

NUMBA_TOKEN_SPAN_AVAILABLE = np is not None and njit is not None

# Assistant turns usually land near the end of the rollout. Checking that tail
# in plain Python is cheaper than converting every call to NumPy arrays.
TAIL_SCAN_WINDOWS = 64
MIN_NUMBA_FULL_LEN = 512
MIN_NUMBA_WORK = 4096


def _find_token_span_python(
    full_tokens: List[int], sub_tokens: List[int], start: int, stop: int = -1
) -> Optional[int]:
    sub_len = len(sub_tokens)
    for i in range(start, stop, -1):
        if full_tokens[i : i + sub_len] == sub_tokens:
            return i
    return None


if NUMBA_TOKEN_SPAN_AVAILABLE:

    @njit(cache=True)
    def _find_token_span_numba(full_tokens: "np.ndarray", sub_tokens: "np.ndarray") -> int:
        full_len = len(full_tokens)
        sub_len = len(sub_tokens)

        for i in range(full_len - sub_len, -1, -1):
            matched = True
            for j in range(sub_len):
                if full_tokens[i + j] != sub_tokens[j]:
                    matched = False
                    break
            if matched:
                return i
        return -1

else:

    def _find_token_span_numba(full_tokens, sub_tokens) -> int:  # pragma: no cover
        return -1


def prepare_token_span_full(full_tokens: List[int]) -> Optional["np.ndarray"]:
    """Prepare a reusable array for repeated span searches on one rollout."""
    if not NUMBA_TOKEN_SPAN_AVAILABLE or not full_tokens:
        return None
    return np.asarray(full_tokens, dtype=np.int64)


def find_token_span(
    full_tokens: List[int],
    sub_tokens: List[int],
    prepared_full_tokens: Optional["np.ndarray"] = None,
) -> Optional[int]:
    """
    Find the last occurrence of ``sub_tokens`` inside ``full_tokens``.

    For common near-tail matches, stay in Python. Only use NumPy + Numba when
    the search is large enough that the conversion cost is likely worthwhile.
    """
    if not sub_tokens or not full_tokens:
        return None

    sub_len = len(sub_tokens)
    full_len = len(full_tokens)
    if sub_len > full_len:
        return None

    last_start = full_len - sub_len
    tail_stop = max(-1, last_start - TAIL_SCAN_WINDOWS)
    tail_match = _find_token_span_python(full_tokens, sub_tokens, last_start, tail_stop)
    if tail_match is not None:
        return tail_match

    work = full_len * sub_len
    if not NUMBA_TOKEN_SPAN_AVAILABLE or full_len < MIN_NUMBA_FULL_LEN or work < MIN_NUMBA_WORK:
        return _find_token_span_python(full_tokens, sub_tokens, tail_stop, -1)

    full_arr = prepared_full_tokens
    if full_arr is None:
        full_arr = np.asarray(full_tokens, dtype=np.int64)
    sub_arr = np.asarray(sub_tokens, dtype=np.int64)
    match = _find_token_span_numba(full_arr, sub_arr)
    return None if match < 0 else int(match)
