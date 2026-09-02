#!/usr/bin/env python3
"""Preregistered analysis for the original-question closure (frozen protocol v1).

Two subcommands:
  architecture  -> reads results/architecture_v1/raw, writes architecture_headline.json
                   and tables (paired comparisons, five-condition decisions, distributed
                   equivalence, distributional and harmed-set statistics), and the
                   machine-readable pass flags the carrier rule consumes.
  drift         -> reads results/drift_v1/raw, writes drift_headline.json and tables
                   (co-primary decision, per-delta secondary comparisons, difference-in-
                   differences, declaration-error, distributional statistics, and the
                   robustness classification).

All paired bootstraps are percentile 95% intervals over the scenario seed as the
resampling unit, with the frozen bootstrap seed and 20000 resamples. Completion
differences are reported in tasks per 48-task run.
"""
import argparse
import csv
import json
import os
from collections import defaultdict

import numpy as np

import oqlib  # noqa: F401
from lib.analysis import cell_bootstrap
from lib.archetypes import RESOURCES

HERE = os.path.dirname(os.path.abspath(__file__))
ARCH = os.environ.get("OQ_ARCH_DIR", os.path.join(HERE, "results", "architecture_v1"))
DRIFT = os.environ.get("OQ_DRIFT_DIR", os.path.join(HERE, "results", "drift_v1"))
TPR = 48
BOOT_SEED = 20260902
N_BOOT = 20000
EPS = 1e-9


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def load_json(p):
    with open(p) as f:
        return json.load(f)


def paired(idx, seeds, cell, treat, base, field):
    out = []
    for s in seeds:
        kt, kb = (cell, s, treat), (cell, s, base)
        if kt in idx and kb in idx and idx[kt][field] not in ("", None) and idx[kb][field] not in ("", None):
            out.append(float(idx[kt][field]) - float(idx[kb][field]))
    return out


def stats_tasks(diffs, name):
    a = np.asarray(diffs, float)
    b = cell_bootstrap(diffs, name, BOOT_SEED, N_BOOT)
    return {"mean_tasks": b["mean"] * TPR, "ci_lo_tasks": b["ci_lo"] * TPR, "ci_hi_tasks": b["ci_hi"] * TPR,
            "mean_frac": b["mean"], "ci_lo_frac": b["ci_lo"], "ci_hi_frac": b["ci_hi"], "n": int(a.size)}


def five_condition(cell_policy, paired_qo, cell, treat, base):
    t = cell_policy[cell][treat]
    b = cell_policy[cell][base]
    cmp = paired_qo[cell]["%s_minus_%s" % (treat, base)]
    c1 = t["qo_tasks_per_run"] > b["qo_tasks_per_run"]
    c2 = cmp["ci_lo_tasks"] > 0.0
    c3 = cmp["mean_tasks"] >= 1.0
    c4 = t["frac_zero_qo"] <= b["frac_zero_qo"] + 1e-12
    c5 = t["zero_events"] and b["zero_events"]
    return {"c1_higher": c1, "c2_ci_above_zero": c2, "c3_at_least_one_task": c3,
            "c4_no_zero_increase": c4, "c5_zero_events": c5,
            "pass": bool(c1 and c2 and c3 and c4 and c5),
            "mean_tasks": cmp["mean_tasks"], "ci_lo_tasks": cmp["ci_lo_tasks"], "ci_hi_tasks": cmp["ci_hi_tasks"]}


def distributional(agents_by, cell, arm, ref_arm, seeds):
    """Per-(scenario,agent) loss/gain of arm relative to ref_arm (queue-order)."""
    a_rows = {(r["seed"], r["agent"]): float(r["queue_order_completion"]) for r in agents_by[(cell, arm)]}
    r_rows = {(r["seed"], r["agent"]): float(r["queue_order_completion"]) for r in agents_by[(cell, ref_arm)]}
    keys = sorted(set(a_rows) & set(r_rows))
    diffs = np.array([(a_rows[k] - r_rows[k]) * TPR for k in keys])  # tasks per run per agent (each agent runs 8; scale by tpa=8)
    # per-agent completion is a fraction of 8 tasks; multiply by 8 for tasks completed by that agent
    per_agent = np.array([(a_rows[k] - r_rows[k]) * 8 for k in keys])
    harmed = per_agent < -EPS
    better = per_agent > EPS
    comp = np.array([a_rows[k] * 8 for k in keys])
    return {
        "n_agent_obs": int(len(keys)),
        "frac_harmed": float(np.mean(harmed)) if len(keys) else 0.0,
        "n_harmed": int(harmed.sum()),
        "mean_loss_harmed": float(-per_agent[harmed].mean()) if harmed.any() else 0.0,
        "median_loss_harmed": float(-np.median(per_agent[harmed])) if harmed.any() else 0.0,
        "worst_loss": float(-per_agent.min()) if len(keys) else 0.0,
        "frac_better": float(np.mean(better)) if len(keys) else 0.0,
        "mean_gain_better": float(per_agent[better].mean()) if better.any() else 0.0,
        "median_gain_better": float(np.median(per_agent[better])) if better.any() else 0.0,
        "frac_zero": float(np.mean(comp <= 1e-12)) if len(keys) else 0.0,
        "min_completion_tasks": float(comp.min()) if len(keys) else 0.0,
        "bottom_decile_tasks": float(np.percentile(comp, 10)) if len(keys) else 0.0,
        "mean_completion_tasks": float(comp.mean()) if len(keys) else 0.0,
    }


def harmed_set_compare(agents_by, cell, arm_a, arm_b, ref_arm):
    """Compare who is harmed (relative to ref_arm) under arm_a vs arm_b, per (seed,agent)."""
    def harmset(arm):
        a = {(r["seed"], r["agent"]): float(r["queue_order_completion"]) for r in agents_by[(cell, arm)]}
        rr = {(r["seed"], r["agent"]): float(r["queue_order_completion"]) for r in agents_by[(cell, ref_arm)]}
        keys = set(a) & set(rr)
        return a, rr, keys
    a_a, ref_a, ka = harmset(arm_a)
    a_b, ref_b, kb = harmset(arm_b)
    keys = sorted(ka & kb)
    harmed_a = {k for k in keys if a_a[k] - ref_a[k] < -EPS}
    harmed_b = {k for k in keys if a_b[k] - ref_b[k] < -EPS}
    inter = harmed_a & harmed_b
    union = harmed_a | harmed_b
    agree = sum(1 for k in keys if (k in harmed_a) == (k in harmed_b))
    eq_complete = sum(1 for k in keys if abs(a_a[k] - a_b[k]) <= 1e-12)
    diffs = np.array([abs(a_a[k] - a_b[k]) * 8 for k in keys])
    # bottom-decile membership (per cell) agreement
    comp_a = {k: a_a[k] for k in keys}
    comp_b = {k: a_b[k] for k in keys}
    thr_a = np.percentile(list(comp_a.values()), 10) if keys else 0
    thr_b = np.percentile(list(comp_b.values()), 10) if keys else 0
    bd_a = {k for k in keys if comp_a[k] <= thr_a}
    bd_b = {k for k in keys if comp_b[k] <= thr_b}
    bd_agree = sum(1 for k in keys if (k in bd_a) == (k in bd_b)) / len(keys) if keys else 1.0
    prec = len(inter) / len(harmed_b) if harmed_b else (1.0 if not harmed_a else 0.0)
    rec = len(inter) / len(harmed_a) if harmed_a else (1.0 if not harmed_b else 0.0)
    return {
        "n": len(keys), "harmed_a": len(harmed_a), "harmed_b": len(harmed_b),
        "exact_harmed_set_equal": bool(harmed_a == harmed_b),
        "harm_indicator_agreement": agree / len(keys) if keys else 1.0,
        "harmed_set_jaccard": (len(inter) / len(union)) if union else 1.0,
        "a_only_harmed": len(harmed_a - harmed_b), "b_only_harmed": len(harmed_b - harmed_a),
        "precision_ref_a": prec, "recall_ref_a": rec,
        "per_agent_completion_equality": eq_complete / len(keys) if keys else 1.0,
        "max_abs_per_agent_diff_tasks": float(diffs.max()) if keys else 0.0,
        "mean_abs_per_agent_diff_tasks": float(diffs.mean()) if keys else 0.0,
        "harmed_fraction_abs_diff_pp": abs(len(harmed_a) - len(harmed_b)) / len(keys) * 100 if keys else 0.0,
        "bottom_decile_membership_agreement": bd_agree,
    }


def analyze_architecture():
    cfg = load_json(os.path.join(HERE, "config", "architecture_v1.json"))
    runs = load_csv(os.path.join(ARCH, "raw", "runs.csv"))
    agents = load_csv(os.path.join(ARCH, "raw", "agents.csv"))
    dist = load_csv(os.path.join(ARCH, "raw", "distributed.csv"))
    summary = load_json(os.path.join(ARCH, "summary.json"))
    arms = cfg["arms"]
    zero_events_all = (summary["capacity_violations_total"] == 0 and summary["bound_violations_total"] == 0
                       and summary["fallback_used_total"] == 0 and summary["infeasible_runs"] == 0)

    idx, seeds_by_cell, seen = {}, defaultdict(list), set()
    for r in runs:
        idx[(r["cell"], r["seed"], r["arm"])] = r
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by_cell[r["cell"]].append(r["seed"])
    agents_by = defaultdict(list)
    for a in agents:
        agents_by[(a["cell"], a["arm"])].append(a)

    cells = cfg["co_primary_cells"]
    cell_policy = {}
    for cell in cells:
        seeds = seeds_by_cell[cell]
        cell_policy[cell] = {}
        for arm in arms:
            rows = [idx[(cell, s, arm)] for s in seeds if (cell, s, arm) in idx]
            qo = np.array([float(r["queue_order_completion_mean"]) for r in rows])
            bs = np.array([float(r["best_subset_completion_mean"]) for r in rows])
            fz = np.array([float(r["frac_zero_qo"]) for r in rows])
            cell_policy[cell][arm] = {
                "n": len(rows), "qo_tasks_per_run": float(np.mean([float(r["queue_order_tasks_per_run"]) for r in rows])),
                "bs_tasks_per_run": float(np.mean([float(r["best_subset_tasks_per_run"]) for r in rows])),
                "qo_completion_mean": float(qo.mean()), "bs_completion_mean": float(bs.mean()),
                "frac_zero_qo": float(fz.mean()),
                "cap_util": float(np.mean([float(r["capacity_utilization"]) for r in rows])),
                "zero_events": zero_events_all,
            }

    COMPARISONS = [("central_joint_leontief", "drf"), ("central_joint_leontief", "independent_bundle_maxmin"),
                   ("independent_bundle_maxmin", "drf"), ("central_joint_leontief", "equal"),
                   ("independent_bundle_maxmin", "central_joint_leontief"),
                   ("independent_bundle_maxmin", "equal"), ("separable_leontief_relaxation", "equal"),
                   ("separable_leontief_relaxation", "drf"), ("distributed_price_leontief", "central_joint_leontief"),
                   ("distributed_price_leontief", "drf")]
    paired_qo, paired_bs = {}, {}
    for cell in cells:
        seeds = seeds_by_cell[cell]
        paired_qo[cell], paired_bs[cell] = {}, {}
        for treat, base in COMPARISONS:
            key = "%s_minus_%s" % (treat, base)
            paired_qo[cell][key] = stats_tasks(paired(idx, seeds, cell, treat, base, "queue_order_completion_mean"),
                                               "arch|qo|%s|%s" % (cell, key))
            paired_bs[cell][key] = stats_tasks(paired(idx, seeds, cell, treat, base, "best_subset_completion_mean"),
                                               "arch|bs|%s|%s" % (cell, key))

    # five-condition decisions
    def both(treat, base):
        return {cell: five_condition(cell_policy, paired_qo, cell, treat, base) for cell in cells}
    fresh = both("central_joint_leontief", "drf")
    coordination = both("central_joint_leontief", "independent_bundle_maxmin")
    indep_drf = both("independent_bundle_maxmin", "drf")

    replication_pass = all(fresh[c]["pass"] for c in cells)
    coordination_pass = replication_pass and all(coordination[c]["pass"] for c in cells)
    independent_positive = all(indep_drf[c]["pass"] for c in cells)
    # independent noninferior to central
    indep_noninf = {}
    for cell in cells:
        cmp = paired_qo[cell]["independent_bundle_maxmin_minus_central_joint_leontief"]
        indep_noninf[cell] = bool(cmp["mean_tasks"] >= -0.25 and cmp["ci_lo_tasks"] >= -0.5 and cmp["ci_hi_tasks"] <= 0.5)
    independent_noninferior = all(indep_noninf[c] for c in cells)
    # central-independent equivalence
    cent_indep_equiv = {}
    for cell in cells:
        cmp = paired_qo[cell]["independent_bundle_maxmin_minus_central_joint_leontief"]
        cent_indep_equiv[cell] = bool(abs(cmp["mean_tasks"]) <= 0.25 and cmp["ci_lo_tasks"] >= -0.5 and cmp["ci_hi_tasks"] <= 0.5)

    # separable relaxation structural: allocation and outcome equality vs equal
    def equality_rates(arm_a, arm_b):
        rates = {}
        for cell in cells:
            a_rows = {(r["seed"], r["agent"]): (r["allocated"], float(r["queue_order_completion"]))
                      for r in agents_by[(cell, arm_a)]}
            b_rows = {(r["seed"], r["agent"]): (r["allocated"], float(r["queue_order_completion"]))
                      for r in agents_by[(cell, arm_b)]}
            keys = sorted(set(a_rows) & set(b_rows))
            alloc_eq = np.mean([a_rows[k][0] == b_rows[k][0] for k in keys]) if keys else 1.0
            out_eq = np.mean([abs(a_rows[k][1] - b_rows[k][1]) <= 1e-12 for k in keys]) if keys else 1.0
            rates[cell] = {"allocation_equality_rate": float(alloc_eq), "outcome_equality_rate": float(out_eq)}
        return rates
    relax_vs_equal = equality_rates("separable_leontief_relaxation", "equal")

    # distributed equivalence (continuous objective) from distributed.csv
    gaps = np.array([float(r["rel_obj_gap"]) for r in dist if r["rel_obj_gap"] not in ("", None)])
    feas = np.array([float(r["capacity_residual"]) for r in dist])
    bnd = np.array([float(r["bound_residual"]) for r in dist])
    nonconv = sum(1 for r in dist if str(r["distributed_converged"]).lower() != "true")
    disagree = np.array([int(r["installed_outcome_disagreements"]) for r in dist])
    obj_eq = bool(feas.max() <= 1e-7 and bnd.max() <= 1e-7 and np.mean(gaps <= 1e-4) >= 0.99 and gaps.max() <= 1e-3)
    # distributed outcome equivalence (paired installed completion, dist - central)
    out_stats = {}
    for cell in cells:
        seeds = seeds_by_cell[cell]
        d = paired(idx, seeds, cell, "distributed_price_leontief", "central_joint_leontief", "queue_order_completion_mean")
        out_stats[cell] = stats_tasks(d, "arch|dist_out|%s" % cell)
    outcome_eq = all(abs(out_stats[c]["mean_tasks"]) <= 0.25 and out_stats[c]["ci_lo_tasks"] >= -0.5
                     and out_stats[c]["ci_hi_tasks"] <= 0.5 for c in cells)
    if feas.max() > 1e-7 or bnd.max() > 1e-7:
        dist_class = "TECHNICALLY_INVALID"
    elif obj_eq and outcome_eq:
        dist_class = "OBJECTIVE_AND_OUTCOME_EQUIVALENT"
    elif obj_eq:
        dist_class = "OBJECTIVE_EQUIVALENT_OUTCOME_DIFFERENT"
    else:
        dist_class = "NOT_EQUIVALENT"
    distributed_equivalent = bool(obj_eq and outcome_eq)

    # harmed-set: central vs distributed, relative to equal and to drf
    harmed_central_vs_dist = {cell: {ref: harmed_set_compare(agents_by, cell, "central_joint_leontief",
                                                             "distributed_price_leontief", ref)
                                     for ref in ("equal", "drf")} for cell in cells}
    # distributional per arm vs equal and drf
    distn = {cell: {arm: {"vs_equal": distributional(agents_by, cell, arm, "equal", seeds_by_cell[cell]),
                          "vs_drf": distributional(agents_by, cell, arm, "drf", seeds_by_cell[cell])}
                    for arm in arms} for cell in cells}

    dist_gap_summary = {
        "n": int(len(gaps)), "mean": float(gaps.mean()), "median": float(np.median(gaps)),
        "p95": float(np.percentile(gaps, 95)), "max": float(gaps.max()),
        "frac_le_1e-4": float(np.mean(gaps <= 1e-4)), "max_feasibility_residual": float(feas.max()),
        "max_bound_residual": float(bnd.max()), "nonconvergence_count": int(nonconv),
        "installed_outcome_disagreements_total": int(disagree.sum()),
        "mean_iterations": float(np.mean([float(r["iterations"]) for r in dist])),
        "mean_message_count": float(np.mean([float(r.get("message_count", 0) or 0) for r in dist])),
        "mean_runtime_ms": float(np.mean([float(r.get("runtime_ms", 0) or 0) for r in dist])),
        "cont_alloc_l1_mean": float(np.mean([float(r["cont_alloc_l1_norm"]) for r in dist if r["cont_alloc_l1_norm"] not in ("", None)])),
        "cont_alloc_l1_max": float(np.max([float(r["cont_alloc_l1_norm"]) for r in dist if r["cont_alloc_l1_norm"] not in ("", None)])),
        "installed_alloc_l1_mean": float(np.mean([float(r["installed_alloc_l1_norm"]) for r in dist if r["installed_alloc_l1_norm"] not in ("", None)])),
        "installed_alloc_linf_max": float(np.max([float(r["installed_alloc_linf"]) for r in dist if r["installed_alloc_linf"] not in ("", None)])),
    }

    flags = {
        "replication_pass": replication_pass, "coordination_pass": coordination_pass,
        "independent_positive": independent_positive, "independent_noninferior": independent_noninferior,
        "distributed_equivalent": distributed_equivalent,
        "indep_noninferior_by_cell": indep_noninf, "central_independent_equivalence_by_cell": cent_indep_equiv,
    }
    headline = {
        "experiment": "architecture", "bootstrap_seed": BOOT_SEED, "n_bootstrap": N_BOOT, "tasks_per_run": TPR,
        "co_primary_cells": cells, "cell_policy": cell_policy, "paired_qo": paired_qo, "paired_best_subset": paired_bs,
        "five_condition": {"fresh_replication": fresh, "coordination": coordination, "independent_vs_drf": indep_drf},
        "flags": flags, "separable_relaxation_vs_equal": relax_vs_equal,
        "distributed_equivalence": {"classification": dist_class, "objective_equivalent": obj_eq,
                                    "outcome_equivalent": outcome_eq, "outcome_stats": out_stats,
                                    "gap_summary": dist_gap_summary},
        "harmed_set_central_vs_distributed": harmed_central_vs_dist,
        "distributional": distn, "summary": summary, "zero_events_all": zero_events_all,
    }
    os.makedirs(os.path.join(ARCH, "tables"), exist_ok=True)
    with open(os.path.join(ARCH, "architecture_headline.json"), "w") as f:
        json.dump(headline, f, indent=2)
    _write_arch_tables(cell_policy, paired_qo, cells, arms)
    print("architecture analysis:")
    print("  replication_pass=%s coordination_pass=%s independent_positive=%s independent_noninferior=%s"
          % (replication_pass, coordination_pass, independent_positive, independent_noninferior))
    print("  distributed classification=%s (obj_eq=%s outcome_eq=%s) gap max=%.2e frac<=1e-4=%.4f"
          % (dist_class, obj_eq, outcome_eq, dist_gap_summary["max"], dist_gap_summary["frac_le_1e-4"]))
    for cell in cells:
        print("  %s: central-drf %.3f [%.3f,%.3f]  central-maxmin %.3f [%.3f,%.3f]  maxmin-drf %.3f  relax==equal alloc %.3f"
              % (cell, paired_qo[cell]["central_joint_leontief_minus_drf"]["mean_tasks"],
                 paired_qo[cell]["central_joint_leontief_minus_drf"]["ci_lo_tasks"],
                 paired_qo[cell]["central_joint_leontief_minus_drf"]["ci_hi_tasks"],
                 paired_qo[cell]["central_joint_leontief_minus_independent_bundle_maxmin"]["mean_tasks"],
                 paired_qo[cell]["central_joint_leontief_minus_independent_bundle_maxmin"]["ci_lo_tasks"],
                 paired_qo[cell]["central_joint_leontief_minus_independent_bundle_maxmin"]["ci_hi_tasks"],
                 paired_qo[cell]["independent_bundle_maxmin_minus_drf"]["mean_tasks"],
                 relax_vs_equal[cell]["allocation_equality_rate"]))
    return headline


def _write_arch_tables(cell_policy, paired_qo, cells, arms):
    with open(os.path.join(ARCH, "tables", "cell_arm_means.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["cell", "arm", "qo_tasks_per_run", "bs_tasks_per_run", "frac_zero_qo", "cap_util"])
        for cell in cells:
            for arm in arms:
                r = cell_policy[cell][arm]
                w.writerow([cell, arm, "%.4f" % r["qo_tasks_per_run"], "%.4f" % r["bs_tasks_per_run"],
                            "%.6f" % r["frac_zero_qo"], "%.6f" % r["cap_util"]])
    with open(os.path.join(ARCH, "tables", "paired_comparisons.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["cell", "comparison", "mean_tasks", "ci_lo_tasks", "ci_hi_tasks", "n"])
        for cell in cells:
            for comp, st in paired_qo[cell].items():
                w.writerow([cell, comp, "%.4f" % st["mean_tasks"], "%.4f" % st["ci_lo_tasks"],
                            "%.4f" % st["ci_hi_tasks"], st["n"]])


def analyze_drift():
    cfg = load_json(os.path.join(HERE, "config", "drift_v1.json"))
    runs = load_csv(os.path.join(DRIFT, "raw", "runs.csv"))
    agents = load_csv(os.path.join(DRIFT, "raw", "agents.csv"))
    scenarios = load_csv(os.path.join(DRIFT, "raw", "scenarios.csv"))
    summary = load_json(os.path.join(DRIFT, "summary.json"))
    zero_events_all = (summary["capacity_violations_total"] == 0 and summary["bound_violations_total"] == 0
                       and summary["fallback_used_total"] == 0 and summary["infeasible_runs"] == 0)
    idx, seeds_by_cell, seen = {}, defaultdict(list), set()
    for r in runs:
        idx[(r["cell"], r["seed"], r["arm"])] = r
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by_cell[r["cell"]].append(r["seed"])

    deltas = cfg["delta_levels"]
    contention = list(cfg["contention"])
    DECLS = cfg["declaration_sources"]

    def cell_of(delta, cn):
        return "delta%.2f__%s" % (delta, cn)

    def arm_mean(cell, arm, field):
        seeds = seeds_by_cell[cell]
        vals = [float(idx[(cell, s, arm)][field]) for s in seeds if (cell, s, arm) in idx]
        return float(np.mean(vals)) if vals else None

    def frac_zero(cell, arm):
        seeds = seeds_by_cell[cell]
        vals = [float(idx[(cell, s, arm)]["frac_zero_qo"]) for s in seeds if (cell, s, arm) in idx]
        return float(np.mean(vals)) if vals else None

    # co-primary decision: carrier_stale - drf_stale at delta 0.25
    coprimary = {}
    all_pass = True
    for cc in cfg["co_primary_cells"]:
        cell = cell_of(cc["delta"], cc["contention"])
        seeds = seeds_by_cell[cell]
        d = paired(idx, seeds, cell, "carrier_stale_calibration", "drf_stale_calibration", "queue_order_completion_mean")
        st = stats_tasks(d, "drift|%s|carrier_stale_minus_drf_stale" % cell)
        c1 = arm_mean(cell, "carrier_stale_calibration", "queue_order_tasks_per_run") > arm_mean(cell, "drf_stale_calibration", "queue_order_tasks_per_run")
        c2 = st["ci_lo_tasks"] > 0.0
        c3 = st["mean_tasks"] >= 1.0
        c4 = frac_zero(cell, "carrier_stale_calibration") <= frac_zero(cell, "drf_stale_calibration") + 1e-12
        c5 = zero_events_all
        cell_pass = bool(c1 and c2 and c3 and c4 and c5)
        all_pass = all_pass and cell_pass
        coprimary[cell] = {**st, "c1_higher": c1, "c2_ci_above_zero": c2, "c3_at_least_one_task": c3,
                           "c4_no_zero_increase": c4, "c5_zero_events": c5, "pass": cell_pass}

    # secondary: per delta x contention comparisons
    secondary = {}
    for delta in deltas:
        for cn in contention:
            cell = cell_of(delta, cn)
            seeds = seeds_by_cell[cell]
            block = {}
            for src in DECLS:
                d = paired(idx, seeds, cell, "carrier_%s" % src, "drf_%s" % src, "queue_order_completion_mean")
                block["carrier_minus_drf_%s" % src] = stats_tasks(d, "drift|%s|cmd_%s" % (cell, src))
            # stale vs refreshed carrier and drf
            block["carrier_stale_minus_refreshed"] = stats_tasks(
                paired(idx, seeds, cell, "carrier_stale_calibration", "carrier_refreshed_calibration", "queue_order_completion_mean"),
                "drift|%s|csr" % cell)
            block["drf_stale_minus_refreshed"] = stats_tasks(
                paired(idx, seeds, cell, "drf_stale_calibration", "drf_refreshed_calibration", "queue_order_completion_mean"),
                "drift|%s|dsr" % cell)
            # difference-in-differences: (stale carrier - stale drf) - (refreshed carrier - refreshed drf)
            did = []
            for s in seeds:
                ks = [(cell, s, a) for a in ("carrier_stale_calibration", "drf_stale_calibration",
                                             "carrier_refreshed_calibration", "drf_refreshed_calibration")]
                if all(k in idx for k in ks):
                    v = ((float(idx[ks[0]]["queue_order_completion_mean"]) - float(idx[ks[1]]["queue_order_completion_mean"]))
                         - (float(idx[ks[2]]["queue_order_completion_mean"]) - float(idx[ks[3]]["queue_order_completion_mean"])))
                    did.append(v)
            block["difference_in_differences"] = stats_tasks(did, "drift|%s|did" % cell)
            block["arm_tasks_per_run"] = {a: arm_mean(cell, a, "queue_order_tasks_per_run")
                                          for a in set(r["arm"] for r in runs if r["cell"] == cell)}
            block["best_subset_carrier_minus_drf_stale"] = stats_tasks(
                paired(idx, seeds, cell, "carrier_stale_calibration", "drf_stale_calibration", "best_subset_completion_mean"),
                "drift|%s|bs" % cell)
            secondary[cell] = block

    classification = "ROBUST_AT_MODEST_DRIFT" if all_pass else None
    if not all_pass:
        # refreshed passes at delta 0.25?
        refreshed_pass = True
        for cc in cfg["co_primary_cells"]:
            cell = cell_of(cc["delta"], cc["contention"])
            st = secondary[cell]["carrier_minus_drf_refreshed_calibration"]
            refreshed_pass = refreshed_pass and (st["ci_lo_tasks"] > 0 and st["mean_tasks"] >= 1.0)
        oracle_pass = True
        for cc in cfg["co_primary_cells"]:
            cell = cell_of(cc["delta"], cc["contention"])
            st = secondary[cell]["carrier_minus_drf_execution_queue_oracle"]
            oracle_pass = oracle_pass and (st["ci_lo_tasks"] > 0 and st["mean_tasks"] >= 1.0)
        if refreshed_pass:
            classification = "REFRESH_DEPENDENT"
        elif oracle_pass:
            classification = "ORACLE_DEPENDENT"
        else:
            classification = "NO_MATERIAL_ADVANTAGE_IN_NEW_DESIGN"

    # drift metrics per delta
    scen_by = defaultdict(list)
    for s in scenarios:
        scen_by[s["cell"]].append(s)
    drift_metrics = {}
    for delta in deltas:
        for cn in contention:
            cell = cell_of(delta, cn)
            rows = scen_by[cell]
            if not rows:
                continue
            drift_metrics[cell] = {
                "drift_source_total_mean": float(np.mean([float(r["drift_source_total"]) for r in rows])),
                "changed_identities_total_mean": float(np.mean([float(r["changed_identities_total"]) for r in rows])),
                "task_mixture_tv_from_baseline_mean": float(np.mean([float(r["task_mixture_tv_from_baseline_mean"]) for r in rows])),
                "staleness_error_mean": float(np.mean([float(r["staleness_error_mean"]) for r in rows])),
                "calibration_error_mean": float(np.mean([float(r["calibration_error_mean"]) for r in rows])),
                "latent_oracle_error_mean": float(np.mean([float(r["latent_oracle_error_mean"]) for r in rows])),
                "realized_contention_mean": float(np.mean([max(json.loads(r["realized_contention_by_resource"]).values()) for r in rows])),
            }

    headline = {
        "experiment": "declaration_drift", "carrier": summary.get("carrier"), "bootstrap_seed": BOOT_SEED,
        "n_bootstrap": N_BOOT, "tasks_per_run": TPR, "co_primary_cells": cfg["co_primary_cells"],
        "co_primary_decision": coprimary, "declaration_robustness_classification": classification,
        "secondary": secondary, "drift_metrics": drift_metrics, "summary": summary, "zero_events_all": zero_events_all,
    }
    os.makedirs(os.path.join(DRIFT, "tables"), exist_ok=True)
    with open(os.path.join(DRIFT, "drift_headline.json"), "w") as f:
        json.dump(headline, f, indent=2)
    _write_drift_tables(secondary, deltas, contention, cell_of)
    print("drift analysis: classification=%s carrier=%s" % (classification, summary.get("carrier")))
    for cell, d in coprimary.items():
        print("  co-primary %s: carrier-drf stale %.3f [%.3f,%.3f] pass=%s"
              % (cell, d["mean_tasks"], d["ci_lo_tasks"], d["ci_hi_tasks"], d["pass"]))
    return headline


def _write_drift_tables(secondary, deltas, contention, cell_of):
    with open(os.path.join(DRIFT, "tables", "drift_response.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["cell", "delta", "contention", "comparison", "mean_tasks", "ci_lo_tasks", "ci_hi_tasks", "n"])
        for delta in deltas:
            for cn in contention:
                cell = cell_of(delta, cn)
                if cell not in secondary:
                    continue
                for comp, st in secondary[cell].items():
                    if comp in ("arm_tasks_per_run",):
                        continue
                    w.writerow([cell, delta, cn, comp, "%.4f" % st["mean_tasks"], "%.4f" % st["ci_lo_tasks"],
                                "%.4f" % st["ci_hi_tasks"], st["n"]])


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("which", choices=["architecture", "drift"])
    args = ap.parse_args(argv)
    if args.which == "architecture":
        analyze_architecture()
    else:
        analyze_drift()


if __name__ == "__main__":
    main()
