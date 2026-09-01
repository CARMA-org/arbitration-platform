#!/usr/bin/env python3
"""Confirmatory sweep driver (frozen protocol v1).

Runs the frozen confirmatory configuration: seven workload regimes x two contention
levels x 200 fresh confirmatory seeds x seven policies, under a single frozen
contract definition (unit floors), every policy through the canonical Java runtime.
Records both queue-order completion (the runtime's actual execution) and the
policy-independent locally-optimized completion (best feasible task subset under the
same installed bundle). Writes scenario-, run-, and agent-level raw CSVs under
``results/confirmatory_v1/``. Asserts that confirmatory seeds and task-workload
hashes do not overlap the canonical evaluation or the exploratory pilot.
"""
import argparse
import csv
import json
import os
import time
from collections import defaultdict

import numpy as np

from pilotlib import workload as wlgen
from pilotlib import pilot_scenario, measures, local_opt
from lib import scenario as canon, runner
from lib.archetypes import RESOURCES
from lib.scenario import mandatory_footprint
from lib.seeds import derive_seed

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "results", "confirmatory_v1")
RAW = os.path.join(OUT, "raw")
LOGS = os.path.join(HERE, "logs")
CANON_RAW = os.path.join(HERE, "..", "platform_mediation", "results", "raw")
TASK_TYPES = list(canon.TASK_TYPES)

SCEN_FIELDS = [
    "cell", "regime", "kind", "concentration", "floor_regime", "contention", "contention_ratio",
    "seed", "task_workload_hash", "scenario_hash", "latent_probs_by_agent",
    "realized_task_counts_by_agent", "unique_archetypes_per_agent", "frac_agents_all_four_archetypes",
    "task_entropy_mean", "task_mixture_tv_mean_pairwise", "resource_demand_tv_mean_pairwise",
    "resource_centroid_distance_mean", "aggregate_mandatory_demand", "capacity_by_resource",
    "realized_contention_by_resource", "active_resource_count", "inactive_resources",
    "floor_fraction_by_resource", "declaration_source", "redraws", "reject_reason",
]
RUN_FIELDS = [
    "cell", "regime", "concentration", "contention", "seed", "policy", "utility_family",
    "task_workload_hash", "scenario_hash", "feasible", "solver_status", "fallback_used",
    "queue_order_completion_mean", "queue_order_tasks_per_run",
    "locally_optimized_completion_mean", "locally_optimized_tasks_per_run",
    "qo_completion_min", "qo_completion_p5", "lo_completion_min", "lo_completion_p5",
    "frac_zero_qo", "frac_zero_lo", "frac_worse_than_equal_qo", "frac_worse_than_equal_lo",
    "mean_change_vs_equal_qo", "worst_change_vs_equal_qo",
    "capacity_utilization", "allocation_consumption", "unused_installed_total",
    "alloc_distance_from_equal_mean", "mandatory_failures_total",
    "capacity_by_resource", "mandatory_demand_by_resource", "realized_contention_by_resource",
    "capacity_violation", "bound_violation", "alloc_latency_ms",
]
AGENT_FIELDS = [
    "cell", "regime", "concentration", "contention", "seed", "policy", "utility_family",
    "task_workload_hash", "scenario_hash", "agent", "archetype",
    "queue_order_completion", "locally_optimized_completion", "locally_optimized_count",
    "qo_completion_minus_equal", "lo_completion_minus_equal",
    "mandatory_failures", "quality", "allocated", "charged", "unused", "min_bound", "upper_bound",
    "any_used_at_lower_bound", "all_used_above_lower_bound", "alloc_distance_from_equal",
    "latent_probs",
]
INFEASIBLE_FIELDS = ["cell", "seed", "policy", "solver_status", "fallback_used", "failure_reason"]


def load_config():
    with open(os.path.join(HERE, "config", "confirmatory_v1.json")) as f:
        return json.load(f)


def confirmatory_seeds(cfg):
    return [derive_seed(cfg["seed_label"], "test", i) for i in range(cfg["n_seeds"])]


def _bound_flags(alloc, lower, mand):
    used = [r for r in RESOURCES if mand[r] > 0]
    if not used:
        return False, True
    return (any(alloc.get(r, 0) == lower[r] for r in used),
            all(alloc.get(r, 0) > lower[r] for r in used))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    args = ap.parse_args(argv)
    cfg = load_config()
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(LOGS, exist_ok=True)
    lines = []

    def log(m):
        line = "%s %s" % (time.strftime("%H:%M:%S"), m)
        print(line, flush=True)
        lines.append(line)

    n, tpa = cfg["n_agents"], cfg["tasks_per_agent"]
    floor = cfg["floor_regime"]
    seeds = confirmatory_seeds(cfg)
    policies = cfg["policies"]
    contention = cfg["contention"]
    log("confirmatory v1: solver=%s regimes=%d seeds=%d policies=%d floor=%s"
        % (args.solver_python, len(cfg["workload_regimes"]), len(seeds), len(policies), floor))

    # Build all scenarios with factorial control (one workload per regime,seed reused
    # across contention), collect jobs.
    scen_rows, jobs, meta, scenarios = [], [], [], {}
    subset_tables = {}   # (cell,seed,agent_idx) -> subset table (task-based, policy-independent)
    for regime in cfg["workload_regimes"]:
        for seed in seeds:
            wl = wlgen.generate_workload(regime, seed, n, tpa, cfg["seed_label"])
            for cname, ratio in contention.items():
                cell = "%s__%s" % (regime["name"], cname)
                sc = pilot_scenario.build_scenario(wl, cname, ratio, floor, cell)
                scenarios[(cell, seed)] = sc
                md = [a["mandatory_demand"] for a in sc["agents"]]
                diss = measures.workload_dissimilarity(
                    [a["task_types"] for a in sc["agents"]], md, TASK_TYPES, RESOURCES)
                task_counts = [{c: sum(1 for t in a["task_types"] if t == c) for c in TASK_TYPES}
                               for a in sc["agents"]]
                scen_rows.append({
                    "cell": cell, "regime": regime["name"], "kind": regime["kind"],
                    "concentration": regime.get("concentration"), "floor_regime": floor,
                    "contention": cname, "contention_ratio": ratio, "seed": seed,
                    "task_workload_hash": sc["task_workload_hash"], "scenario_hash": sc["scenario_hash"],
                    "latent_probs_by_agent": json.dumps([a["latent_probs"] for a in sc["agents"]]),
                    "realized_task_counts_by_agent": json.dumps(task_counts),
                    "unique_archetypes_per_agent": json.dumps(diss["unique_archetypes_per_agent"]),
                    "frac_agents_all_four_archetypes": diss["frac_agents_all_four_archetypes"],
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
                # precompute policy-independent subset tables for local optimization
                for i, a in enumerate(sc["agents"]):
                    fps = [mandatory_footprint(t) for t in a["task_types"]]
                    quals = [t["quality"] for t in a["tasks"]]
                    subset_tables[(cell, seed, i)] = local_opt.build_subset_table(fps, quals, RESOURCES)
                for policy in policies:
                    jobs.append(canon.make_job(sc, cell, seed, policy, args.solver_python, True))
                    meta.append((cell, seed, policy))

    t0 = time.time()
    results = runner.run_jobs(jobs, chunk=cfg["chunk"])
    log("ran %d jobs in %.1fs" % (len(jobs), time.time() - t0))
    result_by = {(c, s, p): r for (c, s, p), r in zip(meta, results)}

    run_rows, agent_rows, infeasible_rows = [], [], []
    for (cell, seed, policy), r in zip(meta, results):
        sc = scenarios[(cell, seed)]
        if not r.get("feasible", False):
            infeasible_rows.append({"cell": cell, "seed": seed, "policy": policy,
                                    "solver_status": r.get("solver_status", ""),
                                    "fallback_used": r.get("fallback_used", False),
                                    "failure_reason": r.get("failure_reason") or r.get("error") or ""})
            continue
        if r.get("scenario_hash") != sc["scenario_hash"] or r.get("workload_hash") != sc["task_workload_hash"]:
            raise RuntimeError("hash mismatch %s %s %s" % (cell, seed, policy))
        by_id = {a["id"]: a for a in sc["agents"]}
        eq = result_by.get((cell, seed, "equal"))

        def lo_for(res_obj):
            out = {}
            for a in res_obj["agents"]:
                i = int(a["id"][1:])
                cnt, _, _, _ = local_opt.select_from_table(
                    subset_tables[(cell, seed, i)], a["allocated"], RESOURCES)
                out[a["id"]] = (cnt / tpa, cnt)
            return out

        lo_here = lo_for(r)
        lo_eq = lo_for(eq) if eq else None
        eq_alloc = {a["id"]: a["allocated"] for a in eq["agents"]} if eq else None
        eq_qo = {a["id"]: a["completion"] for a in eq["agents"]} if eq else None

        qo, lo, dists = [], [], []
        n_zero_qo = n_zero_lo = worse_qo = worse_lo = 0
        chg_qo = []
        unused_total = 0
        for a in r["agents"]:
            spec = by_id[a["id"]]
            alloc = a["allocated"]
            lower = spec["min"]
            mand = spec["mandatory_demand"]
            qoc = a["completion"]
            loc_c, loc_n = lo_here[a["id"]]
            qo.append(qoc)
            lo.append(loc_c)
            n_zero_qo += int(qoc <= 1e-12)
            n_zero_lo += int(loc_c <= 1e-12)
            any_at, all_above = _bound_flags(alloc, lower, mand)
            unused_total += sum(a.get("unused", {}).values())
            dist = ""
            qo_me = ""
            lo_me = ""
            if eq_alloc is not None:
                ea = eq_alloc[a["id"]]
                denom = max(sum(ea.values()), 1)
                dist = sum(abs(alloc[rr] - ea[rr]) for rr in RESOURCES) / denom
                dists.append(dist)
                qo_me = qoc - eq_qo[a["id"]]
                lo_me = loc_c - lo_eq[a["id"]][0]
                chg_qo.append(qo_me)
                worse_qo += int(qo_me < -1e-9)
                worse_lo += int(lo_me < -1e-9)
            agent_rows.append({
                "cell": cell, "regime": sc["regime"], "concentration": sc["concentration"],
                "contention": sc["contention"], "seed": seed, "policy": policy,
                "utility_family": r.get("utility_family", ""), "task_workload_hash": sc["task_workload_hash"],
                "scenario_hash": sc["scenario_hash"], "agent": a["id"], "archetype": a.get("archetype", ""),
                "queue_order_completion": qoc, "locally_optimized_completion": loc_c,
                "locally_optimized_count": loc_n, "qo_completion_minus_equal": qo_me,
                "lo_completion_minus_equal": lo_me, "mandatory_failures": a.get("mandatory_failures", 0),
                "quality": a["quality"], "allocated": json.dumps(alloc),
                "charged": json.dumps(a.get("charged", {})), "unused": json.dumps(a.get("unused", {})),
                "min_bound": json.dumps(lower), "upper_bound": json.dumps(spec["upper"]),
                "any_used_at_lower_bound": any_at, "all_used_above_lower_bound": all_above,
                "alloc_distance_from_equal": dist, "latent_probs": json.dumps(spec["latent_probs"]),
            })
        run_rows.append({
            "cell": cell, "regime": sc["regime"], "concentration": sc["concentration"],
            "contention": sc["contention"], "seed": seed, "policy": policy,
            "utility_family": r.get("utility_family", ""), "task_workload_hash": sc["task_workload_hash"],
            "scenario_hash": sc["scenario_hash"], "feasible": True, "solver_status": r.get("solver_status", ""),
            "fallback_used": r.get("fallback_used", False),
            "queue_order_completion_mean": float(np.mean(qo)),
            "queue_order_tasks_per_run": float(np.mean(qo)) * n * tpa,
            "locally_optimized_completion_mean": float(np.mean(lo)),
            "locally_optimized_tasks_per_run": float(np.mean(lo)) * n * tpa,
            "qo_completion_min": float(np.min(qo)), "qo_completion_p5": float(np.percentile(qo, 5)),
            "lo_completion_min": float(np.min(lo)), "lo_completion_p5": float(np.percentile(lo, 5)),
            "frac_zero_qo": n_zero_qo / len(qo), "frac_zero_lo": n_zero_lo / len(lo),
            "frac_worse_than_equal_qo": (worse_qo / len(qo)) if eq_alloc is not None else "",
            "frac_worse_than_equal_lo": (worse_lo / len(lo)) if eq_alloc is not None else "",
            "mean_change_vs_equal_qo": (float(np.mean(chg_qo)) if chg_qo else ""),
            "worst_change_vs_equal_qo": (float(np.min(chg_qo)) if chg_qo else ""),
            "capacity_utilization": r["capacity_utilization"], "allocation_consumption": r["allocation_consumption"],
            "unused_installed_total": unused_total,
            "alloc_distance_from_equal_mean": (float(np.mean(dists)) if dists else ""),
            "mandatory_failures_total": r.get("mandatory_failures_total", 0),
            "capacity_by_resource": json.dumps(r.get("capacity_by_resource", {})),
            "mandatory_demand_by_resource": json.dumps(r.get("mandatory_demand_by_resource", {})),
            "realized_contention_by_resource": json.dumps(r.get("realized_contention_by_resource", {})),
            "capacity_violation": r["capacity_violation"], "bound_violation": r["bound_violation"],
            "alloc_latency_ms": r["allocation_latency_ms"],
        })

    _write(os.path.join(RAW, "scenarios.csv"), SCEN_FIELDS, scen_rows)
    _write(os.path.join(RAW, "runs.csv"), RUN_FIELDS, run_rows)
    _write(os.path.join(RAW, "agents.csv"), AGENT_FIELDS, agent_rows)
    _write(os.path.join(RAW, "infeasible.csv"), INFEASIBLE_FIELDS, infeasible_rows)

    expected = len(cfg["workload_regimes"]) * len(contention) * len(seeds) * len(policies)
    assert len(run_rows) + len(infeasible_rows) == expected, \
        "attempted %d != expected %d" % (len(run_rows) + len(infeasible_rows), expected)

    # Disjointness of seeds and task-workload hashes vs canonical and exploratory.
    conf_seeds = set(seeds)
    canon_seeds = {derive_seed("%s__%s" % (c, k), "test", i)
                   for c in ("homogeneous", "mixed_bundle") for k in ("moderate", "high") for i in range(100)}
    explor_seeds = set(wlgen.dev_seeds("heterogeneity_pilot", 30))
    conf_wh = {s["task_workload_hash"] for s in scen_rows}
    canon_wh = {r["workload_hash"] for r in _read(os.path.join(CANON_RAW, "runs.csv"))}
    explor_wh = ({r["task_workload_hash"] for r in _read(os.path.join(HERE, "results", "raw", "workload_workloads.csv"))}
                 | {r["task_workload_hash"] for r in _read(os.path.join(HERE, "results", "raw", "floor_workloads.csv"))})
    disjoint = {
        "seeds_vs_canonical_overlap": len(conf_seeds & canon_seeds),
        "seeds_vs_exploratory_overlap": len(conf_seeds & explor_seeds),
        "workload_hash_vs_canonical_overlap": len(conf_wh & canon_wh),
        "workload_hash_vs_exploratory_overlap": len(conf_wh & explor_wh),
    }
    for k, v in disjoint.items():
        assert v == 0, "disjointness violation: %s = %d" % (k, v)

    summary = {
        "protocol": "confirmatory_v1", "floor_regime": floor,
        "n_regimes": len(cfg["workload_regimes"]), "n_contention": len(contention),
        "n_seeds_per_cell": len(seeds), "policies": policies,
        "n_agents": n, "tasks_per_agent": tpa, "expected_runs": expected,
        "feasible_runs": len(run_rows), "infeasible_runs": len(infeasible_rows),
        "n_agent_records": len(agent_rows), "n_scenario_rows": len(scen_rows),
        "capacity_violations_total": sum(int(r["capacity_violation"]) for r in run_rows),
        "bound_violations_total": sum(int(r["bound_violation"]) for r in run_rows),
        "fallback_used_total": sum(1 for r in run_rows if str(r["fallback_used"]).lower() == "true"),
        "bootstrap_seed": cfg["bootstrap_seed"], "n_bootstrap": cfg["n_bootstrap"],
        "seed_label": cfg["seed_label"], "primary_policy": cfg["primary_policy"],
        "co_primary_cells": cfg["co_primary_cells"], "disjointness": disjoint,
        "declaration_source": cfg["declaration_source"],
    }
    with open(os.path.join(OUT, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    log("feasible=%d infeasible=%d agents=%d scenarios=%d capviol=%d bndviol=%d fallback=%d disjoint=%s"
        % (len(run_rows), len(infeasible_rows), len(agent_rows), len(scen_rows),
           summary["capacity_violations_total"], summary["bound_violations_total"],
           summary["fallback_used_total"], disjoint))
    with open(os.path.join(LOGS, "run_confirmatory_v1.log"), "w") as f:
        f.write("\n".join(lines) + "\n")


def _read(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def _write(path, fields, rows):
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


if __name__ == "__main__":
    main()
