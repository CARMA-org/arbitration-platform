#!/usr/bin/env python3
"""Platform-mediation sweep driver.

Runs every policy through the canonical Java runtime for each workload
composition and contention level. Each test seed is an independent workload
draw. Writes raw per-run and per-agent CSVs, per-cell policy means, a summary
JSON, logs, and the resolved configuration. Statistical aggregation lives in
make_headline.py.
"""
import argparse
import csv
import json
import os
import time
from collections import defaultdict

import numpy as np

from lib import scenario, runner
from lib.seeds import derive_seed

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
RAW = os.path.join(RESULTS, "raw")
TABLES = os.path.join(HERE, "tables")
LOGS = os.path.join(HERE, "logs")

RUN_FIELDS = [
    "experiment", "cell", "composition", "contention", "seed", "policy",
    "utility_family", "workload_hash", "scenario_hash", "feasible", "solver_status",
    "fallback_allowed", "fallback_used", "welfare_own_family",
    "completion_mean", "completion_min", "completion_p5", "optional_refinement_rate",
    "quality_adjusted_completion", "capacity_utilization", "allocation_consumption",
    "mandatory_failures_total", "blocked_total", "backend_total",
    "capacity_by_resource", "mandatory_demand_by_resource",
    "realized_contention_by_resource", "zero_demand_by_resource",
    "capacity_violation", "bound_violation", "alloc_latency_ms",
]
AGENT_FIELDS = [
    "cell", "composition", "contention", "seed", "policy", "utility_family",
    "workload_hash", "scenario_hash", "agent", "archetype", "priority", "completion",
    "mandatory_failures", "optional_refinement_rate", "quality", "backend_calls",
    "blocked_calls", "allocated", "charged", "unused", "exhausted",
]
INFEASIBLE_FIELDS = ["cell", "seed", "policy", "solver_status", "fallback_allowed",
                     "fallback_used", "failure_reason"]


def load_config():
    with open(os.path.join(HERE, "config", "experiment.json")) as f:
        return json.load(f)


def cells(cfg):
    out = []
    for composition in cfg["compositions"]:
        for cname, ratio in cfg["contention"].items():
            out.append((composition, cname, ratio, "%s__%s" % (composition, cname)))
    return out


def test_seeds(label, n_test):
    return [derive_seed(label, "test", i) for i in range(n_test)]


def run_metrics(res):
    comps = [a["completion"] for a in res["agents"]]
    quals = [a["quality"] for a in res["agents"]]
    refine = [a.get("optional_refinement_rate", 0.0) for a in res["agents"]]
    return {
        "welfare_own_family": res.get("welfare_own_family"),
        "completion_mean": float(np.mean(comps)),
        "completion_min": float(np.min(comps)),
        "completion_p5": float(np.percentile(comps, 5)),
        "optional_refinement_rate": float(np.mean(refine)),
        "quality_adjusted_completion": float(np.mean(quals)),
        "capacity_utilization": res["capacity_utilization"],
        "allocation_consumption": res["allocation_consumption"],
        "mandatory_failures_total": res.get("mandatory_failures_total", 0),
        "blocked_total": res["blocked_calls_total"],
        "backend_total": res["backend_calls_total"],
        "capacity_violation": res["capacity_violation"],
        "bound_violation": res["bound_violation"],
        "alloc_latency_ms": res["allocation_latency_ms"],
    }


def solver_status_class(status):
    s = (status or "").lower()
    if "optimal_inaccurate" in s:
        return "optimal_inaccurate"
    if "optimal" in s:
        return "optimal"
    return "failed"


def evaluate(cfg, mode, solver_python, log):
    os.makedirs(RAW, exist_ok=True)
    run_rows, agent_rows, infeasible_rows = [], [], []
    contention_samples = defaultdict(lambda: defaultdict(list))
    zero_demand_counts = defaultdict(lambda: defaultdict(int))
    hashes_per_cell = {}
    solver_status_counts = defaultdict(lambda: defaultdict(int))
    fallback_used_counts = defaultdict(int)
    resources = cfg["resources"]
    policies = cfg["policies"]
    for composition, cname, ratio, label in cells(cfg):
        seeds = test_seeds(label, cfg[mode]["n_test"])
        jobs, meta = [], []
        cell_hashes = set()
        for seed in seeds:
            sc = scenario.base_scenario(composition, cname, ratio, seed, cfg[mode])
            for r in resources:
                contention_samples[label][r].append(sc["realized_ratio"][r])
                if sc["realized_ratio"][r] == 0.0:
                    zero_demand_counts[label][r] += 1
            cell_hashes.add(sc["scenario_hash"])
            for policy in policies:
                jobs.append(scenario.make_job(sc, label, seed, policy, solver_python, True))
                meta.append((seed, policy, sc["scenario_hash"], sc["workload_hash"]))
        hashes_per_cell[label] = len(cell_hashes)
        t0 = time.time()
        res = runner.run_jobs(jobs, chunk=cfg[mode]["chunk"])
        log("  cell %s: %d runs in %.1fs; distinct workload hashes=%d" %
            (label, len(jobs), time.time() - t0, len(cell_hashes)))
        for (seed, policy, shash, whash), r in zip(meta, res):
            status = r.get("solver_status", "")
            if r.get("fallback_used"):
                fallback_used_counts[policy] += 1
            if policy in cfg["joint_policies"]:
                solver_status_counts[policy][solver_status_class(status)] += 1
            if not r.get("feasible", False):
                log("  INFEASIBLE run: %s seed=%s policy=%s status=%s reason=%s" %
                    (label, seed, policy, status, r.get("failure_reason") or r.get("error")))
                infeasible_rows.append({
                    "cell": label, "seed": seed, "policy": policy, "solver_status": status,
                    "fallback_allowed": r.get("fallback_allowed", False),
                    "fallback_used": r.get("fallback_used", False),
                    "failure_reason": r.get("failure_reason") or r.get("error") or ""})
                continue
            if r.get("scenario_hash") != shash or r.get("workload_hash") != whash:
                raise RuntimeError("hash mismatch %s seed=%s policy=%s" % (label, seed, policy))
            m = run_metrics(r)
            row = {"experiment": "platform_mediation", "cell": label, "composition": composition,
                   "contention": cname, "seed": seed, "policy": policy,
                   "utility_family": r.get("utility_family", ""),
                   "workload_hash": whash, "scenario_hash": shash, "feasible": True,
                   "solver_status": status, "fallback_allowed": r.get("fallback_allowed", False),
                   "fallback_used": r.get("fallback_used", False),
                   "capacity_by_resource": json.dumps(r.get("capacity_by_resource", {})),
                   "mandatory_demand_by_resource": json.dumps(r.get("mandatory_demand_by_resource", {})),
                   "realized_contention_by_resource": json.dumps(r.get("realized_contention_by_resource", {})),
                   "zero_demand_by_resource": json.dumps(r.get("zero_demand_by_resource", {}))}
            row.update(m)
            if row["welfare_own_family"] is None:
                row["welfare_own_family"] = ""
            run_rows.append(row)
            for a in r["agents"]:
                agent_rows.append({
                    "cell": label, "composition": composition, "contention": cname, "seed": seed,
                    "policy": policy, "utility_family": r.get("utility_family", ""),
                    "workload_hash": whash, "scenario_hash": shash, "agent": a["id"],
                    "archetype": a.get("archetype", ""), "priority": a["priority"],
                    "completion": a["completion"], "mandatory_failures": a.get("mandatory_failures", 0),
                    "optional_refinement_rate": a.get("optional_refinement_rate", 0.0),
                    "quality": a["quality"], "backend_calls": a["backend_calls"],
                    "blocked_calls": a["blocked_calls"],
                    "allocated": json.dumps(a.get("allocated", {})),
                    "charged": json.dumps(a.get("charged", {})),
                    "unused": json.dumps(a.get("unused", {})),
                    "exhausted": json.dumps(a.get("exhausted", {}))})
    with open(os.path.join(RAW, "runs.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=RUN_FIELDS)
        w.writeheader()
        w.writerows(run_rows)
    with open(os.path.join(RAW, "agents.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=AGENT_FIELDS)
        w.writeheader()
        w.writerows(agent_rows)
    with open(os.path.join(RAW, "infeasible_runs.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=INFEASIBLE_FIELDS)
        w.writeheader()
        w.writerows(infeasible_rows)

    realized_summary = {}
    for label in contention_samples:
        realized_summary[label] = {}
        for r in resources:
            vals = contention_samples[label][r]
            realized_summary[label][r] = {
                "mean": float(np.mean(vals)), "min": float(np.min(vals)),
                "max": float(np.max(vals)), "zero_demand_count": zero_demand_counts[label][r]}
    return (run_rows, agent_rows, infeasible_rows, realized_summary, hashes_per_cell,
            {k: dict(v) for k, v in solver_status_counts.items()}, dict(fallback_used_counts))


def cell_policy_means(run_rows):
    metrics = ["completion_mean", "completion_min", "completion_p5",
               "optional_refinement_rate", "quality_adjusted_completion",
               "capacity_utilization", "allocation_consumption",
               "mandatory_failures_total", "blocked_total", "backend_total",
               "capacity_violation", "bound_violation", "alloc_latency_ms"]
    by = {}
    for r in run_rows:
        by.setdefault((r["cell"], r["policy"]), []).append(r)
    table = []
    for (cell, policy), rows in sorted(by.items()):
        rec = {"cell": cell, "policy": policy,
               "utility_family": rows[0].get("utility_family", ""), "n": len(rows)}
        for mkey in metrics:
            rec[mkey] = float(np.mean([x[mkey] for x in rows]))
        table.append(rec)
    return table


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
    os.makedirs(TABLES, exist_ok=True)
    log_lines = []

    def log(msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        log_lines.append(line)

    started = time.strftime("%Y-%m-%dT%H:%M:%S")
    log("Platform-mediation sweep: mode=%s solver=%s started=%s" % (mode, args.solver_python, started))
    (run_rows, agent_rows, infeasible_rows, realized, hashes_per_cell,
     solver_status_counts, fallback_used_counts) = evaluate(cfg, mode, args.solver_python, log)
    finished = time.strftime("%Y-%m-%dT%H:%M:%S")
    table = cell_policy_means(run_rows)
    with open(os.path.join(TABLES, "cell_policy_means.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(table[0].keys()))
        w.writeheader()
        w.writerows(table)

    n_cells = len(cells(cfg))
    policies = cfg["policies"]
    expected_runs = n_cells * cfg[mode]["n_test"] * len(policies)
    summary = {
        "mode": mode,
        "started": started,
        "finished": finished,
        "compositions": cfg["compositions"],
        "contention": cfg["contention"],
        "realized_contention_summary": realized,
        "distinct_workload_hashes_per_cell": hashes_per_cell,
        "solver_status_counts": solver_status_counts,
        "fallback_used_counts": fallback_used_counts,
        "n_cells": n_cells,
        "n_test_seeds_per_cell": cfg[mode]["n_test"],
        "n_agents": cfg[mode]["n_agents"],
        "tasks_per_agent": cfg[mode]["tasks_per_agent"],
        "policies": policies,
        "joint_policies": cfg["joint_policies"],
        "reference_policy": cfg["reference_policy"],
        "bootstrap_seed": cfg["bootstrap_seed"],
        "expected_runs": expected_runs,
        "total_test_runs": len(run_rows),
        "infeasible_runs": len(infeasible_rows),
        "n_agent_records": len(agent_rows),
        "capacity_violations_total": sum(r["capacity_violation"] for r in run_rows),
        "bound_violations_total": sum(r["bound_violation"] for r in run_rows),
    }
    assert len(run_rows) + len(infeasible_rows) == expected_runs, \
        "attempted %d != expected %d" % (len(run_rows) + len(infeasible_rows), expected_runs)
    with open(os.path.join(RESULTS, "summary_%s.json" % mode), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(RESULTS, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    resolved = dict(cfg)
    resolved["mode"] = mode
    with open(os.path.join(RESULTS, "resolved_config_%s.json" % mode), "w") as f:
        json.dump(resolved, f, indent=2)
    log("Done: %d feasible runs, %d infeasible (expected total %d), %d agent records; cap_viol=%d bound_viol=%d" %
        (len(run_rows), len(infeasible_rows), expected_runs, len(agent_rows),
         summary["capacity_violations_total"], summary["bound_violations_total"]))
    log("RUN COMPLETE: mode=%s feasible=%d infeasible=%d expected=%d agent_records=%d" %
        (mode, len(run_rows), len(infeasible_rows), expected_runs, len(agent_rows)))
    with open(os.path.join(LOGS, "sweep_%s.log" % mode), "w") as f:
        f.write("\n".join(log_lines) + "\n")


if __name__ == "__main__":
    main()
