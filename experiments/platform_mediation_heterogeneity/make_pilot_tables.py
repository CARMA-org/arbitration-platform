#!/usr/bin/env python3
"""Build the pilot headline JSON and the per-cell / dissimilarity / floor tables
from the raw sweep records. All paper-facing pilot numbers derive from the
headline, which is exactly reconstructible from the raw CSVs."""
import csv
import json
import os
from collections import defaultdict

import numpy as np

from pilotlib import pilot_analysis as pa
from lib.analysis import cell_bootstrap

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
RAW = os.path.join(RESULTS, "raw")
TABLES = os.path.join(HERE, "tables")
RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]
TPR = pa.TASKS_PER_RUN


def load_config():
    with open(os.path.join(HERE, "config", "pilot.json")) as f:
        return json.load(f)


def _exists(sweep):
    return os.path.exists(os.path.join(RAW, "%s_runs.csv" % sweep))


def agent_stats(agents_by, cell, policy):
    rows = agents_by.get((cell, policy), [])
    comps = [float(a["completion"]) for a in rows]
    if not comps:
        return {}
    worse_vals = [float(a["completion_minus_equal"]) for a in rows
                  if a["completion_minus_equal"] not in ("", None)]
    return {
        "agent_completion_mean": float(np.mean(comps)),
        "agent_completion_min": float(np.min(comps)),
        "agent_completion_p5": float(np.percentile(comps, 5)),
        "frac_zero_completion": float(np.mean([1.0 if c <= 1e-12 else 0.0 for c in comps])),
        "frac_worse_than_equal": (float(np.mean([1.0 if v < -1e-9 else 0.0 for v in worse_vals]))
                                  if worse_vals else None),
    }


def run_mean(idx, seeds, cell, policy, field):
    vals = [float(idx[(cell, s, policy)][field]) for s in seeds
            if (cell, s, policy) in idx and idx[(cell, s, policy)][field] not in ("", None)]
    return float(np.mean(vals)) if vals else None


def latency_stats(idx, seeds, cell, policy):
    vals = [float(idx[(cell, s, policy)]["alloc_latency_ms"]) for s in seeds if (cell, s, policy) in idx]
    if not vals:
        return None, None
    return float(np.median(vals)), float(np.percentile(vals, 95))


def build_cell_policy(sweep, cfg, policies):
    runs = pa.load_rows(sweep, "runs")
    agents = pa.load_rows(sweep, "agents")
    idx, seeds_by_cell, cell_meta = pa.index_runs(runs)
    agents_by = defaultdict(list)
    for a in agents:
        agents_by[(a["cell"], a["policy"])].append(a)
    boot = cfg["bootstrap_seed"]
    nboot = cfg["n_bootstrap"]

    headline_cells = {}
    rows = []
    for cell in sorted(seeds_by_cell):
        seeds = seeds_by_cell[cell]
        meta = cell_meta[cell]
        headline_cells[cell] = {}
        for p in policies:
            comp = pa.cell_policy_mean(idx, seeds, cell, p, "completion_mean")
            if comp is None:
                continue
            de = pa.paired_diffs(idx, seeds, cell, p, "equal")
            dd = pa.paired_diffs(idx, seeds, cell, p, "drf")
            be = cell_bootstrap(de, "%s|%s|equal" % (cell, p), boot, nboot)
            bd = cell_bootstrap(dd, "%s|%s|drf" % (cell, p), boot, nboot)
            ast = agent_stats(agents_by, cell, p)
            lat_med, lat_p95 = latency_stats(idx, seeds, cell, p)
            rec = {
                "cell": cell, "regime": meta["regime"], "concentration": meta["concentration"],
                "contention": meta["contention"], "floor_regime": meta["floor_regime"], "policy": p,
                "n_seeds": len(seeds), "completion_mean": comp, "tasks_per_run_mean": comp * TPR,
                "diff_vs_equal": be["mean"], "diff_vs_equal_tasks": be["mean"] * TPR,
                "diff_vs_equal_ci_lo": be["ci_lo"], "diff_vs_equal_ci_hi": be["ci_hi"],
                "diff_vs_equal_ci_lo_tasks": be["ci_lo"] * TPR, "diff_vs_equal_ci_hi_tasks": be["ci_hi"] * TPR,
                "diff_vs_drf": bd["mean"], "diff_vs_drf_tasks": bd["mean"] * TPR,
                "diff_vs_drf_ci_lo": bd["ci_lo"], "diff_vs_drf_ci_hi": bd["ci_hi"],
                "capacity_utilization": run_mean(idx, seeds, cell, p, "capacity_utilization"),
                "alloc_distance_from_equal": run_mean(idx, seeds, cell, p, "alloc_distance_from_equal_mean"),
                "latency_median_ms": lat_med, "latency_p95_ms": lat_p95,
            }
            rec.update(ast)
            rows.append(rec)
            headline_cells[cell][p] = rec
    return rows, headline_cells, seeds_by_cell


def build_dissimilarity(cfg):
    wl = pa.load_rows("workload", "workloads")
    by = defaultdict(list)
    for w in wl:
        by[(w["regime"], w["concentration"], w["contention"])].append(w)
    rows = []
    diss_headline = {}
    for (regime, conc, cont), ws in sorted(by.items()):
        res = np.array([float(w["resource_demand_tv_mean_pairwise"]) for w in ws])
        task = np.array([float(w["task_mixture_tv_mean_pairwise"]) for w in ws])
        ent = np.array([float(w["task_entropy_mean"]) for w in ws])
        four = np.array([float(w["frac_agents_all_four_archetypes"]) for w in ws])
        cent = np.array([float(w["resource_centroid_distance_mean"]) for w in ws])
        active = np.array([float(w["active_resource_count"]) for w in ws])
        realized = []
        for w in ws:
            rc = json.loads(w["realized_contention_by_resource"])
            active_vals = [v for v in rc.values() if v > 0]
            if active_vals:
                realized.append(float(np.mean(active_vals)))
        rec = {
            "regime": regime, "concentration": conc, "contention": cont, "n_seeds": len(ws),
            "resource_demand_tv_mean": float(res.mean()), "resource_demand_tv_median": float(np.median(res)),
            "resource_demand_tv_min": float(res.min()), "resource_demand_tv_max": float(res.max()),
            "resource_demand_tv_p95": float(np.percentile(res, 95)),
            "task_mixture_tv_mean": float(task.mean()), "task_entropy_mean": float(ent.mean()),
            "frac_all_four_mean": float(four.mean()),
            "resource_centroid_distance_mean": float(cent.mean()),
            "active_resource_count_mean": float(active.mean()),
            "mean_realized_contention": float(np.mean(realized)) if realized else None,
        }
        rows.append(rec)
        diss_headline["%s|%s" % (regime, cont)] = rec
    return rows, diss_headline


def build_floor(cfg):
    runs = pa.load_rows("floor", "runs")
    agents = pa.load_rows("floor", "agents")
    wl = pa.load_rows("floor", "workloads")
    infeas = pa.load_rows("floor", "infeasible") if _infeasible_exists("floor") else []
    idx, seeds_by_cell, cell_meta = pa.index_runs(runs)
    agents_by = defaultdict(list)
    for a in agents:
        agents_by[(a["cell"], a["policy"])].append(a)
    agents_all = defaultdict(list)
    for a in agents:
        agents_all[a["cell"]].append(a)
    floor_frac = defaultdict(list)
    for w in wl:
        vals = [v for v in json.loads(w["floor_fraction_by_resource"]).values()]
        floor_frac[w["cell"]].append(float(np.mean(vals)) if vals else 0.0)
    infeas_count = defaultdict(int)
    for r in infeas:
        infeas_count[(r["cell"], r["policy"])] += 1

    policies = cfg["floor_sweep"]["policies"]
    boot = cfg["bootstrap_seed"]
    nboot = cfg["n_bootstrap"]
    rows = []
    headline = {}
    for cell in sorted(seeds_by_cell):
        seeds = seeds_by_cell[cell]
        meta = cell_meta[cell]
        headline[cell] = {}
        for p in policies:
            comp = pa.cell_policy_mean(idx, seeds, cell, p, "completion_mean")
            if comp is None:
                continue
            de = pa.paired_diffs(idx, seeds, cell, p, "equal")
            dd = pa.paired_diffs(idx, seeds, cell, p, "drf")
            be = cell_bootstrap(de, "floor|%s|%s|equal" % (cell, p), boot, nboot)
            bd = cell_bootstrap(dd, "floor|%s|%s|drf" % (cell, p), boot, nboot)
            ast = agent_stats(agents_by, cell, p)
            rec = {
                "floor_regime": meta["floor_regime"], "regime": meta["regime"],
                "concentration": meta["concentration"], "contention": meta["contention"],
                "policy": p, "n_seeds": len(seeds),
                "completion_mean": comp, "tasks_per_run_mean": comp * TPR,
                "diff_vs_equal": be["mean"], "diff_vs_equal_tasks": be["mean"] * TPR,
                "diff_vs_equal_ci_lo": be["ci_lo"], "diff_vs_equal_ci_hi": be["ci_hi"],
                "diff_vs_drf": bd["mean"], "diff_vs_drf_tasks": bd["mean"] * TPR,
                "frac_zero_completion": ast.get("frac_zero_completion"),
                "frac_worse_than_equal": ast.get("frac_worse_than_equal"),
                "agent_completion_min": ast.get("agent_completion_min"),
                "agent_completion_p5": ast.get("agent_completion_p5"),
                "frac_alloc_cells_at_lower": pa.allocation_cell_at_lower_fraction(
                    agents_all[cell], cell, p, RESOURCES),
                "frac_agents_any_used_at_lower": run_mean(
                    idx, seeds, cell, p, "frac_agents_any_used_at_lower_bound"),
                "alloc_distance_from_equal": run_mean(idx, seeds, cell, p, "alloc_distance_from_equal_mean"),
                "capacity_utilization": run_mean(idx, seeds, cell, p, "capacity_utilization"),
                "capacity_violation": int(sum(int(idx[(cell, s, p)]["capacity_violation"])
                                              for s in seeds if (cell, s, p) in idx)),
                "bound_violation": int(sum(int(idx[(cell, s, p)]["bound_violation"])
                                           for s in seeds if (cell, s, p) in idx)),
                "infeasible_runs": infeas_count.get((cell, p), 0),
                "floor_fraction_mean": float(np.mean(floor_frac[cell])) if floor_frac[cell] else 0.0,
            }
            rows.append(rec)
            headline[cell][p] = rec
    return rows, headline


def _infeasible_exists(sweep):
    return os.path.exists(os.path.join(RAW, "%s_infeasible.csv" % sweep))


def write_csv(name, rows):
    if not rows:
        return
    os.makedirs(TABLES, exist_ok=True)
    with open(os.path.join(TABLES, name), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def main():
    cfg = load_config()
    headline = {"tasks_per_run": TPR, "bootstrap_seed": cfg["bootstrap_seed"],
                "n_bootstrap": cfg["n_bootstrap"]}

    if _exists("workload"):
        cp_rows, cp_head, seeds_by_cell = build_cell_policy("workload", cfg, cfg["policies"])
        write_csv("cell_policy_effects.csv", cp_rows)
        diss_rows, diss_head = build_dissimilarity(cfg)
        write_csv("workload_dissimilarity.csv", diss_rows)
        headline["workload"] = {"cells": cp_head, "dissimilarity": diss_head,
                                "seeds_per_cell": {c: len(s) for c, s in seeds_by_cell.items()}}
        print("workload: %d cell-policy rows, %d dissimilarity rows" % (len(cp_rows), len(diss_rows)))

    if _exists("floor"):
        fl_rows, fl_head = build_floor(cfg)
        write_csv("floor_sensitivity.csv", fl_rows)
        headline["floor"] = {"cells": fl_head}
        print("floor: %d rows" % len(fl_rows))

    with open(os.path.join(RESULTS, "pilot_headline.json"), "w") as f:
        json.dump(headline, f, indent=2)
    print("wrote", os.path.join(RESULTS, "pilot_headline.json"))


if __name__ == "__main__":
    main()
