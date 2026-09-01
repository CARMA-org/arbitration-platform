#!/usr/bin/env python3
"""Clean-room independent reconstruction of the heterogeneity confirmatory result.

Reads ONLY the committed raw CSV/JSON files and reconstructs every reported
statistic with newly written logic. It imports none of the project's analysis,
bootstrap, manifest, local-optimization, or seed modules; the ``derive_seed`` and
``cell_bootstrap`` procedures are reimplemented here from their documented
definitions so the committed intervals and disjointness can be reproduced without
trusting the project's code.

Usage: python3 independent_reconstruction.py <repo_root> <out_dir>
Outputs: reconstructed_primary.json, reconstructed_curve.csv,
reconstructed_distribution.csv, and a PASS/FAIL log on stdout.
"""
import csv
import hashlib
import json
import os
import sys
from collections import defaultdict

import numpy as np

RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]
POLICIES = ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
            "joint_cobb_douglas", "joint_ces", "joint_leontief"]
REGIME_ORDER = ["homogeneous", "iid_uniform", "dirichlet_3.0", "dirichlet_1.0",
                "dirichlet_0.3", "dirichlet_0.1", "dirichlet_0.03"]
TPR = 48
BOOT_SEED = 20260901       # frozen in config/confirmatory_v1.json
N_BOOT = 10000             # frozen
CO_PRIMARY = ["dirichlet_0.1__moderate", "dirichlet_0.1__high"]

CHECKS = []


def check(name, ok, detail=""):
    CHECKS.append((name, bool(ok), detail))
    print("  [%s] %s  %s" % ("PASS" if ok else "FAIL", name, detail))


# ---- reimplemented primitives (documented procedures, not imported) ----
def derive_seed(*parts):
    key = "|".join(str(p) for p in parts)
    return int(hashlib.sha256(key.encode()).hexdigest()[:16], 16) % (2 ** 32)


def boot_ci(diffs, name, boot_seed=BOOT_SEED, n_boot=N_BOOT):
    diffs = np.asarray(diffs, float)
    rng = np.random.default_rng(int(hashlib.sha256(("%s|%d" % (name, boot_seed)).encode()).hexdigest()[:16], 16))
    means = np.array([diffs[rng.integers(0, len(diffs), len(diffs))].mean() for _ in range(n_boot)])
    return float(diffs.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def jl(s):
    return json.loads(s)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out = sys.argv[2] if len(sys.argv) > 2 else "."
    base = os.path.join(root, "experiments", "platform_mediation_heterogeneity")
    conf = os.path.join(base, "results", "confirmatory_v1")
    runs = load_csv(os.path.join(conf, "raw", "runs.csv"))
    agents = load_csv(os.path.join(conf, "raw", "agents.csv"))
    scen = load_csv(os.path.join(conf, "raw", "scenarios.csv"))
    infeas = load_csv(os.path.join(conf, "raw", "infeasible.csv"))
    summary = json.load(open(os.path.join(conf, "summary.json")))

    print("=== STRUCTURE ===")
    check("runs_count_19600", len(runs) == 19600, str(len(runs)))
    check("agents_count_117600", len(agents) == 117600, str(len(agents)))
    check("scenarios_count_2800", len(scen) == 2800, str(len(scen)))
    check("infeasible_empty", len(infeas) == 0, str(len(infeas)))

    # unique keys / dedup
    run_keys = [(r["cell"], r["seed"], r["policy"]) for r in runs]
    check("run_keys_unique", len(set(run_keys)) == len(run_keys), "%d unique" % len(set(run_keys)))
    ag_keys = [(a["cell"], a["seed"], a["policy"], a["agent"]) for a in agents]
    check("agent_keys_unique", len(set(ag_keys)) == len(ag_keys), "%d unique" % len(set(ag_keys)))
    sc_keys = [(s["cell"], s["seed"]) for s in scen]
    check("scenario_keys_unique", len(set(sc_keys)) == len(sc_keys), "%d unique" % len(set(sc_keys)))

    # policies per scenario / agents per run / seeds per cell
    pol_by_cs = defaultdict(set)
    for r in runs:
        pol_by_cs[(r["cell"], r["seed"])].add(r["policy"])
    check("seven_policies_per_scenario", all(v == set(POLICIES) for v in pol_by_cs.values()),
          "cells×seeds=%d" % len(pol_by_cs))
    ag_by_run = defaultdict(set)
    for a in agents:
        ag_by_run[(a["cell"], a["seed"], a["policy"])].add(a["agent"])
    check("six_agents_per_run", all(len(v) == 6 for v in ag_by_run.values()), "runs=%d" % len(ag_by_run))
    seeds_by_cell = defaultdict(set)
    for r in runs:
        seeds_by_cell[r["cell"]].add(r["seed"])
    check("200_unique_seeds_per_cell", all(len(v) == 200 for v in seeds_by_cell.values()),
          "cells=%d min=%d max=%d" % (len(seeds_by_cell), min(len(v) for v in seeds_by_cell.values()),
                                      max(len(v) for v in seeds_by_cell.values())))

    # 8 tasks per agent (completion is a multiple of 1/8; task counts sum to 8)
    mult8 = all(abs(float(a["queue_order_completion"]) * 8 - round(float(a["queue_order_completion"]) * 8)) < 1e-9
                for a in agents)
    check("completion_multiple_of_1_8", mult8, "agent queue-order completions are k/8")
    tc_ok = all(sum(v for v in jl(s["realized_task_counts_by_agent"])[i].values()) == 8
                for s in scen for i in range(6))
    check("eight_tasks_per_agent", tc_ok, "realized task counts sum to 8 per agent")

    # scenario/workload hash equality across policies within (cell,seed)
    sh_by_cs = defaultdict(set)
    wh_by_cs = defaultdict(set)
    for r in runs:
        sh_by_cs[(r["cell"], r["seed"])].add(r["scenario_hash"])
        wh_by_cs[(r["cell"], r["seed"])].add(r["task_workload_hash"])
    check("scenario_hash_shared_across_policies", all(len(v) == 1 for v in sh_by_cs.values()), "")
    check("workload_hash_shared_across_policies", all(len(v) == 1 for v in wh_by_cs.values()), "")
    # same workload across contention (per regime, seed)
    wl_by_rs = defaultdict(set)
    for s in scen:
        wl_by_rs[(s["regime"], s["seed"])].add(s["task_workload_hash"])
    check("same_task_workload_across_contention", all(len(v) == 1 for v in wl_by_rs.values()), "")

    # disjointness (reimplemented derive_seed; hashes from committed raw)
    conf_seeds = set(int(x) for x in seeds_by_cell[CO_PRIMARY[0]] | set().union(*seeds_by_cell.values()))
    expected_conf = set(derive_seed("heterogeneity_confirmatory_v1", "test", i) for i in range(200))
    check("confirmatory_seeds_match_derive_seed", conf_seeds == expected_conf,
          "distinct=%d expected=%d equal=%s" % (len(conf_seeds), len(expected_conf), conf_seeds == expected_conf))
    canon_seeds = set(derive_seed("%s__%s" % (c, k), "test", i)
                      for c in ("homogeneous", "mixed_bundle") for k in ("moderate", "high") for i in range(100))
    pilot_seeds = set(derive_seed("heterogeneity_pilot", "dev", i) for i in range(30))
    check("seeds_disjoint_from_canonical", not (conf_seeds & canon_seeds), "overlap=%d" % len(conf_seeds & canon_seeds))
    check("seeds_disjoint_from_pilot", not (conf_seeds & pilot_seeds), "overlap=%d" % len(conf_seeds & pilot_seeds))
    conf_wh = set(s["task_workload_hash"] for s in scen)
    canon_wh = set(r["workload_hash"] for r in load_csv(
        os.path.join(root, "experiments", "platform_mediation", "results", "raw", "runs.csv")))
    pilot_wh = set(r["task_workload_hash"] for r in load_csv(os.path.join(base, "results", "raw", "workload_workloads.csv")))
    pilot_wh |= set(r["task_workload_hash"] for r in load_csv(os.path.join(base, "results", "raw", "floor_workloads.csv")))
    check("workload_hash_disjoint_from_canonical", not (conf_wh & canon_wh), "overlap=%d" % len(conf_wh & canon_wh))
    check("workload_hash_disjoint_from_pilot", not (conf_wh & pilot_wh), "overlap=%d" % len(conf_wh & pilot_wh))

    # no redraw / exclusion / infeasible / fallback / violations
    check("no_redraws", all(int(s["redraws"]) == 0 for s in scen)
          and all(s["reject_reason"] == "" for s in scen), "redraws all 0, no reject reasons")
    check("all_feasible", all(r["feasible"] == "True" for r in runs), "")
    check("no_fallback", all(str(r["fallback_used"]).lower() != "true" for r in runs), "")
    capv = sum(int(r["capacity_violation"]) for r in runs)
    bndv = sum(int(r["bound_violation"]) for r in runs)
    check("no_capacity_or_bound_violation", capv == 0 and bndv == 0, "cap=%d bnd=%d" % (capv, bndv))
    check("run_plus_infeasible_equals_expected", len(runs) + len(infeas) == 19600, "")

    # solver status counts
    status = defaultdict(lambda: defaultdict(int))
    for r in runs:
        status[r["policy"]][r["solver_status"]] += 1
    # accept 'optimal' or 'optimal_inaccurate' (the solver may append '(with warnings)');
    # optimal_inaccurate is a valid feasible cvxpy status also present in the canonical eval.
    joint_optimal = all("optimal" in st for p in ("joint_linear", "joint_cobb_douglas", "joint_ces", "joint_leontief")
                        for st in status[p])
    inacc = sum(v for p in ("joint_linear", "joint_cobb_douglas", "joint_ces", "joint_leontief")
                for st, v in status[p].items() if "inaccurate" in st)
    check("joint_solver_status_optimal_or_inaccurate", joint_optimal,
          "all joint statuses contain 'optimal'; optimal_inaccurate count=%d/11200" % inacc)

    # conservation + completion reconstruction + conversion
    ag_idx = defaultdict(list)
    for a in agents:
        ag_idx[(a["cell"], a["seed"], a["policy"])].append(a)
    cap_ok = charged_ok = compl_ok = conv_ok = True
    for r in runs:
        cap = jl(r["capacity_by_resource"])
        rows = ag_idx[(r["cell"], r["seed"], r["policy"])]
        colsum = {rr: 0 for rr in RESOURCES}
        comps = []
        for a in rows:
            al = jl(a["allocated"]); ch = jl(a["charged"]); un = jl(a["unused"])
            for rr in RESOURCES:
                colsum[rr] += al[rr]
                if ch[rr] > al[rr] + 1e-9 or abs(un[rr] - (al[rr] - ch[rr])) > 1e-9:
                    charged_ok = False
            comps.append(float(a["queue_order_completion"]))
        for rr in RESOURCES:
            if colsum[rr] > cap[rr]:
                cap_ok = False
        if abs(np.mean(comps) - float(r["queue_order_completion_mean"])) > 1e-9:
            compl_ok = False
        if abs(float(r["queue_order_completion_mean"]) * TPR - float(r["queue_order_tasks_per_run"])) > 1e-9:
            conv_ok = False
    check("capacity_conservation", cap_ok, "sum(allocated_r) <= capacity_r for every run")
    check("charged_within_allocated_and_unused_consistent", charged_ok, "charged<=allocated, unused=allocated-charged")
    check("run_completion_equals_agent_mean", compl_ok, "run mean == mean of 6 agent completions")
    check("tasks_per_run_conversion_x48", conv_ok, "queue_order_tasks_per_run == mean*48")

    # ---- PRIMARY reconstruction ----
    print("=== PRIMARY (independent) ===")
    # seed order = first appearance in runs.csv (matches the analysis' seeds_by_cell build order)
    seed_order = defaultdict(list)
    seen = set()
    run_mean = {}
    for r in runs:
        run_mean[(r["cell"], r["seed"], r["policy"])] = float(r["queue_order_completion_mean"])
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seed_order[r["cell"]].append(r["seed"])

    def policy_mean(cell, p):
        return float(np.mean([run_mean[(cell, s, p)] for s in seed_order[cell]]))

    def paired(cell, treat, base):
        return np.array([run_mean[(cell, s, treat)] - run_mean[(cell, s, base)] for s in seed_order[cell]])

    # agent-level zero-completion fraction per (cell, policy)
    def frac_zero(cell, p):
        rows = ag_idx_by_cp[(cell, p)]
        return float(np.mean([1.0 if float(a["queue_order_completion"]) <= 1e-12 else 0.0 for a in rows]))
    ag_idx_by_cp = defaultdict(list)
    for a in agents:
        ag_idx_by_cp[(a["cell"], a["policy"])].append(a)

    zero_events = (summary["capacity_violations_total"] == 0 and summary["bound_violations_total"] == 0
                   and summary["fallback_used_total"] == 0 and summary["infeasible_runs"] == 0)

    primary = {"bootstrap_seed": BOOT_SEED, "n_bootstrap": N_BOOT, "cells": {}}
    all_pass = True
    for cell in CO_PRIMARY:
        leo = policy_mean(cell, "joint_leontief")
        drf = policy_mean(cell, "drf")
        eq = policy_mean(cell, "equal")
        d = paired(cell, "joint_leontief", "drf")
        name = "%s|queue_order|joint_leontief_minus_drf" % cell
        m, lo, hi = boot_ci(d, name)
        # paired-t SE (secondary sensitivity)
        se = float(np.std(d, ddof=1) / np.sqrt(len(d)))
        de = paired(cell, "joint_leontief", "equal")
        name_e = "%s|queue_order|joint_leontief_minus_equal" % cell
        me, loe, hie = boot_ci(de, name_e)
        fz_leo, fz_drf = frac_zero(cell, "joint_leontief"), frac_zero(cell, "drf")
        c1 = leo > drf
        c2 = lo > 0.0
        c3 = (m * TPR) >= 1.0
        c4 = fz_leo <= fz_drf + 1e-12
        c5 = zero_events
        cell_pass = c1 and c2 and c3 and c4 and c5
        all_pass = all_pass and cell_pass
        primary["cells"][cell] = {
            "leontief_mean": leo, "drf_mean": drf, "equal_mean": eq,
            "leo_minus_drf_frac": m, "ci_lo_frac": lo, "ci_hi_frac": hi,
            "leo_minus_drf_tasks": m * TPR, "ci_lo_tasks": lo * TPR, "ci_hi_tasks": hi * TPR,
            "paired_t_se_tasks": se * TPR, "n_seeds": len(d),
            "leo_minus_equal_tasks": me * TPR, "leo_minus_equal_ci_lo_tasks": loe * TPR,
            "leo_minus_equal_ci_hi_tasks": hie * TPR,
            "frac_zero_leontief": fz_leo, "frac_zero_drf": fz_drf, "frac_zero_denominator": len(ag_idx_by_cp[(cell, "joint_leontief")]),
            "c1_mean_gt_drf": c1, "c2_ci_above_zero": c2, "c3_ge_one_task": c3,
            "c4_no_zero_increase": c4, "c5_zero_events": c5, "cell_pass": cell_pass,
        }
        print("  %s: Leo=%.4f DRF=%.4f eq=%.4f | Leo-DRF=%.4ft [%.4f,%.4f]t (frac[%.5f,%.5f]) SE=%.4ft | Leo-eq=%.3ft"
              % (cell, leo, drf, eq, m * TPR, lo * TPR, hi * TPR, lo, hi, se * TPR, me * TPR))
        print("     c1=%s c2=%s c3=%s c4=%s(%.4f<=%.4f) c5=%s -> PASS=%s"
              % (c1, c2, c3, c4, fz_leo, fz_drf, c5, cell_pass))
    primary["confirmed_all_co_primary_pass"] = all_pass

    # ---- DISTRIBUTIONAL ----
    dist_rows = []
    for cell in CO_PRIMARY:
        for p in ("equal", "drf", "joint_leontief", "joint_cobb_douglas", "joint_ces"):
            rows = ag_idx_by_cp[(cell, p)]
            comps = np.array([float(a["queue_order_completion"]) for a in rows])
            diffe = np.array([float(a["qo_completion_minus_equal"]) for a in rows if a["qo_completion_minus_equal"] not in ("", None)])
            capu = np.mean([float(run_mean_full[(cell, s, p)]["capacity_utilization"]) for s in seed_order[cell]])
            unused = np.mean([float(run_mean_full[(cell, s, p)]["unused_installed_total"]) for s in seed_order[cell]])
            dist_rows.append({
                "cell": cell, "policy": p, "mean": comps.mean(), "min": comps.min(),
                "p5": float(np.percentile(comps, 5)), "frac_zero": float(np.mean(comps <= 1e-12)),
                "frac_worse_than_equal": (float(np.mean(diffe < -1e-9)) if diffe.size else float("nan")),
                "worst_change_vs_equal": (float(diffe.min()) if diffe.size else 0.0),
                "cap_util": capu, "unused_installed": unused, "n_agents": len(rows),
            })

    # ---- RESPONSE CURVE (all regimes) ----
    curve = []
    for reg in REGIME_ORDER:
        for cont in ("moderate", "high"):
            cell = "%s__%s" % (reg, cont)
            if cell not in seed_order:
                continue
            d = paired(cell, "joint_leontief", "drf")
            name = "%s|queue_order|joint_leontief_minus_drf" % cell
            m, lo, hi = boot_ci(d, name)
            res_tv = np.mean([float(s["resource_demand_tv_mean_pairwise"]) for s in scen if s["cell"] == cell])
            ent = np.mean([float(s["task_entropy_mean"]) for s in scen if s["cell"] == cell])
            curve.append({"regime": reg, "contention": cont, "res_tv_mean": float(res_tv),
                          "entropy_mean": float(ent), "leo_minus_drf_tasks": m * TPR,
                          "ci_lo_frac": lo, "ci_hi_frac": hi, "ci_lo_tasks": lo * TPR, "ci_hi_tasks": hi * TPR})

    # monotonicity test (exact) on the response curve, per contention, in concentration order
    mono = {}
    for cont in ("moderate", "high"):
        vals = [c["leo_minus_drf_tasks"] for c in curve if c["contention"] == cont]
        strict = all(vals[i] < vals[i + 1] for i in range(len(vals) - 1))
        weak = all(vals[i] <= vals[i + 1] for i in range(len(vals) - 1))
        # violations of weak monotonicity
        viol = [(REGIME_ORDER[i], vals[i], REGIME_ORDER[i + 1], vals[i + 1])
                for i in range(len(vals) - 1) if vals[i] > vals[i + 1] + 1e-12]
        mono[cont] = {"values_in_concentration_order": vals, "strict": strict, "weak": weak, "violations": viol}
    # confirm resource-demand TV is itself monotone in concentration order (so the
    # concentration order equals the dissimilarity order for the monotonicity claim)
    res_seq = [c["res_tv_mean"] for c in curve if c["contention"] == "moderate"]
    mono["res_tv_monotone_in_concentration_order"] = all(res_seq[i] < res_seq[i + 1] for i in range(len(res_seq) - 1))
    mono["res_tv_sequence"] = res_seq
    primary["monotonicity"] = mono

    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "reconstructed_primary.json"), "w") as f:
        json.dump(primary, f, indent=2)
    with open(os.path.join(out, "reconstructed_curve.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(curve[0].keys()), lineterminator="\n")
        w.writeheader(); w.writerows(curve)
    with open(os.path.join(out, "reconstructed_distribution.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(dist_rows[0].keys()), lineterminator="\n")
        w.writeheader(); w.writerows(dist_rows)

    print("=== MONOTONICITY (concentration order: homogeneous->iid->3.0->1.0->0.3->0.1->0.03) ===")
    for cont in ("moderate", "high"):
        print("  %s: strict=%s weak=%s violations=%s" % (cont, mono[cont]["strict"], mono[cont]["weak"], mono[cont]["violations"]))

    n_fail = sum(1 for _, ok, _ in CHECKS if not ok)
    print("=== RECONSTRUCTED DECISION: confirmed=%s ; structural failures=%d ===" % (all_pass, n_fail))
    return 0 if n_fail == 0 else 1


# capacity/unused per run needs a small index built before distributional loop
run_mean_full = {}
if __name__ == "__main__":
    _root = sys.argv[1] if len(sys.argv) > 1 else "."
    _conf = os.path.join(_root, "experiments", "platform_mediation_heterogeneity", "results", "confirmatory_v1")
    for _r in load_csv(os.path.join(_conf, "raw", "runs.csv")):
        run_mean_full[(_r["cell"], _r["seed"], _r["policy"])] = _r
    sys.exit(main())
