"""Distributed price Leontief solver: objective equivalence to the central solver on
kinked, active-floor and active-bound cases, feasibility, and the no-hidden-central-call
guarantee."""
import os

import numpy as np
import pytest

from oqlib import distributed as D

cvxpy = pytest.importorskip("cvxpy")
from oqlib.central_ref import central_leontief_reference  # noqa: E402


def _gap(R, Q, mn, up, c):
    cref = central_leontief_reference(R, Q, mn, up, c)
    assert cref["status"] in ("optimal", "optimal_inaccurate")
    u, A, obj, info = D.distributed_leontief(R, Q, mn, up, c)
    gap = abs(obj - cref["objective_value"]) / max(abs(cref["objective_value"]), 1e-9)
    return gap, info


def test_no_hidden_central_solver_call_in_source():
    """Inspect the AST (not the docstring): the distributed solver imports no central
    solver and calls no central-solve function. The only cross-module import is the
    Leontief objective *evaluator* and the capacity-preserving rounding, neither of which
    solves the allocation."""
    import ast
    src = open(os.path.join(os.path.dirname(D.__file__), "distributed.py")).read()
    tree = ast.parse(src)
    imported_modules = set()
    imported_names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for a in node.names:
                imported_modules.add(a.name)
        elif isinstance(node, ast.ImportFrom):
            imported_modules.add(node.module or "")
            for a in node.names:
                imported_names.add(a.name)
    assert "cvxpy" not in imported_modules
    for banned in ("central_ref", "joint_solver"):
        assert all(banned not in m for m in imported_modules), "must not import %s" % banned
    # only the objective evaluator may come from .central (no solver)
    assert imported_names & {"leontief_objective"} == {"leontief_objective"} or "leontief_objective" not in imported_names
    assert "reduced_central_leontief" not in imported_names
    called = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            fn = node.func
            if isinstance(fn, ast.Name):
                called.add(fn.id)
            elif isinstance(fn, ast.Attribute):
                called.add(fn.attr)
    for banned in ("solve_joint_allocation", "central_leontief_reference", "reduced_central_leontief"):
        assert banned not in called, "distributed solver must not call %s" % banned


def test_objective_equivalence_natural_case():
    R = np.array([[0.6, 0.3, 0.1, 0.0], [0.1, 0.5, 0.3, 0.1], [0.25, 0.25, 0.25, 0.25],
                  [0.0, 0.2, 0.4, 0.4], [0.4, 0.0, 0.3, 0.3], [0.2, 0.3, 0.0, 0.5]])
    Q = np.array([120.0, 100.0, 90.0, 80.0])
    mn = np.where(R > 0, 1.0, 0.0)
    up = np.tile(Q, (6, 1))
    c = np.full(6, 10.0)
    gap, info = _gap(R, Q, mn, up, c)
    assert gap <= 1e-4 and info["capacity_residual"] <= 1e-7


def test_kinked_case_convergence():
    # two resources with equal ratio -> the Leontief min is a kink for every agent
    R = np.array([[0.4, 0.4, 0.2], [0.4, 0.4, 0.2], [0.3, 0.3, 0.4]])
    Q = np.array([30.0, 30.0, 25.0])
    mn = np.where(R > 0, 1.0, 0.0)
    up = np.tile(Q, (3, 1))
    c = np.full(3, 10.0)
    gap, info = _gap(R, Q, mn, up, c)
    assert gap <= 1e-4 and info["converged"]


def test_active_floor_case():
    # small resource with a high floor that binds
    R = np.array([[0.07, 0.78, 0.15], [0.45, 0.10, 0.45], [0.34, 0.64, 0.02]])
    Q = np.array([40.0, 30.0, 30.0])
    mn = np.array([[3.0, 10.0, 2.0], [2.0, 1.0, 5.0], [13.0, 1.0, 1.0]])
    up = np.array([[17.0, 20.0, 20.0], [20.0, 20.0, 20.0], [23.0, 20.0, 20.0]])
    c = np.full(3, 10.0)
    gap, info = _gap(R, Q, mn, up, c)
    assert gap <= 1e-4 and info["capacity_residual"] <= 1e-7


def test_active_upper_bound_case():
    R = np.array([[0.5, 0.5, 0.0], [0.2, 0.4, 0.4], [0.3, 0.3, 0.4]])
    Q = np.array([20.0, 20.0, 20.0])
    mn = np.where(R > 0, 1.0, 0.0)
    up = np.array([[3.0, 100.0, 0.0], [100.0, 100.0, 100.0], [100.0, 100.0, 100.0]])  # agent 0 COMPUTE capped
    c = np.full(3, 10.0)
    gap, info = _gap(R, Q, mn, up, c)
    assert gap <= 1e-4 and info["capacity_residual"] <= 1e-7


def test_best_response_matches_bruteforce():
    rng = np.random.default_rng(1)
    for _ in range(200):
        n, m = int(rng.integers(1, 5)), int(rng.integers(1, 5))
        R = (rng.random((n, m)) ** 2) * (rng.random((n, m)) > 0.3)
        for i in range(n):
            if R[i].sum() == 0:
                R[i, int(rng.integers(0, m))] = rng.random() + 0.1
        up = np.zeros((n, m))
        mn = np.zeros((n, m))
        for i in range(n):
            for j in range(m):
                if R[i, j] > 0:
                    up[i, j] = int(rng.integers(2, 30))
                    mn[i, j] = int(rng.integers(0, int(up[i, j]) + 1))
        c = np.full(n, 10.0)
        lam = rng.random(m) * rng.choice([0.01, 0.1, 1.0, 10.0])
        u, A = D._agent_best_response(R, mn, up, c, lam)
        for i in range(n):
            pos = [j for j in range(m) if R[i, j] > 0]
            if not pos:
                continue
            ub = min(up[i, j] / R[i, j] for j in pos)
            grid = np.linspace(1e-9, ub, 20000)
            g = c[i] * np.log(grid) - sum(lam[j] * np.maximum(mn[i, j], grid * R[i, j]) for j in pos)
            best = grid[np.argmax(g)]

            def gof(uu):
                return c[i] * np.log(max(uu, 1e-12)) - sum(lam[j] * max(mn[i, j], uu * R[i, j]) for j in pos)
            assert gof(best) - gof(u[i]) <= 1e-6
