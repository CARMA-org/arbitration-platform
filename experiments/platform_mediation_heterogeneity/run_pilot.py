#!/usr/bin/env python3
"""Heterogeneity-pilot sweep driver.

Two sweeps share this driver:

* ``--sweep workload`` -- the workload-concentration comparison. Seven workload
  regimes (homogeneous, iid_uniform, and five symmetric-Dirichlet concentrations)
  under the fixed ``unit`` floor, both contention levels, all seven policies.
* ``--sweep floor`` -- the floor-sensitivity comparison. Six floor regimes over
  three workload regimes, both contention levels, and the minimum policy set
  (equal, DRF, joint_linear).

Factorial control: an agent workload is generated once per (regime, seed) and the
same task realization is used at both contention levels. Every policy in a cell
and seed receives the same scenario (same ``scenario_hash``); every workload is
identical across contention (same ``task_workload_hash``). Every policy is
executed through the canonical Java runtime (PlatformMediationHarness) via
``lib.runner``. Raw workload-, run-, and agent-level CSVs are written per sweep.
"""
import argparse
import csv
import json
import os
import time
from collections import defaultdict

import numpy as np

from pilotlib import workload as wlgen
from pilotlib import pilot_scenario, measures
from lib import scenario as canon, runner
from lib.archetypes import RESOURCES

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
RAW = os.path.join(RESULTS, "raw")
LOGS = os.path.join(HERE, "logs")

TASK_TYPES = list(canon.TASK_TYPES)

WORKLOAD_FIELDS = [
    "sweep", "cell", "regime", "kind", "concentration", "floor_regime",
    "contention", "contention_ratio", "seed", "task_workload_hash", "scenario_hash",
    "latent_probs_by_agent", "realized_task_counts_by_agent", "unique_archetypes_per_agent",
    "frac_agents_all_four_archetypes", "unique_archetypes_per_agent_mean",
    "task_entropy_mean", "task_mixture_tv_mean_pairwise",
    "resource_demand_tv_mean_pairwise", "resource_centroid_distance_mean",
    "aggregate_mandatory_demand", "capacity_by_resource", "realized_contention_by_resource",
    "active_resource_count", "inactive_resources", "floor_fraction_by_resource",
    "declaration_source", "redraws", "reject_reason",
]
RUN_FIELDS = [
    "sweep", "cell", "regime", "concentration", "floor_regime", "contention", "seed",
    "policy", "utility_family", "task_workload_hash", "scenario_hash", "feasible",
    "solver_status", "fallback_allowed", "fallback_used",
    "completion_mean", "completion_min", "completion_p5", "completed_tasks_per_run",
    "quality_adjusted_completion", "optional_refinement_rate",
    "capacity_utilization", "allocation_consumption",
    "frac_agents_zero_completion", "frac_agents_any_used_at_lower_bound",
    "frac_agents_all_used_above_lower_bound", "frac_agents_worse_than_equal",
    "alloc_distance_from_equal_mean",
    "mandatory_failures_total", "blocked_total", "backend_total",
    "capacity_by_resource", "mandatory_demand_by_resource", "realized_contention_by_resource",
    "capacity_violation", "bound_violation", "alloc_latency_ms",
]
AGENT_FIELDS = [
    "sweep", "cell", "regime", "concentration", "floor_regime", "contention", "seed",
    "policy", "utility_family", "task_workload_hash", "scenario_hash", "agent", "archetype",
    "priority", "completion", "zero_completion", "mandatory_failures", "quality",
    "backend_calls", "blocked_calls", "allocated", "charged", "min_bound", "upper_bound",
    "any_used_at_lower_bound", "all_used_above_lower_bound", "alloc_distance_from_equal",
    "completion_minus_equal", "latent_probs",
]
INFEASIBLE_FIELDS = ["sweep", "cell", "seed", "policy", "solver_status",
                     "fallback_allowed", "fallback_used", "failure_reason"]


def load_config():
    with open(os.path.join(HERE, "config", "pilot.json")) as f:
        return json.load(f)


def build_cells(cfg, sweep):
    """Return list of (regime_dict, floor_regime, contention_name, ratio, cell_label)."""
    contention = cfg["contention"]
    out = []
    if sweep == "workload":
        floor = cfg["default_floor_regime"]
        for regime in cfg["workload_regimes"]:
            for cname, ratio in contention.items():
                out.append((regime, floor, cname, ratio, "%s__%s" % (regime["name"], cname)))
    elif sweep == "floor":
        wanted = set(cfg["floor_sweep"]["workload_regimes"])
        regimes = [r for r in cfg["workload_regimes"] if r["name"] in wanted]
        for regime in regimes:
            for floor in cfg["floor_regimes"]:
                for cname, ratio in contention.items():
                    out.append((regime, floor, cname, ratio,
                                "%s__%s__%s" % (regime["name"], floor, cname)))
    else:
        raise ValueError("unknown sweep %r" % sweep)
    return out


def policies_for(cfg, sweep):
    return cfg["policies"] if sweep == "workload" else cfg["floor_sweep"]["policies"]


def _agent_bound_flags(alloc, lower, mand):
    """(any_used_at_lower, all_used_above_lower) over resources the agent uses."""
    used = [r for r in RESOURCES if mand[r] > 0]
    if not used:
        return False, True
    any_at = any(alloc.get(r, 0) == lower[r] for r in used)
    all_above = all(alloc.get(r, 0) > lower[r] for r in used)
    return any_at, all_above


def run_one_sweep(cfg, sweep, solver_python, log):
    os.makedirs(RAW, exist_ok=True)
    n = cfg["n_agents"]
    tpa = cfg["tasks_per_agent"]
    seeds = wlgen.dev_seeds(cfg["seed_label"], cfg["n_dev_seeds"])
    policies = policies_for(cfg, sweep)
    cells = build_cells(cfg, sweep)

    workload_rows, run_rows, agent_rows, infeasible_rows = [], [], [], []
    jobs, meta = [], []
    scenarios = {}   # (cell, seed) -> scenario dict
    wl_cache = {}    # (regime_name, seed) -> workload (contention-independent)

    for regime, floor, cname, ratio, cell in cells:
        for seed in seeds:
            wl_key = (regime["name"], seed)
            if wl_key not in wl_cache:
                wl_cache[wl_key] = wlgen.generate_workload(regime, seed, n, tpa, cfg["seed_label"])
            wl = wl_cache[wl_key]
            sc = pilot_scenario.build_scenario(wl, cname, ratio, floor, cell)
            scenarios[(cell, seed)] = sc

            md = [a["mandatory_demand"] for a in sc["agents"]]
            diss = measures.workload_dissimilarity(
                [a["task_types"] for a in sc["agents"]], md, TASK_TYPES, RESOURCES)
            task_counts = [{c: sum(1 for t in a["task_types"] if t == c) for c in TASK_TYPES}
                           for a in sc["agents"]]
            workload_rows.append({
                "sweep": sweep, "cell": cell, "regime": regime["name"], "kind": regime["kind"],
                "concentration": regime.get("concentration"), "floor_regime": floor,
                "contention": cname, "contention_ratio": ratio, "seed": seed,
                "task_workload_hash": sc["task_workload_hash"], "scenario_hash": sc["scenario_hash"],
                "latent_probs_by_agent": json.dumps([a["latent_probs"] for a in sc["agents"]]),
                "realized_task_counts_by_agent": json.dumps(task_counts),
                "unique_archetypes_per_agent": json.dumps(diss["unique_archetypes_per_agent"]),
                "frac_agents_all_four_archetypes": diss["frac_agents_all_four_archetypes"],
                "unique_archetypes_per_agent_mean": diss["unique_archetypes_per_agent_mean"],
                "task_entropy_mean": diss["task_entropy_mean"],
                "task_mixture_tv_mean_pairwise": diss["task_mixture_tv_mean_pairwise"],
                "resource_demand_tv_mean_pairwise": diss["resource_demand_tv_mean_pairwise"],
                "resource_centroid_distance_mean": diss["resource_centroid_distance_mean"],
                "aggregate_mandatory_demand": json.dumps(sc["total_mandatory_demand"]),
                "capacity_by_resource": json.dumps(sc["capacities"]),
                "realized_contention_by_resource": json.dumps(sc["realized_ratio"]),
                "active_resource_count": len(sc["active_resources"]),
                "inactive_resources": json.dumps(sc["inactive_resources"]),
                "floor_fraction_by_resource": json.dumps(sc["floor_fraction"]),
                "declaration_source": sc["declaration_source"], "redraws": sc["redraws"],
                "reject_reason": "",
            })
            for policy in policies:
                jobs.append(canon.make_job(sc, cell, seed, policy, solver_python, True))
                meta.append((cell, seed, policy))

    t0 = time.time()
    results = runner.run_jobs(jobs, chunk=cfg["chunk"])
    log("  %s: %d jobs across %d cells x %d seeds x %d policies in %.1fs"
        % (sweep, len(jobs), len(cells), len(seeds), len(policies), time.time() - t0))

    result_by = {}
    for (cell, seed, policy), r in zip(meta, results):
        result_by[(cell, seed, policy)] = r

    for (cell, seed, policy), r in zip(meta, results):
        sc = scenarios[(cell, seed)]
        status = r.get("solver_status", "")
        if not r.get("feasible", False):
            infeasible_rows.append({
                "sweep": sweep, "cell": cell, "seed": seed, "policy": policy,
                "solver_status": status, "fallback_allowed": r.get("fallback_allowed", False),
                "fallback_used": r.get("fallback_used", False),
                "failure_reason": r.get("failure_reason") or r.get("error") or ""})
            log("  INFEASIBLE %s seed=%s policy=%s status=%s" % (cell, seed, policy, status))
            continue
        if r.get("scenario_hash") != sc["scenario_hash"] or r.get("workload_hash") != sc["task_workload_hash"]:
            raise RuntimeError("hash mismatch %s seed=%s policy=%s" % (cell, seed, policy))

        equal_r = result_by.get((cell, seed, "equal"))
        by_id = {a["id"]: a for a in sc["agents"]}
        equal_alloc = {a["id"]: json.loads(a["allocated"]) if isinstance(a["allocated"], str) else a["allocated"]
                       for a in equal_r["agents"]} if equal_r else None
        equal_compl = {a["id"]: a["completion"] for a in equal_r["agents"]} if equal_r else None

        comps = [a["completion"] for a in r["agents"]]
        quals = [a["quality"] for a in r["agents"]]
        refine = [a.get("optional_refinement_rate", 0.0) for a in r["agents"]]
        n_zero = 0
        any_at_count = 0
        all_above_count = 0
        worse_count = 0
        dists = []
        for a in r["agents"]:
            spec = by_id[a["id"]]
            alloc = a["allocated"]
            lower = spec["min"]
            mand = spec["mandatory_demand"]
            any_at, all_above = _agent_bound_flags(alloc, lower, mand)
            any_at_count += int(any_at)
            all_above_count += int(all_above)
            zc = (a["completion"] <= 1e-12)
            n_zero += int(zc)
            dist = ""
            cme = ""
            if equal_alloc is not None:
                ea = equal_alloc[a["id"]]
                denom = max(sum(ea.values()), 1)
                dist = sum(abs(alloc[r2] - ea[r2]) for r2 in RESOURCES) / denom
                cme = a["completion"] - equal_compl[a["id"]]
                if cme < -1e-9:
                    worse_count += 1
                dists.append(dist)
            agent_rows.append({
                "sweep": sweep, "cell": cell, "regime": sc["regime"], "concentration": sc["concentration"],
                "floor_regime": sc["floor_regime"], "contention": sc["contention"], "seed": seed,
                "policy": policy, "utility_family": r.get("utility_family", ""),
                "task_workload_hash": sc["task_workload_hash"], "scenario_hash": sc["scenario_hash"],
                "agent": a["id"], "archetype": a.get("archetype", ""), "priority": a["priority"],
                "completion": a["completion"], "zero_completion": zc,
                "mandatory_failures": a.get("mandatory_failures", 0), "quality": a["quality"],
                "backend_calls": a["backend_calls"], "blocked_calls": a["blocked_calls"],
                "allocated": json.dumps(alloc), "charged": json.dumps(a.get("charged", {})),
                "min_bound": json.dumps(lower), "upper_bound": json.dumps(spec["upper"]),
                "any_used_at_lower_bound": any_at, "all_used_above_lower_bound": all_above,
                "alloc_distance_from_equal": dist, "completion_minus_equal": cme,
                "latent_probs": json.dumps(spec["latent_probs"]),
            })

        run_rows.append({
            "sweep": sweep, "cell": cell, "regime": sc["regime"], "concentration": sc["concentration"],
            "floor_regime": sc["floor_regime"], "contention": sc["contention"], "seed": seed,
            "policy": policy, "utility_family": r.get("utility_family", ""),
            "task_workload_hash": sc["task_workload_hash"], "scenario_hash": sc["scenario_hash"],
            "feasible": True, "solver_status": status,
            "fallback_allowed": r.get("fallback_allowed", False), "fallback_used": r.get("fallback_used", False),
            "completion_mean": float(np.mean(comps)), "completion_min": float(np.min(comps)),
            "completion_p5": float(np.percentile(comps, 5)),
            "completed_tasks_per_run": float(np.mean(comps)) * n * tpa,
            "quality_adjusted_completion": float(np.mean(quals)),
            "optional_refinement_rate": float(np.mean(refine)),
            "capacity_utilization": r["capacity_utilization"],
            "allocation_consumption": r["allocation_consumption"],
            "frac_agents_zero_completion": n_zero / len(comps),
            "frac_agents_any_used_at_lower_bound": any_at_count / len(comps),
            "frac_agents_all_used_above_lower_bound": all_above_count / len(comps),
            "frac_agents_worse_than_equal": (worse_count / len(comps)) if equal_alloc is not None else "",
            "alloc_distance_from_equal_mean": (float(np.mean(dists)) if dists else ""),
            "mandatory_failures_total": r.get("mandatory_failures_total", 0),
            "blocked_total": r["blocked_calls_total"], "backend_total": r["backend_calls_total"],
            "capacity_by_resource": json.dumps(r.get("capacity_by_resource", {})),
            "mandatory_demand_by_resource": json.dumps(r.get("mandatory_demand_by_resource", {})),
            "realized_contention_by_resource": json.dumps(r.get("realized_contention_by_resource", {})),
            "capacity_violation": r["capacity_violation"], "bound_violation": r["bound_violation"],
            "alloc_latency_ms": r["allocation_latency_ms"],
        })

    _write(os.path.join(RAW, "%s_workloads.csv" % sweep), WORKLOAD_FIELDS, workload_rows)
    _write(os.path.join(RAW, "%s_runs.csv" % sweep), RUN_FIELDS, run_rows)
    _write(os.path.join(RAW, "%s_agents.csv" % sweep), AGENT_FIELDS, agent_rows)
    _write(os.path.join(RAW, "%s_infeasible.csv" % sweep), INFEASIBLE_FIELDS, infeasible_rows)

    expected = len(cells) * len(seeds) * len(policies)
    assert len(run_rows) + len(infeasible_rows) == expected, \
        "%s: attempted %d != expected %d" % (sweep, len(run_rows) + len(infeasible_rows), expected)

    summary = {
        "sweep": sweep,
        "n_cells": len(cells), "n_seeds_per_cell": len(seeds), "policies": policies,
        "n_agents": n, "tasks_per_agent": tpa, "contention": cfg["contention"],
        "expected_runs": expected, "feasible_runs": len(run_rows),
        "infeasible_runs": len(infeasible_rows), "n_agent_records": len(agent_rows),
        "n_workload_rows": len(workload_rows),
        "capacity_violations_total": sum(int(r["capacity_violation"]) for r in run_rows),
        "bound_violations_total": sum(int(r["bound_violation"]) for r in run_rows),
        "fallback_used_total": sum(1 for r in run_rows if str(r["fallback_used"]).lower() == "true"),
        "bootstrap_seed": cfg["bootstrap_seed"], "n_bootstrap": cfg["n_bootstrap"],
        "seed_label": cfg["seed_label"], "dev_seeds": seeds,
        "declaration_source": pilot_scenario.DECLARATION_SOURCE,
    }
    with open(os.path.join(RESULTS, "summary_%s.json" % sweep), "w") as f:
        json.dump(summary, f, indent=2)
    log("  %s: feasible=%d infeasible=%d agents=%d capviol=%d bndviol=%d fallback=%d"
        % (sweep, len(run_rows), len(infeasible_rows), len(agent_rows),
           summary["capacity_violations_total"], summary["bound_violations_total"],
           summary["fallback_used_total"]))
    return summary


def _write(path, fields, rows):
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sweep", choices=["workload", "floor", "all"], required=True)
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    args = ap.parse_args()
    cfg = load_config()
    os.makedirs(LOGS, exist_ok=True)
    lines = []

    def log(msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        lines.append(line)

    log("heterogeneity pilot: sweep=%s solver=%s" % (args.sweep, args.solver_python))
    sweeps = ["workload", "floor"] if args.sweep == "all" else [args.sweep]
    for sw in sweeps:
        run_one_sweep(cfg, sw, args.solver_python, log)
    with open(os.path.join(LOGS, "run_pilot_%s.log" % args.sweep), "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
