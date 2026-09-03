"""Tests for the comprehensive v2 verifier (verify_oq_v2.py), including negative mutation
tests: each mutation of a committed quantity must make verification fail.

The mutation tests copy the experiment tree into a temp location structured as
``<tmp>/experiments/platform_mediation_original_question`` so the manifests' repo-relative
paths still resolve, mutate exactly one committed value, and assert the relevant verifier
check reports failure.
"""
import csv
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EXP = os.path.abspath(os.path.join(HERE, ".."))
BASE_ROOT = os.path.abspath(os.path.join(EXP, "..", ".."))
if EXP not in sys.path:
    sys.path.insert(0, EXP)

import verify_oq_v2 as V  # noqa: E402


def _copy_tree(tmp_path):
    dst = os.path.join(str(tmp_path), "experiments", "platform_mediation_original_question")
    shutil.copytree(EXP, dst, ignore=shutil.ignore_patterns("__pycache__", ".pytest_cache", "*.pyc"))
    return dst


def _edit_json(path, mutate):
    d = json.load(open(path))
    mutate(d)
    with open(path, "w") as f:
        json.dump(d, f, indent=2)


def _rewrite_csv(path, transform):
    rows = list(csv.DictReader(open(path)))
    fields = list(rows[0].keys())
    rows = transform(rows)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


# ---------------- positive tests ----------------
def test_git_and_manifest_checks_pass_on_real_tree():
    gc, _ = V.git_checks(BASE_ROOT)
    assert all(gc.values()), [k for k, v in gc.items() if not v]
    mc = V.manifest_checks(EXP, BASE_ROOT)
    assert all(mc.values()), [k for k, v in mc.items() if not v]


def test_fast_reconstruction_checks_pass_on_real_tree():
    """Non-bootstrap reconstructions (distributional, harmed-set, best-subset sample,
    feasibility, seed disjointness, drift metrics, supplement) all pass on committed data."""
    ac, _ = V.verify_architecture(EXP, do_bootstrap=False, bs_sample=300)
    assert all(ac.values()), [k for k, v in ac.items() if not v]
    dc, _ = V.verify_drift(EXP, do_bootstrap=False, bs_sample=300)
    assert all(dc.values()), [k for k, v in dc.items() if not v]
    sc = V.verify_secondary_supplement(EXP)
    assert all(sc.values()), [k for k, v in sc.items() if not v]
    xc = V.verify_distributed_no_central_call(EXP)
    assert all(xc.values()), [k for k, v in xc.items() if not v]


def test_full_verification_is_verified():
    rep = V.run(EXP, BASE_ROOT, do_git=True, do_bootstrap=True, bs_sample=None)
    assert rep["verification_status"] == "VERIFIED", rep["failed_checks"]
    assert rep["n_fail"] == 0
    assert rep["n_checks"] >= 60


# ---------------- negative mutation tests ----------------
def test_mutate_primary_ci_endpoint_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    hp = os.path.join(oq, "results", "drift_v1", "drift_headline.json")
    _edit_json(hp, lambda d: d["co_primary_decision"]["delta0.25__moderate"].update({"ci_lo_tasks": 0.111}))
    dc, _ = V.verify_drift(oq, do_bootstrap=True, bs_sample=1)
    assert dc["drift_co_primary_mean_and_ci"] is False


def test_mutate_harmed_set_statistic_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    hp = os.path.join(oq, "results", "architecture_v1", "architecture_headline.json")
    _edit_json(hp, lambda d: d["harmed_set_central_vs_distributed"]["dirichlet_0.1__high"]["equal"]
               .update({"harmed_set_jaccard": 0.5}))
    ac, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=1)
    assert ac["every_harmed_set_statistic"] is False


def test_mutate_best_subset_count_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    ap = os.path.join(oq, "results", "architecture_v1", "raw", "agents.csv")

    def bump_first(rows):
        c = int(rows[0]["best_subset_count"])
        rows[0]["best_subset_count"] = str(0 if c >= 8 else c + 1)  # deliberately wrong
        return rows
    _rewrite_csv(ap, bump_first)
    ac, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=50)
    assert ac["exact_best_subset_enumeration"] is False


def test_delete_arm_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    rp = os.path.join(oq, "results", "architecture_v1", "raw", "runs.csv")
    _rewrite_csv(rp, lambda rows: [r for r in rows if r["arm"] != "drf"])
    # a deleted arm breaks structural checks (and may raise mid-reconstruction); run() treats
    # a raised section as a failure, so the whole verification must not be VERIFIED.
    rep = V.run(oq, base_root=None, do_git=False, do_bootstrap=False, bs_sample=1)
    assert rep["verification_status"] != "VERIFIED"
    assert rep["n_fail"] >= 1


def test_mutate_manifest_hash_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    mp = os.path.join(oq, "results", "architecture_v1", "EXPERIMENT_MANIFEST.json")
    _edit_json(mp, lambda d: d["artifacts"][0].update({"sha256": "0" * 64}))
    mc = V.manifest_checks(oq, base_root=None)
    assert mc["architecture_manifest_hashes"] is False


def test_mutate_correction_manifest_hash_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    mp = os.path.join(oq, "CORRECTION_MANIFEST.json")
    _edit_json(mp, lambda d: d["artifacts"][0].update({"sha256": "0" * 64}))
    mc = V.manifest_checks(oq, base_root=None)
    assert mc["correction_manifest_hashes"] is False


def test_unmutated_copy_still_passes(tmp_path):
    """Control: an unmutated copy passes every non-git check (guards against the mutation
    tests trivially failing for reasons other than the mutation)."""
    oq = _copy_tree(tmp_path)
    rep = V.run(oq, base_root=None, do_git=False, do_bootstrap=False, bs_sample=50)
    # only structural/reconstruction checks run here; all must pass
    assert rep["n_fail"] == 0, rep["failed_checks"]
