"""Central Leontief reference: the reduced form matches the canonical joint solver, and
the reference objective is the weighted-log Leontief welfare."""
import numpy as np
import pytest

cvxpy = pytest.importorskip("cvxpy")
from oqlib import central as C  # noqa: E402
from oqlib.central_ref import central_leontief_reference  # noqa: E402


def _scn():
    R = np.array([[0.6, 0.3, 0.1, 0.0], [0.1, 0.5, 0.3, 0.1], [0.25, 0.25, 0.25, 0.25],
                  [0.0, 0.2, 0.4, 0.4], [0.4, 0.0, 0.3, 0.3], [0.2, 0.3, 0.0, 0.5]])
    Q = np.array([120.0, 100.0, 90.0, 80.0])
    mn = np.where(R > 0, 1.0, 0.0)
    up = np.tile(Q, (6, 1))
    c = np.full(6, 10.0)
    return R, Q, mn, up, c


def test_reduced_central_matches_joint_solver():
    R, Q, mn, up, c = _scn()
    u, A, obj = C.reduced_central_leontief(R, Q, mn, up, c)
    cref = central_leontief_reference(R, Q, mn, up, c)
    assert cref["status"] in ("optimal", "optimal_inaccurate")
    rel = abs(obj - cref["objective_value"]) / max(abs(cref["objective_value"]), 1e-9)
    assert rel <= 1e-6


def test_leontief_objective_is_weighted_log_min_ratio():
    R, Q, mn, up, c = _scn()
    u, A, obj = C.reduced_central_leontief(R, Q, mn, up, c)
    # recompute from A directly
    total = 0.0
    for i in range(R.shape[0]):
        ratios = [A[i, j] / R[i, j] for j in range(R.shape[1]) if R[i, j] > 0]
        total += c[i] * np.log(max(min(ratios), 1e-12))
    assert abs(total - C.leontief_objective(A, R, c)) <= 1e-9
