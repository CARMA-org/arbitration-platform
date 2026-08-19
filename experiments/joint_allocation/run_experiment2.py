import argparse
import json
import os
import numpy as np

from lib import experiment, aggregate
from lib.generators import gen_breadth_controlled
from lib.seeds import seed_split

HERE = os.path.dirname(os.path.abspath(__file__))
N_AGENTS, N_RES = 8, 4
CAP, LB, UB = 100.0, 1.0, 100.0
BREADTHS = [1.3, 2.0, 3.0, 3.8]
LAMBDAS = [0.0, 0.25, 0.5, 0.75, 1.0]


def make_cells(n_train, n_test):
    cells = []
    for B in BREADTHS:
        for lam in LAMBDAS:
            train, test = seed_split(f"exp2_B{B}_lam{lam}", n_train, n_test)
            cells.append({"label": f"B={B},lam={lam}", "B": B, "lam": lam,
                          "train_seeds": train, "test_seeds": test})
    return cells


def instance_factory(cell, seed):
    rng = np.random.default_rng(seed)
    inst, achieved_B, dissim = gen_breadth_controlled(
        rng, N_AGENTS, N_RES, cell["B"], cell["lam"], CAP, LB, UB)
    return (inst, [{"type": "LINEAR"}] * N_AGENTS,
            {"achieved_breadth": achieved_B, "cosine_dissimilarity": dissim})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    n_train, n_test = (10, 20) if args.smoke else (40, 100)

    cells = make_cells(n_train, n_test)
    tuned_gamma, mean_regret, n_tune = experiment.tune_global_gamma(cells, instance_factory)

    tag = "smoke" if args.smoke else "full"
    raw_csv, _ = experiment.evaluate(
        "experiment2", cells, instance_factory, tuned_gamma, f"experiment2_{tag}.csv")

    agg = aggregate.aggregate(raw_csv)
    table_path = os.path.join(HERE, "tables", f"experiment2_{tag}.csv")
    agg.to_csv(table_path, index=False)

    summary = {
        "experiment": "experiment2", "tag": tag,
        "n_train_per_cell": n_train, "n_test_per_cell": n_test,
        "breadths": BREADTHS, "lambdas": LAMBDAS,
        "tuned_gamma": tuned_gamma,
        "mean_regret_by_gamma": {str(k): v for k, v in mean_regret.items()},
        "raw_csv": os.path.relpath(raw_csv, HERE),
        "table_csv": os.path.relpath(table_path, HERE),
    }
    with open(os.path.join(HERE, "results", f"experiment2_{tag}_summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
