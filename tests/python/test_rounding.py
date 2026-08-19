import numpy as np
import pytest

from lib import rounding


def test_naive_can_violate_capacity():
    cont = np.array([[50.5, 25.4], [24.5, 49.5], [25.0, 25.1]])
    naive = rounding.naive_cellwise_round(cont)
    assert naive.sum(axis=0)[0] == 101  # exceeds capacity 100


def test_largest_remainder_preserves_capacity_random():
    rng = np.random.default_rng(0)
    for _ in range(500):
        n = rng.integers(2, 8)
        m = rng.integers(1, 5)
        cap = rng.integers(20, 100, size=m)
        cont = np.zeros((n, m))
        lower = np.zeros((n, m), dtype=np.int64)
        upper = np.zeros((n, m), dtype=np.int64)
        for j in range(m):
            ub = rng.integers(5, cap[j] + 1, size=n)
            frac = rng.uniform(0, 1, size=n)
            col = frac / frac.sum() * cap[j]
            col = np.minimum(col, ub)
            cont[:, j] = col
            upper[:, j] = ub
        out = rounding.largest_remainder_round(cont, lower, upper, cap)
        assert np.all(out.sum(axis=0) <= cap)
        assert np.all(out >= lower)
        assert np.all(out <= upper)


def test_largest_remainder_respects_lower_bounds():
    cont = np.array([[10.4, 5.5], [10.4, 5.5]])
    lower = np.array([[10, 5], [10, 5]], dtype=np.int64)
    upper = np.array([[50, 50], [50, 50]], dtype=np.int64)
    cap = np.array([100, 100], dtype=np.int64)
    out = rounding.largest_remainder_round(cont, lower, upper, cap)
    assert np.all(out >= lower)


def test_largest_remainder_deterministic():
    cont = np.array([[33.4, 0.0], [33.4, 0.0], [33.4, 0.0]])
    lower = np.zeros((3, 2), dtype=np.int64)
    upper = np.full((3, 2), 100, dtype=np.int64)
    cap = np.array([100, 100], dtype=np.int64)
    a = rounding.largest_remainder_round(cont, lower, upper, cap)
    b = rounding.largest_remainder_round(cont, lower, upper, cap)
    assert np.array_equal(a, b)
    assert a.sum(axis=0)[0] <= 100
