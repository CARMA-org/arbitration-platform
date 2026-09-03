#!/usr/bin/env python3
"""SHA-256 correction manifest for the original-question-closure v2 correction.

Records, per artifact, the repo-relative path, byte size, SHA-256 and a category:
  original_raw          -- the frozen architecture/drift raw CSVs (unchanged since 1e2e1d9)
  original_manifest     -- the frozen EXPERIMENT_MANIFEST.json files (unchanged since 1e2e1d9)
  corrected_document    -- documents whose prose/interpretation was corrected
  script                -- generators/analysis scripts added or corrected in the v2 correction
  test                  -- new tests for the correction
  supplemental_output   -- the preregistered secondary drift completion outputs

The ``original_raw`` and ``original_manifest`` artifacts are asserted to be byte-identical to
the experimental head; no raw datum, mechanism, frozen rule, seed or outcome was changed.

Usage: make_correction_manifest.py            (writes CORRECTION_MANIFEST.json)
       make_correction_manifest.py --verify   (checks every recorded hash still matches)
"""
import argparse
import hashlib
import json
import os
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BASE_HEAD = "1e2e1d968e9204a44567c3571c0d75f5900319cc"
OUT = os.path.join(HERE, "CORRECTION_MANIFEST.json")

ORIGINAL_RAW = [
    "results/architecture_v1/raw/scenarios.csv", "results/architecture_v1/raw/runs.csv",
    "results/architecture_v1/raw/agents.csv", "results/architecture_v1/raw/distributed.csv",
    "results/architecture_v1/raw/infeasible.csv",
    "results/drift_v1/raw/scenarios.csv", "results/drift_v1/raw/runs.csv",
    "results/drift_v1/raw/agents.csv", "results/drift_v1/raw/declarations.csv",
    "results/drift_v1/raw/distributed.csv", "results/drift_v1/raw/infeasible.csv",
]
ORIGINAL_MANIFEST = [
    "results/architecture_v1/EXPERIMENT_MANIFEST.json",
    "results/drift_v1/EXPERIMENT_MANIFEST.json",
]
CORRECTED_DOCUMENT = [
    "ORIGINAL_QUESTION_CLOSURE.md", "COMPARATOR_AUDIT.md", "DISTRIBUTED_SOLVER.md",
    "CORRECTIONS_AFTER_VERIFICATION.md",
]
SCRIPT = ["make_closure_report.py", "make_comparator_audit.py", "complete_drift_secondary.py",
          "make_correction_manifest.py"]
TEST = ["tests/test_oq_secondary_completion.py"]
SUPPLEMENTAL_OUTPUT = [
    "results/drift_v1/preregistered_secondary_completion/drift_secondary_completion.json",
    "results/drift_v1/preregistered_secondary_completion/drift_secondary_completion.csv",
]
CATEGORIES = [
    ("original_raw", ORIGINAL_RAW), ("original_manifest", ORIGINAL_MANIFEST),
    ("corrected_document", CORRECTED_DOCUMENT), ("script", SCRIPT),
    ("test", TEST), ("supplemental_output", SUPPLEMENTAL_OUTPUT),
]


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def git_blob_sha(rev, relpath):
    """SHA-256 of the file's bytes at a git revision (via `git show`), or None."""
    try:
        data = subprocess.check_output(["git", "-C", ROOT, "show", "%s:experiments/platform_mediation_original_question/%s" % (rev, relpath)])
        return hashlib.sha256(data).hexdigest()
    except Exception:
        return None


def build():
    artifacts = []
    for category, rels in CATEGORIES:
        for rel in rels:
            p = os.path.join(HERE, rel)
            artifacts.append({
                "category": category,
                "path": os.path.relpath(p, ROOT),
                "bytes": os.path.getsize(p),
                "sha256": sha256(p),
            })
    # invariance: original_raw + original_manifest must equal the experimental head bytes
    invariance = []
    for category, rels in CATEGORIES:
        if category not in ("original_raw", "original_manifest"):
            continue
        for rel in rels:
            base = git_blob_sha(BASE_HEAD, rel)
            cur = sha256(os.path.join(HERE, rel))
            invariance.append({"path": rel, "matches_%s" % BASE_HEAD[:7]: (base == cur), "sha256": cur})
    return {
        "scope": "original_question_closure_correction_v2",
        "base_experimental_head": BASE_HEAD,
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "invariants": {
            "statement": ("No raw datum, mechanism, frozen rule, seed or outcome changed. The original_raw and "
                          "original_manifest artifacts are byte-identical to the experimental head."),
            "verified_against": BASE_HEAD,
            "original_bytes_match_base": all(i["matches_%s" % BASE_HEAD[:7]] for i in invariance),
            "detail": invariance,
        },
        "n_artifacts": len(artifacts),
        "artifacts": artifacts,
    }


def verify():
    manifest = json.load(open(OUT))
    problems = []
    for art in manifest["artifacts"]:
        p = os.path.join(ROOT, art["path"])
        if not os.path.exists(p):
            problems.append((art["path"], "missing"))
        elif sha256(p) != art["sha256"]:
            problems.append((art["path"], "hash mismatch"))
    if not manifest["invariants"]["original_bytes_match_base"]:
        problems.append(("<invariants>", "original bytes differ from base head"))
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args(argv)
    if args.verify:
        problems = verify()
        if problems:
            print("CORRECTION MANIFEST VERIFY FAILED:")
            for path, why in problems:
                print("  %s: %s" % (path, why))
            raise SystemExit(1)
        print("correction manifest verify: all hashes match and invariants hold")
        return
    manifest = build()
    with open(OUT, "w") as f:
        json.dump(manifest, f, indent=2)
    print("correction manifest: %d artifacts, original_bytes_match_base=%s -> %s"
          % (manifest["n_artifacts"], manifest["invariants"]["original_bytes_match_base"],
             os.path.relpath(OUT, ROOT)))


if __name__ == "__main__":
    main()
