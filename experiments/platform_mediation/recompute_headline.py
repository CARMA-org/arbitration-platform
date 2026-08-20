#!/usr/bin/env python3
"""Independent recomputation of headline platform-mediation statistics from the
raw per-run CSV, used to cross-check run_sweep aggregation."""
import csv
import json
import os
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
POLICIES = ["equal", "drf", "separable", "joint_linear",
            "joint_cobb_douglas", "joint_ces", "joint_leontief"]


def load():
    with open(os.path.join(HERE, "results", "raw", "runs.csv")) as f:
        return list(csv.DictReader(f))


def boot_ci(diff, n_boot=2000, seed=12345):
    diff = np.asarray(diff, float)
    if len(diff) == 0:
        return float("nan"), float("nan"), float("nan"), 0
    rng = np.random.default_rng(seed)
    means = np.array([diff[rng.integers(0, len(diff), len(diff))].mean() for _ in range(n_boot)])
    return float(diff.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)), len(diff)


def main():
    rows = load()
    by = defaultdict(list)
    idx = {}
    for r in rows:
        by[r["policy"]].append(float(r["completion_mean"]))
        idx[(r["cell"], r["seed"], r["policy"])] = r
    cells = sorted({r["cell"] for r in rows})
    seeds = defaultdict(set)
    for r in rows:
        seeds[r["cell"]].add(r["seed"])

    overall = {p: (float(np.mean(by[p])) if by[p] else None) for p in POLICIES}

    def paired(treat, base):
        d = []
        for c in cells:
            for s in seeds[c]:
                t = idx.get((c, s, treat))
                b = idx.get((c, s, base))
                if t and b:
                    d.append(float(t["completion_mean"]) - float(b["completion_mean"]))
        return boot_ci(d)

    comparisons = {}
    for jp in ["joint_linear", "joint_cobb_douglas", "joint_ces", "joint_leontief"]:
        for base in ["equal", "drf"]:
            m, lo, hi, n = paired(jp, base)
            comparisons["%s_minus_%s" % (jp, base)] = {"mean": m, "ci_lo": lo, "ci_hi": hi, "n": n}
    for jp in ["joint_cobb_douglas", "joint_ces", "joint_leontief"]:
        m, lo, hi, n = paired(jp, "joint_linear")
        comparisons["%s_minus_joint_linear" % jp] = {"mean": m, "ci_lo": lo, "ci_hi": hi, "n": n}

    out = {
        "n_runs": len(rows),
        "overall_completion_by_policy": overall,
        "paired_completion_diffs": comparisons,
    }
    print(json.dumps(out, indent=2))
    with open(os.path.join(HERE, "results", "recomputed_headline.json"), "w") as f:
        json.dump(out, f, indent=2)


if __name__ == "__main__":
    main()
