#!/usr/bin/env python3
"""Final consolidation manifest for the original-question-closure consolidation onto ``main``.

Records, in one place:
  * the immutable milestone anchors (preregistration, result head, correction, v2 verification);
  * every file changed relative to the v2 head ``c678a0a`` with its SHA-256 and a change
    classification (verification / documentation / ci / provenance / packaging);
  * the invariant that NO raw/frozen outcome-relevant blob changed, checked INDEPENDENTLY
    against the git anchor blobs (not against any stored boolean);
  * confirmation that no new confirmatory run was executed by this consolidation;
  * the before/after remote-ref inventories (intended final: only ``main`` + the archive branch);
  * the exact verification/test commands;
  * the final status.

``--verify`` re-checks: (a) every immutable-anchor file's current bytes still equal its git
anchor-blob bytes (no raw/frozen change); and (b) every recorded stable artifact still hashes as
recorded. Files explicitly marked ``mutable`` (the CI workflow, which loses its staging trigger
in the post-promotion cleanup commit, and this manifest itself) are checked for existence only.
"""
import argparse
import hashlib
import json
import os
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OQ_REL = "experiments/platform_mediation_original_question"
OUT = os.path.join(HERE, "FINAL_CONSOLIDATION_MANIFEST.json")

ANCHORS = {
    "preregistration": "7ebf8b70366b8b68a90554a722f097d8acea3f01",
    "result_head": "1e2e1d968e9204a44567c3571c0d75f5900319cc",
    "correction": "601ca56752d16fe5b9364590f95ef5335331e9b5",
    "v2_verification": "c678a0a96aba563ceff52e4d6b889fb90db316ec",
    "old_main": "91d8ad77d2b549933b46d566e5776b67b87628ff",
}
# Frozen-at-preregistration files (must equal the 7ebf8b7 blob).
PREREG_IMMUTABLE = [
    "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md", "config/architecture_v1.json", "config/drift_v1.json",
    "CONFIRMATORY_SEED_MANIFEST.json", "make_oq_analysis.py", "select_drift_carrier.py",
    "run_architecture.py", "run_declaration_drift.py", "run_original_question_closure.py",
    "oqlib/__init__.py", "oqlib/central.py", "oqlib/central_ref.py", "oqlib/distributed.py",
    "oqlib/maxmin.py", "oqlib/mechanisms.py", "oqlib/seeds_oq.py", "oqlib/driftgen.py",
    "oqlib/drift_scenario.py", "oqlib/declarations.py", "oqlib/execute.py", "oqlib/jobs.py",
    "oqlib/leontief_relaxation.py",
]
# Raw data, generated result artifacts, experiment manifests and the carrier decision (must
# equal the 1e2e1d9 blob).
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

# Consolidation changes (repo-relative), each classified. ``mutable`` files are existence-only.
CLASSIFICATION = {
    OQ_REL + "/verify_oq_final.py": "verification",
    OQ_REL + "/tests/test_verify_oq_final.py": "verification",
    OQ_REL + "/VERIFICATION_REPORT_FINAL.json": "verification",
    OQ_REL + "/VERIFICATION_SUMMARY_FINAL.md": "verification",
    OQ_REL + "/VERIFICATION_FINAL_AUDIT.md": "verification",
    "README.md": "documentation",
    "docs/PLATFORM_EVALUATION.md": "documentation",
    OQ_REL + "/DISTRIBUTED_SOLVER.md": "documentation",
    OQ_REL + "/CORRECTIONS_AFTER_VERIFICATION.md": "documentation",
    OQ_REL + "/ORIGINAL_QUESTION_CLOSURE.md": "documentation",
    OQ_REL + "/CORRECTION_MANIFEST.json": "provenance",
    OQ_REL + "/make_closure_report.py": "provenance",
    OQ_REL + "/complete_drift_secondary.py": "provenance",
    OQ_REL + "/make_final_manifest.py": "provenance",
    "experiments/platform_mediation_heterogeneity/make_confirmatory_manifest.py": "provenance",
    "experiments/platform_mediation_heterogeneity/make_pilot_manifest.py": "provenance",
    "experiments/platform_mediation_heterogeneity/tests/test_manifest_base_resolution.py": "provenance",
    ".github/workflows/ci.yml": "ci",
}
MUTABLE = {".github/workflows/ci.yml", OQ_REL + "/FINAL_CONSOLIDATION_MANIFEST.json"}

TEST_COMMANDS = [
    "mvn -B test",
    "python -m pytest tests/python -q",
    "python -m pytest experiments/platform_mediation_heterogeneity/tests -q",
    "python -m pytest experiments/platform_mediation_original_question/tests -q",
    "python experiments/platform_mediation_original_question/verify_oq_final.py --candidate-rev HEAD",
    "python experiments/platform_mediation_original_question/make_correction_manifest.py --verify",
    "python experiments/platform_mediation_original_question/make_final_manifest.py --verify",
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def blob_sha(rev, relpath):
    try:
        raw = subprocess.check_output(["git", "-C", ROOT, "cat-file", "blob", "%s:%s" % (rev, relpath)],
                                      stderr=subprocess.DEVNULL)
    except Exception:
        return None
    return hashlib.sha256(raw).hexdigest()


def check_immutable():
    """Independently confirm no raw/frozen file changed vs its anchor. Returns (ok, mismatches)."""
    bad = []
    for rel in PREREG_IMMUTABLE:
        p = os.path.join(ROOT, OQ_REL, rel)
        if not os.path.exists(p) or sha256(p) != blob_sha(ANCHORS["preregistration"], "%s/%s" % (OQ_REL, rel)):
            bad.append(rel)
    for rel in RESULT_IMMUTABLE:
        p = os.path.join(ROOT, OQ_REL, rel)
        if not os.path.exists(p) or sha256(p) != blob_sha(ANCHORS["result_head"], "%s/%s" % (OQ_REL, rel)):
            bad.append(rel)
    return (len(bad) == 0), bad


def build(before_inventory=None, after_inventory=None):
    ok, bad = check_immutable()
    changed = []
    for rel, category in sorted(CLASSIFICATION.items()):
        p = os.path.join(ROOT, rel)
        entry = {"path": rel, "classification": category, "mutable": rel in MUTABLE}
        if os.path.exists(p) and rel not in MUTABLE:
            entry["sha256"] = sha256(p)
            entry["bytes"] = os.path.getsize(p)
        elif os.path.exists(p):
            entry["exists"] = True
        else:
            entry["exists"] = False
        changed.append(entry)
    return {
        "scope": "original_question_closure_final_consolidation",
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "anchors": ANCHORS,
        "invariants": {
            "no_raw_or_frozen_outcome_changed": ok,
            "immutable_mismatches": bad,
            "no_new_confirmatory_run_executed": True,
            "confirmatory_runs_referenced": 20400,
            "verification_is_raw_data_reconstruction_not_rerun": True,
        },
        "n_changed_files": len(changed),
        "changed_files": changed,
        "test_commands": TEST_COMMANDS,
        "remote_refs_before": before_inventory,
        "remote_refs_after_intended": after_inventory or [
            "main", "archive/main-before-original-question-closure-20260903",
        ],
        "status": "CONSOLIDATED" if ok else "INVARIANT_VIOLATION",
    }


def verify():
    manifest = json.load(open(OUT))
    problems = []
    ok, bad = check_immutable()
    if not ok:
        problems.append(("<immutable-invariant>", "raw/frozen changed vs anchor: %s" % bad))
    for art in manifest["changed_files"]:
        p = os.path.join(ROOT, art["path"])
        if art.get("mutable"):
            if not os.path.exists(p):
                problems.append((art["path"], "missing (mutable)"))
            continue
        if "sha256" in art:
            if not os.path.exists(p):
                problems.append((art["path"], "missing"))
            elif sha256(p) != art["sha256"]:
                problems.append((art["path"], "hash mismatch"))
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--before-inventory", default=None, help="path to a text file of the pre-consolidation refs")
    args = ap.parse_args(argv)
    if args.verify:
        problems = verify()
        if problems:
            print("FINAL CONSOLIDATION MANIFEST VERIFY FAILED:")
            for path, why in problems:
                print("  %s: %s" % (path, why))
            raise SystemExit(1)
        print("final consolidation manifest verify: immutable invariant holds and all recorded hashes match")
        return
    before = None
    if args.before_inventory and os.path.exists(args.before_inventory):
        before = open(args.before_inventory).read().strip().splitlines()
    manifest = build(before_inventory=before)
    with open(OUT, "w") as f:
        json.dump(manifest, f, indent=2)
    print("final consolidation manifest: %d changed files, no_raw_or_frozen_outcome_changed=%s -> %s"
          % (manifest["n_changed_files"], manifest["invariants"]["no_raw_or_frozen_outcome_changed"],
             os.path.relpath(OUT, ROOT)))


if __name__ == "__main__":
    main()
