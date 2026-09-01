#!/usr/bin/env python3
"""Preregistered confirmatory analysis (frozen protocol v1).

Reads the confirmatory raw records, computes per-cell paired comparisons for both
queue-order and locally-optimized completion with a 10,000-resample paired
bootstrap at the fixed configured seed, writes tables and a machine-readable
headline, and evaluates the frozen success rule on the two co-primary cells. All
statistics are reported per cell; pooled figures are labelled secondary."""
import csv
import json
import os
from collections import defaultdict

import numpy as np

from pilotlib import pilot_analysis  # noqa: F401  import first: sets the canonical lib path
from lib.analysis import cell_bootstrap

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "results", "confirmatory_v1")
RAW = os.path.join(OUT, "raw")
TABLES = os.path.join(OUT, "tables")
POLICIES = ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
            "joint_cobb_douglas", "joint_ces", "joint_leontief"]
REGIME_ORDER = ["homogeneous", "iid_uniform", "dirichlet_3.0", "dirichlet_1.0",
                "dirichlet_0.3", "dirichlet_0.1", "dirichlet_0.03"]
TPR = 48
COMPARISONS = [("joint_leontief", "equal"), ("joint_leontief", "drf"),
               ("joint_cobb_douglas", "drf"), ("decomposed_cobb_douglas", "drf"),
               ("joint_cobb_douglas", "decomposed_cobb_douglas"),
               ("joint_ces", "drf"), ("joint_linear", "drf")]
METRICS = {"queue_order": "queue_order_completion_mean",
           "local_opt": "locally_optimized_completion_mean"}


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def load_json(p):
    with open(p) as f:
        return json.load(f)


def index_runs(runs):
    idx = {}
    seeds_by_cell = defaultdict(list)
    seen = set()
    meta = {}
    for r in runs:
        idx[(r["cell"], r["seed"], r["policy"])] = r
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by_cell[r["cell"]].append(r["seed"])
        meta[r["cell"]] = {"regime": r["regime"], "concentration": r["concentration"],
                           "contention": r["contention"]}
    return idx, seeds_by_cell, meta


def paired_diffs(idx, seeds, cell, treat, base, field):
    return [float(idx[(cell, s, treat)][field]) - float(idx[(cell, s, base)][field])
            for s in seeds if (cell, s, treat) in idx and (cell, s, base) in idx]


def comparison_stats(diffs, name, boot_seed, n_boot):
    a = np.asarray(diffs, float)
    b = cell_bootstrap(diffs, name, boot_seed, n_boot)
    eps = 1e-9
    win = float(np.mean(a > eps)) if a.size else 0.0
    tie = float(np.mean(np.abs(a) <= eps)) if a.size else 0.0
    lose = float(np.mean(a < -eps)) if a.size else 0.0
    return {
        "mean_frac": b["mean"], "ci_lo_frac": b["ci_lo"], "ci_hi_frac": b["ci_hi"],
        "mean_tasks": b["mean"] * TPR, "ci_lo_tasks": b["ci_lo"] * TPR, "ci_hi_tasks": b["ci_hi"] * TPR,
        "n": int(a.size), "std_tasks": float(np.std(a * TPR)),
        "frac_win": win, "frac_tie": tie, "frac_lose": lose,
    }


def agent_stats(agents_by, cell, policy):
    rows = agents_by[(cell, policy)]
    qo = np.array([float(a["queue_order_completion"]) for a in rows])
    lo = np.array([float(a["locally_optimized_completion"]) for a in rows])
    qwe = [float(a["qo_completion_minus_equal"]) for a in rows if a["qo_completion_minus_equal"] not in ("", None)]
    lwe = [float(a["lo_completion_minus_equal"]) for a in rows if a["lo_completion_minus_equal"] not in ("", None)]
    unused = np.array([sum(json.loads(a["unused"]).values()) for a in rows], float)
    return {
        "qo_mean": float(qo.mean()), "qo_min": float(qo.min()), "qo_p5": float(np.percentile(qo, 5)),
        "lo_mean": float(lo.mean()), "lo_min": float(lo.min()), "lo_p5": float(np.percentile(lo, 5)),
        "frac_zero_qo": float(np.mean(qo <= 1e-12)), "frac_zero_lo": float(np.mean(lo <= 1e-12)),
        "frac_worse_qo": (float(np.mean(np.array(qwe) < -1e-9)) if qwe else None),
        "frac_worse_lo": (float(np.mean(np.array(lwe) < -1e-9)) if lwe else None),
        "mean_change_vs_equal_qo": (float(np.mean(qwe)) if qwe else None),
        "worst_change_vs_equal_qo": (float(np.min(qwe)) if qwe else None),
        "unused_installed_mean": float(unused.mean()),
    }


def run_mean(idx, seeds, cell, policy, field):
    vals = [float(idx[(cell, s, policy)][field]) for s in seeds
            if (cell, s, policy) in idx and idx[(cell, s, policy)][field] not in ("", None)]
    return float(np.mean(vals)) if vals else None


def main():
    cfg = load_json(os.path.join(HERE, "config", "confirmatory_v1.json"))
    boot_seed, n_boot = cfg["bootstrap_seed"], cfg["n_bootstrap"]
    runs = load_csv(os.path.join(RAW, "runs.csv"))
    agents = load_csv(os.path.join(RAW, "agents.csv"))
    scenarios = load_csv(os.path.join(RAW, "scenarios.csv"))
    summary = load_json(os.path.join(OUT, "summary.json"))
    idx, seeds_by_cell, meta = index_runs(runs)
    agents_by = defaultdict(list)
    for a in agents:
        agents_by[(a["cell"], a["policy"])].append(a)

    # Dissimilarity per cell
    diss = defaultdict(lambda: defaultdict(list))
    for s in scenarios:
        cell = s["cell"]
        diss[cell]["res"].append(float(s["resource_demand_tv_mean_pairwise"]))
        diss[cell]["task"].append(float(s["task_mixture_tv_mean_pairwise"]))
        diss[cell]["ent"].append(float(s["task_entropy_mean"]))
        diss[cell]["four"].append(float(s["frac_agents_all_four_archetypes"]))
    diss_out = {c: {"res_tv_mean": float(np.mean(d["res"])), "res_tv_min": float(np.min(d["res"])),
                    "res_tv_max": float(np.max(d["res"])), "res_tv_p5": float(np.percentile(d["res"], 5)),
                    "res_tv_p95": float(np.percentile(d["res"], 95)),
                    "task_tv_mean": float(np.mean(d["task"])), "entropy_mean": float(np.mean(d["ent"])),
                    "frac_all_four_mean": float(np.mean(d["four"]))} for c, d in diss.items()}

    # Per-cell per-policy means and paired comparisons (both metrics)
    cell_policy = {}
    paired = {}
    for cell in seeds_by_cell:
        seeds = seeds_by_cell[cell]
        cell_policy[cell] = {}
        for p in POLICIES:
            if (cell, seeds[0], p) not in idx:
                continue
            cell_policy[cell][p] = {
                "n_seeds": len(seeds),
                "qo_completion_mean": run_mean(idx, seeds, cell, p, "queue_order_completion_mean"),
                "qo_tasks_per_run": run_mean(idx, seeds, cell, p, "queue_order_tasks_per_run"),
                "lo_completion_mean": run_mean(idx, seeds, cell, p, "locally_optimized_completion_mean"),
                "lo_tasks_per_run": run_mean(idx, seeds, cell, p, "locally_optimized_tasks_per_run"),
                "cap_util": run_mean(idx, seeds, cell, p, "capacity_utilization"),
                "unused_installed_total_mean": run_mean(idx, seeds, cell, p, "unused_installed_total"),
                "alloc_distance_from_equal": run_mean(idx, seeds, cell, p, "alloc_distance_from_equal_mean"),
                **agent_stats(agents_by, cell, p),
            }
        paired[cell] = {}
        for metric_name, field in METRICS.items():
            paired[cell][metric_name] = {}
            for treat, base in COMPARISONS:
                if (cell, seeds[0], treat) not in idx or (cell, seeds[0], base) not in idx:
                    continue
                d = paired_diffs(idx, seeds, cell, treat, base, field)
                name = "%s|%s|%s_minus_%s" % (cell, metric_name, treat, base)
                paired[cell][metric_name]["%s_minus_%s" % (treat, base)] = comparison_stats(
                    d, name, boot_seed, n_boot)

    # Frozen success rule on co-primary cells (queue-order)
    success = {"rule": cfg["success_rule"], "cells": {}, "confirmed": None}
    zero_events = (summary["capacity_violations_total"] == 0 and summary["bound_violations_total"] == 0
                   and summary["fallback_used_total"] == 0 and summary["infeasible_runs"] == 0)
    all_pass = True
    for cell in cfg["co_primary_cells"]:
        leo = cell_policy[cell]["joint_leontief"]
        drf = cell_policy[cell]["drf"]
        cmp_qo = paired[cell]["queue_order"]["joint_leontief_minus_drf"]
        c1 = leo["qo_completion_mean"] > drf["qo_completion_mean"]
        c2 = cmp_qo["ci_lo_frac"] > 0.0
        c3 = cmp_qo["mean_tasks"] >= 1.0
        c4 = leo["frac_zero_qo"] <= drf["frac_zero_qo"] + 1e-12
        c5 = zero_events
        cell_pass = c1 and c2 and c3 and c4 and c5
        all_pass = all_pass and cell_pass
        success["cells"][cell] = {
            "leontief_qo_completion": leo["qo_completion_mean"], "drf_qo_completion": drf["qo_completion_mean"],
            "leontief_minus_drf_tasks": cmp_qo["mean_tasks"],
            "ci_lo_frac": cmp_qo["ci_lo_frac"], "ci_hi_frac": cmp_qo["ci_hi_frac"],
            "ci_lo_tasks": cmp_qo["ci_lo_tasks"], "ci_hi_tasks": cmp_qo["ci_hi_tasks"],
            "leontief_frac_zero_qo": leo["frac_zero_qo"], "drf_frac_zero_qo": drf["frac_zero_qo"],
            "c1_mean_higher_than_drf": c1, "c2_interval_above_zero": c2,
            "c3_at_least_one_task_per_run": c3, "c4_no_zero_completion_increase": c4,
            "c5_zero_events": c5, "cell_pass": cell_pass,
            "local_opt_leontief_minus_drf_tasks": paired[cell]["local_opt"]["joint_leontief_minus_drf"]["mean_tasks"],
            "local_opt_ci_lo_frac": paired[cell]["local_opt"]["joint_leontief_minus_drf"]["ci_lo_frac"],
        }
    success["confirmed"] = all_pass

    headline = {
        "protocol": "confirmatory_v1", "bootstrap_seed": boot_seed, "n_bootstrap": n_boot,
        "tasks_per_run": TPR, "primary_policy": cfg["primary_policy"],
        "co_primary_cells": cfg["co_primary_cells"], "comparisons": ["%s_minus_%s" % c for c in COMPARISONS],
        "dissimilarity": diss_out, "cell_policy": cell_policy, "paired": paired,
        "success_rule_evaluation": success, "summary": summary,
    }
    os.makedirs(TABLES, exist_ok=True)
    with open(os.path.join(OUT, "confirmatory_headline.json"), "w") as f:
        json.dump(headline, f, indent=2)

    # Tables
    _write_paired(os.path.join(TABLES, "paired_comparisons.csv"), paired, meta, diss_out)
    _write_cell_policy(os.path.join(TABLES, "cell_policy_effects.csv"), cell_policy, meta)
    _write_distributional(os.path.join(TABLES, "distributional.csv"), cell_policy, meta)
    _write_dissimilarity(os.path.join(TABLES, "dissimilarity.csv"), diss_out, meta)

    print("confirmed=%s" % all_pass)
    for cell in cfg["co_primary_cells"]:
        s = success["cells"][cell]
        print("  %s: leo-drf QO=%.3f t [%.4f,%.4f frac]; pass=%s (c1=%s c2=%s c3=%s c4=%s c5=%s); LO=%.3f t"
              % (cell, s["leontief_minus_drf_tasks"], s["ci_lo_frac"], s["ci_hi_frac"], s["cell_pass"],
                 s["c1_mean_higher_than_drf"], s["c2_interval_above_zero"], s["c3_at_least_one_task_per_run"],
                 s["c4_no_zero_completion_increase"], s["c5_zero_events"], s["local_opt_leontief_minus_drf_tasks"]))
    print("wrote", os.path.join(OUT, "confirmatory_headline.json"))


def _order(cells):
    out = []
    for cont in ("moderate", "high"):
        for reg in REGIME_ORDER:
            c = "%s__%s" % (reg, cont)
            if c in cells:
                out.append(c)
    return out


def _w(path, header, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def _write_paired(path, paired, meta, diss):
    header = ["cell", "regime", "contention", "res_tv_mean", "metric", "comparison",
              "mean_frac", "ci_lo_frac", "ci_hi_frac", "mean_tasks", "ci_lo_tasks", "ci_hi_tasks",
              "n_seeds", "std_tasks", "frac_win", "frac_tie", "frac_lose"]
    rows = []
    for cell in _order(paired):
        for metric_name in ("queue_order", "local_opt"):
            for comp, st in paired[cell][metric_name].items():
                rows.append([cell, meta[cell]["regime"], meta[cell]["contention"],
                             "%.6f" % diss[cell]["res_tv_mean"], metric_name, comp,
                             "%.6f" % st["mean_frac"], "%.6f" % st["ci_lo_frac"], "%.6f" % st["ci_hi_frac"],
                             "%.4f" % st["mean_tasks"], "%.4f" % st["ci_lo_tasks"], "%.4f" % st["ci_hi_tasks"],
                             st["n"], "%.4f" % st["std_tasks"], "%.4f" % st["frac_win"],
                             "%.4f" % st["frac_tie"], "%.4f" % st["frac_lose"]])
    _w(path, header, rows)


def _write_cell_policy(path, cp, meta):
    header = ["cell", "regime", "contention", "policy", "n_seeds",
              "qo_completion_mean", "qo_tasks_per_run", "lo_completion_mean", "lo_tasks_per_run",
              "cap_util", "alloc_distance_from_equal", "unused_installed_total_mean"]
    rows = []
    for cell in _order(cp):
        for p in POLICIES:
            if p not in cp[cell]:
                continue
            r = cp[cell][p]
            rows.append([cell, meta[cell]["regime"], meta[cell]["contention"], p, r["n_seeds"],
                         "%.6f" % r["qo_completion_mean"], "%.4f" % r["qo_tasks_per_run"],
                         "%.6f" % r["lo_completion_mean"], "%.4f" % r["lo_tasks_per_run"],
                         "%.6f" % r["cap_util"], "%.6f" % (r["alloc_distance_from_equal"] or 0.0),
                         "%.4f" % r["unused_installed_total_mean"]])
    _w(path, header, rows)


def _write_distributional(path, cp, meta):
    header = ["cell", "regime", "contention", "policy",
              "qo_mean", "qo_min", "qo_p5", "lo_mean", "lo_min", "lo_p5",
              "frac_zero_qo", "frac_zero_lo", "frac_worse_qo", "frac_worse_lo",
              "mean_change_vs_equal_qo", "worst_change_vs_equal_qo", "unused_installed_mean"]
    rows = []
    for cell in _order(cp):
        for p in POLICIES:
            if p not in cp[cell]:
                continue
            r = cp[cell][p]
            def g(k):
                return "" if r[k] is None else "%.6f" % r[k]
            rows.append([cell, meta[cell]["regime"], meta[cell]["contention"], p,
                         g("qo_mean"), g("qo_min"), g("qo_p5"), g("lo_mean"), g("lo_min"), g("lo_p5"),
                         g("frac_zero_qo"), g("frac_zero_lo"), g("frac_worse_qo"), g("frac_worse_lo"),
                         g("mean_change_vs_equal_qo"), g("worst_change_vs_equal_qo"), g("unused_installed_mean")])
    _w(path, header, rows)


def _write_dissimilarity(path, diss, meta):
    header = ["cell", "regime", "contention", "res_tv_mean", "res_tv_min", "res_tv_max",
              "res_tv_p5", "res_tv_p95", "task_tv_mean", "entropy_mean", "frac_all_four_mean"]
    rows = []
    for cell in _order(diss):
        d = diss[cell]
        rows.append([cell, meta[cell]["regime"], meta[cell]["contention"],
                     "%.6f" % d["res_tv_mean"], "%.6f" % d["res_tv_min"], "%.6f" % d["res_tv_max"],
                     "%.6f" % d["res_tv_p5"], "%.6f" % d["res_tv_p95"], "%.6f" % d["task_tv_mean"],
                     "%.6f" % d["entropy_mean"], "%.6f" % d["frac_all_four_mean"]])
    _w(path, header, rows)


if __name__ == "__main__":
    main()
