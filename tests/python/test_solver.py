import numpy as np
import pytest
from scipy.optimize import minimize

import joint_solver as js

BASE = dict(
    n_agents=2, n_resources=2,
    preferences=[[0.9, 0.1], [0.1, 0.9]],
    priority_weights=[1.0, 1.0],
    capacities=[100.0, 100.0],
    minimums=[[1.0, 1.0], [1.0, 1.0]],
    ideals=[[100.0, 100.0], [100.0, 100.0]],
)


def solve(cfg, **over):
    data = dict(BASE)
    data.update(over)
    data["utility_configs"] = [cfg, cfg]
    return js.solve_joint_allocation(data)


# ---------------------------------------------------------------- validation

@pytest.mark.parametrize("over,frag", [
    ({"priority_weights": [1.0, -1.0]}, "strictly positive"),
    ({"preferences": [[0.9, -0.1], [0.1, 0.9]]}, "nonnegative"),
    ({"minimums": [[60.0, 1.0], [60.0, 1.0]]}, "exceeds capacity"),
    ({"minimums": [[101.0, 1.0], [1.0, 1.0]]}, "exceed ideals"),
    ({"capacities": [100.0, 100.0, 100.0]}, "shape"),
    ({"capacities": [float("inf"), 100.0]}, "non-finite"),
])
def test_validation_rejects(over, frag):
    r = solve({"type": "LINEAR"}, **over)
    assert r["status"] == "validation_error"
    assert frag in r["error_message"]
    assert r["allocations"] is None


def test_missing_field():
    data = dict(BASE)
    del data["capacities"]
    r = js.solve_joint_allocation(data)
    assert r["status"] == "validation_error"


# ------------------------------------------------------------- supported models

def test_linear_optimal():
    r = solve({"type": "LINEAR"})
    assert r["status"] == "optimal"
    assert r["solved_utility"] == "LINEAR"
    A = np.array(r["allocations"])
    assert A.sum(axis=0)[0] <= 100 + 1e-6


def test_cobb_douglas_closed_form():
    # weighted-Nash Cobb-Douglas separates per resource; share ~ c_i beta_ij
    r = solve({"type": "COBB_DOUGLAS"})
    assert r["status"] == "optimal"
    A = np.array(r["allocations"])
    assert A[0, 0] == pytest.approx(90.0, abs=0.5)
    assert A[1, 0] == pytest.approx(10.0, abs=0.5)
    assert A[0, 1] == pytest.approx(10.0, abs=0.5)


def test_cobb_douglas_joint_equals_separable():
    # Joint control adds no allocative value when the log objective separates.
    r = solve({"type": "COBB_DOUGLAS"})
    A = np.array(r["allocations"])
    W = np.array(BASE["preferences"])
    for j in range(2):
        # per-resource closed form: a_ij = Q_j * (c_i beta_ij) / sum_k c_k beta_kj
        num = W[:, j]
        sep = 100.0 * num / num.sum()
        assert np.allclose(A[:, j], sep, atol=0.5)


def test_ces_matches_scipy():
    for rho in (-1.0, 0.5):
        r = solve({"type": "CES", "rho": rho})
        assert r["status"] == "optimal", (rho, r)
        W = np.array(BASE["preferences"])

        def ces(w, a):
            return np.sum(w * np.maximum(a, 1e-12) ** rho) ** (1.0 / rho)

        def neg(x):
            A = x.reshape(2, 2)
            return -sum(np.log(max(ces(W[i], A[i]), 1e-12)) for i in range(2))

        best = None
        rng = np.random.default_rng(0)
        for _ in range(30):
            x0 = rng.uniform(1, 99, 4)
            cons = [{"type": "ineq", "fun": (lambda x, j=j: 100 - x[j::2].sum())} for j in range(2)]
            res = minimize(neg, x0, bounds=[(1, 100)] * 4, constraints=cons, method="SLSQP")
            if best is None or res.fun < best.fun:
                best = res
        assert r["welfare"] == pytest.approx(-best.fun, abs=1e-2)


def test_leontief_balanced_bundle():
    # single agent: optimum equalizes a_j / r_j across resources up to capacity
    data = dict(n_agents=1, n_resources=2, preferences=[[1.0, 1.0]],
                priority_weights=[1.0], capacities=[100.0, 100.0],
                minimums=[[0.0, 0.0]], ideals=[[100.0, 100.0]],
                utility_configs=[{"type": "LEONTIEF", "requirements": [2.0, 1.0]}])
    r = js.solve_joint_allocation(data)
    assert r["status"] == "optimal"
    A = np.array(r["allocations"])[0]
    # resource 0 binds at its ideal 100; utility = min(100/2, a1/1) = 50.
    # a1 above 50 is a valid but non-unique slack allocation, so pin the
    # determinate invariants: binding resource and achieved utility.
    assert A[0] == pytest.approx(100.0, abs=0.5)
    assert min(A[0] / 2.0, A[1] / 1.0) == pytest.approx(50.0, abs=0.5)
    assert A[1] >= 50.0 - 0.5


def test_ces_rho1_is_linear_special_case():
    ces = solve({"type": "CES", "rho": 1.0})
    lin = solve({"type": "LINEAR"})
    assert "LINEAR" in ces["solved_utility"]
    assert np.allclose(ces["allocations"], lin["allocations"], atol=0.5)


# ------------------------------------------------------- no silent substitution

def test_no_silent_substitution_ces_negative():
    ces = solve({"type": "CES", "rho": -1.0})
    lin = solve({"type": "LINEAR"})
    assert ces["status"] == "optimal"
    assert ces["solved_utility"] == "CES(rho=-1.0)"
    # a genuine CES(-1) solution must differ from the linear corner solution
    assert not np.allclose(ces["allocations"], lin["allocations"], atol=1.0)


@pytest.mark.parametrize("t", ["SQRT", "LOG", "THRESHOLD", "SATIATION",
                               "NESTED_CES", "SOFTPLUS_LOSS_AVERSION",
                               "ASYMMETRIC_LOG_LOSS_AVERSION", "MADE_UP"])
def test_unsupported_models_rejected(t):
    r = solve({"type": t})
    assert r["status"] == "unsupported_model"
    assert r["solved_utility"] is None
    assert r["allocations"] is None


def test_random_linear_matches_independent_solver():
    rng = np.random.default_rng(7)
    for _ in range(10):
        n, m = 3, 3
        W = rng.uniform(0.05, 1.0, (n, m))
        data = dict(n_agents=n, n_resources=m, preferences=W.tolist(),
                    priority_weights=[1.0] * n, capacities=[100.0] * m,
                    minimums=[[1.0] * m] * n, ideals=[[100.0] * m] * n,
                    utility_configs=[{"type": "LINEAR"}] * n)
        r = js.solve_joint_allocation(data)
        assert r["status"] == "optimal"

        def neg(x):
            A = x.reshape(n, m)
            return -sum(np.log(max(np.sum(W[i] * A[i]), 1e-12)) for i in range(n))

        cons = [{"type": "ineq", "fun": (lambda x, j=j: 100 - x.reshape(n, m)[:, j].sum())}
                for j in range(m)]
        best = None
        for _ in range(8):
            x0 = rng.uniform(1, 30, n * m)
            res = minimize(neg, x0, bounds=[(1, 100)] * (n * m), constraints=cons, method="SLSQP")
            if best is None or res.fun < best.fun:
                best = res
        assert r["welfare"] == pytest.approx(-best.fun, abs=5e-2)
