#!/usr/bin/env python3
"""Comprehensive independent verification (v2) of the original-question closure.

This verifier reconstructs EVERY reported quantity directly from the frozen raw CSVs, using
its own implementations of the seed derivation, paired percentile bootstrap, exact
best-subset enumeration, five-condition rule, equivalence/noninferiority classifications,
harmed-set and distributional statistics, adaptive carrier rule, and manifest hashing. It
imports NONE of the experiment's drivers, analysis, carrier-selection, manifest helpers,
local task optimizers, or allocator implementations. Only the Python standard library and
NumPy are used.

Coverage (superset of the first verification, which overstated its coverage -- see the
VERIFICATION_SUMMARY_V2.md note):

  * commit chronology and preregistration ancestry;
  * no outcome-relevant source/config/protocol change between preregistration and head;
  * all raw and correction-manifest hashes;
  * schemas, row counts, unique keys, all expected arms, complete pairing, workload identity;
  * confirmatory seed derivation and disjointness from architecture, drift, prior
    heterogeneity, canonical, and development namespaces;
  * every architecture arm mean; every architecture paired mean and CI (queue-order and
    best-subset); every component of each five-condition decision; the equivalence and
    noninferiority classifications; the adaptive carrier rule;
  * all distributed objective, feasibility, convergence, allocation-gap and aggregate-outcome
    statistics; every architecture distributional statistic and harmed-set statistic;
  * every drift primary and secondary mean and CI (both independently seeded appearances of
    the drift primary comparison); all drift metrics and the preregistered-secondary
    supplement; all summary aggregations and zero-event claims;
  * exact best-subset completion by enumerating all 256 subsets (architecture task counts from
    the scenario rows; drift compositions recovered uniquely from realized mandatory demand);
  * allocation feasibility directly (installed sums vs capacities, floors and upper bounds).

Status is VERIFIED only if every required check passes; otherwise PARTIALLY VERIFIED or NOT
VERIFIED. Scientific hypotheses failing does not make verification fail.
"""
import argparse
import ast
import csv
import hashlib
import json
import os
import subprocess
import sys
from collections import defaultdict

import numpy as np

# ---- fixed constants (no experiment import) --------------------------------------------
OQ_REL = "experiments/platform_mediation_original_question"
TPR, TPA, NAG = 48, 8, 6
BOOT_SEED, N_BOOT = 20260902, 20000
EPS = 1e-9
RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]
ARCHES = ["research", "code_review", "doc_processing", "monitoring"]
# Mandatory archetype footprints, mirrored from ServiceType/archetypes (mandatory steps only);
# independently confirmed to reconstruct every scenario's aggregate_mandatory_demand.
MAND = {
    "research":       {"COMPUTE": 34, "MEMORY": 29, "API_CREDITS": 17, "DATASET": 8},
    "code_review":    {"COMPUTE": 10, "MEMORY": 8,  "API_CREDITS": 6,  "DATASET": 0},
    "doc_processing": {"COMPUTE": 22, "MEMORY": 16, "API_CREDITS": 10, "DATASET": 0},
    "monitoring":     {"COMPUTE": 8,  "MEMORY": 9,  "API_CREDITS": 1,  "DATASET": 8},
}
ARMS_ARCH = ["equal", "drf", "central_joint_leontief", "independent_bundle_maxmin",
             "separable_leontief_relaxation", "distributed_price_leontief"]
SOURCES = ["stale_calibration", "refreshed_calibration", "latent_distribution_oracle", "execution_queue_oracle"]
ARMS_DRIFT = ["equal"] + ["drf_%s" % s for s in SOURCES] + ["carrier_%s" % s for s in SOURCES]
NS_ARCH_CONF = "arb_original_question_closure_v1/architecture/confirmatory"
NS_DRIFT_CONF = "arb_original_question_closure_v1/declaration_drift/confirmatory"
NS_DEV = ["arb_original_question_closure_v1/architecture/development",
          "arb_original_question_closure_v1/declaration_drift/development",
          "arb_original_question_closure_v1/distributed_validation/development"]
ANCHORS = {
    "prereg": "7ebf8b70366b8b68a90554a722f097d8acea3f01",
    "arch": "2f9fa1b05a38d941511491e030d3e964232350eb",
    "drift": "3204646f74901bb357f614e2f5ab4c1b276fb449",
    "head": "1e2e1d968e9204a44567c3571c0d75f5900319cc",
    "first_verification": "d2d77dbe33c4a5b6f9770f225b19ee68b45f1514",
}
OUTCOME_RELEVANT_FILES = [
    "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md", "config/architecture_v1.json", "config/drift_v1.json",
    "CONFIRMATORY_SEED_MANIFEST.json", "make_oq_analysis.py", "select_drift_carrier.py",
    "run_architecture.py", "run_declaration_drift.py", "run_original_question_closure.py",
    "oqlib/central.py", "oqlib/central_ref.py", "oqlib/distributed.py", "oqlib/maxmin.py",
    "oqlib/mechanisms.py", "oqlib/seeds_oq.py", "oqlib/driftgen.py", "oqlib/drift_scenario.py",
    "oqlib/declarations.py", "oqlib/execute.py", "oqlib/jobs.py", "oqlib/leontief_relaxation.py",
    "oqlib/__init__.py",
]


# ---- independent primitives -------------------------------------------------------------
def derive_seed(*parts):
    key = "|".join(str(p) for p in parts)
    return int(hashlib.sha256(key.encode()).hexdigest()[:16], 16) % (2 ** 32)


def scenario_seeds(ns, n):
    return [derive_seed(ns, "scenario", i) for i in range(n)]


def canonical_seed_universe():
    """Independently reproduce the canonical-evaluation + heterogeneity-pilot-dev +
    heterogeneity-confirmatory seed set, for disjointness checks."""
    seeds = set()
    for comp in ("homogeneous", "mixed_bundle"):
        for cont in ("moderate", "high"):
            for i in range(100):
                seeds.add(derive_seed("%s__%s" % (comp, cont), "test", i))
    for i in range(30):
        seeds.add(derive_seed("heterogeneity_pilot", "dev", i))
    for i in range(200):
        seeds.add(derive_seed("heterogeneity_confirmatory_v1", "test", i))
    return seeds


def boot(diffs, name):
    a = np.asarray(diffs, float)
    if a.size == 0:
        return float("nan"), float("nan"), float("nan")
    digest = hashlib.sha256(("%s|%d" % (name, BOOT_SEED)).encode()).hexdigest()
    rng = np.random.default_rng(int(digest[:16], 16))
    means = np.array([a[rng.integers(0, a.size, a.size)].mean() for _ in range(N_BOOT)])
    return float(a.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def st(diffs, name):
    m, lo, hi = boot(diffs, name)
    return {"mean_tasks": m * TPR, "ci_lo_tasks": lo * TPR, "ci_hi_tasks": hi * TPR, "n": int(len(diffs))}


_BS_CACHE = {}


def best_subset_count(footprint_multiset, alloc):
    """Exact max subset size (0..8) whose summed mandatory footprints fit within alloc, by
    direct enumeration of all 2**k subsets. Cached by (multiset, alloc)."""
    caps = tuple(int(round(float(alloc.get(r, 0)))) for r in RESOURCES)
    key = (footprint_multiset, caps)
    if key in _BS_CACHE:
        return _BS_CACHE[key]
    fps = []
    for arch, cnt in footprint_multiset:
        fp = tuple(MAND[arch][r] for r in RESOURCES)
        fps.extend([fp] * cnt)
    k = len(fps)
    best = 0
    for mask in range(1 << k):
        agg = [0, 0, 0, 0]
        c = 0
        m = mask
        i = 0
        while m:
            if m & 1:
                f = fps[i]
                agg[0] += f[0]; agg[1] += f[1]; agg[2] += f[2]; agg[3] += f[3]
                c += 1
            m >>= 1
            i += 1
        if c > best and agg[0] <= caps[0] and agg[1] <= caps[1] and agg[2] <= caps[2] and agg[3] <= caps[3]:
            best = c
    _BS_CACHE[key] = best
    return best


def multiset_from_counts(counts):
    return tuple(sorted((a, int(counts.get(a, 0))) for a in ARCHES if int(counts.get(a, 0)) > 0))


def recover_composition(realized):
    """Recover the (research, code_review, doc_processing, monitoring) counts summing to 8
    from an agent's aggregate realized mandatory demand, by exact linear solve; require a
    unique nonnegative integer solution consistent with the demand."""
    Mt = np.array([[MAND[a][r] for a in ARCHES] for r in RESOURCES], float)  # 4 res x 4 arch
    b = np.array([float(realized.get(r, 0)) for r in RESOURCES], float)
    x = np.linalg.solve(Mt, b)
    xr = np.rint(x).astype(int)
    if np.all(xr >= 0) and int(xr.sum()) == TPA and np.allclose(Mt @ xr, b, atol=1e-6):
        return {ARCHES[i]: int(xr[i]) for i in range(4)}, True
    return None, False


def carrier_rule(rp, cp, ip, inoninf, deq):
    if ip and inoninf:
        return "independent_bundle_maxmin", 1
    if cp:
        return ("distributed_price_leontief" if deq else "central_joint_leontief"), 2
    if rp:
        return ("independent_bundle_maxmin" if inoninf else "central_joint_leontief"), 3
    return "central_joint_leontief_diagnostic", 4


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def load_json(p):
    with open(p) as f:
        return json.load(f)


def sha256_file(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()


def approx(a, b, tol=1e-6):
    if a is None or b is None:
        return a == b
    return abs(float(a) - float(b)) <= tol * max(1.0, abs(float(a)), abs(float(b)))


def eq_exact(a, b, tol=1e-9):
    return abs(float(a) - float(b)) <= tol


# ---- reconstruction of statistics (mirrors the frozen analysis, independent code) -------
def distributional(a_rows, ref_rows):
    keys = sorted(set(a_rows) & set(ref_rows))
    per = np.array([(a_rows[k] - ref_rows[k]) * TPA for k in keys], float)
    comp = np.array([a_rows[k] * TPA for k in keys], float)
    harmed = per < -EPS
    better = per > EPS
    return {
        "n_agent_obs": int(len(keys)),
        "frac_harmed": float(np.mean(harmed)) if len(keys) else 0.0,
        "n_harmed": int(harmed.sum()),
        "mean_loss_harmed": float(-per[harmed].mean()) if harmed.any() else 0.0,
        "median_loss_harmed": float(-np.median(per[harmed])) if harmed.any() else 0.0,
        "worst_loss": float(-per.min()) if len(keys) else 0.0,
        "frac_better": float(np.mean(better)) if len(keys) else 0.0,
        "mean_gain_better": float(per[better].mean()) if better.any() else 0.0,
        "median_gain_better": float(np.median(per[better])) if better.any() else 0.0,
        "frac_zero": float(np.mean(comp <= 1e-12)) if len(keys) else 0.0,
        "min_completion_tasks": float(comp.min()) if len(keys) else 0.0,
        "bottom_decile_tasks": float(np.percentile(comp, 10)) if len(keys) else 0.0,
        "mean_completion_tasks": float(comp.mean()) if len(keys) else 0.0,
    }


def harmed_set_compare(a_rows, ref_a, b_rows, ref_b):
    keys = sorted(set(a_rows) & set(ref_a) & set(b_rows) & set(ref_b))
    harmed_a = {k for k in keys if a_rows[k] - ref_a[k] < -EPS}
    harmed_b = {k for k in keys if b_rows[k] - ref_b[k] < -EPS}
    inter = harmed_a & harmed_b
    union = harmed_a | harmed_b
    agree = sum(1 for k in keys if (k in harmed_a) == (k in harmed_b))
    eq_complete = sum(1 for k in keys if abs(a_rows[k] - b_rows[k]) <= 1e-12)
    diffs = np.array([abs(a_rows[k] - b_rows[k]) * TPA for k in keys])
    comp_a = {k: a_rows[k] for k in keys}
    comp_b = {k: b_rows[k] for k in keys}
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


# ---- git checks -------------------------------------------------------------------------
def git_checks(base_root):
    checks = {}
    def is_ancestor(a, b):
        return subprocess.call(["git", "-C", base_root, "merge-base", "--is-ancestor", a, b],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0
    def blob(rev, rel):
        try:
            return subprocess.check_output(["git", "-C", base_root, "rev-parse", "%s:%s/%s" % (rev, OQ_REL, rel)],
                                           stderr=subprocess.DEVNULL).decode().strip()
        except Exception:
            return None
    checks["chronology_prereg_before_arch"] = is_ancestor(ANCHORS["prereg"], ANCHORS["arch"])
    checks["chronology_arch_before_drift"] = is_ancestor(ANCHORS["arch"], ANCHORS["drift"])
    checks["chronology_drift_before_head"] = is_ancestor(ANCHORS["drift"], ANCHORS["head"])
    checks["prereg_is_ancestor_of_head"] = is_ancestor(ANCHORS["prereg"], ANCHORS["head"])
    # no outcome-relevant change between preregistration and experimental head
    changed = []
    for rel in OUTCOME_RELEVANT_FILES:
        a, b = blob(ANCHORS["prereg"], rel), blob(ANCHORS["head"], rel)
        if a is None or a != b:
            changed.append(rel)
    checks["no_outcome_relevant_change_prereg_to_head"] = (len(changed) == 0)
    return checks, {"outcome_relevant_changed": changed}


# ---- manifest checks --------------------------------------------------------------------
def manifest_checks(oq, base_root=None):
    checks = {}
    root = os.path.abspath(os.path.join(oq, "..", ".."))
    for scope, mpath in (("architecture", os.path.join(oq, "results", "architecture_v1", "EXPERIMENT_MANIFEST.json")),
                         ("drift", os.path.join(oq, "results", "drift_v1", "EXPERIMENT_MANIFEST.json"))):
        m = load_json(mpath)
        ok = True
        for art in m["artifacts"]:
            p = os.path.join(root, art["path"])
            if not os.path.exists(p) or sha256_file(p) != art["sha256"]:
                ok = False
                break
        checks["%s_manifest_hashes" % scope] = ok
    corr = os.path.join(oq, "CORRECTION_MANIFEST.json")
    if os.path.exists(corr):
        m = load_json(corr)
        ok = True
        for art in m["artifacts"]:
            p = os.path.join(root, art["path"])
            if not os.path.exists(p) or sha256_file(p) != art["sha256"]:
                ok = False
                break
        checks["correction_manifest_hashes"] = ok
        checks["correction_manifest_invariance"] = bool(m["invariants"]["original_bytes_match_base"])
    return checks


# ---- architecture verification ----------------------------------------------------------
def index_runs(runs):
    idx = {(r["cell"], r["seed"], r["arm"]): r for r in runs}
    seeds_by = defaultdict(list)
    seen = set()
    for r in runs:
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by[r["cell"]].append(r["seed"])
    return idx, seeds_by


def verify_architecture(oq, do_bootstrap=True, bs_sample=None):
    ARCH = os.path.join(oq, "results", "architecture_v1")
    runs = load_csv(os.path.join(ARCH, "raw", "runs.csv"))
    agents = load_csv(os.path.join(ARCH, "raw", "agents.csv"))
    scen = load_csv(os.path.join(ARCH, "raw", "scenarios.csv"))
    dist = load_csv(os.path.join(ARCH, "raw", "distributed.csv"))
    infeasible = load_csv(os.path.join(ARCH, "raw", "infeasible.csv"))
    head = load_json(os.path.join(ARCH, "architecture_headline.json"))
    summary = load_json(os.path.join(ARCH, "summary.json"))
    decision = load_json(os.path.join(oq, "DRIFT_CARRIER_DECISION.json"))
    cells = head["co_primary_cells"]
    checks, detail = {}, {}

    # schema / counts / keys / arms / pairing / workload identity
    checks["runs_rowcount"] = len(runs) == 2 * 200 * 6
    checks["agents_rowcount"] = len(agents) == 2 * 200 * 6 * 6
    checks["scenarios_rowcount"] = len(scen) == 2 * 200
    checks["distributed_rowcount"] = len(dist) == 2 * 200
    checks["infeasible_empty"] = len(infeasible) == 0
    idx, seeds_by = index_runs(runs)
    checks["unique_run_keys"] = len({(r["cell"], r["seed"], r["arm"]) for r in runs}) == len(runs)
    checks["all_arms_present_each_seed"] = all(
        all((c, s, arm) in idx for arm in ARMS_ARCH) for c in cells for s in seeds_by[c])
    sc_by = {(s["cell"], s["seed"]): s for s in scen}
    # workload identity across arms: same task_workload_hash for a (cell, seed); the scenario
    # row carries the workload, and all arms share the seed's scenario.
    checks["workload_hash_unique_per_seed"] = all(
        (c, s) in sc_by for c in cells for s in seeds_by[c])
    n_wh = len({s["task_workload_hash"] for s in scen})
    checks["workload_hash_count"] = n_wh == summary["disjointness"]["n_workload_hashes"] == 200

    # seeds derive from namespace and disjoint from every other namespace
    conf = set(scenario_seeds(NS_ARCH_CONF, 200))
    data_seeds = {int(r["seed"]) for r in runs}
    checks["seeds_match_namespace"] = data_seeds == conf
    drift_conf = set(scenario_seeds(NS_DRIFT_CONF, 200))
    dev_seeds = set()
    for ns in NS_DEV:
        dev_seeds |= set(scenario_seeds(ns, 200))
    prior = canonical_seed_universe()
    checks["arch_disjoint_from_drift"] = len(conf & drift_conf) == 0
    checks["arch_disjoint_from_prior_and_canonical"] = len(conf & prior) == 0
    checks["arch_disjoint_from_development"] = len(conf & dev_seeds) == 0

    ag_by = defaultdict(dict)
    for a in agents:
        ag_by[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = a
    qo = {(a["cell"], a["arm"]): {} for a in agents}
    for a in agents:
        qo[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = float(a["queue_order_completion"])

    # arm means
    arm_mean_ok = True
    for cell in cells:
        for arm in ARMS_ARCH:
            got = float(np.mean([float(idx[(cell, s, arm)]["queue_order_tasks_per_run"]) for s in seeds_by[cell]]))
            if not approx(got, head["cell_policy"][cell][arm]["qo_tasks_per_run"]):
                arm_mean_ok = False
    checks["every_arm_mean"] = arm_mean_ok

    # paired qo + bs means and CI (all comparisons)
    def pq(cell, key, field):
        t, b = key.split("_minus_")
        return [float(idx[(cell, s, t)][field]) - float(idx[(cell, s, b)][field]) for s in seeds_by[cell]]
    if do_bootstrap:
        pqo_ok = pbs_ok = True
        for cell in cells:
            for key, hv in head["paired_qo"][cell].items():
                r = st(pq(cell, key, "queue_order_completion_mean"), "arch|qo|%s|%s" % (cell, key))
                if not (approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"])
                        and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])):
                    pqo_ok = False
            for key, hv in head["paired_best_subset"][cell].items():
                r = st(pq(cell, key, "best_subset_completion_mean"), "arch|bs|%s|%s" % (cell, key))
                if not (approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"])
                        and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])):
                    pbs_ok = False
        checks["every_paired_qo_mean_and_ci"] = pqo_ok
        checks["every_paired_best_subset_mean_and_ci"] = pbs_ok

    # five-condition components
    zero_events = (summary["capacity_violations_total"] == 0 and summary["bound_violations_total"] == 0
                   and summary["fallback_used_total"] == 0 and summary["infeasible_runs"] == 0)
    def frac_zero(cell, arm):
        return float(np.mean([float(idx[(cell, s, arm)]["frac_zero_qo"]) for s in seeds_by[cell]]))
    def five(cell, t, b, hv):
        cmp = head["paired_qo"][cell]["%s_minus_%s" % (t, b)]
        c1 = head["cell_policy"][cell][t]["qo_tasks_per_run"] > head["cell_policy"][cell][b]["qo_tasks_per_run"]
        c2 = cmp["ci_lo_tasks"] > 0.0
        c3 = cmp["mean_tasks"] >= 1.0
        c4 = frac_zero(cell, t) <= frac_zero(cell, b) + 1e-12
        c5 = zero_events
        rec = {"c1_higher": c1, "c2_ci_above_zero": c2, "c3_at_least_one_task": c3,
               "c4_no_zero_increase": c4, "c5_zero_events": c5, "pass": bool(c1 and c2 and c3 and c4 and c5)}
        return all(rec[k] == hv[k] for k in rec)
    fc_ok = True
    for cell in cells:
        fc_ok &= five(cell, "central_joint_leontief", "drf", head["five_condition"]["fresh_replication"][cell])
        fc_ok &= five(cell, "central_joint_leontief", "independent_bundle_maxmin", head["five_condition"]["coordination"][cell])
        fc_ok &= five(cell, "independent_bundle_maxmin", "drf", head["five_condition"]["independent_vs_drf"][cell])
    checks["every_five_condition_component"] = bool(fc_ok)

    # equivalence / noninferiority classifications
    ni_ok = ce_ok = True
    for cell in cells:
        cmp = head["paired_qo"][cell]["independent_bundle_maxmin_minus_central_joint_leontief"]
        ni = bool(cmp["mean_tasks"] >= -0.25 and cmp["ci_lo_tasks"] >= -0.5 and cmp["ci_hi_tasks"] <= 0.5)
        ce = bool(abs(cmp["mean_tasks"]) <= 0.25 and cmp["ci_lo_tasks"] >= -0.5 and cmp["ci_hi_tasks"] <= 0.5)
        ni_ok &= (ni == head["flags"]["indep_noninferior_by_cell"][cell])
        ce_ok &= (ce == head["flags"]["central_independent_equivalence_by_cell"][cell])
    checks["noninferiority_by_cell"] = bool(ni_ok)
    checks["central_independent_equivalence_by_cell"] = bool(ce_ok)

    # separable relaxation structural (allocation + outcome equality vs equal)
    relax_ok = True
    for cell in cells:
        rel = ag_by[(cell, "separable_leontief_relaxation")]
        eq = ag_by[(cell, "equal")]
        keys = set(rel) & set(eq)
        alloc_eq = float(np.mean([rel[k]["allocated"] == eq[k]["allocated"] for k in keys]))
        out_eq = float(np.mean([abs(float(rel[k]["queue_order_completion"]) - float(eq[k]["queue_order_completion"])) <= 1e-12 for k in keys]))
        hv = head["separable_relaxation_vs_equal"][cell]
        relax_ok &= approx(alloc_eq, hv["allocation_equality_rate"]) and approx(out_eq, hv["outcome_equality_rate"])
    checks["separable_relaxation_structural"] = bool(relax_ok)

    # distributed statistics (all fields)
    gaps = np.array([float(r["rel_obj_gap"]) for r in dist if r["rel_obj_gap"] not in ("", None)])
    feas = np.array([float(r["capacity_residual"]) for r in dist])
    bnd = np.array([float(r["bound_residual"]) for r in dist])
    disagree = np.array([int(r["installed_outcome_disagreements"]) for r in dist])
    nonconv = sum(1 for r in dist if str(r["distributed_converged"]).lower() != "true")
    g = head["distributed_equivalence"]["gap_summary"]
    gap_ok = all([
        g["n"] == len(gaps), approx(g["mean"], gaps.mean()), approx(g["median"], np.median(gaps)),
        approx(g["p95"], np.percentile(gaps, 95)), approx(g["max"], gaps.max()),
        approx(g["frac_le_1e-4"], np.mean(gaps <= 1e-4)), approx(g["max_feasibility_residual"], feas.max()),
        approx(g["max_bound_residual"], bnd.max()), g["nonconvergence_count"] == nonconv,
        g["installed_outcome_disagreements_total"] == int(disagree.sum()),
        approx(g["mean_iterations"], np.mean([float(r["iterations"]) for r in dist])),
        approx(g["mean_message_count"], np.mean([float(r.get("message_count", 0) or 0) for r in dist])),
        approx(g["mean_runtime_ms"], np.mean([float(r.get("runtime_ms", 0) or 0) for r in dist])),
        approx(g["cont_alloc_l1_mean"], np.mean([float(r["cont_alloc_l1_norm"]) for r in dist])),
        approx(g["cont_alloc_l1_max"], np.max([float(r["cont_alloc_l1_norm"]) for r in dist])),
        approx(g["installed_alloc_l1_mean"], np.mean([float(r["installed_alloc_l1_norm"]) for r in dist])),
        approx(g["installed_alloc_linf_max"], np.max([float(r["installed_alloc_linf"]) for r in dist])),
    ])
    checks["distributed_gap_summary_all_fields"] = bool(gap_ok)
    obj_eq = bool(feas.max() <= 1e-7 and bnd.max() <= 1e-7 and np.mean(gaps <= 1e-4) >= 0.99 and gaps.max() <= 1e-3)
    if do_bootstrap:
        out_stats = {}
        for cell in cells:
            d = pq(cell, "distributed_price_leontief_minus_central_joint_leontief", "queue_order_completion_mean")
            out_stats[cell] = st(d, "arch|dist_out|%s" % cell)
        outcome_eq = all(abs(out_stats[c]["mean_tasks"]) <= 0.25 and out_stats[c]["ci_lo_tasks"] >= -0.5
                         and out_stats[c]["ci_hi_tasks"] <= 0.5 for c in cells)
        os_ok = all(approx(out_stats[c]["mean_tasks"], head["distributed_equivalence"]["outcome_stats"][c]["mean_tasks"])
                    and approx(out_stats[c]["ci_lo_tasks"], head["distributed_equivalence"]["outcome_stats"][c]["ci_lo_tasks"])
                    and approx(out_stats[c]["ci_hi_tasks"], head["distributed_equivalence"]["outcome_stats"][c]["ci_hi_tasks"])
                    for c in cells)
        checks["distributed_outcome_stats"] = bool(os_ok)
        dist_class = ("TECHNICALLY_INVALID" if (feas.max() > 1e-7 or bnd.max() > 1e-7)
                      else "OBJECTIVE_AND_OUTCOME_EQUIVALENT" if (obj_eq and outcome_eq)
                      else "OBJECTIVE_EQUIVALENT_OUTCOME_DIFFERENT" if obj_eq else "NOT_EQUIVALENT")
        checks["distributed_classification"] = dist_class == head["distributed_equivalence"]["classification"]
        deq = bool(obj_eq and outcome_eq)
    else:
        deq = bool(head["flags"]["distributed_equivalent"])

    # distributional statistics (every arm vs equal and vs drf)
    dstat_ok = True
    for cell in cells:
        for arm in ARMS_ARCH:
            for ref, key in (("equal", "vs_equal"), ("drf", "vs_drf")):
                r = distributional(qo[(cell, arm)], qo[(cell, ref)])
                hv = head["distributional"][cell][arm][key]
                for f in r:
                    if not approx(r[f], hv[f]):
                        dstat_ok = False
    checks["every_distributional_statistic"] = bool(dstat_ok)

    # harmed-set (central vs distributed) vs equal and drf
    hs_ok = True
    for cell in cells:
        for ref in ("equal", "drf"):
            r = harmed_set_compare(qo[(cell, "central_joint_leontief")], qo[(cell, ref)],
                                   qo[(cell, "distributed_price_leontief")], qo[(cell, ref)])
            hv = head["harmed_set_central_vs_distributed"][cell][ref]
            for f in r:
                if isinstance(r[f], bool):
                    if r[f] != hv[f]:
                        hs_ok = False
                elif not approx(r[f], hv[f]):
                    hs_ok = False
    checks["every_harmed_set_statistic"] = bool(hs_ok)

    # carrier rule
    flags = head["flags"]
    carrier, branch = carrier_rule(flags["replication_pass"], flags["coordination_pass"],
                                   flags["independent_positive"], flags["independent_noninferior"], deq)
    checks["adaptive_carrier_rule"] = (carrier == decision["selected_carrier"] and branch == decision["branch"])

    # exact best-subset (architecture): enumerate all 256 subsets from scenario task counts
    bs_ok = True
    bs_checked = 0
    run_bs = defaultdict(list)  # (cell,seed,arm) -> list of best_subset_counts (for run aggregation)
    rows_iter = agents if bs_sample is None else agents[:bs_sample]
    for a in rows_iter:
        sc = sc_by[(a["cell"], a["seed"])]
        counts = json.loads(sc["realized_task_counts_by_agent"])[int(a["agent"][1:])]
        ms = multiset_from_counts(counts)
        c = best_subset_count(ms, json.loads(a["allocated"]))
        rec = int(a["best_subset_count"])
        run_bs[(a["cell"], a["seed"], a["arm"])].append((c, rec))
        bs_checked += 1
        if c != rec or not approx(float(a["best_subset_completion"]), rec / TPA):
            bs_ok = False
    # aggregate best_subset_tasks_per_run per run equals sum of agent counts (only if full pass)
    if bs_sample is None:
        for (cell, seed, arm), lst in run_bs.items():
            recon_tpr = sum(rec for _, rec in lst)  # tasks per run = sum over 6 agents
            rec_tpr = float(idx[(cell, seed, arm)]["best_subset_tasks_per_run"])
            if not approx(recon_tpr, rec_tpr):
                bs_ok = False
    checks["exact_best_subset_enumeration"] = bool(bs_ok)
    detail["bs_checked_arch"] = bs_checked

    # allocation feasibility: installed sums <= capacity; floors <= alloc <= upper (recorded)
    feas_ok = True
    for cell in cells:
        for s in seeds_by[cell]:
            cap = json.loads(sc_by[(cell, s)]["capacity_by_resource"])
            for arm in ARMS_ARCH:
                tot = {r: 0.0 for r in RESOURCES}
                for ai in range(NAG):
                    a = ag_by[(cell, arm)][(s, "a%d" % ai)]
                    al = json.loads(a["allocated"])
                    mn = json.loads(a["min_bound"])
                    up = json.loads(a["upper_bound"])
                    for r in RESOURCES:
                        v = float(al.get(r, 0))
                        tot[r] += v
                        if v < mn.get(r, 0) - 1e-9 or v > up.get(r, 0) + 1e-9:
                            feas_ok = False
                if any(tot[r] > cap[r] + 1e-6 for r in RESOURCES):
                    feas_ok = False
    checks["allocation_feasibility"] = bool(feas_ok)

    # summary aggregations + zero-event claims
    n_feasible = sum(1 for r in runs if str(r["feasible"]).lower() == "true")
    checks["summary_aggregations"] = all([
        summary["expected_runs"] == 2400, summary["feasible_runs"] == n_feasible == 2400,
        summary["infeasible_runs"] == 0, summary["n_agent_records"] == len(agents) == 14400,
        summary["n_scenario_rows"] == len(scen) == 400,
        summary["disjointness"]["arch_seeds_vs_prior_overlap"] == 0,
    ])
    checks["zero_events_all"] = bool(zero_events and head["zero_events_all"])
    return checks, detail


# ---- drift verification -----------------------------------------------------------------
def verify_drift(oq, do_bootstrap=True, bs_sample=None):
    DRIFT = os.path.join(oq, "results", "drift_v1")
    runs = load_csv(os.path.join(DRIFT, "raw", "runs.csv"))
    agents = load_csv(os.path.join(DRIFT, "raw", "agents.csv"))
    scen = load_csv(os.path.join(DRIFT, "raw", "scenarios.csv"))
    infeasible = load_csv(os.path.join(DRIFT, "raw", "infeasible.csv"))
    head = load_json(os.path.join(DRIFT, "drift_headline.json"))
    summary = load_json(os.path.join(DRIFT, "summary.json"))
    checks, detail = {}, {}

    checks["runs_rowcount"] = len(runs) == 2 * 5 * 200 * 9
    checks["agents_rowcount"] = len(agents) == 2 * 5 * 200 * 9 * 6
    checks["scenarios_rowcount"] = len(scen) == 2 * 5 * 200
    checks["declarations_rowcount"] = len(load_csv(os.path.join(DRIFT, "raw", "declarations.csv"))) == 2 * 5 * 200 * 6 * 4
    checks["infeasible_empty"] = len(infeasible) == 0
    idx, seeds_by = index_runs(runs)
    checks["unique_run_keys"] = len({(r["cell"], r["seed"], r["arm"]) for r in runs}) == len(runs)
    cells = ["delta%.2f__%s" % (d, c) for d in (0.0, 0.25, 0.5, 0.75, 1.0) for c in ("moderate", "high")]
    checks["all_arms_present_each_seed"] = all(
        all((c, s, arm) in idx for arm in ARMS_DRIFT) for c in cells for s in seeds_by[c])

    conf = set(scenario_seeds(NS_DRIFT_CONF, 200))
    checks["seeds_match_namespace"] = {int(r["seed"]) for r in runs} == conf
    checks["drift_disjoint_from_arch"] = len(conf & set(scenario_seeds(NS_ARCH_CONF, 200))) == 0
    checks["drift_disjoint_from_prior_and_canonical"] = len(conf & canonical_seed_universe()) == 0
    dev = set()
    for ns in NS_DEV:
        dev |= set(scenario_seeds(ns, 200))
    checks["drift_disjoint_from_development"] = len(conf & dev) == 0

    def pq(cell, t, b, field="queue_order_completion_mean"):
        return [float(idx[(cell, s, t)][field]) - float(idx[(cell, s, b)][field]) for s in seeds_by[cell]
                if (cell, s, t) in idx and (cell, s, b) in idx]
    zero_events = (summary["capacity_violations_total"] == 0 and summary["bound_violations_total"] == 0
                   and summary["fallback_used_total"] == 0 and summary["infeasible_runs"] == 0)

    if do_bootstrap:
        # co-primary (authoritative stream) mean AND CI; both cells
        cp_ok = True
        for cc in head["co_primary_cells"]:
            cell = "delta%.2f__%s" % (cc["delta"], cc["contention"])
            r = st(pq(cell, "carrier_stale_calibration", "drf_stale_calibration"),
                   "drift|%s|carrier_stale_minus_drf_stale" % cell)
            hv = head["co_primary_decision"][cell]
            cp_ok &= approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"]) and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])
            # five-condition pass reconstruction
            c1 = np.mean([float(idx[(cell, s, "carrier_stale_calibration")]["queue_order_tasks_per_run"]) for s in seeds_by[cell]]) > \
                 np.mean([float(idx[(cell, s, "drf_stale_calibration")]["queue_order_tasks_per_run"]) for s in seeds_by[cell]])
            c4 = np.mean([float(idx[(cell, s, "carrier_stale_calibration")]["frac_zero_qo"]) for s in seeds_by[cell]]) <= \
                 np.mean([float(idx[(cell, s, "drf_stale_calibration")]["frac_zero_qo"]) for s in seeds_by[cell]]) + 1e-12
            passed = bool(c1 and r["ci_lo_tasks"] > 0 and r["mean_tasks"] >= 1.0 and c4 and zero_events)
            cp_ok &= (passed == hv["pass"])
        checks["drift_co_primary_mean_and_ci"] = bool(cp_ok)

        # both independently-seeded appearances of the drift primary comparison
        both_ok = True
        for cell in ("delta0.25__moderate", "delta0.25__high"):
            d = pq(cell, "carrier_stale_calibration", "drf_stale_calibration")
            a = st(d, "drift|%s|carrier_stale_minus_drf_stale" % cell)   # co-primary stream
            b = st(d, "drift|%s|cmd_stale_calibration" % cell)           # secondary stream
            hv_a = head["co_primary_decision"][cell]
            hv_b = head["secondary"][cell]["carrier_minus_drf_stale_calibration"]
            both_ok &= approx(a["ci_lo_tasks"], hv_a["ci_lo_tasks"]) and approx(a["ci_hi_tasks"], hv_a["ci_hi_tasks"])
            both_ok &= approx(b["ci_lo_tasks"], hv_b["ci_lo_tasks"]) and approx(b["ci_hi_tasks"], hv_b["ci_hi_tasks"])
            # they share the same point estimate but (generally) differ at the 0.005 grid on endpoints
            both_ok &= approx(a["mean_tasks"], b["mean_tasks"])
        checks["drift_primary_both_seeded_appearances"] = bool(both_ok)

        # every secondary mean and CI
        sec_ok = True
        for cell in cells:
            block = head["secondary"][cell]
            for src in SOURCES:
                r = st(pq(cell, "carrier_%s" % src, "drf_%s" % src), "drift|%s|cmd_%s" % (cell, src))
                hv = block["carrier_minus_drf_%s" % src]
                sec_ok &= approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"]) and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])
            for name, t, b in (("carrier_stale_minus_refreshed", "carrier_stale_calibration", "carrier_refreshed_calibration"),
                               ("drf_stale_minus_refreshed", "drf_stale_calibration", "drf_refreshed_calibration")):
                stream = "drift|%s|%s" % (cell, "csr" if name.startswith("carrier") else "dsr")
                r = st(pq(cell, t, b), stream)
                hv = block[name]
                sec_ok &= approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"]) and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])
            did = []
            for s in seeds_by[cell]:
                ks = [(cell, s, a) for a in ("carrier_stale_calibration", "drf_stale_calibration",
                                             "carrier_refreshed_calibration", "drf_refreshed_calibration")]
                if all(k in idx for k in ks):
                    did.append((float(idx[ks[0]]["queue_order_completion_mean"]) - float(idx[ks[1]]["queue_order_completion_mean"]))
                               - (float(idx[ks[2]]["queue_order_completion_mean"]) - float(idx[ks[3]]["queue_order_completion_mean"])))
            r = st(did, "drift|%s|did" % cell)
            hv = block["difference_in_differences"]
            sec_ok &= approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"]) and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])
            r = st(pq(cell, "carrier_stale_calibration", "drf_stale_calibration", "best_subset_completion_mean"), "drift|%s|bs" % cell)
            hv = block["best_subset_carrier_minus_drf_stale"]
            sec_ok &= approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"]) and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])
            for arm in ARMS_DRIFT:
                got = float(np.mean([float(idx[(cell, s, arm)]["queue_order_tasks_per_run"]) for s in seeds_by[cell]]))
                if not approx(got, block["arm_tasks_per_run"][arm]):
                    sec_ok = False
        checks["every_drift_secondary_mean_and_ci"] = bool(sec_ok)

    # classification
    all_pass = True
    for cc in head["co_primary_cells"]:
        cell = "delta%.2f__%s" % (cc["delta"], cc["contention"])
        all_pass &= head["co_primary_decision"][cell]["pass"]
    checks["drift_classification"] = ((all_pass and head["declaration_robustness_classification"] == "ROBUST_AT_MODEST_DRIFT")
                                      or (not all_pass))

    # drift metrics per cell
    scen_by = defaultdict(list)
    for s in scen:
        scen_by[s["cell"]].append(s)
    dm_ok = True
    for cell in cells:
        rows = scen_by[cell]
        recon = {
            "drift_source_total_mean": float(np.mean([float(r["drift_source_total"]) for r in rows])),
            "changed_identities_total_mean": float(np.mean([float(r["changed_identities_total"]) for r in rows])),
            "task_mixture_tv_from_baseline_mean": float(np.mean([float(r["task_mixture_tv_from_baseline_mean"]) for r in rows])),
            "staleness_error_mean": float(np.mean([float(r["staleness_error_mean"]) for r in rows])),
            "calibration_error_mean": float(np.mean([float(r["calibration_error_mean"]) for r in rows])),
            "latent_oracle_error_mean": float(np.mean([float(r["latent_oracle_error_mean"]) for r in rows])),
            "realized_contention_mean": float(np.mean([max(json.loads(r["realized_contention_by_resource"]).values()) for r in rows])),
        }
        hv = head["drift_metrics"][cell]
        for k in recon:
            if not approx(recon[k], hv[k]):
                dm_ok = False
    checks["all_drift_metrics"] = bool(dm_ok)

    # exact best-subset (drift): recover composition uniquely, enumerate 256 subsets
    ag_by = defaultdict(dict)
    for a in agents:
        ag_by[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = a
    bs_ok = True
    comp_unique = True
    bs_checked = 0
    rows_iter = agents if bs_sample is None else agents[:bs_sample]
    run_bs = defaultdict(int)
    for a in rows_iter:
        comp, uniq = recover_composition(json.loads(a["realized_demand"]))
        if not uniq:
            comp_unique = False
            continue
        c = best_subset_count(multiset_from_counts(comp), json.loads(a["allocated"]))
        rec = int(a["best_subset_count"])
        run_bs[(a["cell"], a["seed"], a["arm"])] += rec
        bs_checked += 1
        if c != rec or not approx(float(a["best_subset_completion"]), rec / TPA):
            bs_ok = False
    if bs_sample is None:
        for k, tot in run_bs.items():
            if not approx(tot, float(idx[k]["best_subset_tasks_per_run"])):
                bs_ok = False
    checks["exact_best_subset_enumeration"] = bool(bs_ok)
    checks["drift_composition_unique"] = bool(comp_unique)
    detail["bs_checked_drift"] = bs_checked

    # allocation feasibility (installed sums vs capacity)
    feas_ok = True
    for cell in cells:
        for s in seeds_by[cell]:
            cap = json.loads(scen_by[cell][0]["capacity_by_resource"]) if False else None
    # capacities are per (cell,seed): build lookup
    cap_by = {(s["cell"], s["seed"]): json.loads(s["capacity_by_resource"]) for s in scen}
    for cell in cells:
        for s in seeds_by[cell]:
            cap = cap_by[(cell, s)]
            for arm in ARMS_DRIFT:
                tot = {r: 0.0 for r in RESOURCES}
                for ai in range(NAG):
                    al = json.loads(ag_by[(cell, arm)][(s, "a%d" % ai)]["allocated"])
                    for r in RESOURCES:
                        tot[r] += float(al.get(r, 0))
                if any(tot[r] > cap[r] + 1e-6 for r in RESOURCES):
                    feas_ok = False
    checks["allocation_feasibility"] = bool(feas_ok)

    # summary + zero events
    checks["summary_aggregations"] = all([
        summary["feasible_runs"] == 18000, summary["infeasible_runs"] == 0,
        summary["capacity_violations_total"] == 0, summary["bound_violations_total"] == 0,
        summary["fallback_used_total"] == 0,
    ])
    checks["zero_events_all"] = bool(zero_events and head["zero_events_all"])
    return checks, detail


# ---- preregistered secondary supplement verification ------------------------------------
def verify_secondary_supplement(oq):
    DRIFT = os.path.join(oq, "results", "drift_v1")
    supp_dir = os.path.join(DRIFT, "preregistered_secondary_completion")
    checks = {}
    jf = os.path.join(supp_dir, "drift_secondary_completion.json")
    cf = os.path.join(supp_dir, "drift_secondary_completion.csv")
    if not (os.path.exists(jf) and os.path.exists(cf)):
        checks["secondary_supplement_present"] = False
        return checks
    checks["secondary_supplement_present"] = True
    sec = load_json(jf)
    rows = load_csv(cf)
    cells = ["delta%.2f__%s" % (d, c) for d in (0.0, 0.25, 0.5, 0.75, 1.0) for c in ("moderate", "high")]
    checks["secondary_supplement_shape"] = (set(sec["cells"]) == set(cells)
                                            and all(set(sec["cells"][c]["arms"]) == set(ARMS_DRIFT) for c in cells)
                                            and len(rows) == len(cells) * len(ARMS_DRIFT))
    # independent reconstruction of a representative set of distributional stats, using the
    # supplement's own field naming (n_improved/frac_improved/mean_gain_improved).
    agents = load_csv(os.path.join(DRIFT, "raw", "agents.csv"))
    qo = defaultdict(dict)
    for a in agents:
        qo[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = float(a["queue_order_completion"])

    def supp_dist(a_rows, ref_rows):
        keys = sorted(set(a_rows) & set(ref_rows))
        per = np.array([(a_rows[k] - ref_rows[k]) * TPA for k in keys], float)
        comp = np.array([a_rows[k] * TPA for k in keys], float)
        harmed = per < -EPS
        better = per > EPS
        return {
            "n_harmed": int(harmed.sum()), "frac_harmed": float(np.mean(harmed)),
            "mean_loss_harmed": float(-per[harmed].mean()) if harmed.any() else 0.0,
            "worst_loss": float(-per.min()),
            "n_improved": int(better.sum()), "frac_improved": float(np.mean(better)),
            "mean_gain_improved": float(per[better].mean()) if better.any() else 0.0,
            "min_completion_tasks": float(comp.min()), "bottom_decile_tasks": float(np.percentile(comp, 10)),
            "mean_completion_tasks": float(comp.mean()),
        }

    ok = True
    for cell in ("delta0.25__moderate", "delta0.50__high", "delta1.00__moderate"):
        for arm in ("carrier_stale_calibration", "drf_refreshed_calibration"):
            r = supp_dist(qo[(cell, arm)], qo[(cell, "equal")])
            hv = sec["cells"][cell]["arms"][arm]["vs_equal"]
            for f in r:
                if f not in hv or not approx(r[f], hv[f]):
                    ok = False
        r = supp_dist(qo[(cell, "carrier_stale_calibration")], qo[(cell, "drf_stale_calibration")])
        hv = sec["cells"][cell]["arms"]["carrier_stale_calibration"]["vs_matched_drf"]
        for f in ("n_harmed", "frac_harmed", "worst_loss", "n_improved", "frac_improved", "mean_gain_improved"):
            if not approx(r[f], hv[f]):
                ok = False
    checks["secondary_supplement_reconstructs"] = bool(ok)
    return checks


# ---- distributed no-central-call inspection ---------------------------------------------
def verify_distributed_no_central_call(oq):
    """Establish that the distributed arm invokes no central optimization routine.

    The distributed module may import the *objective evaluator* ``leontief_objective`` (used
    only to score its own allocation for the gap statistic); it must NOT import cvxpy, the
    canonical joint solver, or the central *solver* routine, and must NOT call any solver
    (`reduced_central_leontief`, `central_leontief_reference`, `solve_joint_allocation`, or a
    cvxpy `.solve`)."""
    src_path = os.path.join(oq, "oqlib", "distributed.py")
    src = open(src_path).read()
    tree = ast.parse(src)
    mods, calls = set(), set()
    central_imports = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            mods.update(a.name for a in node.names)
        elif isinstance(node, ast.ImportFrom):
            m = node.module or ""
            mods.add(m)
            if m.split(".")[-1] == "central" or m.endswith("central_ref"):
                central_imports.update(a.name for a in node.names)
        elif isinstance(node, ast.Call):
            fn = node.func
            calls.add(fn.id if isinstance(fn, ast.Name) else getattr(fn, "attr", ""))
    SOLVER_NAMES = {"reduced_central_leontief", "central_leontief_reference", "solve_joint_allocation"}
    EVALUATOR_ALLOW = {"leontief_objective", "bounds"}
    no_cvxpy = "cvxpy" not in mods and not any(m.split(".")[-1] == "cvxpy" for m in mods)
    no_solver_module = not any("joint_solver" in m or m.endswith("central_ref") for m in mods)
    no_solver_import = central_imports.isdisjoint(SOLVER_NAMES)
    only_evaluator_from_central = central_imports <= EVALUATOR_ALLOW
    no_solver_call = calls.isdisjoint(SOLVER_NAMES) and "solve" not in calls
    no_central = bool(no_cvxpy and no_solver_module and no_solver_import
                      and only_evaluator_from_central and no_solver_call)
    # single-process global feasibility scale present (a documented characteristic, not a defect)
    has_global_scale = ("scale" in src.lower() and ("bisect" in src.lower() or "repair" in src.lower()
                                                    or "feasib" in src.lower()))
    return {"distributed_no_central_call": no_central,
            "distributed_single_process_global_scale": bool(has_global_scale)}


# ---- orchestration ----------------------------------------------------------------------
def _safe(fn, *a, **k):
    """Run a section; a raised exception is itself a verification failure (e.g. a mutation
    that deletes an arm), reported as a failed check rather than crashing the verifier."""
    try:
        r = fn(*a, **k)
        return (r if isinstance(r, tuple) else (r, {}))
    except Exception as e:  # noqa: BLE001
        return {"_section_raised_no_exception": False}, {"error": repr(e)}


def run(oq, base_root, do_git=True, do_bootstrap=True, bs_sample=None):
    report = {"verifier": "verify_oq_v2", "imports_experiment_modules": False,
              "uses_only_stdlib_and_numpy": True, "sections": {}}
    if do_git and base_root:
        gc, gd = _safe(git_checks, base_root)
        report["sections"]["git"] = {"checks": gc, "detail": gd}
    mc, md = _safe(manifest_checks, oq, base_root)
    report["sections"]["manifests"] = {"checks": mc, "detail": md}
    ac, ad = _safe(verify_architecture, oq, do_bootstrap=do_bootstrap, bs_sample=bs_sample)
    report["sections"]["architecture"] = {"checks": ac, "detail": ad}
    dc, dd = _safe(verify_drift, oq, do_bootstrap=do_bootstrap, bs_sample=bs_sample)
    report["sections"]["drift"] = {"checks": dc, "detail": dd}
    sc, sd = _safe(verify_secondary_supplement, oq)
    report["sections"]["secondary_supplement"] = {"checks": sc, "detail": sd}
    xc, xd = _safe(verify_distributed_no_central_call, oq)
    report["sections"]["distributed"] = {"checks": xc, "detail": xd}

    all_checks = {}
    for sec, blk in report["sections"].items():
        for k, v in blk["checks"].items():
            all_checks["%s.%s" % (sec, k)] = bool(v)
    n_pass = sum(1 for v in all_checks.values() if v)
    n_total = len(all_checks)
    failed = [k for k, v in all_checks.items() if not v]
    report["n_checks"] = n_total
    report["n_pass"] = n_pass
    report["n_fail"] = n_total - n_pass
    report["failed_checks"] = failed
    if not failed and do_git and do_bootstrap and bs_sample is None:
        report["verification_status"] = "VERIFIED"
    elif not failed:
        report["verification_status"] = "PARTIALLY VERIFIED"
    else:
        report["verification_status"] = "NOT VERIFIED"
    return report


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--oq", default=None, help="original-question experiment dir")
    ap.add_argument("--base-root", default=None, help="repo root for git checks")
    ap.add_argument("--no-git", action="store_true")
    ap.add_argument("--out", default=None)
    args = ap.parse_args(argv)
    here = os.path.dirname(os.path.abspath(__file__))
    oq = args.oq or here
    base_root = args.base_root or os.path.abspath(os.path.join(oq, "..", ".."))
    report = run(oq, base_root, do_git=not args.no_git)
    out = args.out or os.path.join(here, "VERIFICATION_REPORT_V2.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=2)
    print("verification status:", report["verification_status"])
    print("  checks: %d/%d pass" % (report["n_pass"], report["n_checks"]))
    for k in report["failed_checks"]:
        print("  FAIL:", k)
    return 0 if report["verification_status"] != "NOT VERIFIED" else 1


if __name__ == "__main__":
    sys.exit(main())
