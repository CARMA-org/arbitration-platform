import argparse
import json
import os

from lib import experiment, aggregate
from lib.generators import gen_dirichlet, hill_breadth, cosine_dissimilarity
from lib.seeds import seed_split
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
N_AGENTS, N_RES = 6, 3
CAP, LB, UB = 100.0, 1.0, 100.0
ALPHAS = [0.1, 0.3, 1.0, 3.0, 10.0]


def make_cells(n_train, n_test):
    cells = []
    for alpha in ALPHAS:
        train, test = seed_split(f"exp1_alpha{alpha}", n_train, n_test)
        cells.append({"label": f"alpha={alpha}", "alpha": alpha,
                      "train_seeds": train, "test_seeds": test})
    return cells


def instance_factory(cell, seed):
    rng = np.random.default_rng(seed)
    inst = gen_dirichlet(rng, N_AGENTS, N_RES, cell["alpha"], CAP, LB, UB)
    meta = {"achieved_breadth": float(np.mean([hill_breadth(inst.W[i]) for i in range(inst.n)])),
            "cosine_dissimilarity": cosine_dissimilarity(inst.W)}
    return inst, [{"type": "LINEAR"}] * N_AGENTS, meta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    n_train, n_test = (20, 20) if args.smoke else (30, 100)

    cells = make_cells(n_train, n_test)
    tuned_gamma, mean_regret, n_tune = experiment.tune_global_gamma(cells, instance_factory)

    tag = "smoke" if args.smoke else "full"
    raw_csv, _ = experiment.evaluate(
        "experiment1", cells, instance_factory, tuned_gamma, f"experiment1_{tag}.csv")

    agg = aggregate.aggregate(raw_csv)
    table_path = os.path.join(HERE, "tables", f"experiment1_{tag}.csv")
    agg.to_csv(table_path, index=False)

    summary = {
        "experiment": "experiment1", "tag": tag,
        "n_train_per_cell": n_train, "n_test_per_cell": n_test,
        "tuned_gamma": tuned_gamma,
        "mean_regret_by_gamma": {str(k): v for k, v in mean_regret.items()},
        "raw_csv": os.path.relpath(raw_csv, HERE),
        "table_csv": os.path.relpath(table_path, HERE),
    }
    with open(os.path.join(HERE, "results", f"experiment1_{tag}_summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
