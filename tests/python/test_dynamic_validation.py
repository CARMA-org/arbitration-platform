import json
import os
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_DYN = os.path.join(ROOT, "experiments", "dynamic_allocation")
_saved_path = list(sys.path)
_saved_lib = {k: v for k, v in list(sys.modules.items()) if k == "lib" or k.startswith("lib.")}
sys.path.insert(0, _DYN)
import run_dynamic as rd
sys.path[:] = _saved_path
for _k in [k for k in list(sys.modules) if k == "lib" or k.startswith("lib.")]:
    del sys.modules[_k]
sys.modules.update(_saved_lib)

SMALL = {"n_pool": 6, "n_base": 3, "epochs": 5, "seeds": 1, "tasks_per_agent": 3}


def _run_policy(monkeypatch, solver_fn, policy="reoptimize", cfg=SMALL):
    monkeypatch.setattr(rd.joint_solver, "solve_joint_allocation", solver_fn)
    seed = rd.derive_seed("dynamic_seed", 0)
    pool = rd.build_pool(seed, cfg)
    events, sh = rd.event_schedule(seed, cfg)
    rows = []
    row = rd.simulate_policy(policy, pool, cfg, events, sh, seed, lambda r: rows.append(r))
    return row, rows


def test_feasible_solution():
    res = {"status": "optimal", "allocations": [[1, 1], [1, 1]]}
    assert rd.validate_solution(res, 2, 2, 0, "reoptimize", 0, "base") == "feasible"


def test_optimal_null_allocations_rejected():
    with pytest.raises(rd.SolverResultError):
        rd.validate_solution({"status": "optimal", "allocations": None},
                             2, 2, 0, "reoptimize", 0, "base")


def test_optimal_wrong_dimensions_rejected():
    with pytest.raises(rd.SolverResultError):
        rd.validate_solution({"status": "optimal", "allocations": [[1, 1]]},
                             2, 2, 0, "reoptimize", 0, "base")


def test_infeasible_classified():
    assert rd.validate_solution({"status": "infeasible", "allocations": None},
                                2, 2, 0, "reoptimize", 0, "base") == "infeasible"


def test_capacity_validation_error_is_infeasible():
    res = {"status": "validation_error",
           "error_message": "resource 0: sum of minimums 5 exceeds capacity 3"}
    assert rd.validate_solution(res, 2, 2, 0, "reoptimize", 0, "base") == "infeasible"


def test_solver_error_raises_informative():
    res = {"status": "solver_error", "error_type": "MissingDependency",
           "error_message": "cvxpy is not installed"}
    with pytest.raises(rd.SolverResultError) as exc:
        rd.validate_solution(res, 2, 2, 7, "leases", 3, "base")
    msg = str(exc.value)
    assert "seed=7" in msg and "policy=leases" in msg and "epoch=3" in msg
    assert "solver_error" in msg


def test_base_infeasibility_is_failclosed(monkeypatch):
    def infeasible(data):
        return {"status": "infeasible", "allocations": None,
                "error_type": None, "error_message": "infeasible"}
    row, rows = _run_policy(monkeypatch, infeasible, "reoptimize")
    assert row["base_infeasible_epochs"] == SMALL["epochs"]
    assert len(rows) == SMALL["epochs"]
    for r in rows:
        assert json.loads(r["discrete_alloc"]) == []
        assert json.loads(r["achieved_utils"]) == []


def test_floor_infeasibility_distinguished_from_base(monkeypatch):
    def floors_infeasible(data):
        if "utility_floors" in data and any(f is not None for f in data["utility_floors"]):
            return {"status": "infeasible", "allocations": None, "error_message": "floor"}
        return {"status": "optimal", "allocations": data["minimums"],
                "error_type": None, "error_message": None}
    row, rows = _run_policy(monkeypatch, floors_infeasible, "permanent_floors")
    assert row["infeasible_floor_epochs"] >= 1
    assert row["base_infeasible_epochs"] == 0


def test_nonoptimal_final_never_rounded(monkeypatch):
    calls = {"n": 0}
    orig = rd.capacity_preserving_round

    def spy(*a, **k):
        calls["n"] += 1
        return orig(*a, **k)
    monkeypatch.setattr(rd, "capacity_preserving_round", spy)

    def infeasible(data):
        return {"status": "infeasible", "allocations": None, "error_message": "x"}
    _run_policy(monkeypatch, infeasible, "reoptimize")
    assert calls["n"] == 0


def test_solver_error_in_simulation_raises(monkeypatch):
    def erroring(data):
        return {"status": "solver_error", "error_type": "MissingDependency",
                "error_message": "cvxpy is not installed", "allocations": None}
    with pytest.raises(rd.SolverResultError):
        _run_policy(monkeypatch, erroring, "reoptimize")


def test_smoke_seed0_reoptimize_runs_with_cvxpy():
    if not rd.joint_solver.CVXPY_AVAILABLE:
        pytest.skip("cvxpy required")
    cfg = rd.CFG["smoke"]
    seed = rd.derive_seed("dynamic_seed", 0)
    pool = rd.build_pool(seed, cfg)
    events, sh = rd.event_schedule(seed, cfg)
    rows = []
    row = rd.simulate_policy("reoptimize", pool, cfg, events, sh, seed, lambda r: rows.append(r))
    assert len(rows) == cfg["epochs"]
    assert row["base_infeasible_epochs"] == 0


def _shortfall_ctx():
    cfg = SMALL
    seed = rd.derive_seed("dynamic_seed", 0)
    pool = rd.build_pool(seed, cfg)
    profiles = {i: dict(pool[i]["profile"], priority=pool[i]["priority"])
                for i in range(cfg["n_pool"])}
    active = list(range(cfg["n_base"]))
    caps = rd.base_capacities(pool, active)
    promised = {0: 1.0}
    return active, caps, profiles, promised, seed


def _bisect(monkeypatch, solver_fn):
    monkeypatch.setattr(rd.joint_solver, "solve_joint_allocation", solver_fn)
    active, caps, profiles, promised, seed = _shortfall_ctx()
    return rd.proportional_shortfall(active, caps, profiles, promised, seed,
                                     "leases_shortfall", 0, len(active), len(rd.RESOURCES))


def test_bisection_feasible_raises_lower_bound(monkeypatch):
    def always_feasible(data):
        return {"status": "optimal", "allocations": data["minimums"],
                "error_type": None, "error_message": None}
    assert _bisect(monkeypatch, always_feasible) > 0.99


def test_bisection_infeasible_lowers_upper_bound(monkeypatch):
    def feasible_below_quarter(data):
        floors = data.get("utility_floors") or []
        if any(f is not None and f > 0.25 for f in floors):
            return {"status": "infeasible", "allocations": None,
                    "error_type": None, "error_message": "floors infeasible"}
        return {"status": "optimal", "allocations": data["minimums"],
                "error_type": None, "error_message": None}
    scale = _bisect(monkeypatch, feasible_below_quarter)
    assert 0.24 < scale <= 0.2500001


def test_bisection_solver_error_raises(monkeypatch):
    def erroring(data):
        return {"status": "solver_error", "allocations": None,
                "error_type": "SolverError", "error_message": "boom"}
    with pytest.raises(rd.SolverResultError) as exc:
        _bisect(monkeypatch, erroring)
    assert "shortfall_bisect_0" in str(exc.value)


def test_bisection_unbounded_raises(monkeypatch):
    def unbounded(data):
        return {"status": "unbounded", "allocations": None,
                "error_type": None, "error_message": "unbounded"}
    with pytest.raises(rd.SolverResultError):
        _bisect(monkeypatch, unbounded)


def test_bisection_optimal_null_allocations_raises(monkeypatch):
    def null_alloc(data):
        return {"status": "optimal", "allocations": None,
                "error_type": None, "error_message": None}
    with pytest.raises(rd.SolverResultError):
        _bisect(monkeypatch, null_alloc)


def test_bisection_optimal_malformed_allocations_raises(monkeypatch):
    def malformed(data):
        return {"status": "optimal", "allocations": [[1.0]],
                "error_type": None, "error_message": None}
    with pytest.raises(rd.SolverResultError):
        _bisect(monkeypatch, malformed)


def test_smoke_seed0_leases_shortfall_runs_with_cvxpy():
    if not rd.joint_solver.CVXPY_AVAILABLE:
        pytest.skip("cvxpy required")
    cfg = rd.CFG["smoke"]
    seed = rd.derive_seed("dynamic_seed", 0)
    pool = rd.build_pool(seed, cfg)
    events, sh = rd.event_schedule(seed, cfg)
    rows = []
    row = rd.simulate_policy("leases_shortfall", pool, cfg, events, sh, seed,
                             lambda r: rows.append(r))
    assert len(rows) == cfg["epochs"]
    assert row["base_infeasible_epochs"] == 0
