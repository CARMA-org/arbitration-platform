#!/usr/bin/env python3
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
sys.path.insert(0, HERE)

import joint_solver
from lib.capacity_rounding import capacity_preserving_round

TOOL = "org.carma.arbitration.experiment.DecomposedCobbDouglasTool"
N_CASES = 600
SEED = 7788
FEAS_TOL = 1e-4
BOUND_PROCEDURE = ("per case: n in [2,6], m in [2,4]; each agent row beta_i ~ Dirichlet(1) "
                   "over resources (all positive, sums to 1); priority_i ~ U[1,5]; "
                   "lower_ij ~ int U[0,3]; upper_ij = lower_ij + int U[1,12]; capacity_j = int "
                   "between sum(lower_j) and sum(upper_j) so box and capacity constraints bind")


def classpath():
    with open(os.path.join(ROOT, "cp.txt")) as f:
        deps = f.read().strip()
    return os.path.join(ROOT, "target", "classes") + os.pathsep + deps


def run_tool(cases):
    payload = "\n".join(json.dumps(c) for c in cases) + "\n"
    proc = subprocess.run(["java", "-cp", classpath(), TOOL], input=payload,
                          capture_output=True, text=True, cwd=ROOT)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit("decomposition tool failed")
    lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
    return [json.loads(ln) for ln in lines]


def generate(rng):
    n = int(rng.integers(2, 7))
    m = int(rng.integers(2, 5))
    beta = rng.dirichlet(np.ones(m), size=n)
    priority = rng.uniform(1.0, 5.0, n)
    lower = rng.integers(0, 4, size=(n, m))
    upper = lower + rng.integers(1, 13, size=(n, m))
    caps = []
    for j in range(m):
        lo, hi = int(lower[:, j].sum()), int(upper[:, j].sum())
        caps.append(int(rng.integers(lo, hi + 1)))
    return n, m, beta, priority, lower, upper, np.array(caps)


def joint_solve(n, m, beta, priority, lower, upper, caps):
    data = {"n_agents": n, "n_resources": m, "preferences": beta.tolist(),
            "priority_weights": priority.tolist(), "capacities": caps.tolist(),
            "minimums": lower.tolist(), "ideals": upper.tolist(),
            "utility_configs": [{"type": "COBB_DOUGLAS"}] * n}
    res = joint_solver.solve_joint_allocation(data)
    if res["status"] not in ("optimal", "optimal_inaccurate"):
        return None, res["status"]
    return np.array(res["allocations"]), res["status"]


def joint_feasible(jc, lower, upper, caps):
    if np.any(jc < lower - FEAS_TOL) or np.any(jc > upper + FEAS_TOL):
        return False
    return bool(np.all(jc.sum(axis=0) <= caps + FEAS_TOL))


def main():
    rng = np.random.default_rng(SEED)
    instances = [generate(rng) for _ in range(N_CASES)]
    tool_cases = [{"W": b.tolist(), "lower": lo.tolist(), "upper": up.tolist(),
                   "priority": p.tolist(), "capacities": cap.tolist()}
                  for (n, m, b, p, lo, up, cap) in instances]
    tool_out = run_tool(tool_cases)

    n_optimal = n_inaccurate = n_failed = n_joint_infeasible = 0
    max_abs_cont = 0.0
    max_rel = 0.0
    clean_compared = 0
    total_int_cells = 0
    int_diff_cells = 0
    for (n, m, beta, priority, lower, upper, caps), tout in zip(instances, tool_out):
        jc, status = joint_solve(n, m, beta, priority, lower, upper, caps)
        if jc is None:
            n_failed += 1
            continue
        feas = joint_feasible(jc, lower, upper, caps)
        if not feas:
            n_joint_infeasible += 1
        if status == "optimal":
            n_optimal += 1
        else:
            n_inaccurate += 1

        dec_round = np.array(tout["rounded"])
        joint_round = np.array(capacity_preserving_round(
            [[float(v) for v in row] for row in np.clip(jc, lower, upper)],
            lower.tolist(), upper.tolist(), caps.tolist()))
        total_int_cells += n * m
        int_diff_cells += int(np.sum(dec_round != joint_round))

        if status == "optimal" and feas:
            dc = np.array(tout["continuous"])
            diff = np.abs(dc - jc)
            max_abs_cont = max(max_abs_cont, float(diff.max()))
            denom = np.maximum(np.abs(jc), 1.0)
            max_rel = max(max_rel, float((diff / denom).max()))
            clean_compared += 1

    out = {
        "n_cases": N_CASES,
        "seed": SEED,
        "bound_generation": BOUND_PROCEDURE,
        "joint_solver_status_counts": {
            "optimal": n_optimal, "optimal_inaccurate": n_inaccurate, "failed": n_failed},
        "joint_returned_infeasible_continuous": n_joint_infeasible,
        "continuous_comparison": {
            "restricted_to": "joint solves with status optimal and feasible continuous output",
            "n_compared": clean_compared,
            "max_abs_continuous_diff": max_abs_cont,
            "relative_error_definition": "max over cells of |decomposed - joint| / max(|joint|, 1)",
            "max_relative_error": max_rel,
        },
        "installed_integer_comparison": {
            "note": ("both continuous solutions clipped to bounds and rounded with the shared "
                     "capacity-preserving rule, as the platform installs them"),
            "agent_resource_cells": total_int_cells,
            "integer_diff_cells": int_diff_cells,
            "integer_diff_share": (int_diff_cells / total_int_cells) if total_int_cells else 0.0,
        },
    }
    with open(os.path.join(HERE, "results", "decomposition_validation.json"), "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
