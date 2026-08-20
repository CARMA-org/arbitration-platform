#!/usr/bin/env python3
"""Platform-mediation sweep driver.

Runs every policy through the canonical Java runtime for each workload regime and
contention level, selects the separable exponent on calibration seeds only, then
evaluates on disjoint paired test seeds and writes raw per-run CSVs, aggregate
tables, a summary JSON, logs, and a copy of the resolved configuration.
"""
import argparse
import csv
import json
import os
import time

import numpy as np

from lib import scenario, runner, stats
from lib.seeds import seed_split

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
RAW = os.path.join(RESULTS, "raw")
TABLES = os.path.join(HERE, "tables")
LOGS = os.path.join(HERE, "logs")

RUN_FIELDS = [
    "experiment", "cell", "regime", "contention", "seed", "policy", "gamma",
    "feasible", "declared_welfare", "completion_mean", "completion_min",
    "completion_p5", "quality_mean", "priority_weighted_slo", "utilization_mean",
    "blocked_total", "backend_total", "capacity_violation", "bound_violation",
    "alloc_latency_ms",
]
AGENT_FIELDS = [
    "cell", "regime", "contention", "seed", "policy", "agent", "priority",
    "completion", "quality", "slo", "backend_calls", "blocked_calls",
]


def load_config():
    with open(os.path.join(HERE, "config", "experiment.json")) as f:
        cfg = json.load(f)
    for mode in ("smoke", "full"):
        cfg[mode]["upper_frac"] = cfg["upper_frac"]
    return cfg


def cells(cfg):
    out = []
    for regime in cfg["regimes"]:
        for cname, ratio in cfg["contention"].items():
            out.append((regime, cname, ratio, "%s__%s" % (regime, cname)))
    return out


def run_metrics(res):
    comps = [a["completion"] for a in res["agents"]]
    quals = [a["quality"] for a in res["agents"]]
    util = res.get("utilization", {})
    util_mean = float(np.mean(list(util.values()))) if util else 0.0
    return {
        "declared_welfare": res["declared_welfare"],
        "completion_mean": float(np.mean(comps)),
        "completion_min": float(np.min(comps)),
        "completion_p5": float(np.percentile(comps, 5)),
        "quality_mean": float(np.mean(quals)),
        "priority_weighted_slo": res["priority_weighted_slo"],
        "utilization_mean": util_mean,
        "blocked_total": res["blocked_calls_total"],
        "backend_total": res["backend_calls_total"],
        "capacity_violation": res["capacity_violation"],
        "bound_violation": res["bound_violation"],
        "alloc_latency_ms": res["allocation_latency_ms"],
    }


def calibrate_gamma(cfg, mode, solver_python, log):
    """Pick one global gamma minimizing pooled declared-welfare regret vs joint
    over calibration seeds only."""
    grid = cfg["gamma_grid"]
    sums = {g: 0.0 for g in grid}
    count = 0
    for regime, cname, ratio, label in cells(cfg):
        calib, _ = seed_split(label, cfg[mode]["n_calibration"], cfg[mode]["n_test"])
        jobs, meta = [], []
        for seed in calib:
            sc = scenario.base_scenario(regime, cname, ratio, seed, cfg[mode])
            jobs.append(scenario.make_job(sc, label, seed, "joint", 1.0, solver_python, False))
            meta.append(("joint", seed, None))
            for g in grid:
                jobs.append(scenario.make_job(sc, label, seed, "separable", g, solver_python, False))
                meta.append(("separable", seed, g))
        res = runner.run_jobs(jobs, chunk=cfg[mode]["chunk"])
        by = {}
        for (kind, seed, g), r in zip(meta, res):
            if not r.get("feasible", False):
                log("  calibration infeasible: %s seed=%s kind=%s" % (label, seed, kind))
                continue
            by[(kind, seed, g)] = r["declared_welfare"]
        for seed in calib:
            if ("joint", seed, None) not in by:
                continue
            jw = by[("joint", seed, None)]
            for g in grid:
                key = ("separable", seed, g)
                if key in by:
                    sums[g] += jw - by[key]
            count += 1
    mean_regret = {g: (sums[g] / count if count else float("inf")) for g in grid}
    best = min(mean_regret, key=mean_regret.get)
    log("Calibration: pooled %d seeds; mean declared-welfare regret by gamma: %s" %
        (count, {g: round(v, 4) for g, v in mean_regret.items()}))
    log("Selected separable gamma = %s" % best)
    return best, mean_regret, count


def evaluate(cfg, mode, gamma, solver_python, log):
    os.makedirs(RAW, exist_ok=True)
    run_rows, agent_rows = [], []
    for regime, cname, ratio, label in cells(cfg):
        _, test = seed_split(label, cfg[mode]["n_calibration"], cfg[mode]["n_test"])
        jobs, meta = [], []
        for seed in test:
            sc = scenario.base_scenario(regime, cname, ratio, seed, cfg[mode])
            for policy in cfg["policies"]:
                g = gamma if policy == "separable" else 1.0
                jobs.append(scenario.make_job(sc, label, seed, policy, g, solver_python, True))
                meta.append((seed, policy, g))
        t0 = time.time()
        res = runner.run_jobs(jobs, chunk=cfg[mode]["chunk"])
        log("  cell %s: %d runs in %.1fs" % (label, len(jobs), time.time() - t0))
        for (seed, policy, g), r in zip(meta, res):
            if not r.get("feasible", False):
                log("  INFEASIBLE run: %s seed=%s policy=%s msg=%s" %
                    (label, seed, policy, r.get("message") or r.get("error")))
                continue
            m = run_metrics(r)
            row = {"experiment": "platform_mediation", "cell": label, "regime": regime,
                   "contention": cname, "seed": seed, "policy": policy, "gamma": g,
                   "feasible": True}
            row.update(m)
            run_rows.append(row)
            for a in r["agents"]:
                agent_rows.append({
                    "cell": label, "regime": regime, "contention": cname, "seed": seed,
                    "policy": policy, "agent": a["id"], "priority": a["priority"],
                    "completion": a["completion"], "quality": a["quality"],
                    "slo": a["slo"], "backend_calls": a["backend_calls"],
                    "blocked_calls": a["blocked_calls"]})
    with open(os.path.join(RAW, "runs.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=RUN_FIELDS)
        w.writeheader()
        w.writerows(run_rows)
    with open(os.path.join(RAW, "agents.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=AGENT_FIELDS)
        w.writeheader()
        w.writerows(agent_rows)
    return run_rows, agent_rows


def aggregate(run_rows, agent_rows, cfg, gamma):
    metrics = ["completion_mean", "quality_mean", "priority_weighted_slo",
               "completion_min", "completion_p5", "declared_welfare",
               "utilization_mean", "blocked_total", "backend_total",
               "capacity_violation", "bound_violation", "alloc_latency_ms"]
    # Per-cell per-policy means.
    cell_table = []
    by_cell_policy = {}
    for r in run_rows:
        by_cell_policy.setdefault((r["cell"], r["policy"]), []).append(r)
    for (cell, policy), rows in sorted(by_cell_policy.items()):
        rec = {"cell": cell, "policy": policy, "n": len(rows)}
        for mkey in metrics:
            rec[mkey] = float(np.mean([x[mkey] for x in rows]))
        cell_table.append(rec)

    # Paired joint-vs-baseline differences with bootstrap CIs, keyed by cell.
    paired = []
    idx = {}
    for r in run_rows:
        idx[(r["cell"], r["seed"], r["policy"])] = r
    cells_seen = sorted({r["cell"] for r in run_rows})
    seeds_by_cell = {}
    for r in run_rows:
        seeds_by_cell.setdefault(r["cell"], set()).add(r["seed"])
    diff_metrics = ["completion_mean", "quality_mean", "priority_weighted_slo",
                    "completion_min", "completion_p5", "declared_welfare"]
    for cell in cells_seen:
        seeds = sorted(seeds_by_cell[cell])
        for base in ["equal", "drf", "separable"]:
            for mkey in diff_metrics:
                ja, ba = [], []
                for s in seeds:
                    j = idx.get((cell, s, "joint"))
                    b = idx.get((cell, s, base))
                    if j and b:
                        ja.append(j[mkey])
                        ba.append(b[mkey])
                ci = stats.paired_diff_ci(ja, ba)
                paired.append({"cell": cell, "comparison": "joint_minus_" + base,
                               "metric": mkey, "mean_diff": ci["mean"],
                               "ci_lo": ci["lo"], "ci_hi": ci["hi"], "n_pairs": ci["n"]})

    # Individual-agent loss relative to equal quotas (per policy).
    indiv = []
    ag_idx = {}
    for a in agent_rows:
        ag_idx[(a["cell"], a["seed"], a["agent"], a["policy"])] = a
    keys = sorted({(a["cell"], a["seed"], a["agent"]) for a in agent_rows})
    for policy in ["joint", "separable", "drf"]:
        for cell in cells_seen:
            losses = []
            for (c, s, agent) in keys:
                if c != cell:
                    continue
                p = ag_idx.get((c, s, agent, policy))
                e = ag_idx.get((c, s, agent, "equal"))
                if p and e:
                    losses.append(p["completion"] - e["completion"])
            if losses:
                arr = np.array(losses)
                indiv.append({"cell": cell, "policy": policy,
                              "mean_completion_change_vs_equal": float(arr.mean()),
                              "worst_agent_loss_vs_equal": float(arr.min()),
                              "frac_agents_worse": float((arr < -1e-9).mean()),
                              "n_agents": len(arr)})
    return cell_table, paired, indiv


def write_tables(cell_table, paired, indiv):
    os.makedirs(TABLES, exist_ok=True)
    with open(os.path.join(TABLES, "cell_policy_means.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(cell_table[0].keys()))
        w.writeheader()
        w.writerows(cell_table)
    with open(os.path.join(TABLES, "paired_differences.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(paired[0].keys()))
        w.writeheader()
        w.writerows(paired)
    with open(os.path.join(TABLES, "individual_loss.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(indiv[0].keys()))
        w.writeheader()
        w.writerows(indiv)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    args = ap.parse_args()
    if args.smoke == args.full:
        ap.error("choose exactly one of --smoke / --full")
    mode = "smoke" if args.smoke else "full"

    cfg = load_config()
    os.makedirs(LOGS, exist_ok=True)
    log_path = os.path.join(LOGS, "sweep_%s.log" % mode)
    log_lines = []

    def log(msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        log_lines.append(line)

    log("Platform-mediation sweep: mode=%s solver=%s" % (mode, args.solver_python))
    gamma, mean_regret, cal_count = calibrate_gamma(cfg, mode, args.solver_python, log)
    run_rows, agent_rows = evaluate(cfg, mode, gamma, args.solver_python, log)
    cell_table, paired, indiv = aggregate(run_rows, agent_rows, cfg, gamma)
    write_tables(cell_table, paired, indiv)

    total_runs = len(run_rows)
    n_cells = len(cells(cfg))
    summary = {
        "mode": mode,
        "selected_gamma": gamma,
        "gamma_grid": cfg["gamma_grid"],
        "gamma_mean_regret": {str(k): v for k, v in mean_regret.items()},
        "calibration_pairs": cal_count,
        "n_cells": n_cells,
        "n_calibration_seeds_per_cell": cfg[mode]["n_calibration"],
        "n_test_seeds_per_cell": cfg[mode]["n_test"],
        "policies": cfg["policies"],
        "total_test_runs": total_runs,
        "capacity_violations_total": sum(r["capacity_violation"] for r in run_rows),
        "bound_violations_total": sum(r["bound_violation"] for r in run_rows),
    }
    with open(os.path.join(RESULTS, "summary_%s.json" % mode), "w") as f:
        json.dump(summary, f, indent=2)
    resolved = dict(cfg)
    resolved["mode"] = mode
    resolved["selected_gamma"] = gamma
    with open(os.path.join(RESULTS, "resolved_config_%s.json" % mode), "w") as f:
        json.dump(resolved, f, indent=2)
    with open(log_path, "w") as f:
        f.write("\n".join(log_lines) + "\n")
    log("Done: %d test runs across %d cells; gamma*=%s; capacity_viol=%d bound_viol=%d" %
        (total_runs, n_cells, gamma, summary["capacity_violations_total"],
         summary["bound_violations_total"]))


if __name__ == "__main__":
    main()
