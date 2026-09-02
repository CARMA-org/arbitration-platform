#!/usr/bin/env python3
"""Architecture experiment confirmatory driver (frozen protocol v1).

Six arms per scenario, all enforced through the canonical Java runtime: ``equal``,
``drf``, ``central_joint_leontief`` (the verified joint Leontief mechanism) are computed
inside the harness; ``independent_bundle_maxmin``, ``separable_leontief_relaxation`` and
``distributed_price_leontief`` are computed in Python and installed verbatim through the
harness contract path. Fresh Dirichlet(0.1) workloads under the confirmed heterogeneity
scenario and capacity construction, exact pending-queue declarations, unit floors, two
contention levels, 200 fresh confirmatory seeds per contention cell. Records queue-order
completion (the runtime's actual execution) and exact best-subset completion (direct
enumeration of all 256 task subsets), per-agent outcomes, and the distributed solver's
convergence and objective/allocation gap versus the central continuous solver.

The run is resumable: rows are written incrementally per (cell, seed) unit, completed
units are skipped on restart, and the raw tables are rewritten in a single canonical
order at the end so the committed data is byte-identical regardless of resumption.
"""
import argparse
import json
import os
import time

import numpy as np

import oqlib  # noqa: F401  (import first: puts pilotlib, lib and the repo root on sys.path)
from pilotlib import workload as wlgen, pilot_scenario, measures, local_opt
from lib import runner
from lib.archetypes import RESOURCES
from lib.scenario import mandatory_footprint
from lib.seeds import derive_seed

from oqlib import seeds_oq as S
from oqlib import mechanisms as MECH
from oqlib.jobs import make_native_job, make_preinstalled_job, scenario_arrays
from oqlib.central_ref import central_leontief_reference
from oqlib.execute import RawStore

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "results", "architecture_v1")
LOGS = os.path.join(HERE, "logs")
TASK_TYPES = ["research", "code_review", "doc_processing", "monitoring"]

NATIVE = {"equal": "equal", "drf": "drf", "central_joint_leontief": "joint_leontief"}
PYARMS = ["independent_bundle_maxmin", "separable_leontief_relaxation", "distributed_price_leontief"]
ARMS = ["equal", "drf", "central_joint_leontief"] + PYARMS

SCEN_FIELDS = [
    "cell", "regime", "concentration", "contention", "contention_ratio", "seed",
    "task_workload_hash", "scenario_hash", "declaration_source",
    "latent_probs_by_agent", "realized_task_counts_by_agent", "unique_archetypes_per_agent",
    "frac_agents_all_four_archetypes", "task_entropy_mean", "task_mixture_tv_mean_pairwise",
    "resource_demand_tv_mean_pairwise", "resource_centroid_distance_mean",
    "aggregate_mandatory_demand", "capacity_by_resource", "realized_contention_by_resource",
    "active_resource_count",
]
RUN_FIELDS = [
    "cell", "regime", "contention", "seed", "arm", "solver_status", "feasible", "fallback_used",
    "queue_order_completion_mean", "queue_order_tasks_per_run",
    "best_subset_completion_mean", "best_subset_tasks_per_run",
    "frac_zero_qo", "frac_zero_bs", "capacity_utilization", "unused_installed_total",
    "capacity_violation", "bound_violation", "alloc_latency_ms",
]
AGENT_FIELDS = [
    "cell", "regime", "contention", "seed", "arm", "agent", "archetype",
    "queue_order_completion", "best_subset_completion", "best_subset_count",
    "mandatory_failures", "allocated", "charged", "unused", "min_bound", "upper_bound",
]
DIST_FIELDS = [
    "cell", "contention", "seed",
    "central_status", "central_objective", "distributed_objective", "rel_obj_gap",
    "distributed_converged", "iterations", "message_count", "runtime_ms",
    "capacity_residual", "bound_residual", "primal_residual", "dual_residual",
    "cont_alloc_l1_norm", "cont_alloc_linf",
    "installed_alloc_l1_norm", "installed_alloc_linf", "installed_outcome_disagreements",
    "technically_valid",
]
INFEAS_FIELDS = ["cell", "seed", "arm", "solver_status", "failure_reason"]

TABLES = {"scenarios": SCEN_FIELDS, "runs": RUN_FIELDS, "agents": AGENT_FIELDS,
          "distributed": DIST_FIELDS, "infeasible": INFEAS_FIELDS}
UNIT_KEYS = {"scenarios": ("cell", "seed"), "runs": ("cell", "seed", "arm"),
             "agents": ("cell", "seed", "arm", "agent"), "distributed": ("cell", "seed"),
             "infeasible": ("cell", "seed", "arm")}


def load_config():
    with open(os.path.join(HERE, "config", "architecture_v1.json")) as f:
        return json.load(f)


def build_scenarios(cfg, seeds, namespace):
    regime = cfg["workload_regime"]
    scenarios, subset_tables = {}, {}
    for seed in seeds:
        wl = wlgen.generate_workload(regime, seed, cfg["n_agents"], cfg["tasks_per_agent"],
                                     namespace)
        for cname, ratio in cfg["contention"].items():
            cell = "%s__%s" % (regime["name"], cname)
            sc = pilot_scenario.build_scenario(wl, cname, ratio, cfg["floor_regime"], cell)
            scenarios[(cell, seed)] = sc
            for i, a in enumerate(sc["agents"]):
                fps = [mandatory_footprint(t) for t in a["task_types"]]
                quals = [t["quality"] for t in a["tasks"]]
                subset_tables[(cell, seed, i)] = local_opt.build_subset_table(fps, quals, RESOURCES)
    return scenarios, subset_tables


def _cont_distance(A, B):
    A = np.asarray(A, float)
    B = np.asarray(B, float)
    denom = max(B.sum(), 1.0)
    return float(np.abs(A - B).sum() / denom), float(np.abs(A - B).max())


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    ap.add_argument("--block", type=int, default=50)
    ap.add_argument("--dev-seeds", type=int, default=0,
                    help="development smoke: use the disjoint development namespace and N seeds, "
                         "writing to a separate dev output dir (never touches confirmatory seeds).")
    args = ap.parse_args(argv)
    cfg = load_config()
    out = OUT
    namespace = cfg["seed_namespace"]
    n_seeds = cfg["n_seeds"]
    if args.dev_seeds > 0:
        namespace = S.NS_ARCH_DEV
        n_seeds = args.dev_seeds
        out = os.environ.get("OQ_DEV_OUT", os.path.join(HERE, "results", "_dev_architecture"))
    os.makedirs(LOGS, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    sp = args.solver_python
    n, tpa, tpr = cfg["n_agents"], cfg["tasks_per_agent"], cfg["tasks_per_run"]

    seeds = S.scenario_seeds(namespace, n_seeds)
    S.assert_disjoint_scenario_seeds(namespace, n_seeds)

    scenarios, subset_tables = build_scenarios(cfg, seeds, namespace)
    cells = ["%s__%s" % (cfg["workload_regime"]["name"], c) for c in cfg["contention"]]
    units = [(cell, seed) for cell in cells for seed in seeds]

    store = RawStore(out, TABLES, UNIT_KEYS)
    todo = [u for u in units if not store.is_done(u)]
    t0 = time.time()
    log_lines = []

    def log(m):
        line = "%s %s" % (time.strftime("%H:%M:%S"), m)
        print(line, flush=True)
        log_lines.append(line)

    log("architecture v1: solver=%s seeds=%d cells=%d arms=%d units_total=%d units_todo=%d"
        % (sp, len(seeds), len(cells), len(ARMS), len(units), len(todo)))

    for start in range(0, len(todo), args.block):
        block = todo[start:start + args.block]
        jobs, jobmeta, precomp = [], [], {}
        for (cell, seed) in block:
            sc = scenarios[(cell, seed)]
            R, Q, mn, up, c = scenario_arrays(sc)
            mm = MECH.independent_bundle_maxmin_alloc(sc)
            rx = MECH.separable_leontief_relaxation_alloc(sc)
            dist_alloc, A_dist, dist_obj, dist_info = MECH.distributed_price_leontief_full(sc)
            cref = central_leontief_reference(R, Q, mn, up, c)
            precomp[(cell, seed)] = {"R": R, "Q": Q, "mn": mn, "up": up, "c": c,
                                     "mm": mm, "rx": rx, "dist_alloc": dist_alloc,
                                     "dist_info": dist_info, "A_dist": A_dist,
                                     "dist_obj": dist_obj, "cref": cref}
            allocs = {"independent_bundle_maxmin": mm,
                      "separable_leontief_relaxation": rx,
                      "distributed_price_leontief": dist_alloc}
            for arm in ARMS:
                if arm in NATIVE:
                    jobs.append(make_native_job(sc, cell, seed, NATIVE[arm], sp))
                else:
                    jobs.append(make_preinstalled_job(sc, cell, seed, arm, allocs[arm], sp))
                jobmeta.append((cell, seed, arm))
        results = runner.run_jobs(jobs, chunk=cfg["chunk"])
        rby = {(c_, s_, a_): r for (c_, s_, a_), r in zip(jobmeta, results)}

        for (cell, seed) in block:
            sc = scenarios[(cell, seed)]
            pc = precomp[(cell, seed)]
            rows = {t: [] for t in TABLES}
            # scenario row
            md = [a["mandatory_demand"] for a in sc["agents"]]
            diss = measures.workload_dissimilarity(
                [a["task_types"] for a in sc["agents"]], md, TASK_TYPES, RESOURCES)
            task_counts = [{cc: sum(1 for t in a["task_types"] if t == cc) for cc in TASK_TYPES}
                           for a in sc["agents"]]
            rows["scenarios"].append({
                "cell": cell, "regime": sc["regime"], "concentration": sc["concentration"],
                "contention": sc["contention"], "contention_ratio": sc["contention_ratio"], "seed": seed,
                "task_workload_hash": sc["task_workload_hash"], "scenario_hash": sc["scenario_hash"],
                "declaration_source": sc["declaration_source"],
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
            })
            installed = {}   # arm -> {agent_id: (qo, bs_count, alloc)}
            for arm in ARMS:
                r = rby[(cell, seed, arm)]
                if not r.get("feasible", False):
                    rows["infeasible"].append({"cell": cell, "seed": seed, "arm": arm,
                                               "solver_status": r.get("solver_status", ""),
                                               "failure_reason": r.get("failure_reason") or r.get("error") or ""})
                    continue
                if r.get("scenario_hash") != sc["scenario_hash"] or r.get("workload_hash") != sc["task_workload_hash"]:
                    raise RuntimeError("hash mismatch %s %s %s" % (cell, seed, arm))
                qo, bs = [], []
                per_agent = {}
                unused_total = 0
                for a in r["agents"]:
                    i = int(a["id"][1:])
                    alloc = a["allocated"]
                    cnt, _, _, _ = local_opt.select_from_table(subset_tables[(cell, seed, i)], alloc, RESOURCES)
                    qoc = a["completion"]
                    qo.append(qoc)
                    bs.append(cnt / tpa)
                    unused_total += sum(a.get("unused", {}).values())
                    per_agent[a["id"]] = (qoc, cnt, alloc)
                    spec = sc["agents"][i]
                    rows["agents"].append({
                        "cell": cell, "regime": sc["regime"], "contention": sc["contention"],
                        "seed": seed, "arm": arm, "agent": a["id"], "archetype": a.get("archetype", ""),
                        "queue_order_completion": qoc, "best_subset_completion": cnt / tpa,
                        "best_subset_count": cnt, "mandatory_failures": a.get("mandatory_failures", 0),
                        "allocated": json.dumps(alloc), "charged": json.dumps(a.get("charged", {})),
                        "unused": json.dumps(a.get("unused", {})),
                        "min_bound": json.dumps(spec["min"]), "upper_bound": json.dumps(spec["upper"]),
                    })
                installed[arm] = per_agent
                rows["runs"].append({
                    "cell": cell, "regime": sc["regime"], "contention": sc["contention"], "seed": seed,
                    "arm": arm, "solver_status": r.get("solver_status", ""), "feasible": True,
                    "fallback_used": r.get("fallback_used", False),
                    "queue_order_completion_mean": float(np.mean(qo)),
                    "queue_order_tasks_per_run": float(np.mean(qo)) * n * tpa,
                    "best_subset_completion_mean": float(np.mean(bs)),
                    "best_subset_tasks_per_run": float(np.mean(bs)) * n * tpa,
                    "frac_zero_qo": float(np.mean(np.array(qo) <= 1e-12)),
                    "frac_zero_bs": float(np.mean(np.array(bs) <= 1e-12)),
                    "capacity_utilization": r["capacity_utilization"],
                    "unused_installed_total": unused_total,
                    "capacity_violation": r["capacity_violation"], "bound_violation": r["bound_violation"],
                    "alloc_latency_ms": r["allocation_latency_ms"],
                })
            # distributed vs central (continuous objective + installed allocation/outcome)
            cref = pc["cref"]
            cobj = cref.get("objective_value")
            dobj = pc["dist_obj"]
            gap = abs(dobj - cobj) / max(abs(cobj), 1e-9) if cobj is not None else ""
            A_central = np.array(cref["allocations"]) if cref.get("allocations") is not None else None
            cl1 = cli = ""
            if A_central is not None:
                cl1, cli = _cont_distance(pc["A_dist"], A_central)
            di = pc["dist_info"]
            il1 = ili = disagree = ""
            if "distributed_price_leontief" in installed and "central_joint_leontief" in installed:
                dist_ag = installed["distributed_price_leontief"]
                cen_ag = installed["central_joint_leontief"]
                Ad = np.array([[dist_ag[a][2][r] for r in RESOURCES] for a in sorted(dist_ag)])
                Ac = np.array([[cen_ag[a][2][r] for r in RESOURCES] for a in sorted(cen_ag)])
                il1, ili = _cont_distance(Ad, Ac)
                disagree = int(sum(1 for a in dist_ag if abs(dist_ag[a][0] - cen_ag[a][0]) > 1e-12))
            tech_valid = bool(di["capacity_residual"] <= 1e-7 and di["bound_residual"] <= 1e-7)
            rows["distributed"].append({
                "cell": cell, "contention": sc["contention"], "seed": seed,
                "central_status": cref.get("status", ""),
                "central_objective": cobj if cobj is not None else "",
                "distributed_objective": dobj, "rel_obj_gap": gap,
                "distributed_converged": di["converged"], "iterations": di["iterations"],
                "message_count": di["message_count"], "runtime_ms": di["runtime_ms"],
                "capacity_residual": di["capacity_residual"], "bound_residual": di["bound_residual"],
                "primal_residual": di["primal_residual"], "dual_residual": di["dual_residual"],
                "cont_alloc_l1_norm": cl1, "cont_alloc_linf": cli,
                "installed_alloc_l1_norm": il1, "installed_alloc_linf": ili,
                "installed_outcome_disagreements": disagree, "technically_valid": tech_valid,
            })
            store.append((cell, seed), rows)
        log("block %d-%d done (%.1fs elapsed)" % (start, start + len(block), time.time() - t0))

    counts = store.finalize()
    log("finalized raw: %s" % counts)

    # disjointness of confirmatory seeds and task-workload hashes
    import csv as _csv
    with open(os.path.join(out, "raw", "scenarios.csv")) as f:
        scen_rows = list(_csv.DictReader(f))
    conf_wh = {r["task_workload_hash"] for r in scen_rows}
    prior_seeds = S.canonical_seed_universe()
    disjoint = {
        "arch_seeds_vs_prior_overlap": len(set(seeds) & prior_seeds),
        "n_workload_hashes": len(conf_wh),
    }
    assert disjoint["arch_seeds_vs_prior_overlap"] == 0, "seed disjointness violation"

    with open(os.path.join(out, "raw", "runs.csv")) as f:
        run_rows = list(_csv.DictReader(f))
    with open(os.path.join(out, "raw", "agents.csv")) as f:
        agent_rows = list(_csv.DictReader(f))
    with open(os.path.join(out, "raw", "infeasible.csv")) as f:
        infeas_rows = list(_csv.DictReader(f))
    summary = {
        "experiment": "architecture", "version": "v1", "arms": ARMS,
        "n_seeds": len(seeds), "cells": cells, "expected_runs": len(units) * len(ARMS),
        "feasible_runs": len(run_rows), "infeasible_runs": len(infeas_rows),
        "n_agent_records": len(agent_rows), "n_scenario_rows": len(scen_rows),
        "capacity_violations_total": sum(int(r["capacity_violation"]) for r in run_rows),
        "bound_violations_total": sum(int(r["bound_violation"]) for r in run_rows),
        "fallback_used_total": sum(1 for r in run_rows if str(r["fallback_used"]).lower() == "true"),
        "bootstrap_seed": cfg["bootstrap_seed"], "n_bootstrap": cfg["n_bootstrap"],
        "seed_namespace": cfg["seed_namespace"], "co_primary_cells": cfg["co_primary_cells"],
        "disjointness": disjoint,
    }
    with open(os.path.join(out, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(LOGS, "run_architecture_v1.log"), "w") as f:
        f.write("\n".join(log_lines) + "\n")
    log("summary: feasible=%d infeasible=%d capviol=%d bndviol=%d fallback=%d"
        % (len(run_rows), len(infeas_rows), summary["capacity_violations_total"],
           summary["bound_violations_total"], summary["fallback_used_total"]))


if __name__ == "__main__":
    main()
