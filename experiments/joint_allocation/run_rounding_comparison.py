import json
import os
import numpy as np

from lib import generators, rounding, solver
from lib.seeds import derive_seed

HERE = os.path.dirname(os.path.abspath(__file__))
N_AGENTS, N_RES = 6, 3
CAP, LB, UB = 100.0, 1.0, 100.0
ALPHAS = [0.1, 0.3, 1.0, 3.0, 10.0]
PER_ALPHA = 200
LINEAR = [{"type": "LINEAR"}] * N_AGENTS


def linear_utils(W, A):
    return np.array([float(np.sum(W[i] * A[i])) for i in range(len(A))])


def welfare(W, A, c):
    u = linear_utils(W, A)
    return float(np.sum(c * np.log(np.maximum(u, 1e-9))))


def geomean_ratio(u_round, u_cont):
    r = np.maximum(u_round, 1e-9) / np.maximum(u_cont, 1e-9)
    return float(np.exp(np.mean(np.log(r))))


def run():
    stats = {rule: {"cap_violations": 0, "max_excess": 0.0, "bound_violations": 0,
                    "d_welfare": [], "geomean_loss": [], "worst_indiv_change": []}
             for rule in ("naive", "largest_remainder")}
    n_instances = 0
    for a_idx, alpha in enumerate(ALPHAS):
        for k in range(PER_ALPHA):
            rng = np.random.default_rng(derive_seed("rounding", alpha, k))
            inst = generators.gen_dirichlet(rng, N_AGENTS, N_RES, alpha, CAP, LB, UB)
            res = solver.solve(inst, LINEAR)
            if res["status"] not in ("optimal", "optimal_inaccurate"):
                continue
            n_instances += 1
            A = np.asarray(res["allocations"], dtype=float)
            u_cont = linear_utils(inst.W, A)
            w_cont = welfare(inst.W, A, inst.c)
            lower = inst.mins.astype(np.int64)
            upper = inst.ideals.astype(np.int64)
            cap = inst.Q.astype(np.int64)

            naive = rounding.naive_cellwise_round(A)
            lr = rounding.largest_remainder_round(A, lower, upper, cap)

            for rule, R in (("naive", naive), ("largest_remainder", lr)):
                colsum = R.sum(axis=0)
                excess = colsum - cap
                viol = int(np.sum(excess > 0))
                if viol:
                    stats[rule]["cap_violations"] += viol
                    stats[rule]["max_excess"] = max(stats[rule]["max_excess"], float(excess.max()))
                bviol = int(np.sum(R < lower) + np.sum(R > upper))
                stats[rule]["bound_violations"] += bviol
                u_r = linear_utils(inst.W, R.astype(float))
                stats[rule]["d_welfare"].append(welfare(inst.W, R.astype(float), inst.c) - w_cont)
                stats[rule]["geomean_loss"].append(1.0 - geomean_ratio(u_r, u_cont))
                rel = (u_r - u_cont) / np.maximum(u_cont, 1e-9)
                stats[rule]["worst_indiv_change"].append(float(rel.min()))

    out = {"n_instances": n_instances, "config": {
        "n_agents": N_AGENTS, "n_resources": N_RES, "cap": CAP, "lb": LB, "ub": UB,
        "dirichlet_alphas": ALPHAS, "per_alpha": PER_ALPHA}}
    for rule, s in stats.items():
        out[rule] = {
            "capacity_violations": s["cap_violations"],
            "max_excess": s["max_excess"],
            "bound_violations": s["bound_violations"],
            "median_change_weighted_log_welfare": float(np.median(s["d_welfare"])),
            "mean_change_weighted_log_welfare": float(np.mean(s["d_welfare"])),
            "median_geomean_utility_loss": float(np.median(s["geomean_loss"])),
            "worst_individual_utility_change": float(np.min(s["worst_indiv_change"])),
        }
    with open(os.path.join(HERE, "results", "rounding_comparison.json"), "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    run()
