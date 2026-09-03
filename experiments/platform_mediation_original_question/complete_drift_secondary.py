#!/usr/bin/env python3
"""Post-result completion of the preregistered drift secondary reporting.

The frozen protocol (ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md, Section 6) promised drift
distributional outcomes, utilization, realized contention, and dissimilarity per delta and
contention. Several of these were recorded in the raw data or are deterministically derivable
from it under the preregistered analysis, but were not emitted into ``drift_headline.json``.
This script emits them AFTER the fact, as a completion of the preregistered secondary
reporting. It:

  * reads ONLY the frozen raw files under ``results/drift_v1/raw/``;
  * makes NO discretionary threshold and NO arm selection (every delta, contention cell,
    declaration source and applicable arm is reported);
  * uses the SAME per-agent task scaling as the architecture analysis (each agent runs 8
    tasks: per-agent completion fractions are multiplied by 8 for per-agent task counts, and
    run-level fractions by 48 for tasks per 48-task run);
  * does NOT touch ``drift_headline.json`` or ``drift_response.csv``.

Outputs (under ``results/drift_v1/preregistered_secondary_completion/``):
  * ``drift_secondary_completion.json`` -- nested, per cell / source / arm;
  * ``drift_secondary_completion.csv``  -- flat, one row per (cell, arm).

Usage: complete_drift_secondary.py            (writes into the committed drift dir)
       OQ_DRIFT_DIR=/path complete_drift_secondary.py   (analysis-only over a copy)
"""
import csv
import json
import os
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
DRIFT = os.environ.get("OQ_DRIFT_DIR", os.path.join(HERE, "results", "drift_v1"))
OUTDIR = os.path.join(DRIFT, "preregistered_secondary_completion")
TPR = 48        # tasks per 48-task run
TPA = 8         # tasks per agent (per-agent completion scaling)
EPS = 1e-9
RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]
SOURCES = ["stale_calibration", "refreshed_calibration", "latent_distribution_oracle", "execution_queue_oracle"]


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def _f(x):
    return float(x) if x not in ("", None) else 0.0


def distributional(a_rows, ref_rows):
    """Per-(seed,agent) loss/gain of an arm relative to a reference arm, in tasks (x8),
    identical in construction to the architecture analysis' distributional statistics."""
    keys = sorted(set(a_rows) & set(ref_rows))
    per = np.array([(a_rows[k] - ref_rows[k]) * TPA for k in keys], float)
    comp = np.array([a_rows[k] * TPA for k in keys], float)
    harmed = per < -EPS
    better = per > EPS
    return {
        "n_agent_obs": int(len(keys)),
        "n_harmed": int(harmed.sum()),
        "frac_harmed": float(np.mean(harmed)) if len(keys) else 0.0,
        "mean_loss_harmed": float(-per[harmed].mean()) if harmed.any() else 0.0,
        "median_loss_harmed": float(-np.median(per[harmed])) if harmed.any() else 0.0,
        "worst_loss": float(-per.min()) if len(keys) else 0.0,
        "n_improved": int(better.sum()),
        "frac_improved": float(np.mean(better)) if len(keys) else 0.0,
        "mean_gain_improved": float(per[better].mean()) if better.any() else 0.0,
        "median_gain_improved": float(np.median(per[better])) if better.any() else 0.0,
        "frac_zero": float(np.mean(comp <= 1e-12)) if len(keys) else 0.0,
        "min_completion_tasks": float(comp.min()) if len(keys) else 0.0,
        "bottom_decile_tasks": float(np.percentile(comp, 10)) if len(keys) else 0.0,
        "mean_completion_tasks": float(comp.mean()) if len(keys) else 0.0,
    }


def build():
    runs = load_csv(os.path.join(DRIFT, "raw", "runs.csv"))
    agents = load_csv(os.path.join(DRIFT, "raw", "agents.csv"))
    scen = load_csv(os.path.join(DRIFT, "raw", "scenarios.csv"))

    # index runs by (cell, seed, arm); preserve seed order of first appearance
    run_idx = {(r["cell"], r["seed"], r["arm"]): r for r in runs}
    seeds_by = defaultdict(list)
    seen = set()
    arms_by_cell = defaultdict(list)
    arm_seen = set()
    for r in runs:
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by[r["cell"]].append(r["seed"])
        if (r["cell"], r["arm"]) not in arm_seen:
            arm_seen.add((r["cell"], r["arm"]))
            arms_by_cell[r["cell"]].append(r["arm"])
    # per-agent queue-order completion by (cell, arm) -> {(seed,agent): frac}
    ag_by = defaultdict(dict)
    policy_kind = {}
    decl_source = {}
    for a in agents:
        ag_by[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = float(a["queue_order_completion"])
        policy_kind[(a["cell"], a["arm"])] = a["policy_kind"]
        decl_source[(a["cell"], a["arm"])] = a["declaration_source"]
    scen_by = defaultdict(list)
    for s in scen:
        scen_by[s["cell"]].append(s)

    def arm_run_means(cell, arm):
        seeds = seeds_by[cell]
        rows = [run_idx[(cell, s, arm)] for s in seeds if (cell, s, arm) in run_idx]
        return {
            "n": len(rows),
            "mean_qo_tasks_per_run": float(np.mean([_f(r["queue_order_tasks_per_run"]) for r in rows])),
            "mean_bs_tasks_per_run": float(np.mean([_f(r["best_subset_tasks_per_run"]) for r in rows])),
            "mean_cap_util": float(np.mean([_f(r["capacity_utilization"]) for r in rows])),
            "mean_unused_installed": float(np.mean([_f(r["unused_installed_total"]) for r in rows])),
            "frac_zero_qo": float(np.mean([_f(r["frac_zero_qo"]) for r in rows])),
            "policy_kind": policy_kind.get((cell, arm), ""),
            "declaration_source": decl_source.get((cell, arm), ""),
        }

    def scenario_metrics(cell):
        rows = scen_by[cell]
        def mean(col):
            return float(np.mean([_f(r[col]) for r in rows]))
        realized = float(np.mean([max(json.loads(r["realized_contention_by_resource"]).values()) for r in rows]))
        return {
            "n_scenarios": len(rows),
            "drift_source_total_mean": mean("drift_source_total"),
            "changed_identities_total_mean": mean("changed_identities_total"),
            "task_mixture_tv_from_baseline_mean": mean("task_mixture_tv_from_baseline_mean"),
            "mand_demand_tv_mean_pairwise": mean("mand_demand_tv_mean_pairwise"),
            "task_entropy_mean": mean("task_entropy_mean"),
            "cross_agent_dissimilarity_mean": mean("cross_agent_dissimilarity"),
            "staleness_error_mean": mean("staleness_error_mean"),
            "calibration_error_mean": mean("calibration_error_mean"),
            "latent_oracle_error_mean": mean("latent_oracle_error_mean"),
            "realized_contention_mean": realized,
        }

    # order cells by (delta, contention) as they appear
    cell_order = []
    for r in runs:
        if r["cell"] not in cell_order:
            cell_order.append(r["cell"])
    cell_order.sort(key=lambda c: (float(c.split("__")[0].replace("delta", "")), c.split("__")[1] != "moderate"))

    out = {
        "experiment": "declaration_drift_preregistered_secondary_completion",
        "note": ("Post-result completion of preregistered secondary drift reporting. Reads only the frozen "
                 "raw drift files; makes no discretionary threshold or arm selection; uses the architecture "
                 "analysis' per-agent x8 task scaling. Does not modify drift_headline.json or drift_response.csv. "
                 "The co-primary interval in drift_headline.json remains authoritative for the primary decision."),
        "tasks_per_run": TPR, "tasks_per_agent": TPA,
        "cells": {},
    }
    rows_csv = []
    for cell in cell_order:
        delta = float(cell.split("__")[0].replace("delta", ""))
        contention = cell.split("__")[1]
        sm = scenario_metrics(cell)
        eq_rows = ag_by[(cell, "equal")]
        cell_block = {"delta": delta, "contention": contention, "scenario_metrics": sm, "arms": {}}
        for arm in arms_by_cell[cell]:
            rm = arm_run_means(cell, arm)
            a_rows = ag_by[(cell, arm)]
            block = dict(rm)
            block["vs_equal"] = distributional(a_rows, eq_rows)
            # matched carrier-vs-DRF distributional outcomes (only for carrier_<source> arms)
            if arm.startswith("carrier_"):
                src = arm[len("carrier_"):]
                matched = "drf_%s" % src
                if (cell, matched) in [(cell, x) for x in arms_by_cell[cell]]:
                    block["vs_matched_drf"] = distributional(a_rows, ag_by[(cell, matched)])
            cell_block["arms"][arm] = block

            # flat CSV row
            row = {
                "cell": cell, "delta": delta, "contention": contention,
                "declaration_source": rm["declaration_source"], "arm": arm, "policy_kind": rm["policy_kind"],
                "mean_qo_tasks_per_run": rm["mean_qo_tasks_per_run"],
                "mean_bs_tasks_per_run": rm["mean_bs_tasks_per_run"],
                "mean_cap_util": rm["mean_cap_util"], "mean_unused_installed": rm["mean_unused_installed"],
                "frac_zero_qo": rm["frac_zero_qo"],
                "min_completion_tasks": block["vs_equal"]["min_completion_tasks"],
                "bottom_decile_tasks": block["vs_equal"]["bottom_decile_tasks"],
                "mean_completion_tasks": block["vs_equal"]["mean_completion_tasks"],
            }
            for pref, key in (("vs_equal", "vs_equal"), ("vs_matched_drf", "vs_matched_drf")):
                d = block.get(key)
                for fld in ("n_harmed", "frac_harmed", "mean_loss_harmed", "median_loss_harmed", "worst_loss",
                            "n_improved", "frac_improved", "mean_gain_improved", "median_gain_improved"):
                    row["%s_%s" % (pref, fld)] = (d[fld] if d is not None else "")
            row.update({
                "drift_source_total_mean": sm["drift_source_total_mean"],
                "changed_identities_total_mean": sm["changed_identities_total_mean"],
                "task_mixture_tv_from_baseline_mean": sm["task_mixture_tv_from_baseline_mean"],
                "mand_demand_tv_mean_pairwise": sm["mand_demand_tv_mean_pairwise"],
                "task_entropy_mean": sm["task_entropy_mean"],
                "cross_agent_dissimilarity_mean": sm["cross_agent_dissimilarity_mean"],
                "staleness_error_mean": sm["staleness_error_mean"],
                "calibration_error_mean": sm["calibration_error_mean"],
                "latent_oracle_error_mean": sm["latent_oracle_error_mean"],
                "realized_contention_mean": sm["realized_contention_mean"],
            })
            rows_csv.append(row)
        out["cells"][cell] = cell_block
    return out, rows_csv


CSV_FIELDS = [
    "cell", "delta", "contention", "declaration_source", "arm", "policy_kind",
    "mean_qo_tasks_per_run", "mean_bs_tasks_per_run", "mean_cap_util", "mean_unused_installed", "frac_zero_qo",
    "min_completion_tasks", "bottom_decile_tasks", "mean_completion_tasks",
    "vs_equal_n_harmed", "vs_equal_frac_harmed", "vs_equal_mean_loss_harmed", "vs_equal_median_loss_harmed",
    "vs_equal_worst_loss", "vs_equal_n_improved", "vs_equal_frac_improved", "vs_equal_mean_gain_improved",
    "vs_equal_median_gain_improved",
    "vs_matched_drf_n_harmed", "vs_matched_drf_frac_harmed", "vs_matched_drf_mean_loss_harmed",
    "vs_matched_drf_median_loss_harmed", "vs_matched_drf_worst_loss", "vs_matched_drf_n_improved",
    "vs_matched_drf_frac_improved", "vs_matched_drf_mean_gain_improved", "vs_matched_drf_median_gain_improved",
    "drift_source_total_mean", "changed_identities_total_mean", "task_mixture_tv_from_baseline_mean",
    "mand_demand_tv_mean_pairwise", "task_entropy_mean", "cross_agent_dissimilarity_mean",
    "staleness_error_mean", "calibration_error_mean", "latent_oracle_error_mean", "realized_contention_mean",
]


def main():
    out, rows_csv = build()
    os.makedirs(OUTDIR, exist_ok=True)
    with open(os.path.join(OUTDIR, "drift_secondary_completion.json"), "w") as f:
        json.dump(out, f, indent=2)
    with open(os.path.join(OUTDIR, "drift_secondary_completion.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS, lineterminator="\n")
        w.writeheader()
        for row in rows_csv:
            w.writerow({k: row.get(k, "") for k in CSV_FIELDS})
    print("wrote %d cells, %d arm rows -> %s" % (len(out["cells"]), len(rows_csv),
                                                 os.path.relpath(OUTDIR, HERE)))


if __name__ == "__main__":
    main()
