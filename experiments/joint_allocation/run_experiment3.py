import argparse
import json
import os
import numpy as np

from lib import experiment, aggregate, rules, metrics
from lib.generators import gen_breadth_controlled
from lib.seeds import seed_split

HERE = os.path.dirname(os.path.abspath(__file__))
N_AGENTS, N_RES = 8, 4
CAP, LB, UB = 100.0, 1.0, 100.0
BREADTHS = [1.5, 3.0, 3.8]
LAMBDAS = [0.25, 0.75, 1.0]

FAMILIES = {
    "COBB_DOUGLAS": {"type": "COBB_DOUGLAS"},
    "CES_0.5": {"type": "CES", "rho": 0.5},
    "LINEAR": {"type": "LINEAR"},
    "LEONTIEF": {"type": "LEONTIEF"},
}

OMITTED = {
    "CES_-1": "CES rho=-1 is supported and validated on small instances "
              "(tests/python/test_solver.py matches multi-start SciPy), but at the "
              "8-agent 4-resource scale its pnorm-over-exponential-cone formulation "
              "solves only to optimal_inaccurate and occasionally returns points worse "
              "than the separable comparator, so objective regret is not reliable. "
              "Omitted from the sweep rather than reported from an unreliable solve.",
}


def make_cells(family, n_train, n_test):
    cells = []
    for B in BREADTHS:
        for lam in LAMBDAS:
            train, test = seed_split(f"exp3_{family}_B{B}_lam{lam}", n_train, n_test)
            cells.append({"label": f"B={B},lam={lam}", "B": B, "lam": lam,
                          "train_seeds": train, "test_seeds": test})
    return cells


def make_instance(cell, seed):
    rng = np.random.default_rng(seed)
    inst, achieved_B, dissim = gen_breadth_controlled(
        rng, N_AGENTS, N_RES, cell["B"], cell["lam"], CAP, LB, UB)
    return inst, {"achieved_breadth": float(achieved_B), "cosine_dissimilarity": float(dissim)}


def instance_factory(family, cell, seed):
    inst, meta = make_instance(cell, seed)
    return inst, cfgs_for(family, inst), meta


def cfgs_for(family, inst):
    base = FAMILIES[family]
    if family == "LEONTIEF":
        # Explicit, documented parameterization: requirement vector = preference
        # weights (all strictly positive here), so the balanced bundle is
        # proportional to preferences. Not inferred inside the solver.
        return [{"type": "LEONTIEF", "requirements": np.maximum(inst.W[i], 1e-6).tolist()}
                for i in range(inst.n)]
    return [dict(base)] * inst.n


def _solve_resource_subproblem(weights, lower, upper, cap):
    import cvxpy as cp
    n = len(weights)
    a = cp.Variable(n, nonneg=True)
    obj = cp.Maximize(cp.sum(cp.multiply(weights, cp.log(a))))
    cons = [cp.sum(a) <= cap, a >= lower, a <= upper]
    prob = cp.Problem(obj, cons)
    prob.solve(solver=cp.CLARABEL)
    return np.maximum(a.value, 0.0)


def verify_cobb_douglas_separability(cells):
    """Joint Cobb-Douglas control adds no allocative value: its weighted-log
    welfare equals the stack of per-resource independently solved weighted-log
    subproblems (weights c_i * beta_ij). Allocations can differ only in
    near-degenerate columns where every weight is ~0 (indeterminate but
    welfare-irrelevant), so allocation diff is reported for weighted columns."""
    welfare_diff = 0.0
    alloc_diff_weighted = 0.0
    checked = 0
    for cell in cells[:3]:
        for seed in cell["test_seeds"][:10]:
            inst, _ = make_instance(cell, seed)
            cfgs = cfgs_for("COBB_DOUGLAS", inst)
            A_joint, res = rules.joint_nash(inst, cfgs)
            if A_joint is None:
                continue
            sep = np.zeros_like(A_joint)
            for j in range(inst.m):
                w = inst.c * inst.W[:, j]
                sep[:, j] = _solve_resource_subproblem(
                    w, inst.mins[:, j], inst.ideals[:, j], inst.Q[j])
                if inst.W[:, j].max() > 1e-3:
                    alloc_diff_weighted = max(
                        alloc_diff_weighted, float(np.max(np.abs(A_joint[:, j] - sep[:, j]))))
            wj = metrics.weighted_log_objective(inst, cfgs, A_joint)
            ws = metrics.weighted_log_objective(inst, cfgs, sep)
            welfare_diff = max(welfare_diff, abs(wj - ws))
            checked += 1
    return {"n_checked": checked,
            "welfare_abs_diff": welfare_diff,
            "alloc_max_diff_weighted_columns": alloc_diff_weighted}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    n_train, n_test = (8, 15) if args.smoke else (20, 50)
    tag = "smoke" if args.smoke else "full"

    per_family = {}
    for family in FAMILIES:
        cells = make_cells(family, n_train, n_test)

        def inst_factory(cell, seed, _f=family):
            return instance_factory(_f, cell, seed)

        tuned_gamma, mean_regret, _ = experiment.tune_global_gamma(cells, inst_factory)
        raw_csv, _ = experiment.evaluate(
            f"experiment3_{family}", cells, inst_factory,
            tuned_gamma, f"experiment3_{family}_{tag}.csv")
        per_family[family] = {"tuned_gamma": tuned_gamma,
                              "mean_regret_by_gamma": {str(k): v for k, v in mean_regret.items()},
                              "raw_csv": os.path.relpath(raw_csv, HERE)}
        agg = aggregate.aggregate(raw_csv)
        agg.to_csv(os.path.join(HERE, "tables", f"experiment3_{family}_{tag}.csv"), index=False)

    cd_cells = make_cells("COBB_DOUGLAS", n_train, n_test)
    cd_diff = verify_cobb_douglas_separability(cd_cells)

    summary = {"experiment": "experiment3", "tag": tag,
               "n_train_per_cell": n_train, "n_test_per_cell": n_test,
               "families": list(FAMILIES), "per_family": per_family,
               "omitted_families": OMITTED,
               "cobb_douglas_joint_vs_separable": cd_diff}
    with open(os.path.join(HERE, "results", f"experiment3_{tag}_summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
