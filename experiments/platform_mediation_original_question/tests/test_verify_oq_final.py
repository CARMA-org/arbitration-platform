"""Tests for the final comprehensive verifier (verify_oq_final.py).

Positive tests confirm VERIFIED on the committed tree. Negative mutation tests alter exactly
one committed quantity and assert the specific responsible check fails — covering the
supplement (including non-representative cells/arms and CSV-only / JSON-only values, keys and
headers), schemas, workload/CRN invariants, seed disjointness, immutable-file bytes,
raw-derived flags/decision, and architecture/drift completeness. A code-quality self-audit
asserts the verifier itself is free of the defect classes called out for the v2 verifier
(duplicate dict keys, dead ``if False`` code, duplicate check assignments, non-unique check
keys) and imports no experiment code.
"""
import ast
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

import verify_oq_final as V  # noqa: E402

DRIFT_REL = os.path.join("results", "drift_v1")
ARCH_REL = os.path.join("results", "architecture_v1")


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
    with open(path, newline="") as f:
        rd = csv.reader(f)
        header = next(rd)
        rows = [dict(zip(header, r)) for r in rd]
    rows = transform(rows)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=header, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


# ------------------------- positive -------------------------
def test_full_verification_is_verified():
    rep = V.run(EXP, BASE_ROOT, candidate_rev="HEAD", do_git=True, do_bootstrap=True, bs_sample=None)
    assert rep["verification_status"] == "VERIFIED", rep["failed_checks"]
    assert rep["n_fail"] == 0
    assert rep["n_checks"] >= 90
    assert rep["coverage"]["architecture_agent_records_best_subset_enumerated"] == 14400
    assert rep["coverage"]["drift_agent_records_best_subset_enumerated"] == 108000
    assert rep["coverage"]["secondary_json_entries_reconstructed"] == 90
    assert rep["reran_confirmatory_runs"] is False


def test_fast_mode_all_pass():
    rep = V.run(EXP, BASE_ROOT, candidate_rev="HEAD", do_git=True, do_bootstrap=False, bs_sample=500)
    assert rep["n_fail"] == 0, rep["failed_checks"]


def test_seed_disjointness_actual_overlaps_zero():
    c, d = V.verify_seed_disjointness(EXP, BASE_ROOT)
    assert all(c.values()), [k for k, v in c.items() if not v]
    assert all(v == 0 for v in d["actual_overlaps"].values())
    assert d["counts"]["canonical"] > 0 and d["counts"]["pilot"] > 0 and d["counts"]["heterogeneity_confirmatory"] > 0


def test_immutable_blobs_pass_on_real_tree():
    c, _ = V.verify_provenance(EXP, BASE_ROOT, "HEAD")
    assert all(c.values()), [k for k, v in c.items() if not v]


# ------------------------- (A) secondary supplement -------------------------
def test_mutate_secondary_json_nonrepresentative_cell_arm(tmp_path):
    """A non-representative cell (delta0.75) and an arm not covered by v2's representative
    checks (drf_execution_queue_oracle) must still be verified."""
    oq = _copy_tree(tmp_path)
    jf = os.path.join(oq, DRIFT_REL, "preregistered_secondary_completion", "drift_secondary_completion.json")
    _edit_json(jf, lambda d: d["cells"]["delta0.75__high"]["arms"]["drf_execution_queue_oracle"]["vs_equal"]
               .update({"n_harmed": 99999}))
    c, _ = V.verify_secondary_supplement(oq)
    assert c["secondary_json_every_cell_arm_field"] is False


def test_mutate_secondary_json_only_value(tmp_path):
    oq = _copy_tree(tmp_path)
    jf = os.path.join(oq, DRIFT_REL, "preregistered_secondary_completion", "drift_secondary_completion.json")
    _edit_json(jf, lambda d: d["cells"]["delta0.25__moderate"]["arms"]["carrier_stale_calibration"]
               .update({"mean_qo_tasks_per_run": -1.0}))
    c, _ = V.verify_secondary_supplement(oq)
    # JSON differs from raw and from the (unmutated) CSV
    assert c["secondary_json_every_cell_arm_field"] is False
    assert c["secondary_json_csv_agreement"] is False


def test_mutate_secondary_csv_only_value(tmp_path):
    oq = _copy_tree(tmp_path)
    cf = os.path.join(oq, DRIFT_REL, "preregistered_secondary_completion", "drift_secondary_completion.csv")

    def bump(rows):
        rows[5]["vs_equal_n_harmed"] = "424242"
        return rows
    _rewrite_csv(cf, bump)
    c, _ = V.verify_secondary_supplement(oq)
    assert c["secondary_csv_values_every_row_field"] is False


def test_mutate_secondary_csv_header(tmp_path):
    oq = _copy_tree(tmp_path)
    cf = os.path.join(oq, DRIFT_REL, "preregistered_secondary_completion", "drift_secondary_completion.csv")
    lines = open(cf).read().splitlines()
    lines[0] = lines[0].replace("mean_qo_tasks_per_run", "mean_qo_TYPO", 1)
    open(cf, "w").write("\n".join(lines) + "\n")
    c, _ = V.verify_secondary_supplement(oq)
    assert c["secondary_csv_header_exact"] is False


def test_mutate_secondary_csv_duplicate_rowkey(tmp_path):
    oq = _copy_tree(tmp_path)
    cf = os.path.join(oq, DRIFT_REL, "preregistered_secondary_completion", "drift_secondary_completion.csv")

    def dup(rows):
        rows[1]["cell"], rows[1]["arm"] = rows[0]["cell"], rows[0]["arm"]  # collide key
        return rows
    _rewrite_csv(cf, dup)
    c, _ = V.verify_secondary_supplement(oq)
    assert c["secondary_csv_no_duplicate_keys"] is False


# ------------------------- (B) schemas / joins -------------------------
def test_mutate_raw_header_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "runs.csv")
    lines = open(p).read().splitlines()
    lines[0] = lines[0].replace("queue_order_completion_mean", "qo_mean_TYPO", 1)
    open(p, "w").write("\n".join(lines) + "\n")
    c, _ = V.verify_schemas(oq)
    assert c["exact_headers_all_tables"] is False


def test_duplicate_run_row_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "runs.csv")
    _rewrite_csv(p, lambda rows: rows + [dict(rows[0])])
    c, _ = V.verify_schemas(oq)
    assert c["arch_unique_keys"] is False


def test_delete_agent_row_breaks_join(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "agents.csv")
    _rewrite_csv(p, lambda rows: rows[1:])  # drop one agent record
    c, _ = V.verify_schemas(oq)
    assert c["arch_structural_joins"] is False


# ------------------------- (C) workload / CRN -------------------------
def test_mutate_capacity_across_delta_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, DRIFT_REL, "raw", "scenarios.csv")

    def tweak(rows):
        rows[10]["capacity_by_resource"] = json.dumps({"COMPUTE": 1, "MEMORY": 1, "API_CREDITS": 1, "DATASET": 1})
        return rows
    _rewrite_csv(p, tweak)
    c, _ = V.verify_workload_and_crn(oq)
    assert c["drift_capacity_invariant_across_delta"] is False


def test_break_arch_workload_reuse_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "scenarios.csv")

    def tweak(rows):
        rows[0]["task_workload_hash"] = "deadbeef" * 8  # break per-seed cross-contention reuse
        return rows
    _rewrite_csv(p, tweak)
    c, _ = V.verify_workload_and_crn(oq)
    assert c["arch_workload_reused_across_contention"] is False


# ------------------------- (D) seeds -------------------------
def test_mutate_seed_breaks_formula(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "scenarios.csv")

    def tweak(rows):
        rows[0]["seed"] = "123456789"  # not in the derived namespace
        return rows
    _rewrite_csv(p, tweak)
    c, _ = V.verify_seed_disjointness(oq, BASE_ROOT)
    assert c["arch_seeds_match_formula"] is False


# ------------------------- (E) immutable bytes -------------------------
def test_mutate_immutable_raw_file_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "agents.csv")

    def bump(rows):
        rows[0]["queue_order_completion"] = "0.123456"
        return rows
    _rewrite_csv(p, bump)
    c, _ = V.verify_provenance(oq, BASE_ROOT, "HEAD")
    assert c["result_immutable_bytes_match_working_and_candidate"] is False


# ------------------------- (F) raw-derived flags / decision -------------------------
def test_mutate_headline_flag_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    hp = os.path.join(oq, ARCH_REL, "architecture_headline.json")
    _edit_json(hp, lambda d: d["flags"].update({"coordination_pass": True}))  # raw says False
    c, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=1)
    assert c["raw_derived_flags_match_headline"] is False


def test_mutate_carrier_decision_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    dp = os.path.join(oq, "DRIFT_CARRIER_DECISION.json")
    _edit_json(dp, lambda d: d.update({"selected_carrier": "independent_bundle_maxmin"}))
    c, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=1)
    assert c["adaptive_carrier_rule_from_raw"] is False


# ------------------------- (G) architecture completeness -------------------------
def test_mutate_best_subset_count_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "agents.csv")

    def bump(rows):
        c = int(rows[0]["best_subset_count"])
        rows[0]["best_subset_count"] = str(0 if c >= 8 else c + 1)
        return rows
    _rewrite_csv(p, bump)
    c, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=60)
    assert c["exact_best_subset_enumeration_all_records"] is False


def test_mutate_distributional_stat_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    hp = os.path.join(oq, ARCH_REL, "architecture_headline.json")
    _edit_json(hp, lambda d: d["distributional"]["dirichlet_0.1__moderate"]["central_joint_leontief"]["vs_drf"]
               .update({"n_harmed": 4321}))
    c, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=1)
    assert c["every_distributional_statistic"] is False


def test_mutate_arm_mean_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    hp = os.path.join(oq, ARCH_REL, "architecture_headline.json")
    _edit_json(hp, lambda d: d["cell_policy"]["dirichlet_0.1__high"]["central_joint_leontief"]
               .update({"qo_tasks_per_run": 999.0}))
    c, _ = V.verify_architecture(oq, do_bootstrap=False, bs_sample=1)
    assert c["every_arm_every_field_mean"] is False


# ------------------------- (H) drift completeness -------------------------
def test_mutate_drift_best_subset_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, DRIFT_REL, "raw", "agents.csv")

    def bump(rows):
        c = int(rows[0]["best_subset_count"])
        rows[0]["best_subset_count"] = str(0 if c >= 8 else c + 1)
        return rows
    _rewrite_csv(p, bump)
    c, _ = V.verify_drift(oq, do_bootstrap=False, bs_sample=60)
    assert c["exact_best_subset_enumeration_all_records"] is False


def test_mutate_drift_secondary_ci_fails(tmp_path):
    oq = _copy_tree(tmp_path)
    hp = os.path.join(oq, DRIFT_REL, "drift_headline.json")
    _edit_json(hp, lambda d: d["secondary"]["delta0.75__high"]["carrier_minus_drf_latent_distribution_oracle"]
               .update({"ci_hi_tasks": 123.4}))
    c, _ = V.verify_drift(oq, do_bootstrap=True, bs_sample=1)
    assert c["every_drift_secondary_mean_and_ci"] is False


def test_delete_arm_makes_not_verified(tmp_path):
    oq = _copy_tree(tmp_path)
    p = os.path.join(oq, ARCH_REL, "raw", "runs.csv")
    _rewrite_csv(p, lambda rows: [r for r in rows if r["arm"] != "drf"])
    rep = V.run(oq, base_root=BASE_ROOT, candidate_rev="HEAD", do_git=False, do_bootstrap=False, bs_sample=1)
    assert rep["verification_status"] != "VERIFIED"
    assert rep["n_fail"] >= 1


def test_unmutated_copy_passes_nonbootstrap(tmp_path):
    oq = _copy_tree(tmp_path)
    rep = V.run(oq, base_root=BASE_ROOT, candidate_rev="HEAD", do_git=True, do_bootstrap=False, bs_sample=80)
    assert rep["n_fail"] == 0, rep["failed_checks"]


# ------------------------- (I) code-quality self-audit -------------------------
def _final_source_tree():
    return ast.parse(open(os.path.join(EXP, "verify_oq_final.py")).read())


def test_no_duplicate_dict_literal_keys():
    import collections
    tree = _final_source_tree()
    for node in ast.walk(tree):
        if isinstance(node, ast.Dict):
            keys = [k.value for k in node.keys if isinstance(k, ast.Constant) and isinstance(k.value, str)]
            dup = [k for k, c in collections.Counter(keys).items() if c > 1]
            assert not dup, "duplicate dict keys at line %d: %s" % (node.lineno, dup)


def test_no_dead_constant_conditionals():
    tree = _final_source_tree()
    for node in ast.walk(tree):
        if isinstance(node, ast.If) and isinstance(node.test, ast.Constant):
            raise AssertionError("dead constant conditional (if True/if False) at line %d" % node.lineno)


def test_no_duplicate_check_assignments_within_function():
    """No `checks["X"] = ...` assigned twice inside the same function (silent overwrite)."""
    import collections
    tree = _final_source_tree()
    for fn in ast.walk(tree):
        if not isinstance(fn, ast.FunctionDef):
            continue
        assigned = []
        for node in ast.walk(fn):
            if isinstance(node, ast.Assign):
                for tgt in node.targets:
                    if (isinstance(tgt, ast.Subscript) and isinstance(tgt.value, ast.Name)
                            and tgt.value.id == "checks" and isinstance(tgt.slice, ast.Constant)):
                        assigned.append(tgt.slice.value)
        dup = [k for k, c in collections.Counter(assigned).items() if c > 1]
        assert not dup, "duplicate checks[] assignment in %s: %s" % (fn.name, dup)


def test_check_keys_globally_unique():
    rep = V.run(EXP, BASE_ROOT, candidate_rev="HEAD", do_git=True, do_bootstrap=False, bs_sample=1)
    flat = []
    for sec, blk in rep["sections"].items():
        for k in blk["checks"]:
            flat.append("%s.%s" % (sec, k))
    assert len(flat) == len(set(flat))
    # every check value is a bool
    for sec, blk in rep["sections"].items():
        for k, v in blk["checks"].items():
            assert isinstance(v, bool), "%s.%s not bool" % (sec, k)


def test_verifier_imports_no_experiment_code():
    tree = _final_source_tree()
    banned = ("oqlib", "lib.", "pilotlib", "make_oq", "make_closure", "make_comparator",
              "make_correction", "select_drift_carrier", "complete_drift_secondary",
              "run_architecture", "run_declaration_drift", "run_original_question", "cvxpy")
    mods = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            mods.update(a.name for a in node.names)
        elif isinstance(node, ast.ImportFrom):
            mods.add(node.module or "")
    for m in mods:
        assert not any(m == b or m.startswith(b) for b in banned), "verifier imports experiment code: %s" % m
