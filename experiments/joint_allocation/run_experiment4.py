import argparse
import json
import os
import numpy as np

from lib import experiment, aggregate
from lib.generators import gen_breadth_controlled
from lib.seeds import seed_split, derive_seed

HERE = os.path.dirname(os.path.abspath(__file__))
N_AGENTS, N_RES = 8, 4
CAP = 100.0
B_REP, LAM_REP = 3.0, 0.5
H_OVER_Q = [1.0, 0.5, 0.25]
L_OVER_Q = [0.0, 0.01, 0.05]
PRIOR_S = [0.0, 0.5, 1.0]


def lognormal_priorities(seed, n, s):
    if s <= 0:
        return np.ones(n)
    rng = np.random.default_rng(derive_seed("exp4_prior", seed, s))
    z = rng.standard_normal(n)
    return np.exp(s * z - 0.5 * s * s)


def bounds_instance(cell, seed):
    rng = np.random.default_rng(seed)
    lb = cell["l_over_q"] * CAP
    ub = cell["h_over_q"] * CAP
    inst, B, dis = gen_breadth_controlled(rng, N_AGENTS, N_RES, B_REP, LAM_REP,
                                          CAP, max(lb, 1e-6), ub)
    inst.mins[:] = lb
    return inst, [{"type": "LINEAR"}] * N_AGENTS, {"achieved_breadth": B, "cosine_dissimilarity": dis}


def prior_instance(cell, seed):
    rng = np.random.default_rng(seed)
    inst, B, dis = gen_breadth_controlled(rng, N_AGENTS, N_RES, B_REP, LAM_REP, CAP, 1.0, 100.0)
    inst.c = lognormal_priorities(seed, N_AGENTS, cell["s"])
    return inst, [{"type": "LINEAR"}] * N_AGENTS, {"achieved_breadth": B, "cosine_dissimilarity": dis}


def run_sub(name, cells, factory, n_train, n_test, tag):
    tuned_gamma, mean_regret, _ = experiment.tune_global_gamma(cells, factory)
    raw_csv, _ = experiment.evaluate(name, cells, factory, tuned_gamma, f"{name}_{tag}.csv")
    agg = aggregate.aggregate(raw_csv)
    agg.to_csv(os.path.join(HERE, "tables", f"{name}_{tag}.csv"), index=False)
    return {"tuned_gamma": tuned_gamma,
            "mean_regret_by_gamma": {str(k): v for k, v in mean_regret.items()},
            "raw_csv": os.path.relpath(raw_csv, HERE)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    n_train, n_test = (8, 15) if args.smoke else (20, 60)
    tag = "smoke" if args.smoke else "full"

    bounds_cells = []
    for h in H_OVER_Q:
        for l in L_OVER_Q:
            if l * N_AGENTS >= h or l >= h:
                continue
            train, test = seed_split(f"exp4_bounds_h{h}_l{l}", n_train, n_test)
            bounds_cells.append({"label": f"h/Q={h},l/Q={l}", "h_over_q": h, "l_over_q": l,
                                 "train_seeds": train, "test_seeds": test})

    prior_cells = []
    for s in PRIOR_S:
        train, test = seed_split(f"exp4_prior_s{s}", n_train, n_test)
        prior_cells.append({"label": f"s={s}", "s": s,
                            "train_seeds": train, "test_seeds": test})

    summary = {"experiment": "experiment4", "tag": tag,
               "n_train_per_cell": n_train, "n_test_per_cell": n_test,
               "representative_cell": {"breadth": B_REP, "lambda": LAM_REP},
               "bounds": run_sub("experiment4_bounds", bounds_cells, bounds_instance, n_train, n_test, tag),
               "priorities": run_sub("experiment4_priorities", prior_cells, prior_instance, n_train, n_test, tag)}
    with open(os.path.join(HERE, "results", f"experiment4_{tag}_summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
