#!/usr/bin/env python3
"""Final comprehensive independent verification of the original-question closure.

This is the authoritative verifier for the original-question closure. It supersedes and
extends ``verify_oq_v2.py`` (whose exact blob remains recoverable at commit
``c678a0a96aba563ceff52e4d6b889fb90db316ec`` and in the pre-consolidation recovery bundle).
See ``VERIFICATION_FINAL_AUDIT.md`` for the exact list of v2 gaps/defects this verifier
closes.

Design constraints (independence):
  * imports NONE of the experiment drivers, allocator/optimizer implementations, analysis
    generator, carrier selector, secondary-completion generator, or manifest generators;
  * uses ONLY the Python standard library and NumPy;
  * reconstructs every reported quantity directly from the frozen raw CSVs with its own
    implementations of seed derivation, the paired percentile bootstrap, exact best-subset
    enumeration (all 256 subsets), the five-condition rule, equivalence/noninferiority
    classifications, harmed-set/distributional statistics, the adaptive carrier rule, and
    the drift classification tree.

What this pass is and is NOT:
  * It re-derives results from COMMITTED RAW DATA. It does NOT re-execute the 20,400
    confirmatory policy runs. Full end-to-end reproduction from a clean clone is a separate,
    documented step (REPRODUCIBILITY.md / CI), not this verifier.

Coverage is enumerated explicitly in the emitted report under ``coverage`` and the per-check
lists; the report does not rely on a single aggregate count. Status is VERIFIED only when
every required check passes (scientific hypotheses failing is not a verification failure).
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
# Mandatory archetype resource footprints (mandatory steps only), mirrored independently
# from the service archetypes. Independently re-validated below against every architecture
# scenario's committed ``aggregate_mandatory_demand`` (see verify_footprint_table).
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
AGENTS = ["a%d" % i for i in range(NAG)]
ARCH_CELLS = ["dirichlet_0.1__moderate", "dirichlet_0.1__high"]
DELTAS = [0.0, 0.25, 0.5, 0.75, 1.0]
CONTENTIONS = ["moderate", "high"]
DRIFT_CELLS = ["delta%.2f__%s" % (d, c) for d in DELTAS for c in CONTENTIONS]

NS_ARCH_CONF = "arb_original_question_closure_v1/architecture/confirmatory"
NS_DRIFT_CONF = "arb_original_question_closure_v1/declaration_drift/confirmatory"
NS_DEV = ["arb_original_question_closure_v1/architecture/development",
          "arb_original_question_closure_v1/declaration_drift/development",
          "arb_original_question_closure_v1/distributed_validation/development"]

# Immutable milestones (verified independently by git ancestry + blob identity below).
ANCHORS = {
    "prereg": "7ebf8b70366b8b68a90554a722f097d8acea3f01",
    "arch": "2f9fa1b05a38d941511491e030d3e964232350eb",
    "drift": "3204646f74901bb357f614e2f5ab4c1b276fb449",
    "result_head": "1e2e1d968e9204a44567c3571c0d75f5900319cc",
    "correction": "601ca56752d16fe5b9364590f95ef5335331e9b5",
    "v2": "c678a0a96aba563ceff52e4d6b889fb90db316ec",
}
# Files frozen at preregistration (protocol, configs, seed manifest, drivers, outcome-relevant
# oqlib) -> must be byte-identical to the preregistration commit.
PREREG_IMMUTABLE = [
    "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md", "config/architecture_v1.json", "config/drift_v1.json",
    "CONFIRMATORY_SEED_MANIFEST.json", "make_oq_analysis.py", "select_drift_carrier.py",
    "run_architecture.py", "run_declaration_drift.py", "run_original_question_closure.py",
    "oqlib/__init__.py", "oqlib/central.py", "oqlib/central_ref.py", "oqlib/distributed.py",
    "oqlib/maxmin.py", "oqlib/mechanisms.py", "oqlib/seeds_oq.py", "oqlib/driftgen.py",
    "oqlib/drift_scenario.py", "oqlib/declarations.py", "oqlib/execute.py", "oqlib/jobs.py",
    "oqlib/leontief_relaxation.py",
]
# Raw data, generated result artifacts, experiment manifests, and the frozen carrier
# decision -> must be byte-identical to the result head (1e2e1d9).
RESULT_IMMUTABLE = [
    "results/architecture_v1/raw/scenarios.csv", "results/architecture_v1/raw/runs.csv",
    "results/architecture_v1/raw/agents.csv", "results/architecture_v1/raw/distributed.csv",
    "results/architecture_v1/raw/infeasible.csv", "results/drift_v1/raw/scenarios.csv",
    "results/drift_v1/raw/runs.csv", "results/drift_v1/raw/agents.csv",
    "results/drift_v1/raw/declarations.csv", "results/drift_v1/raw/distributed.csv",
    "results/drift_v1/raw/infeasible.csv", "results/architecture_v1/EXPERIMENT_MANIFEST.json",
    "results/drift_v1/EXPERIMENT_MANIFEST.json", "results/architecture_v1/architecture_headline.json",
    "results/drift_v1/drift_headline.json", "results/architecture_v1/summary.json",
    "results/drift_v1/summary.json", "results/architecture_v1/tables/cell_arm_means.csv",
    "results/architecture_v1/tables/paired_comparisons.csv", "results/drift_v1/tables/drift_response.csv",
    "DRIFT_CARRIER_DECISION.json",
]

# Exact expected CSV headers (ordered) for every raw table (section B).
EXPECT_HEADERS = {
    "arch/scenarios": ["cell", "regime", "concentration", "contention", "contention_ratio", "seed",
                       "task_workload_hash", "scenario_hash", "declaration_source", "latent_probs_by_agent",
                       "realized_task_counts_by_agent", "unique_archetypes_per_agent",
                       "frac_agents_all_four_archetypes", "task_entropy_mean", "task_mixture_tv_mean_pairwise",
                       "resource_demand_tv_mean_pairwise", "resource_centroid_distance_mean",
                       "aggregate_mandatory_demand", "capacity_by_resource", "realized_contention_by_resource",
                       "active_resource_count"],
    "arch/runs": ["cell", "regime", "contention", "seed", "arm", "solver_status", "feasible", "fallback_used",
                  "queue_order_completion_mean", "queue_order_tasks_per_run", "best_subset_completion_mean",
                  "best_subset_tasks_per_run", "frac_zero_qo", "frac_zero_bs", "capacity_utilization",
                  "unused_installed_total", "capacity_violation", "bound_violation", "alloc_latency_ms"],
    "arch/agents": ["cell", "regime", "contention", "seed", "arm", "agent", "archetype",
                    "queue_order_completion", "best_subset_completion", "best_subset_count",
                    "mandatory_failures", "allocated", "charged", "unused", "min_bound", "upper_bound"],
    "arch/distributed": ["cell", "contention", "seed", "central_status", "central_objective",
                         "distributed_objective", "rel_obj_gap", "distributed_converged", "iterations",
                         "message_count", "runtime_ms", "capacity_residual", "bound_residual",
                         "primal_residual", "dual_residual", "cont_alloc_l1_norm", "cont_alloc_linf",
                         "installed_alloc_l1_norm", "installed_alloc_linf", "installed_outcome_disagreements",
                         "technically_valid"],
    "arch/infeasible": ["cell", "seed", "arm", "solver_status", "failure_reason"],
    "drift/scenarios": ["cell", "delta", "contention", "contention_ratio", "seed", "task_workload_hash",
                        "capacity_by_resource", "realized_contention_by_resource", "active_resource_count",
                        "drift_source_total", "changed_identities_total", "task_mixture_tv_from_baseline_mean",
                        "mand_demand_tv_mean_pairwise", "task_entropy_mean", "cross_agent_dissimilarity",
                        "staleness_error_mean", "calibration_error_mean", "latent_oracle_error_mean"],
    "drift/runs": ["cell", "delta", "contention", "seed", "arm", "policy_kind", "declaration_source",
                   "solver_status", "feasible", "fallback_used", "queue_order_completion_mean",
                   "queue_order_tasks_per_run", "best_subset_completion_mean", "best_subset_tasks_per_run",
                   "frac_zero_qo", "frac_zero_bs", "capacity_utilization", "unused_installed_total",
                   "capacity_violation", "bound_violation", "alloc_latency_ms"],
    "drift/agents": ["cell", "delta", "contention", "seed", "arm", "policy_kind", "declaration_source",
                     "agent", "queue_order_completion", "best_subset_completion", "best_subset_count",
                     "mandatory_failures", "allocated", "declared_demand", "realized_demand"],
    "drift/declarations": ["delta", "contention", "seed", "agent", "source", "declared_demand",
                           "staleness_error", "calibration_error", "latent_oracle_error"],
    "drift/distributed": ["cell", "delta", "contention", "seed", "declaration_source", "central_status",
                          "central_objective", "distributed_objective", "rel_obj_gap", "distributed_converged",
                          "iterations", "capacity_residual", "bound_residual", "installed_alloc_l1_norm",
                          "installed_outcome_disagreements", "technically_valid"],
    "drift/infeasible": ["cell", "seed", "arm", "solver_status", "failure_reason"],
}
SECONDARY_CSV_FIELDS = [
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


# ---- independent primitives -------------------------------------------------------------
def derive_seed(*parts):
    key = "|".join(str(p) for p in parts)
    return int(hashlib.sha256(key.encode()).hexdigest()[:16], 16) % (2 ** 32)


def scenario_seeds(ns, n):
    return [derive_seed(ns, "scenario", i) for i in range(n)]


def canonical_formula_universe():
    """Independently reproduce the canonical evaluation + heterogeneity pilot-dev +
    heterogeneity confirmatory seed formulas (for the formula-based disjointness check)."""
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
    """Paired percentile bootstrap, byte-identical to the frozen ``cell_bootstrap``:
    per-comparison RNG seeded by sha256(name|BOOT_SEED); 20000 resamples; 2.5/97.5 pct."""
    a = np.asarray(diffs, float)
    if a.size == 0:
        return float("nan"), float("nan"), float("nan")
    digest = hashlib.sha256(("%s|%d" % (name, BOOT_SEED)).encode()).hexdigest()
    rng = np.random.default_rng(int(digest[:16], 16))
    idx = rng.integers(0, a.size, size=(N_BOOT, a.size))  # bit-identical to N_BOOT sequential draws
    means = a[idx].mean(axis=1)
    return float(a.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def st(diffs, name):
    m, lo, hi = boot(diffs, name)
    return {"mean_tasks": m * TPR, "ci_lo_tasks": lo * TPR, "ci_hi_tasks": hi * TPR, "n": int(len(diffs))}


def multiset_from_counts(counts):
    return tuple(sorted((a, int(counts.get(a, 0))) for a in ARCHES if int(counts.get(a, 0)) > 0))


# per-composition precomputed enumeration of ALL 256 subsets (size, demand) --------------
_ENUM_CACHE = {}


def enumerate_256(multiset):
    """Return (sizes[256], demand[256,4]) enumerating ALL 2**8 subsets of the 8-task queue
    described by ``multiset`` (archetype -> count). Cached per distinct composition."""
    if multiset in _ENUM_CACHE:
        return _ENUM_CACHE[multiset]
    fps = []
    for arch, cnt in multiset:
        fp = [MAND[arch][r] for r in RESOURCES]
        fps.extend([fp] * cnt)
    k = len(fps)
    assert k == TPA, "composition must describe exactly 8 tasks, got %d" % k
    fps = np.array(fps, dtype=np.int64)                    # 8 x 4
    n_masks = 1 << k                                       # exactly 256
    bits = ((np.arange(n_masks)[:, None] >> np.arange(k)[None, :]) & 1).astype(np.int64)  # 256 x 8
    sizes = bits.sum(axis=1)                               # 256
    demand = bits @ fps                                    # 256 x 4
    _ENUM_CACHE[multiset] = (sizes, demand)
    return sizes, demand


def best_subset_for_caps(multiset, caps4):
    sizes, demand = enumerate_256(multiset)
    caps = np.array(caps4, dtype=np.int64)
    fit = (demand <= caps[None, :]).all(axis=1)
    return int(sizes[fit].max()) if fit.any() else 0


def recover_composition(realized):
    """Recover (research, code_review, doc_processing, monitoring) counts summing to 8 from an
    agent's aggregate realized mandatory demand by exact 4x4 linear solve; require a unique
    nonnegative-integer solution consistent with the demand."""
    Mt = np.array([[MAND[a][r] for a in ARCHES] for r in RESOURCES], float)
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


def distributional(a_rows, ref_rows):
    """Per-(seed,agent) loss/gain of an arm vs a reference (queue-order), per-agent x8 tasks."""
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


def supp_distributional(a_rows, ref_rows):
    """Supplement's distributional dict (n_improved/frac_improved/... naming) — matches
    complete_drift_secondary.distributional exactly."""
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


def harmed_set_compare(a_rows, ref_a, b_rows, ref_b):
    keys = sorted(set(a_rows) & set(ref_a) & set(b_rows) & set(ref_b))
    harmed_a = {k for k in keys if a_rows[k] - ref_a[k] < -EPS}
    harmed_b = {k for k in keys if b_rows[k] - ref_b[k] < -EPS}
    inter = harmed_a & harmed_b
    union = harmed_a | harmed_b
    agree = sum(1 for k in keys if (k in harmed_a) == (k in harmed_b))
    eq_complete = sum(1 for k in keys if abs(a_rows[k] - b_rows[k]) <= 1e-12)
    diffs = np.array([abs(a_rows[k] - b_rows[k]) * TPA for k in keys])
    thr_a = np.percentile([a_rows[k] for k in keys], 10) if keys else 0
    thr_b = np.percentile([b_rows[k] for k in keys], 10) if keys else 0
    bd_a = {k for k in keys if a_rows[k] <= thr_a}
    bd_b = {k for k in keys if b_rows[k] <= thr_b}
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


# ---- io helpers -------------------------------------------------------------------------
def read_csv_raw(p):
    """Return (header_list, list_of_dict_rows) preserving exact header order."""
    with open(p, newline="") as f:
        rd = csv.reader(f)
        header = next(rd)
        rows = [dict(zip(header, r)) for r in rd]
    return header, rows


def load_csv(p):
    return read_csv_raw(p)[1]


def load_json(p):
    with open(p) as f:
        return json.load(f)


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def approx(a, b, tol=1e-6):
    if a is None or b is None:
        return a == b
    return abs(float(a) - float(b)) <= tol * max(1.0, abs(float(a)), abs(float(b)))


def _blob_sha(base_root, rev, relpath):
    """sha256 of the committed blob CONTENT at rev:relpath (independent of any manifest)."""
    try:
        raw = subprocess.check_output(["git", "-C", base_root, "cat-file", "blob", "%s:%s" % (rev, relpath)],
                                      stderr=subprocess.DEVNULL)
    except Exception:
        return None
    return hashlib.sha256(raw).hexdigest()


def _blob_id(base_root, rev, relpath):
    try:
        return subprocess.check_output(["git", "-C", base_root, "rev-parse", "%s:%s" % (rev, relpath)],
                                       stderr=subprocess.DEVNULL).decode().strip()
    except Exception:
        return None


# ---- (E + chronology) provenance and independent immutable-blob verification -------------
def verify_provenance(oq, base_root, candidate_rev):
    checks, detail = {}, {}

    def is_anc(a, b):
        return subprocess.call(["git", "-C", base_root, "merge-base", "--is-ancestor", a, b],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0

    checks["chronology_prereg_before_arch"] = is_anc(ANCHORS["prereg"], ANCHORS["arch"])
    checks["chronology_arch_before_drift"] = is_anc(ANCHORS["arch"], ANCHORS["drift"])
    checks["chronology_drift_before_result_head"] = is_anc(ANCHORS["drift"], ANCHORS["result_head"])
    checks["chronology_result_before_correction"] = is_anc(ANCHORS["result_head"], ANCHORS["correction"])
    checks["chronology_correction_before_v2"] = is_anc(ANCHORS["correction"], ANCHORS["v2"])
    checks["prereg_is_ancestor_of_candidate"] = is_anc(ANCHORS["prereg"], candidate_rev)
    checks["v2_is_ancestor_of_candidate"] = is_anc(ANCHORS["v2"], candidate_rev)

    # Independent immutable-blob verification: hash the working file AND compare to the
    # committed anchor blob content (NOT to any manifest boolean).
    pre_bad, res_bad = [], []
    for rel in PREREG_IMMUTABLE:
        want = _blob_sha(base_root, ANCHORS["prereg"], "%s/%s" % (OQ_REL, rel))
        got = sha256_file(os.path.join(oq, rel)) if os.path.exists(os.path.join(oq, rel)) else None
        cand = _blob_sha(base_root, candidate_rev, "%s/%s" % (OQ_REL, rel))
        if want is None or got != want or cand != want:
            pre_bad.append(rel)
    for rel in RESULT_IMMUTABLE:
        want = _blob_sha(base_root, ANCHORS["result_head"], "%s/%s" % (OQ_REL, rel))
        got = sha256_file(os.path.join(oq, rel)) if os.path.exists(os.path.join(oq, rel)) else None
        cand = _blob_sha(base_root, candidate_rev, "%s/%s" % (OQ_REL, rel))
        if want is None or got != want or cand != want:
            res_bad.append(rel)
    checks["prereg_immutable_bytes_match_working_and_candidate"] = (len(pre_bad) == 0)
    checks["result_immutable_bytes_match_working_and_candidate"] = (len(res_bad) == 0)
    detail["prereg_immutable_mismatches"] = pre_bad
    detail["result_immutable_mismatches"] = res_bad
    detail["n_prereg_immutable"] = len(PREREG_IMMUTABLE)
    detail["n_result_immutable"] = len(RESULT_IMMUTABLE)

    # Cross-check CORRECTION_MANIFEST's own recorded sha256 against independently computed
    # blob-content sha256 at 1e2e1d9 (verify the manifest's claims; do not trust its boolean).
    corr = load_json(os.path.join(oq, "CORRECTION_MANIFEST.json"))
    checks["correction_manifest_base_is_result_head"] = (corr["base_experimental_head"] == ANCHORS["result_head"])
    cm_bad = []
    for art in corr["artifacts"]:
        if art["category"] not in ("original_raw", "original_manifest"):
            continue
        rel_in_oq = art["path"].split(OQ_REL + "/", 1)[-1]
        indep = _blob_sha(base_root, ANCHORS["result_head"], "%s/%s" % (OQ_REL, rel_in_oq))
        work = sha256_file(os.path.join(oq, rel_in_oq)) if os.path.exists(os.path.join(oq, rel_in_oq)) else None
        if not (art["sha256"] == indep == work):
            cm_bad.append(rel_in_oq)
    checks["correction_manifest_original_hashes_independently_confirmed"] = (len(cm_bad) == 0)
    detail["correction_manifest_mismatches"] = cm_bad

    # carrier-decision provenance strings match the true preregistration.
    dec = load_json(os.path.join(oq, "DRIFT_CARRIER_DECISION.json"))
    checks["carrier_decision_prereg_commit_matches"] = (dec["public_preregistration_commit"] == ANCHORS["prereg"])
    checks["carrier_decision_prereg_protocol_sha_matches"] = (
        dec["public_preregistration_protocol_sha256"]
        == _blob_sha(base_root, ANCHORS["prereg"], "%s/%s" % (OQ_REL, "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md")))
    return checks, detail


# ---- (B) exact schemas and structural joins ---------------------------------------------
def verify_schemas(oq):
    checks, detail = {}, {}
    A = os.path.join(oq, "results", "architecture_v1", "raw")
    D = os.path.join(oq, "results", "drift_v1", "raw")
    files = {
        "arch/scenarios": os.path.join(A, "scenarios.csv"), "arch/runs": os.path.join(A, "runs.csv"),
        "arch/agents": os.path.join(A, "agents.csv"), "arch/distributed": os.path.join(A, "distributed.csv"),
        "arch/infeasible": os.path.join(A, "infeasible.csv"), "drift/scenarios": os.path.join(D, "scenarios.csv"),
        "drift/runs": os.path.join(D, "runs.csv"), "drift/agents": os.path.join(D, "agents.csv"),
        "drift/declarations": os.path.join(D, "declarations.csv"), "drift/distributed": os.path.join(D, "distributed.csv"),
        "drift/infeasible": os.path.join(D, "infeasible.csv"),
    }
    hdr_bad = []
    tables = {}
    for name, path in files.items():
        header, rows = read_csv_raw(path)
        tables[name] = rows
        if header != EXPECT_HEADERS[name]:
            hdr_bad.append(name)
    checks["exact_headers_all_tables"] = (len(hdr_bad) == 0)
    detail["header_mismatches"] = hdr_bad

    def unique_key(rows, cols):
        seen = set()
        for r in rows:
            k = tuple(r[c] for c in cols)
            if k in seen:
                return False
            seen.add(k)
        return True

    # row counts
    checks["arch_rowcounts"] = (len(tables["arch/scenarios"]) == 400 and len(tables["arch/runs"]) == 2400
                                and len(tables["arch/agents"]) == 14400 and len(tables["arch/distributed"]) == 400
                                and len(tables["arch/infeasible"]) == 0)
    checks["drift_rowcounts"] = (len(tables["drift/scenarios"]) == 2000 and len(tables["drift/runs"]) == 18000
                                 and len(tables["drift/agents"]) == 108000 and len(tables["drift/declarations"]) == 48000
                                 and len(tables["drift/distributed"]) == 0 and len(tables["drift/infeasible"]) == 0)

    # unique logical keys (no duplicate rows on the key)
    checks["arch_unique_keys"] = all([
        unique_key(tables["arch/scenarios"], ["cell", "seed"]),
        unique_key(tables["arch/runs"], ["cell", "seed", "arm"]),
        unique_key(tables["arch/agents"], ["cell", "seed", "arm", "agent"]),
        unique_key(tables["arch/distributed"], ["cell", "seed"]),
    ])
    checks["drift_unique_keys"] = all([
        unique_key(tables["drift/scenarios"], ["cell", "seed"]),
        unique_key(tables["drift/runs"], ["cell", "seed", "arm"]),
        unique_key(tables["drift/agents"], ["cell", "seed", "arm", "agent"]),
        unique_key(tables["drift/declarations"], ["delta", "contention", "seed", "agent", "source"]),
    ])

    # structural joins: exactly one scenario per (cell,seed); every arm once; six agents join.
    def join_ok(scen, runs, agents, cells, arms):
        scen_keys = {(r["cell"], r["seed"]) for r in scen}
        if len(scen_keys) != len(scen):
            return False
        # one scenario per (cell,seed) already ensured by unique_key + count; check runs arms
        by_cs = defaultdict(list)
        for r in runs:
            by_cs[(r["cell"], r["seed"])].append(r["arm"])
        if set(by_cs) != scen_keys:
            return False
        for k, a in by_cs.items():
            if sorted(a) != sorted(arms):
                return False
        # every agents row joins to a run key; exactly 6 agents a0..a5 per run
        by_csa = defaultdict(set)
        run_keys = {(r["cell"], r["seed"], r["arm"]) for r in runs}
        for a in agents:
            key = (a["cell"], a["seed"], a["arm"])
            if key not in run_keys:
                return False  # orphan agent
            by_csa[key].add(a["agent"])
        if set(by_csa) != run_keys:
            return False  # a run with no agents
        return all(v == set(AGENTS) for v in by_csa.values())

    checks["arch_structural_joins"] = join_ok(tables["arch/scenarios"], tables["arch/runs"],
                                              tables["arch/agents"], ARCH_CELLS, ARMS_ARCH)
    checks["drift_structural_joins"] = join_ok(tables["drift/scenarios"], tables["drift/runs"],
                                               tables["drift/agents"], DRIFT_CELLS, ARMS_DRIFT)
    # declarations: 4 sources x 6 agents per (cell->(delta,contention),seed); join to scenarios
    scen_ds = {(r["delta"], r["contention"], r["seed"]) for r in tables["drift/scenarios"]}
    decl_by = defaultdict(set)
    decl_ok = True
    for d in tables["drift/declarations"]:
        k = (d["delta"], d["contention"], d["seed"])
        if k not in scen_ds:
            decl_ok = False
        decl_by[k].add((d["agent"], d["source"]))
    if set(decl_by) != scen_ds:
        decl_ok = False
    want_pairs = {(a, s) for a in AGENTS for s in SOURCES}
    checks["drift_declarations_complete_join"] = bool(decl_ok and all(v == want_pairs for v in decl_by.values()))

    # cells present exactly as expected
    checks["arch_cells_exact"] = ({r["cell"] for r in tables["arch/scenarios"]} == set(ARCH_CELLS))
    checks["drift_cells_exact"] = ({r["cell"] for r in tables["drift/scenarios"]} == set(DRIFT_CELLS))
    detail["tables_present"] = sorted(files)
    return checks, detail


# ---- (C) workload-hash, capacity and common-random-number invariants --------------------
def verify_workload_and_crn(oq):
    checks, detail = {}, {}
    asc = load_csv(os.path.join(oq, "results", "architecture_v1", "raw", "scenarios.csv"))
    dsc = load_csv(os.path.join(oq, "results", "drift_v1", "raw", "scenarios.csv"))
    a_agents = load_csv(os.path.join(oq, "results", "architecture_v1", "raw", "agents.csv"))
    d_agents = load_csv(os.path.join(oq, "results", "drift_v1", "raw", "agents.csv"))

    # architecture: one workload per seed, reused across the two contention levels.
    a_by_seed = defaultdict(set)
    for r in asc:
        a_by_seed[r["seed"]].add(r["task_workload_hash"])
    a_comp_by_seed = defaultdict(set)
    for r in asc:
        a_comp_by_seed[r["seed"]].add(r["realized_task_counts_by_agent"])
    n_arch_wh = len({r["task_workload_hash"] for r in asc})
    checks["arch_workload_reused_across_contention"] = all(len(v) == 1 for v in a_by_seed.values())
    checks["arch_workload_hash_count_is_200"] = (n_arch_wh == 200 and len(a_by_seed) == 200)
    checks["arch_composition_reused_across_contention"] = all(len(v) == 1 for v in a_comp_by_seed.values())
    detail["arch_unique_workload_hashes"] = n_arch_wh

    # drift: workload hash is scoped to the physical scenario (cell,seed) -> UNIQUE per row
    # (2000), shared across the 9 declaration-source arms; the physical task composition is
    # reused across contention for the same (delta,seed). (Documented divergence from an
    # earlier "1000" expectation: the frozen drift design does NOT reuse the *hash* across
    # contention; see VERIFICATION_FINAL_AUDIT.md.)
    n_drift_wh = len({r["task_workload_hash"] for r in dsc})
    checks["drift_workload_hash_unique_per_scenario_2000"] = (n_drift_wh == 2000 and len(dsc) == 2000)
    detail["drift_unique_workload_hashes"] = n_drift_wh
    # shared across arms: every runs/agents row joins to exactly one scenario hash
    dwh = {(r["cell"], r["seed"]): r["task_workload_hash"] for r in dsc}
    checks["drift_agents_join_workload_hash"] = all((a["cell"], a["seed"]) in dwh for a in d_agents)
    checks["arch_agents_join_workload_hash"] = all(
        (a["cell"], a["seed"]) in {(s["cell"], s["seed"]) for s in asc} for a in a_agents)
    # physical composition (equal arm realized_demand) reused across contention per (delta,seed,agent)
    comp_by = defaultdict(dict)
    for a in d_agents:
        if a["arm"] == "equal":
            comp_by[(a["delta"], a["seed"], a["agent"])][a["contention"]] = a["realized_demand"]
    checks["drift_composition_reused_across_contention"] = all(
        len(set(v.values())) == 1 for v in comp_by.values() if len(v) == 2)

    # capacity invariant across delta for same (contention, seed) (baseline-latent capacity)
    cap_by = defaultdict(set)
    for r in dsc:
        cap_by[(r["contention"], r["seed"])].add(r["capacity_by_resource"])
    checks["drift_capacity_invariant_across_delta"] = all(len(v) == 1 for v in cap_by.values())
    detail["drift_capacity_groups"] = len(cap_by)

    # common-random-number nested-drift invariants (deterministically recoverable from raw)
    dsrc = defaultdict(dict)
    chg_le = True
    for r in dsc:
        dsrc[(r["contention"], r["seed"])][float(r["delta"])] = float(r["drift_source_total"])
        if float(r["changed_identities_total"]) > float(r["drift_source_total"]) + 1e-9:
            chg_le = False
    mono, d0zero, d1full = True, True, True
    for k, dd in dsrc.items():
        xs = [dd[d] for d in sorted(dd)]
        if any(xs[i + 1] < xs[i] - 1e-9 for i in range(len(xs) - 1)):
            mono = False
        if dd.get(0.0, 0.0) != 0.0:
            d0zero = False
        if abs(dd.get(1.0, 48.0) - 48.0) > 1e-9:  # delta=1 -> all 48 draws from the drift source
            d1full = False
    checks["drift_crn_drift_source_monotonic_in_delta"] = mono
    checks["drift_crn_zero_drift_at_delta0"] = d0zero
    checks["drift_crn_full_drift_at_delta1"] = d1full
    checks["drift_changed_identities_le_drift_source"] = chg_le
    return checks, detail


# ---- (D) seed disjointness from ACTUAL committed seed sets ------------------------------
def _seed_col(base_root, relpath, col="seed"):
    p = os.path.join(base_root, relpath)
    if not os.path.exists(p):
        return None
    return {int(r[col]) for r in load_csv(p)}


def verify_seed_disjointness(oq, base_root):
    checks, detail = {}, {}
    # ACTUAL confirmatory seeds from committed raw data
    actual_arch = {int(r["seed"]) for r in load_csv(os.path.join(oq, "results", "architecture_v1", "raw", "scenarios.csv"))}
    actual_drift = {int(r["seed"]) for r in load_csv(os.path.join(oq, "results", "drift_v1", "raw", "scenarios.csv"))}
    # ACTUAL prior seeds from the other committed experiments (loaded, not just regenerated)
    canon = set()
    for rel in ("experiments/platform_mediation/results/raw/runs.csv",
                "experiments/platform_mediation/results/raw/agents.csv"):
        s = _seed_col(base_root, rel)
        if s:
            canon |= s
    pilot = set()
    for rel in ("experiments/platform_mediation_heterogeneity/results/raw/workload_workloads.csv",
                "experiments/platform_mediation_heterogeneity/results/raw/floor_workloads.csv",
                "experiments/platform_mediation_heterogeneity/results/raw/workload_runs.csv",
                "experiments/platform_mediation_heterogeneity/results/raw/floor_runs.csv"):
        s = _seed_col(base_root, rel)
        if s:
            pilot |= s
    hetconf = _seed_col(base_root, "experiments/platform_mediation_heterogeneity/results/confirmatory_v1/raw/scenarios.csv") or set()

    detail["counts"] = {"arch": len(actual_arch), "drift": len(actual_drift), "canonical": len(canon),
                        "pilot": len(pilot), "heterogeneity_confirmatory": len(hetconf)}
    overlaps = {
        "arch_vs_drift": len(actual_arch & actual_drift),
        "arch_vs_canonical": len(actual_arch & canon), "arch_vs_pilot": len(actual_arch & pilot),
        "arch_vs_hetconf": len(actual_arch & hetconf),
        "drift_vs_canonical": len(actual_drift & canon), "drift_vs_pilot": len(actual_drift & pilot),
        "drift_vs_hetconf": len(actual_drift & hetconf),
    }
    detail["actual_overlaps"] = overlaps
    checks["actual_seed_overlaps_all_zero"] = all(v == 0 for v in overlaps.values())
    checks["arch_seeds_200_unique"] = (len(actual_arch) == 200)
    checks["drift_seeds_200_unique"] = (len(actual_drift) == 200)
    checks["prior_seed_sets_nonempty"] = (len(canon) > 0 and len(pilot) > 0 and len(hetconf) > 0)

    # formula reproduction: actual == derive_seed formula == committed seed manifest
    formula_arch = set(scenario_seeds(NS_ARCH_CONF, 200))
    formula_drift = set(scenario_seeds(NS_DRIFT_CONF, 200))
    man = load_json(os.path.join(oq, "CONFIRMATORY_SEED_MANIFEST.json"))
    checks["arch_seeds_match_formula"] = (actual_arch == formula_arch)
    checks["drift_seeds_match_formula"] = (actual_drift == formula_drift)
    checks["arch_seeds_match_manifest_list"] = (actual_arch == set(man["architecture_scenario_seeds"]))
    checks["drift_seeds_match_manifest_list"] = (actual_drift == set(man["drift_scenario_seeds"]))
    # development namespaces (not committed as raw data) — formula disjointness
    dev = set()
    for ns in NS_DEV:
        dev |= set(scenario_seeds(ns, 200))
    checks["confirmatory_disjoint_from_development_formula"] = (len(dev & (actual_arch | actual_drift)) == 0)
    checks["confirmatory_disjoint_from_canonical_formula"] = (
        len(canonical_formula_universe() & (actual_arch | actual_drift)) == 0)
    return checks, detail


# ---- indexing helpers -------------------------------------------------------------------
def _index_runs(runs):
    idx = {(r["cell"], r["seed"], r["arm"]): r for r in runs}
    seeds_by, seen = defaultdict(list), set()
    for r in runs:
        if (r["cell"], r["seed"]) not in seen:
            seen.add((r["cell"], r["seed"]))
            seeds_by[r["cell"]].append(r["seed"])
    return idx, seeds_by


def _best_subset_all(agent_rows, comp_of):
    """Reconstruct best_subset_count for EVERY agent row by enumerating all 256 subsets.
    ``comp_of(row) -> (multiset, ok)``. Returns (all_match, n_checked, n_comp_fail, run_bs)
    where run_bs maps (cell,seed,arm) -> summed recorded counts (for run aggregation)."""
    # group by composition multiset for vectorized fit across records that share it
    groups = defaultdict(list)     # multiset -> list of (caps4, recorded, run_key)
    n_comp_fail = 0
    for a in agent_rows:
        ms, ok = comp_of(a)
        if not ok:
            n_comp_fail += 1
            continue
        caps = json.loads(a["allocated"])
        caps4 = tuple(int(round(float(caps.get(r, 0)))) for r in RESOURCES)
        groups[ms].append((caps4, int(a["best_subset_count"]),
                           (a["cell"], a["seed"], a["arm"]), float(a["best_subset_completion"])))
    all_match = True
    n_checked = 0
    run_bs = defaultdict(int)
    for ms, items in groups.items():
        sizes, demand = enumerate_256(ms)
        caps = np.array([it[0] for it in items], dtype=np.int64)          # M x 4
        fit = (demand[None, :, :] <= caps[:, None, :]).all(axis=2)        # M x 256
        big = np.where(fit, sizes[None, :], -1)
        best = big.max(axis=1)                                            # M
        for i, it in enumerate(items):
            rec = it[1]
            n_checked += 1
            run_bs[it[2]] += rec
            if int(best[i]) != rec or not approx(it[3], rec / TPA):
                all_match = False
    return all_match, n_checked, n_comp_fail, run_bs


# ---- (F + G) architecture verification --------------------------------------------------
def verify_architecture(oq, do_bootstrap=True, bs_sample=None):
    ARCH = os.path.join(oq, "results", "architecture_v1")
    runs = load_csv(os.path.join(ARCH, "raw", "runs.csv"))
    agents = load_csv(os.path.join(ARCH, "raw", "agents.csv"))
    scen = load_csv(os.path.join(ARCH, "raw", "scenarios.csv"))
    dist = load_csv(os.path.join(ARCH, "raw", "distributed.csv"))
    head = load_json(os.path.join(ARCH, "architecture_headline.json"))
    summary = load_json(os.path.join(ARCH, "summary.json"))
    decision = load_json(os.path.join(oq, "DRIFT_CARRIER_DECISION.json"))
    cells = head["co_primary_cells"]
    checks, detail = {}, {}
    idx, seeds_by = _index_runs(runs)
    sc_by = {(s["cell"], s["seed"]): s for s in scen}
    ag_by = defaultdict(dict)
    qo = defaultdict(dict)
    for a in agents:
        ag_by[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = a
        qo[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = float(a["queue_order_completion"])

    # independently re-validate the MAND footprint table against aggregate_mandatory_demand
    fp_ok = True
    for s in scen:
        counts = json.loads(s["realized_task_counts_by_agent"])
        agg = defaultdict(float)
        for per_agent in counts:
            for arch_name, c in per_agent.items():
                for r in RESOURCES:
                    agg[r] += MAND[arch_name][r] * int(c)
        want = json.loads(s["aggregate_mandatory_demand"])
        if any(abs(agg[r] - float(want.get(r, 0))) > 1e-6 for r in RESOURCES):
            fp_ok = False
            break
    checks["footprint_table_reconstructs_aggregate_demand"] = fp_ok

    # arm means (qo + bs tasks/run, frac_zero, cap_util) — every cell, every arm, every field
    means_ok = True
    for cell in cells:
        for arm in ARMS_ARCH:
            rows = [idx[(cell, s, arm)] for s in seeds_by[cell]]
            recon = {
                "qo_tasks_per_run": float(np.mean([float(r["queue_order_tasks_per_run"]) for r in rows])),
                "bs_tasks_per_run": float(np.mean([float(r["best_subset_tasks_per_run"]) for r in rows])),
                "qo_completion_mean": float(np.mean([float(r["queue_order_completion_mean"]) for r in rows])),
                "bs_completion_mean": float(np.mean([float(r["best_subset_completion_mean"]) for r in rows])),
                "frac_zero_qo": float(np.mean([float(r["frac_zero_qo"]) for r in rows])),
                "cap_util": float(np.mean([float(r["capacity_utilization"]) for r in rows])),
            }
            hv = head["cell_policy"][cell][arm]
            for k, v in recon.items():
                if not approx(v, hv[k]):
                    means_ok = False
    checks["every_arm_every_field_mean"] = means_ok

    # paired qo + bs means and CIs for every committed comparison
    def pq(cell, key, field):
        t, b = key.split("_minus_")
        return [float(idx[(cell, s, t)][field]) - float(idx[(cell, s, b)][field]) for s in seeds_by[cell]]
    if do_bootstrap:
        pqo_ok = pbs_ok = True
        n_cmp = 0
        for cell in cells:
            for key, hv in head["paired_qo"][cell].items():
                r = st(pq(cell, key, "queue_order_completion_mean"), "arch|qo|%s|%s" % (cell, key))
                n_cmp += 1
                if not (approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"])
                        and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"]) and r["n"] == hv["n"]):
                    pqo_ok = False
            for key, hv in head["paired_best_subset"][cell].items():
                r = st(pq(cell, key, "best_subset_completion_mean"), "arch|bs|%s|%s" % (cell, key))
                if not (approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"])
                        and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"])):
                    pbs_ok = False
        checks["every_paired_qo_mean_and_ci"] = pqo_ok
        checks["every_paired_best_subset_mean_and_ci"] = pbs_ok
        detail["arch_paired_comparisons_per_cell"] = n_cmp // len(cells) if cells else 0

    # zero-event totals reconstructed directly from raw (not trusted from summary)
    raw_zero = {
        "capacity_violations_total": sum(int(float(r["capacity_violation"])) for r in runs),
        "bound_violations_total": sum(int(float(r["bound_violation"])) for r in runs),
        "fallback_used_total": sum(1 for r in runs if str(r["fallback_used"]).lower() == "true"),
        "infeasible_runs": sum(1 for r in runs if str(r["feasible"]).lower() != "true"),
    }
    zero_events = all(v == 0 for v in raw_zero.values())
    checks["zero_events_reconstructed_from_raw"] = bool(zero_events and head["zero_events_all"])
    checks["summary_zero_event_totals_match_raw"] = all(summary[k] == raw_zero[k] for k in raw_zero)

    def frac_zero(cell, arm):
        return float(np.mean([float(idx[(cell, s, arm)]["frac_zero_qo"]) for s in seeds_by[cell]]))

    # five-condition components recomputed from raw (do NOT trust head booleans)
    def five(cell, t, b):
        cmp = head["paired_qo"][cell]["%s_minus_%s" % (t, b)]
        c1 = head["cell_policy"][cell][t]["qo_tasks_per_run"] > head["cell_policy"][cell][b]["qo_tasks_per_run"]
        c2 = cmp["ci_lo_tasks"] > 0.0
        c3 = cmp["mean_tasks"] >= 1.0
        c4 = frac_zero(cell, t) <= frac_zero(cell, b) + 1e-12
        c5 = zero_events
        return {"c1_higher": c1, "c2_ci_above_zero": c2, "c3_at_least_one_task": c3,
                "c4_no_zero_increase": c4, "c5_zero_events": c5, "pass": bool(c1 and c2 and c3 and c4 and c5)}
    fc_ok = True
    five_out = {"fresh_replication": {}, "coordination": {}, "independent_vs_drf": {}}
    pairs = {"fresh_replication": ("central_joint_leontief", "drf"),
             "coordination": ("central_joint_leontief", "independent_bundle_maxmin"),
             "independent_vs_drf": ("independent_bundle_maxmin", "drf")}
    for cmpname, (t, b) in pairs.items():
        for cell in cells:
            rec = five(cell, t, b)
            five_out[cmpname][cell] = rec
            hv = head["five_condition"][cmpname][cell]
            for k in ("c1_higher", "c2_ci_above_zero", "c3_at_least_one_task", "c4_no_zero_increase",
                      "c5_zero_events", "pass"):
                if rec[k] != hv[k]:
                    fc_ok = False
    checks["every_five_condition_component_from_raw"] = bool(fc_ok)

    # equivalence / noninferiority per cell (recomputed)
    ni_ok = ce_ok = True
    indep_noninf, cent_equiv = {}, {}
    for cell in cells:
        cmp = head["paired_qo"][cell]["independent_bundle_maxmin_minus_central_joint_leontief"]
        ni = bool(cmp["mean_tasks"] >= -0.25 and cmp["ci_lo_tasks"] >= -0.5 and cmp["ci_hi_tasks"] <= 0.5)
        ce = bool(abs(cmp["mean_tasks"]) <= 0.25 and cmp["ci_lo_tasks"] >= -0.5 and cmp["ci_hi_tasks"] <= 0.5)
        indep_noninf[cell], cent_equiv[cell] = ni, ce
        ni_ok &= (ni == head["flags"]["indep_noninferior_by_cell"][cell])
        ce_ok &= (ce == head["flags"]["central_independent_equivalence_by_cell"][cell])
    checks["noninferiority_by_cell_from_raw"] = bool(ni_ok)
    checks["central_independent_equivalence_by_cell_from_raw"] = bool(ce_ok)

    # separable relaxation structural: allocation + outcome equality vs equal
    relax_ok = True
    for cell in cells:
        rel = ag_by[(cell, "separable_leontief_relaxation")]
        eq = ag_by[(cell, "equal")]
        keys = set(rel) & set(eq)
        alloc_eq = float(np.mean([rel[k]["allocated"] == eq[k]["allocated"] for k in keys]))
        out_eq = float(np.mean([abs(float(rel[k]["queue_order_completion"])
                                     - float(eq[k]["queue_order_completion"])) <= 1e-12 for k in keys]))
        hv = head["separable_relaxation_vs_equal"][cell]
        relax_ok &= approx(alloc_eq, hv["allocation_equality_rate"]) and approx(out_eq, hv["outcome_equality_rate"])
    checks["separable_relaxation_structural"] = bool(relax_ok)

    # distributed: every gap-summary field reconstructed from raw
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
        checks["distributed_outcome_stats_from_raw"] = bool(os_ok)
        dist_class = ("TECHNICALLY_INVALID" if (feas.max() > 1e-7 or bnd.max() > 1e-7)
                      else "OBJECTIVE_AND_OUTCOME_EQUIVALENT" if (obj_eq and outcome_eq)
                      else "OBJECTIVE_EQUIVALENT_OUTCOME_DIFFERENT" if obj_eq else "NOT_EQUIVALENT")
        checks["distributed_classification_from_raw"] = (dist_class == head["distributed_equivalence"]["classification"]
                                                         == decision["inputs"]["distributed_classification"])
        deq = bool(obj_eq and outcome_eq)
    else:
        deq = bool(head["flags"]["distributed_equivalent"])

    # every distributional statistic (each arm vs equal and vs drf)
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

    # every harmed-set statistic (central vs distributed) vs equal and drf
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

    # (F) raw-derived carrier flags -> carrier rule -> committed decision
    rp = all(five_out["fresh_replication"][c]["pass"] for c in cells)
    cp = rp and all(five_out["coordination"][c]["pass"] for c in cells)
    ip = all(five_out["independent_vs_drf"][c]["pass"] for c in cells)
    inoninf = all(indep_noninf[c] for c in cells)
    raw_flags = {"replication_pass": rp, "coordination_pass": cp, "independent_positive": ip,
                 "independent_noninferior": inoninf, "distributed_equivalent": deq}
    checks["raw_derived_flags_match_headline"] = all(bool(raw_flags[k]) == bool(head["flags"][k]) for k in raw_flags)
    checks["raw_derived_flags_match_carrier_decision"] = all(
        bool(raw_flags[k]) == bool(decision["conditions"][k]) for k in raw_flags)
    carrier, branch = carrier_rule(rp, cp, ip, inoninf, deq)
    checks["adaptive_carrier_rule_from_raw"] = (carrier == decision["selected_carrier"] and branch == decision["branch"])
    detail["raw_flags"] = raw_flags

    # exact best-subset: enumerate all 256 subsets for EVERY architecture agent record
    def comp_arch(a):
        counts = json.loads(sc_by[(a["cell"], a["seed"])]["realized_task_counts_by_agent"])[int(a["agent"][1:])]
        return multiset_from_counts(counts), True
    rows_iter = agents if bs_sample is None else agents[:bs_sample]
    bs_ok, n_checked, _, run_bs = _best_subset_all(rows_iter, comp_arch)
    if bs_sample is None:
        for k, tot in run_bs.items():
            if not approx(tot, float(idx[k]["best_subset_tasks_per_run"])):
                bs_ok = False
    checks["exact_best_subset_enumeration_all_records"] = bool(bs_ok)
    detail["arch_best_subset_records_checked"] = n_checked

    # allocation feasibility directly (installed sums vs capacity; recorded bounds)
    feas_ok = True
    for cell in cells:
        for s in seeds_by[cell]:
            cap = json.loads(sc_by[(cell, s)]["capacity_by_resource"])
            for arm in ARMS_ARCH:
                tot = {r: 0.0 for r in RESOURCES}
                for ag in AGENTS:
                    a = ag_by[(cell, arm)][(s, ag)]
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
    checks["allocation_feasibility_direct"] = bool(feas_ok)

    # summary record-count aggregations reconstructed from raw
    n_feasible = sum(1 for r in runs if str(r["feasible"]).lower() == "true")
    checks["summary_record_counts_match_raw"] = all([
        summary["expected_runs"] == 2400, summary["feasible_runs"] == n_feasible == 2400,
        summary["infeasible_runs"] == 0, summary["n_agent_records"] == len(agents) == 14400,
        summary["n_scenario_rows"] == len(scen) == 400, summary["disjointness"]["n_workload_hashes"] == 200,
    ])
    return checks, detail


# ---- (F + H) drift verification ---------------------------------------------------------
def verify_drift(oq, do_bootstrap=True, bs_sample=None):
    DRIFT = os.path.join(oq, "results", "drift_v1")
    runs = load_csv(os.path.join(DRIFT, "raw", "runs.csv"))
    agents = load_csv(os.path.join(DRIFT, "raw", "agents.csv"))
    scen = load_csv(os.path.join(DRIFT, "raw", "scenarios.csv"))
    head = load_json(os.path.join(DRIFT, "drift_headline.json"))
    summary = load_json(os.path.join(DRIFT, "summary.json"))
    checks, detail = {}, {}
    idx, seeds_by = _index_runs(runs)
    cells = DRIFT_CELLS

    checks["carrier_is_central_native_nondistributed"] = (
        summary["carrier"] == "central_joint_leontief" and summary["is_distributed_carrier"] is False
        and head.get("carrier") == "central_joint_leontief")

    def pq(cell, t, b, field="queue_order_completion_mean"):
        return [float(idx[(cell, s, t)][field]) - float(idx[(cell, s, b)][field]) for s in seeds_by[cell]]

    raw_zero = {
        "capacity_violations_total": sum(int(float(r["capacity_violation"])) for r in runs),
        "bound_violations_total": sum(int(float(r["bound_violation"])) for r in runs),
        "fallback_used_total": sum(1 for r in runs if str(r["fallback_used"]).lower() == "true"),
        "infeasible_runs": sum(1 for r in runs if str(r["feasible"]).lower() != "true"),
    }
    zero_events = all(v == 0 for v in raw_zero.values())
    checks["zero_events_reconstructed_from_raw"] = bool(zero_events and head["zero_events_all"])
    checks["summary_zero_event_totals_match_raw"] = all(summary[k] == raw_zero[k] for k in raw_zero)

    if do_bootstrap:
        cp_ok = True
        for cc in head["co_primary_cells"]:
            cell = "delta%.2f__%s" % (cc["delta"], cc["contention"])
            r = st(pq(cell, "carrier_stale_calibration", "drf_stale_calibration"),
                   "drift|%s|carrier_stale_minus_drf_stale" % cell)
            hv = head["co_primary_decision"][cell]
            cp_ok &= (approx(r["mean_tasks"], hv["mean_tasks"]) and approx(r["ci_lo_tasks"], hv["ci_lo_tasks"])
                      and approx(r["ci_hi_tasks"], hv["ci_hi_tasks"]))
            c1 = np.mean([float(idx[(cell, s, "carrier_stale_calibration")]["queue_order_tasks_per_run"]) for s in seeds_by[cell]]) > \
                 np.mean([float(idx[(cell, s, "drf_stale_calibration")]["queue_order_tasks_per_run"]) for s in seeds_by[cell]])
            c4 = np.mean([float(idx[(cell, s, "carrier_stale_calibration")]["frac_zero_qo"]) for s in seeds_by[cell]]) <= \
                 np.mean([float(idx[(cell, s, "drf_stale_calibration")]["frac_zero_qo"]) for s in seeds_by[cell]]) + 1e-12
            passed = bool(c1 and r["ci_lo_tasks"] > 0 and r["mean_tasks"] >= 1.0 and c4 and zero_events)
            cp_ok &= (passed == hv["pass"] and c1 == hv["c1_higher"] and c4 == hv["c4_no_zero_increase"])
        checks["drift_co_primary_mean_ci_and_five_condition"] = bool(cp_ok)

        # both independently-seeded appearances of the primary comparison (co-primary + secondary)
        both_ok = True
        for cell in ("delta0.25__moderate", "delta0.25__high"):
            d = pq(cell, "carrier_stale_calibration", "drf_stale_calibration")
            a = st(d, "drift|%s|carrier_stale_minus_drf_stale" % cell)
            b = st(d, "drift|%s|cmd_stale_calibration" % cell)
            hv_a = head["co_primary_decision"][cell]
            hv_b = head["secondary"][cell]["carrier_minus_drf_stale_calibration"]
            both_ok &= approx(a["ci_lo_tasks"], hv_a["ci_lo_tasks"]) and approx(a["ci_hi_tasks"], hv_a["ci_hi_tasks"])
            both_ok &= approx(b["ci_lo_tasks"], hv_b["ci_lo_tasks"]) and approx(b["ci_hi_tasks"], hv_b["ci_hi_tasks"])
            both_ok &= approx(a["mean_tasks"], b["mean_tasks"])
        checks["drift_primary_both_seeded_appearances"] = bool(both_ok)

        # every secondary mean and CI, every cell
        sec_ok = True
        for cell in cells:
            block = head["secondary"][cell]
            for src in SOURCES:
                r = st(pq(cell, "carrier_%s" % src, "drf_%s" % src), "drift|%s|cmd_%s" % (cell, src))
                hv = block["carrier_minus_drf_%s" % src]
                sec_ok &= all(approx(r[k], hv[k]) for k in ("mean_tasks", "ci_lo_tasks", "ci_hi_tasks"))
            for name, t, b, tag in (("carrier_stale_minus_refreshed", "carrier_stale_calibration",
                                     "carrier_refreshed_calibration", "csr"),
                                    ("drf_stale_minus_refreshed", "drf_stale_calibration",
                                     "drf_refreshed_calibration", "dsr")):
                r = st(pq(cell, t, b), "drift|%s|%s" % (cell, tag))
                hv = block[name]
                sec_ok &= all(approx(r[k], hv[k]) for k in ("mean_tasks", "ci_lo_tasks", "ci_hi_tasks"))
            did = []
            for s in seeds_by[cell]:
                ks = [(cell, s, a) for a in ("carrier_stale_calibration", "drf_stale_calibration",
                                             "carrier_refreshed_calibration", "drf_refreshed_calibration")]
                did.append((float(idx[ks[0]]["queue_order_completion_mean"]) - float(idx[ks[1]]["queue_order_completion_mean"]))
                           - (float(idx[ks[2]]["queue_order_completion_mean"]) - float(idx[ks[3]]["queue_order_completion_mean"])))
            r = st(did, "drift|%s|did" % cell)
            hv = block["difference_in_differences"]
            sec_ok &= all(approx(r[k], hv[k]) for k in ("mean_tasks", "ci_lo_tasks", "ci_hi_tasks"))
            r = st(pq(cell, "carrier_stale_calibration", "drf_stale_calibration", "best_subset_completion_mean"),
                   "drift|%s|bs" % cell)
            hv = block["best_subset_carrier_minus_drf_stale"]
            sec_ok &= all(approx(r[k], hv[k]) for k in ("mean_tasks", "ci_lo_tasks", "ci_hi_tasks"))
            for arm in ARMS_DRIFT:
                got = float(np.mean([float(idx[(cell, s, arm)]["queue_order_tasks_per_run"]) for s in seeds_by[cell]]))
                if not approx(got, block["arm_tasks_per_run"][arm]):
                    sec_ok = False
        checks["every_drift_secondary_mean_and_ci"] = bool(sec_ok)

    # classification via the EXACT frozen tree (recomputed, not "not-all-pass")
    def cell_pass_from_head(cell):
        return bool(head["co_primary_decision"][cell]["pass"])
    coprimary_cells = ["delta%.2f__%s" % (cc["delta"], cc["contention"]) for cc in head["co_primary_cells"]]
    all_pass = all(cell_pass_from_head(c) for c in coprimary_cells)
    if all_pass:
        expect_class = "ROBUST_AT_MODEST_DRIFT"
    else:
        def pass_src(src):
            ok = True
            for c in coprimary_cells:
                stt = head["secondary"][c]["carrier_minus_drf_%s" % src]
                ok = ok and (stt["ci_lo_tasks"] > 0 and stt["mean_tasks"] >= 1.0)
            return ok
        if pass_src("refreshed_calibration"):
            expect_class = "REFRESH_DEPENDENT"
        elif pass_src("execution_queue_oracle"):
            expect_class = "ORACLE_DEPENDENT"
        else:
            expect_class = "NO_MATERIAL_ADVANTAGE_IN_NEW_DESIGN"
    checks["drift_classification_exact_tree"] = (head["declaration_robustness_classification"] == expect_class)
    detail["drift_classification"] = expect_class

    # drift metrics per cell (all fields)
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

    # exact best-subset: enumerate all 256 subsets for EVERY drift agent record
    def comp_drift(a):
        comp, ok = recover_composition(json.loads(a["realized_demand"]))
        return (multiset_from_counts(comp) if ok else None), ok
    rows_iter = agents if bs_sample is None else agents[:bs_sample]
    bs_ok, n_checked, n_comp_fail, run_bs = _best_subset_all(rows_iter, comp_drift)
    if bs_sample is None:
        for k, tot in run_bs.items():
            if not approx(tot, float(idx[k]["best_subset_tasks_per_run"])):
                bs_ok = False
    checks["exact_best_subset_enumeration_all_records"] = bool(bs_ok)
    checks["drift_composition_uniquely_recovered"] = (n_comp_fail == 0)
    detail["drift_best_subset_records_checked"] = n_checked

    # allocation feasibility (installed sums vs capacity)
    ag_by = defaultdict(dict)
    for a in agents:
        ag_by[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = a
    cap_by = {(s["cell"], s["seed"]): json.loads(s["capacity_by_resource"]) for s in scen}
    feas_ok = True
    for cell in cells:
        for s in seeds_by[cell]:
            cap = cap_by[(cell, s)]
            for arm in ARMS_DRIFT:
                tot = {r: 0.0 for r in RESOURCES}
                for ag in AGENTS:
                    al = json.loads(ag_by[(cell, arm)][(s, ag)]["allocated"])
                    for r in RESOURCES:
                        tot[r] += float(al.get(r, 0))
                if any(tot[r] > cap[r] + 1e-6 for r in RESOURCES):
                    feas_ok = False
    checks["allocation_feasibility_direct"] = bool(feas_ok)

    checks["summary_record_counts_match_raw"] = all([
        summary["feasible_runs"] == 18000 == sum(1 for r in runs if str(r["feasible"]).lower() == "true"),
        summary["n_arms_per_unit"] == 9, len(agents) == 108000, len(runs) == 18000,
    ])
    return checks, detail


# ---- (A) exhaustive preregistered-secondary supplement (every cell/arm/field; JSON<->CSV) --
def verify_secondary_supplement(oq):
    DRIFT = os.path.join(oq, "results", "drift_v1")
    supp = os.path.join(DRIFT, "preregistered_secondary_completion")
    checks, detail = {}, {}
    jf, cf = os.path.join(supp, "drift_secondary_completion.json"), os.path.join(supp, "drift_secondary_completion.csv")
    checks["secondary_supplement_present"] = os.path.exists(jf) and os.path.exists(cf)
    if not checks["secondary_supplement_present"]:
        return checks, detail
    sec = load_json(jf)
    csv_header, csv_rows = read_csv_raw(cf)
    runs = load_csv(os.path.join(DRIFT, "raw", "runs.csv"))
    agents = load_csv(os.path.join(DRIFT, "raw", "agents.csv"))
    scen = load_csv(os.path.join(DRIFT, "raw", "scenarios.csv"))
    idx, seeds_by = _index_runs(runs)
    qo = defaultdict(dict)
    pk_of, ds_of = {}, {}
    for a in agents:
        qo[(a["cell"], a["arm"])][(a["seed"], a["agent"])] = float(a["queue_order_completion"])
        pk_of[(a["cell"], a["arm"])] = a["policy_kind"]
        ds_of[(a["cell"], a["arm"])] = a["declaration_source"]
    scen_by = defaultdict(list)
    for s in scen:
        scen_by[s["cell"]].append(s)

    # exact structure: 10 cells, 9 arms each, expected keys
    checks["secondary_cells_and_arms_exact"] = (
        set(sec["cells"]) == set(DRIFT_CELLS)
        and all(set(sec["cells"][c]["arms"]) == set(ARMS_DRIFT) for c in DRIFT_CELLS))
    checks["secondary_csv_header_exact"] = (csv_header == SECONDARY_CSV_FIELDS)
    checks["secondary_csv_rowcount_90"] = (len(csv_rows) == len(DRIFT_CELLS) * len(ARMS_DRIFT) == 90)
    # duplicate absence in CSV (one row per (cell,arm))
    csv_keys = [(r["cell"], r["arm"]) for r in csv_rows]
    checks["secondary_csv_no_duplicate_keys"] = (len(csv_keys) == len(set(csv_keys)))

    def arm_run_means(cell, arm):
        rows = [idx[(cell, s, arm)] for s in seeds_by[cell]]
        return {
            "n": len(rows),
            "mean_qo_tasks_per_run": float(np.mean([float(r["queue_order_tasks_per_run"]) for r in rows])),
            "mean_bs_tasks_per_run": float(np.mean([float(r["best_subset_tasks_per_run"]) for r in rows])),
            "mean_cap_util": float(np.mean([float(r["capacity_utilization"]) for r in rows])),
            "mean_unused_installed": float(np.mean([float(r["unused_installed_total"]) for r in rows])),
            "frac_zero_qo": float(np.mean([float(r["frac_zero_qo"]) for r in rows])),
        }

    def scen_metrics(cell):
        rows = scen_by[cell]
        mean = lambda col: float(np.mean([float(r[col]) for r in rows]))
        return {
            "n_scenarios": len(rows), "drift_source_total_mean": mean("drift_source_total"),
            "changed_identities_total_mean": mean("changed_identities_total"),
            "task_mixture_tv_from_baseline_mean": mean("task_mixture_tv_from_baseline_mean"),
            "mand_demand_tv_mean_pairwise": mean("mand_demand_tv_mean_pairwise"),
            "task_entropy_mean": mean("task_entropy_mean"),
            "cross_agent_dissimilarity_mean": mean("cross_agent_dissimilarity"),
            "staleness_error_mean": mean("staleness_error_mean"), "calibration_error_mean": mean("calibration_error_mean"),
            "latent_oracle_error_mean": mean("latent_oracle_error_mean"),
            "realized_contention_mean": float(np.mean([max(json.loads(r["realized_contention_by_resource"]).values()) for r in rows])),
        }

    csv_by = {(r["cell"], r["arm"]): r for r in csv_rows}
    json_ok = csv_ok = jc_ok = sm_ok = True
    n_json_entries = 0
    RM_FIELDS = ["n", "mean_qo_tasks_per_run", "mean_bs_tasks_per_run", "mean_cap_util",
                 "mean_unused_installed", "frac_zero_qo"]
    CSV_9 = ["n_harmed", "frac_harmed", "mean_loss_harmed", "median_loss_harmed", "worst_loss",
             "n_improved", "frac_improved", "mean_gain_improved", "median_gain_improved"]
    SM_CSV = ("drift_source_total_mean", "changed_identities_total_mean", "task_mixture_tv_from_baseline_mean",
              "mand_demand_tv_mean_pairwise", "task_entropy_mean", "cross_agent_dissimilarity_mean",
              "staleness_error_mean", "calibration_error_mean", "latent_oracle_error_mean", "realized_contention_mean")
    RM_CSV = ("mean_qo_tasks_per_run", "mean_bs_tasks_per_run", "mean_cap_util", "mean_unused_installed", "frac_zero_qo")

    def fnum(v):
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    for cell in DRIFT_CELLS:
        sm = scen_metrics(cell)
        hv_sm = (sec.get("cells", {}).get(cell, {}) or {}).get("scenario_metrics", {})
        for k in sm:
            if k not in hv_sm or not approx(sm[k], hv_sm[k]):
                sm_ok = False
        cblk = sec.get("cells", {}).get(cell, {}) or {}
        if not (approx(cblk.get("delta"), float(cell.split("__")[0].replace("delta", "")))
                and cblk.get("contention") == cell.split("__")[1]):
            sm_ok = False
        eqrows = qo[(cell, "equal")]
        for arm in ARMS_DRIFT:
            n_json_entries += 1
            blk = (cblk.get("arms", {}) or {}).get(arm)
            rm = arm_run_means(cell, arm)
            ve = supp_distributional(qo[(cell, arm)], eqrows)
            vm = None
            if arm.startswith("carrier_"):
                vm = supp_distributional(qo[(cell, arm)], qo[(cell, "drf_%s" % arm[len("carrier_"):])])
            # ---- JSON vs RAW ----
            if not isinstance(blk, dict):
                json_ok = False
            else:
                for k in RM_FIELDS:
                    if not approx(rm[k], blk.get(k)):
                        json_ok = False
                if blk.get("policy_kind") != pk_of[(cell, arm)] or blk.get("declaration_source") != ds_of[(cell, arm)]:
                    json_ok = False
                jve = blk.get("vs_equal", {}) or {}
                for f in ve:
                    if f not in jve or not approx(ve[f], jve[f]):
                        json_ok = False
                if vm is not None:
                    jvm = blk.get("vs_matched_drf")
                    if not isinstance(jvm, dict):
                        json_ok = False
                    else:
                        for f in vm:
                            if f not in jvm or not approx(vm[f], jvm[f]):
                                json_ok = False
                elif "vs_matched_drf" in blk:
                    json_ok = False
            # ---- CSV vs RAW (guarded; malformed CSV -> False, never crash) ----
            row = csv_by.get((cell, arm))
            if row is None:
                csv_ok = jc_ok = False
                continue
            csv_num = {k: rm[k] for k in RM_CSV}
            csv_num.update({"min_completion_tasks": ve["min_completion_tasks"],
                            "bottom_decile_tasks": ve["bottom_decile_tasks"],
                            "mean_completion_tasks": ve["mean_completion_tasks"]})
            for f in CSV_9:
                csv_num["vs_equal_%s" % f] = ve[f]
            for k in SM_CSV:
                csv_num[k] = sm[k]
            for k, want in csv_num.items():
                got = fnum(row.get(k))
                if got is None or not approx(got, want):
                    csv_ok = False
            for f in CSV_9:
                mv = row.get("vs_matched_drf_%s" % f)
                if vm is not None:
                    got = fnum(mv)
                    if got is None or not approx(got, vm[f]):
                        csv_ok = False
                elif mv != "":
                    csv_ok = False
            if (row.get("contention") != cell.split("__")[1] or row.get("declaration_source") != ds_of[(cell, arm)]
                    or row.get("policy_kind") != pk_of[(cell, arm)]
                    or fnum(row.get("delta")) is None
                    or not approx(fnum(row.get("delta")), float(cell.split("__")[0].replace("delta", "")))):
                csv_ok = False
            # ---- JSON vs CSV directly (cross-artifact agreement) ----
            if not isinstance(blk, dict):
                jc_ok = False
            else:
                jve = blk.get("vs_equal", {}) or {}
                jc_num = {k: blk.get(k) for k in RM_CSV}
                jc_num.update({"min_completion_tasks": jve.get("min_completion_tasks"),
                               "bottom_decile_tasks": jve.get("bottom_decile_tasks"),
                               "mean_completion_tasks": jve.get("mean_completion_tasks")})
                for f in CSV_9:
                    jc_num["vs_equal_%s" % f] = jve.get(f)
                for k, jval in jc_num.items():
                    got = fnum(row.get(k))
                    if jval is None or got is None or not approx(got, jval):
                        jc_ok = False
                if row.get("declaration_source") != blk.get("declaration_source") \
                        or row.get("policy_kind") != blk.get("policy_kind"):
                    jc_ok = False
                if vm is not None:
                    jvm = blk.get("vs_matched_drf", {}) or {}
                    for f in CSV_9:
                        got = fnum(row.get("vs_matched_drf_%s" % f))
                        jval = jvm.get(f)
                        if jval is None or got is None or not approx(got, jval):
                            jc_ok = False
    checks["secondary_json_every_cell_arm_field"] = bool(json_ok)
    checks["secondary_scenario_metrics_every_cell"] = bool(sm_ok)
    checks["secondary_csv_values_every_row_field"] = bool(csv_ok)
    checks["secondary_json_csv_agreement"] = bool(jc_ok)
    checks["secondary_json_entry_count_90"] = (n_json_entries == 90)
    detail["secondary_json_entries"] = n_json_entries
    return checks, detail


# ---- distributed inspection (no central-solver invocation) ------------------------------
def verify_distributed_inspection(oq):
    src = open(os.path.join(oq, "oqlib", "distributed.py")).read()
    tree = ast.parse(src)
    mods, calls, central_imports = set(), set(), set()
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
    SOLVER = {"reduced_central_leontief", "central_leontief_reference", "solve_joint_allocation"}
    no_cvxpy = "cvxpy" not in mods and not any(m.split(".")[-1] == "cvxpy" for m in mods)
    no_solver_module = not any("joint_solver" in m or m.endswith("central_ref") for m in mods)
    no_solver_import = central_imports.isdisjoint(SOLVER)
    only_evaluator_from_central = central_imports <= {"leontief_objective", "bounds"}
    no_solver_call = calls.isdisjoint(SOLVER) and "solve" not in calls
    return ({"distributed_no_central_solver_call": bool(no_cvxpy and no_solver_module and no_solver_import
                                                        and only_evaluator_from_central and no_solver_call),
             "distributed_imports_objective_evaluator_only": bool(only_evaluator_from_central and central_imports)},
            {"central_imports": sorted(central_imports)})


# ---- manifests (recompute artifact hashes; independent, not trusting stored booleans) ----
def verify_manifests(oq, base_root):
    checks, detail = {}, {}
    root = os.path.abspath(os.path.join(oq, "..", ".."))
    for scope, mpath in (("architecture", os.path.join(oq, "results", "architecture_v1", "EXPERIMENT_MANIFEST.json")),
                         ("drift", os.path.join(oq, "results", "drift_v1", "EXPERIMENT_MANIFEST.json"))):
        m = load_json(mpath)
        bad = [art["path"] for art in m["artifacts"]
               if not os.path.exists(os.path.join(root, art["path"]))
               or sha256_file(os.path.join(root, art["path"])) != art["sha256"]]
        checks["%s_manifest_artifact_hashes" % scope] = (len(bad) == 0)
        detail["%s_manifest_bad" % scope] = bad
    corr = load_json(os.path.join(oq, "CORRECTION_MANIFEST.json"))
    bad = [art["path"] for art in corr["artifacts"]
           if not os.path.exists(os.path.join(root, art["path"]))
           or sha256_file(os.path.join(root, art["path"])) != art["sha256"]]
    checks["correction_manifest_artifact_hashes"] = (len(bad) == 0)
    checks["correction_manifest_artifact_count"] = (corr["n_artifacts"] == len(corr["artifacts"]) == 24)
    detail["correction_manifest_bad"] = bad
    return checks, detail


# ---- orchestration ----------------------------------------------------------------------
# category tags per section (report distinguishes evidence kinds; see VERIFICATION_SUMMARY_FINAL.md)
SECTION_CATEGORY = {
    "provenance": "git_provenance", "manifests": "git_provenance_and_regeneration",
    "schemas": "raw_derived_structure", "workload_crn": "raw_derived_structure",
    "seeds": "raw_derived_and_formula", "architecture": "raw_derived", "drift": "raw_derived",
    "secondary_supplement": "raw_derived", "distributed_inspection": "static_source_inspection",
}


def _safe(fn, *a, **k):
    try:
        r = fn(*a, **k)
        return r if isinstance(r, tuple) else (r, {})
    except Exception as e:  # noqa: BLE001
        return {"_section_raised_no_exception": False}, {"error": repr(e)}


def run(oq, base_root, candidate_rev=None, do_git=True, do_bootstrap=True, bs_sample=None):
    report = {"verifier": "verify_oq_final", "supersedes": "verify_oq_v2",
              "imports_experiment_modules": False, "uses_only_stdlib_and_numpy": True,
              "reran_confirmatory_runs": False, "sections": {}}
    if candidate_rev is None:
        candidate_rev = "HEAD"
    if do_git and base_root:
        report["sections"]["provenance"] = dict(zip(("checks", "detail"),
                                                     _safe(verify_provenance, oq, base_root, candidate_rev)))
        report["sections"]["manifests"] = dict(zip(("checks", "detail"), _safe(verify_manifests, oq, base_root)))
        report["sections"]["seeds"] = dict(zip(("checks", "detail"),
                                                _safe(verify_seed_disjointness, oq, base_root)))
    report["sections"]["schemas"] = dict(zip(("checks", "detail"), _safe(verify_schemas, oq)))
    report["sections"]["workload_crn"] = dict(zip(("checks", "detail"), _safe(verify_workload_and_crn, oq)))
    report["sections"]["architecture"] = dict(zip(("checks", "detail"),
                                                   _safe(verify_architecture, oq, do_bootstrap=do_bootstrap, bs_sample=bs_sample)))
    report["sections"]["drift"] = dict(zip(("checks", "detail"),
                                            _safe(verify_drift, oq, do_bootstrap=do_bootstrap, bs_sample=bs_sample)))
    report["sections"]["secondary_supplement"] = dict(zip(("checks", "detail"), _safe(verify_secondary_supplement, oq)))
    report["sections"]["distributed_inspection"] = dict(zip(("checks", "detail"), _safe(verify_distributed_inspection, oq)))

    all_checks = {}
    for sec, blk in report["sections"].items():
        for k, v in blk["checks"].items():
            all_checks["%s.%s" % (sec, k)] = bool(v)
    failed = sorted(k for k, v in all_checks.items() if not v)
    report["n_checks"] = len(all_checks)
    report["n_pass"] = len(all_checks) - len(failed)
    report["n_fail"] = len(failed)
    report["failed_checks"] = failed
    report["by_category"] = {}
    for sec, blk in report["sections"].items():
        cat = SECTION_CATEGORY.get(sec, "other")
        report["by_category"].setdefault(cat, {"n_checks": 0, "n_pass": 0})
        for v in blk["checks"].values():
            report["by_category"][cat]["n_checks"] += 1
            report["by_category"][cat]["n_pass"] += 1 if v else 0
    # coverage summary (explicit; not a single aggregate number)
    cov = {}
    a_det = report["sections"].get("architecture", {}).get("detail", {})
    d_det = report["sections"].get("drift", {}).get("detail", {})
    s_det = report["sections"].get("secondary_supplement", {}).get("detail", {})
    cov["architecture_agent_records_best_subset_enumerated"] = a_det.get("arch_best_subset_records_checked")
    cov["drift_agent_records_best_subset_enumerated"] = d_det.get("drift_best_subset_records_checked")
    cov["secondary_json_entries_reconstructed"] = s_det.get("secondary_json_entries")
    cov["subsets_enumerated_per_record"] = 256
    report["coverage"] = cov
    if not failed and do_git and do_bootstrap and bs_sample is None:
        report["verification_status"] = "VERIFIED"
    elif not failed:
        report["verification_status"] = "PARTIALLY VERIFIED (fast mode)"
    else:
        report["verification_status"] = "NOT VERIFIED"
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--oq", default=None)
    ap.add_argument("--base-root", default=None)
    ap.add_argument("--candidate-rev", default=None, help="git rev whose blobs are the candidate (default HEAD)")
    ap.add_argument("--no-git", action="store_true")
    ap.add_argument("--fast", action="store_true", help="skip bootstrap + sample best-subset (dev only)")
    ap.add_argument("--out", default=None)
    args = ap.parse_args(argv)
    here = os.path.dirname(os.path.abspath(__file__))
    oq = args.oq or here
    base_root = args.base_root or os.path.abspath(os.path.join(oq, "..", ".."))
    rep = run(oq, base_root, candidate_rev=args.candidate_rev, do_git=not args.no_git,
              do_bootstrap=not args.fast, bs_sample=(500 if args.fast else None))
    out = args.out or os.path.join(here, "VERIFICATION_REPORT_FINAL.json")
    with open(out, "w") as f:
        json.dump(rep, f, indent=2)
    print("verification status:", rep["verification_status"])
    print("  checks: %d/%d pass" % (rep["n_pass"], rep["n_checks"]))
    for cat, v in rep["by_category"].items():
        print("  [%s] %d/%d" % (cat, v["n_pass"], v["n_checks"]))
    for k in rep["failed_checks"]:
        print("  FAIL:", k)
    return 0 if rep["verification_status"] != "NOT VERIFIED" else 1


if __name__ == "__main__":
    sys.exit(main())
