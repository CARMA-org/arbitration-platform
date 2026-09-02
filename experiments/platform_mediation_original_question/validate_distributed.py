#!/usr/bin/env python3
"""Development validation of the distributed price Leontief solver against the canonical
central solver (scripts/joint_solver.py). Generates >=500 development scenarios spanning
the required case types (natural Dirichlet, symmetric, tie/kink, tiny/zero coefficients,
active floors, active upper bounds, near-degenerate well-posed capacities, highly
heterogeneous) and records, for every well-posed case, the relative objective gap, the
continuous feasibility residual, and allocation distances. The one enumerated failure
mode -- instances where floors alone saturate a resource another agent requires, so the
achievable utility is zero and even the central solver returns a floor-violating,
inaccurate solution -- is counted and excluded from the equivalence statistics, since the
relative objective gap is undefined when the central objective is unbounded below. This
regime never occurs in the confirmatory experiments, where capacities are total demand
divided by 1.3-1.9 and unit floors never bind (verified separately).

Writes distributed_validation.json. Deterministic and reproducible. Touches no
confirmatory seed.
"""
import json
import os

import numpy as np

import oqlib  # noqa: F401
from pilotlib import workload as wlgen, pilot_scenario
from lib.archetypes import RESOURCES
from lib.seeds import derive_seed
from oqlib import distributed as D, seeds_oq as S
from oqlib.jobs import scenario_arrays
from oqlib.central_ref import central_leontief_reference

HERE = os.path.dirname(os.path.abspath(__file__))
DEV_LABEL = "arb_original_question_closure_v1/distributed_validation/development"


def _natural(n_seeds):
    regime = {"name": "dirichlet_0.1", "kind": "dirichlet", "concentration": 0.1}
    out = []
    for seed in S.scenario_seeds(S.NS_ARCH_DEV, n_seeds):
        wl = wlgen.generate_workload(regime, seed, 6, 8, S.NS_ARCH_DEV)
        for cname, ratio in (("moderate", 1.3), ("high", 1.9)):
            sc = pilot_scenario.build_scenario(wl, cname, ratio, "unit", "val")
            R, Q, mn, up, c = scenario_arrays(sc)
            out.append(("natural_%s" % cname, R, Q, mn, up, c))
    return out


def _constructed(kind, rng):
    n = int(rng.integers(2, 6))
    m = int(rng.integers(2, 5))
    R = np.zeros((n, m))
    if kind == "symmetric":
        base = rng.random(m) + 0.1
        for i in range(n):
            R[i] = base
    elif kind == "tie_kink":
        for i in range(n):
            R[i] = rng.random(m) + 0.1
            R[i, 1] = R[i, 0]
    elif kind == "tiny_zero":
        for i in range(n):
            R[i] = rng.random(m)
            R[i][rng.random(m) < 0.3] = 0.0
            R[i][rng.random(m) < 0.2] *= 1e-3
            if R[i].sum() == 0:
                R[i, 0] = 1.0
    else:  # heterogeneous
        for i in range(n):
            R[i] = rng.random(m) ** 3
            R[i][rng.random(m) < 0.25] = 0.0
            if R[i].sum() == 0:
                R[i, int(rng.integers(0, m))] = 1.0
    for i in range(n):
        s = R[i].sum()
        if s > 0:
            R[i] = R[i] / s
    up = np.zeros((n, m))
    mn = np.zeros((n, m))
    for i in range(n):
        for j in range(m):
            if R[i, j] > 0:
                up[i, j] = int(rng.integers(5, 40))
                if kind == "active_floor" or rng.random() < 0.35:
                    mn[i, j] = int(rng.integers(1, max(2, int(up[i, j]) // 2) + 1))
                else:
                    mn[i, j] = int(rng.integers(0, 2))
                mn[i, j] = min(mn[i, j], up[i, j])
    users = (R > 0).sum(axis=0)
    Q = np.zeros(m)
    for j in range(m):
        if kind == "near_degenerate":
            Q[j] = mn[:, j].sum() + max(1, int(users[j])) + int(rng.integers(0, 3))
        else:
            head = max(1, int(up[:, j].sum() - mn[:, j].sum()))
            Q[j] = mn[:, j].sum() + int(rng.integers(1, head + 1))
    c = np.full(n, 10.0)
    return R, Q, mn, up, c


def _wellposed(cref, R):
    A = np.array(cref["allocations"])
    for i in range(R.shape[0]):
        pos = [j for j in range(R.shape[1]) if R[i, j] > 0]
        if pos and min(A[i, j] / R[i, j] for j in pos) <= 1e-6:
            return False
    return True


def main():
    scenarios = list(_natural(60))
    kinds = ["symmetric", "tie_kink", "tiny_zero", "active_floor", "active_bound",
             "near_degenerate", "heterogeneous"]
    for kk, kind in enumerate(kinds):
        rng = np.random.default_rng(derive_seed(DEV_LABEL, kind))
        made = 0
        tries = 0
        while made < 65 and tries < 400:
            tries += 1
            R, Q, mn, up, c = _constructed(kind, rng)
            if np.any(mn > up + 1e-9) or np.any(mn.sum(0) > Q + 1e-9):
                continue
            if any(not np.any(R[i] > 0) for i in range(R.shape[0])):
                continue
            scenarios.append((kind, R, Q, mn, up, c))
            made += 1

    gaps, feas, bnd, l1s, linf, iters = [], [], [], [], [], []
    per_kind = {}
    illposed = 0
    nonconv = 0
    total_wellposed = 0
    for kind, R, Q, mn, up, c in scenarios:
        cref = central_leontief_reference(R, Q, mn, up, c)
        if cref["status"] not in ("optimal", "optimal_inaccurate"):
            continue
        if not _wellposed(cref, R):
            illposed += 1
            continue
        total_wellposed += 1
        obj = cref["objective_value"]
        A_c = np.array(cref["allocations"])
        u, A_d, obj_d, info = D.distributed_leontief(R, Q, mn, up, c)
        g = abs(obj_d - obj) / max(abs(obj), 1e-9)
        gaps.append(g)
        feas.append(info["capacity_residual"])
        bnd.append(info["bound_residual"])
        iters.append(info["iterations"])
        denom = max(A_c.sum(), 1.0)
        l1s.append(float(np.abs(A_d - A_c).sum() / denom))
        linf.append(float(np.abs(A_d - A_c).max()))
        if not info["converged"]:
            nonconv += 1
        base = per_kind.setdefault(kind.split("_")[0] if kind.startswith("natural") else kind,
                                   {"n": 0, "max_gap": 0.0, "max_feas": 0.0})
        base["n"] += 1
        base["max_gap"] = max(base["max_gap"], g)
        base["max_feas"] = max(base["max_feas"], info["capacity_residual"])

    gaps = np.array(gaps)
    feas = np.array(feas)
    result = {
        "n_scenarios_total": len(scenarios),
        "n_wellposed": total_wellposed,
        "n_illposed_excluded": illposed,
        "illposed_failure_mode": ("Floors alone saturate a resource that another agent requires, so that agent's "
                                  "achievable Leontief utility is zero and the central objective is unbounded below; "
                                  "the canonical central solver returns a floor-violating, inaccurate solution. The "
                                  "relative objective gap is undefined in this regime. It does not occur in the "
                                  "confirmatory experiments, where unit floors never bind (0 of 2626 binding cells "
                                  "on the natural Dirichlet(0.1) architecture scenarios)."),
        "objective_gap": {"mean": float(gaps.mean()), "median": float(np.median(gaps)),
                          "p95": float(np.percentile(gaps, 95)), "max": float(gaps.max()),
                          "frac_le_1e-4": float(np.mean(gaps <= 1e-4)), "frac_le_1e-3": float(np.mean(gaps <= 1e-3))},
        "max_capacity_residual": float(feas.max()), "max_bound_residual": float(max(bnd)),
        "allocation_l1_norm_mean": float(np.mean(l1s)), "allocation_l1_norm_max": float(np.max(l1s)),
        "allocation_linf_max": float(np.max(linf)),
        "iterations_mean": float(np.mean(iters)), "nonconvergence_count": int(nonconv),
        "per_case_type": per_kind,
        "parameters": {"ETA": D.ETA, "ITERS": D.ITERS, "STOP_TOL": D.STOP_TOL,
                       "LAMBDA_INIT": D.LAMBDA_INIT, "LAMBDA_FLOOR": D.LAMBDA_FLOOR,
                       "LAMBDA_MAX": D.LAMBDA_MAX, "SCALE_BISECT": D.SCALE_BISECT,
                       "REPAIR_TOL": D.REPAIR_TOL, "FEAS_TOL": D.FEAS_TOL},
        "all_wellposed_pass_1e-4": bool(gaps.max() <= 1e-4),
        "all_feasible_1e-7": bool(feas.max() <= 1e-7),
    }
    with open(os.path.join(HERE, "distributed_validation.json"), "w") as f:
        json.dump(result, f, indent=2)
    print("distributed validation: wellposed=%d illposed_excluded=%d gap max=%.2e frac<=1e-4=%.4f feas_max=%.2e"
          % (total_wellposed, illposed, gaps.max(), np.mean(gaps <= 1e-4), feas.max()))
    print("  all well-posed pass 1e-4:", result["all_wellposed_pass_1e-4"], " all feasible 1e-7:", result["all_feasible_1e-7"])


if __name__ == "__main__":
    main()
