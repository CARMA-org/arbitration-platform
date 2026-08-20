#!/usr/bin/env python3
"""Generate the machine-readable headline results file from the raw per-run and
per-agent CSVs. All paper-facing numbers derive from this file."""
import csv
import json
import os
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name):
    with open(os.path.join(HERE, "results", "raw", name)) as f:
        return list(csv.DictReader(f))


def boot_ci(diff, n_boot=2000, seed=12345):
    diff = np.asarray(diff, float)
    if len(diff) == 0:
        return {"mean": float("nan"), "ci_lo": float("nan"), "ci_hi": float("nan"), "n": 0}
    rng = np.random.default_rng(seed)
    means = np.array([diff[rng.integers(0, len(diff), len(diff))].mean() for _ in range(n_boot)])
    return {"mean": float(diff.mean()), "ci_lo": float(np.percentile(means, 2.5)),
            "ci_hi": float(np.percentile(means, 97.5)), "n": int(len(diff))}


def main():
    runs = load("runs.csv")
    agents = load("agents.csv")
    with open(os.path.join(HERE, "config", "experiment.json")) as f:
        cfg = json.load(f)
    policies = cfg["policies"]
    ref = cfg.get("joint_reference", "joint_linear")
    joints = [p for p in policies if p.startswith("joint")]
    nonlinear = [p for p in joints if p != ref]

    cells = sorted({r["cell"] for r in runs})
    seeds_by_cell = defaultdict(set)
    idx = {}
    for r in runs:
        seeds_by_cell[r["cell"]].add(r["seed"])
        idx[(r["cell"], r["seed"], r["policy"])] = r

    overall = {}
    util = {}
    consume = {}
    lat = defaultdict(list)
    by_pol = defaultdict(list)
    for r in runs:
        by_pol[r["policy"]].append(r)
    for p in policies:
        rows = by_pol.get(p, [])
        overall[p] = float(np.mean([float(x["completion_mean"]) for x in rows])) if rows else None
        util[p] = float(np.mean([float(x["capacity_utilization"]) for x in rows])) if rows else None
        consume[p] = float(np.mean([float(x["allocation_consumption"]) for x in rows])) if rows else None
    all_lat = [float(r["alloc_latency_ms"]) for r in runs]

    def paired(treat, base, metric="completion_mean"):
        d = []
        for c in cells:
            for s in sorted(seeds_by_cell[c]):
                t = idx.get((c, s, treat))
                b = idx.get((c, s, base))
                if t and b:
                    d.append(float(t[metric]) - float(b[metric]))
        return boot_ci(d)

    comparisons = {}
    for jp in joints:
        for base in ["equal", "drf"]:
            comparisons["%s_minus_%s" % (jp, base)] = paired(jp, base)
    for jp in nonlinear:
        comparisons["%s_minus_joint_linear" % jp] = paired(jp, ref)

    decomposed_vs_joint = {}
    max_abs = 0.0
    for c in cells:
        for s in sorted(seeds_by_cell[c]):
            t = idx.get((c, s, "decomposed_cobb_douglas"))
            b = idx.get((c, s, "joint_cobb_douglas"))
            if t and b:
                max_abs = max(max_abs, abs(float(t["completion_mean"]) - float(b["completion_mean"])))
    decomposed_vs_joint["max_abs_completion_diff"] = max_abs
    decomposed_vs_joint["paired_completion"] = paired("decomposed_cobb_douglas", "joint_cobb_douglas")

    homogeneous_cells = [c for c in cells if c.startswith("homogeneous")]
    homo_spread = 0.0
    for c in homogeneous_cells:
        vals = [overall_cell(runs, c, p) for p in policies]
        vals = [v for v in vals if v is not None]
        if vals:
            homo_spread = max(homo_spread, max(vals) - min(vals))

    ag_idx = {}
    for a in agents:
        ag_idx[(a["cell"], a["seed"], a["agent"], a["policy"])] = a
    keys = sorted({(a["cell"], a["seed"], a["agent"]) for a in agents})
    harm = {}
    for p in joints + ["drf"]:
        losses = []
        for (c, s, agent) in keys:
            pr = ag_idx.get((c, s, agent, p))
            e = ag_idx.get((c, s, agent, "equal"))
            if pr and e:
                losses.append(float(pr["completion"]) - float(e["completion"]))
        arr = np.array(losses) if losses else np.array([0.0])
        harm[p] = {"mean_change_vs_equal": float(arr.mean()),
                   "worst_loss_vs_equal": float(arr.min()),
                   "frac_worse": float((arr < -1e-9).mean()), "n": int(len(losses))}

    headline = {
        "n_runs": len(runs),
        "n_agent_records": len(agents),
        "policies": policies,
        "cells": cells,
        "overall_completion_by_policy": overall,
        "capacity_utilization_by_policy": util,
        "allocation_consumption_by_policy": consume,
        "paired_completion_diffs": comparisons,
        "decomposed_vs_joint_cobb_douglas": decomposed_vs_joint,
        "homogeneous_null_max_spread": homo_spread,
        "individual_harm_vs_equal": harm,
        "allocation_latency_ms": {"median": float(np.median(all_lat)),
                                  "p95": float(np.percentile(all_lat, 95)),
                                  "max": float(np.max(all_lat))},
        "capacity_violations": sum(int(r["capacity_violation"]) for r in runs),
        "bound_violations": sum(int(r["bound_violation"]) for r in runs),
    }
    out = os.path.join(HERE, "results", "headline.json")
    with open(out, "w") as f:
        json.dump(headline, f, indent=2)
    print(json.dumps({"overall": {k: round(v, 3) if v is not None else None
                                  for k, v in overall.items()},
                      "homogeneous_null_max_spread": round(homo_spread, 4),
                      "decomposed_vs_joint_cd_max_abs": round(max_abs, 4)}, indent=2))


def overall_cell(runs, cell, policy):
    vals = [float(r["completion_mean"]) for r in runs if r["cell"] == cell and r["policy"] == policy]
    return float(np.mean(vals)) if vals else None


if __name__ == "__main__":
    main()
