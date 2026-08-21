#!/usr/bin/env python3
"""Generate the machine-readable headline results file from the raw per-run and
per-agent CSVs. All paper-facing numbers derive from this file."""
import csv
import json
import os
from collections import defaultdict

import numpy as np

from lib.analysis import cell_bootstrap, stratified_bootstrap

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name):
    with open(os.path.join(HERE, "results", "raw", name)) as f:
        return list(csv.DictReader(f))


def load_json(rel):
    path = os.path.join(HERE, "results", rel)
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def main():
    runs = load("runs.csv")
    agents = load("agents.csv")
    with open(os.path.join(HERE, "config", "experiment.json")) as f:
        cfg = json.load(f)
    policies = cfg["policies"]
    joints = cfg["joint_policies"]
    ref = cfg["reference_policy"]
    boot_seed = cfg["bootstrap_seed"]
    n_boot = 2000

    cells = sorted({r["cell"] for r in runs})
    mixed_cells = [c for c in cells if c.startswith("mixed")]
    homo_cells = [c for c in cells if c.startswith("homogeneous")]
    seeds_by_cell = defaultdict(list)
    seen = set()
    idx = {}
    for r in runs:
        idx[(r["cell"], r["seed"], r["policy"])] = r
        key = (r["cell"], r["seed"])
        if key not in seen:
            seen.add(key)
            seeds_by_cell[r["cell"]].append(r["seed"])

    def cell_policy_mean(cell, policy, metric):
        vals = [float(idx[(cell, s, policy)][metric]) for s in seeds_by_cell[cell]
                if (cell, s, policy) in idx]
        return float(np.mean(vals)) if vals else None

    per_cell_completion = {c: {p: cell_policy_mean(c, p, "completion_mean") for p in policies}
                           for c in cells}

    workload_variation = {}
    for c in mixed_cells:
        for p in policies:
            vals = [float(idx[(c, s, p)]["completion_mean"]) for s in seeds_by_cell[c]
                    if (c, s, p) in idx]
            workload_variation["%s|%s" % (c, p)] = {
                "distinct_values": len(set(vals)), "std": float(np.std(vals)), "n": len(vals)}
    min_distinct_mixed = min((v["distinct_values"] for v in workload_variation.values()), default=0)

    comparisons = [(jp, "equal") for jp in joints] + [(jp, "drf") for jp in joints]
    comparisons += [(jp, ref) for jp in joints if jp != ref]
    comparisons += [("decomposed_cobb_douglas", "joint_cobb_douglas")]

    per_cell_paired = {}
    mixed_aggregate = {}
    for treat, base in comparisons:
        name = "%s_minus_%s" % (treat, base)
        per_cell_paired[name] = {}
        for c in cells:
            diffs = [float(idx[(c, s, treat)]["completion_mean"]) - float(idx[(c, s, base)]["completion_mean"])
                     for s in seeds_by_cell[c]
                     if (c, s, treat) in idx and (c, s, base) in idx]
            per_cell_paired[name][c] = cell_bootstrap(diffs, "%s|%s" % (name, c), boot_seed, n_boot)
        mixed_diffs = []
        for c in mixed_cells:
            mixed_diffs.append([float(idx[(c, s, treat)]["completion_mean"]) - float(idx[(c, s, base)]["completion_mean"])
                                for s in seeds_by_cell[c]
                                if (c, s, treat) in idx and (c, s, base) in idx])
        mixed_aggregate[name] = stratified_bootstrap(mixed_diffs, "mixed|%s" % name, boot_seed, n_boot)

    homo_symmetry = {}
    max_spread = 0.0
    for c in homo_cells:
        vals = [per_cell_completion[c][p] for p in policies if per_cell_completion[c][p] is not None]
        spread = (max(vals) - min(vals)) if vals else 0.0
        homo_symmetry[c] = spread
        max_spread = max(max_spread, spread)

    ag_idx = {}
    for a in agents:
        ag_idx[(a["cell"], a["seed"], a["agent"], a["policy"])] = a
    ag_keys = {(a["cell"], a["seed"], a["agent"]) for a in agents}
    max_int_diff = 0
    diff_records = 0
    total_records = 0
    for (c, s, ag) in ag_keys:
        d = ag_idx.get((c, s, ag, "decomposed_cobb_douglas"))
        j = ag_idx.get((c, s, ag, "joint_cobb_douglas"))
        if d and j:
            total_records += 1
            da = json.loads(d["allocated"])
            ja = json.loads(j["allocated"])
            md = max(abs(da[r] - ja[r]) for r in da)
            if md > 0:
                diff_records += 1
            max_int_diff = max(max_int_diff, md)
    cd_completion = mixed_aggregate.get("decomposed_cobb_douglas_minus_joint_cobb_douglas", {})

    run_completion_diff = 0
    run_pairs = 0
    max_run_completion_diff = 0.0
    for c in cells:
        for s in seeds_by_cell[c]:
            d = idx.get((c, s, "decomposed_cobb_douglas"))
            j = idx.get((c, s, "joint_cobb_douglas"))
            if d and j:
                run_pairs += 1
                delta = abs(float(d["completion_mean"]) - float(j["completion_mean"]))
                if delta > 1e-12:
                    run_completion_diff += 1
                max_run_completion_diff = max(max_run_completion_diff, delta)

    validation = load_json("decomposition_validation.json")

    lat = defaultdict(list)
    for r in runs:
        lat[r["policy"]].append(float(r["alloc_latency_ms"]))
    latency_by_policy = {}
    for p in policies:
        v = np.asarray(lat.get(p, [0.0]))
        latency_by_policy[p] = {"n": int(len(lat.get(p, []))), "median": float(np.median(v)),
                                "p95": float(np.percentile(v, 95)), "max": float(np.max(v))}
    joint_lat = np.asarray([x for p in joints for x in lat.get(p, [])])
    joint_latency = {"n": int(len(joint_lat)), "median": float(np.median(joint_lat)),
                     "p95": float(np.percentile(joint_lat, 95)), "max": float(np.max(joint_lat))}

    harm = {}
    harm_keys = [(c, s, ag) for (c, s, ag) in ag_keys if c in mixed_cells]
    for p in joints + ["drf", "decomposed_cobb_douglas"]:
        losses = []
        for (c, s, ag) in harm_keys:
            pr = ag_idx.get((c, s, ag, p))
            e = ag_idx.get((c, s, ag, "equal"))
            if pr and e:
                losses.append(float(pr["completion"]) - float(e["completion"]))
        arr = np.array(losses) if losses else np.array([0.0])
        harm[p] = {"mean_change_vs_equal": float(arr.mean()), "worst_loss_vs_equal": float(arr.min()),
                   "frac_worse": float((arr < -1e-9).mean()), "n": int(len(losses))}

    util = {p: float(np.mean([float(r["capacity_utilization"]) for r in runs if r["policy"] == p]))
            for p in policies}
    cons = {p: float(np.mean([float(r["allocation_consumption"]) for r in runs if r["policy"] == p]))
            for p in policies}

    summary = load_json("summary.json") or {}

    headline = {
        "n_runs": len(runs),
        "n_agent_records": len(agents),
        "policies": policies,
        "cells": cells,
        "mixed_cells": mixed_cells,
        "homogeneous_cells": homo_cells,
        "bootstrap_seed": boot_seed,
        "n_bootstrap": n_boot,
        "per_cell_completion": per_cell_completion,
        "workload_variation_mixed": workload_variation,
        "min_distinct_completion_mixed": min_distinct_mixed,
        "per_cell_paired_completion": per_cell_paired,
        "mixed_aggregate_completion": mixed_aggregate,
        "homogeneous_symmetry_max_spread": max_spread,
        "homogeneous_symmetry_by_cell": homo_symmetry,
        "cobb_douglas_decomposition": {
            "measured_continuous_validation": validation,
            "run_agent_records": {
                "max_installed_integer_unit_diff": max_int_diff,
                "agent_records_with_installed_diff": diff_records,
                "agent_records_total": total_records,
                "fraction_records_with_installed_diff": (diff_records / total_records) if total_records else 0.0,
            },
            "run_completion": {
                "run_pairs": run_pairs,
                "runs_with_completion_diff": run_completion_diff,
                "fraction_runs_with_completion_diff": (run_completion_diff / run_pairs) if run_pairs else 0.0,
                "max_abs_completion_diff": max_run_completion_diff,
                "mixed_paired_completion_diff": cd_completion,
            },
        },
        "solver_status_counts": summary.get("solver_status_counts", {}),
        "fallback_used_counts": summary.get("fallback_used_counts", {}),
        "infeasible_runs": summary.get("infeasible_runs", 0),
        "realized_contention_summary": summary.get("realized_contention_summary", {}),
        "capacity_utilization_by_policy": util,
        "allocation_consumption_by_policy": cons,
        "latency_by_policy_ms": latency_by_policy,
        "joint_latency_ms": joint_latency,
        "individual_change_vs_equal_mixed": harm,
        "capacity_violations": sum(int(r["capacity_violation"]) for r in runs),
        "bound_violations": sum(int(r["bound_violation"]) for r in runs),
    }
    tables_dir = os.path.join(HERE, "tables")
    os.makedirs(tables_dir, exist_ok=True)

    def write_table(name, rows):
        if not rows:
            return
        with open(os.path.join(tables_dir, name), "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)

    paired_rows = []
    for name, by_cell in per_cell_paired.items():
        for c, d in by_cell.items():
            paired_rows.append({"cell": c, "comparison": name, "metric": "completion_mean",
                                "mean_diff": d["mean"], "ci_lo": d["ci_lo"], "ci_hi": d["ci_hi"],
                                "n_pairs": d["n"]})
    write_table("paired_differences.csv", paired_rows)
    write_table("mixed_aggregate.csv", [
        {"comparison": k, "metric": "completion_mean", "mean_diff": v["mean"],
         "ci_lo": v["ci_lo"], "ci_hi": v["ci_hi"], "n_cells": v["n_cells"], "n_per_cell": v["n_per_cell"]}
        for k, v in mixed_aggregate.items()])
    write_table("latency_by_policy.csv", [
        {"policy": p, **latency_by_policy[p]} for p in policies])
    write_table("individual_change.csv", [
        {"policy": p, **harm[p]} for p in sorted(harm)])

    with open(os.path.join(HERE, "results", "headline.json"), "w") as f:
        json.dump(headline, f, indent=2)
    print(json.dumps({
        "min_distinct_completion_mixed": min_distinct_mixed,
        "homogeneous_symmetry_max_spread": round(max_spread, 4),
        "cd_max_installed_int_diff": max_int_diff,
        "cd_fraction_records_diff": round(diff_records / total_records, 4) if total_records else 0.0,
        "cd_runs_with_completion_diff": run_completion_diff,
        "joint_latency_median": joint_latency["median"],
        "solver_status_counts": summary.get("solver_status_counts", {}),
    }, indent=2))


if __name__ == "__main__":
    main()
