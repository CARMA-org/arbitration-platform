#!/usr/bin/env python3
"""SHA-256 manifests for the original-question-closure artifacts.

Subcommands:
  source        -> source, tests, configs, protocol and audit documents
  architecture  -> architecture raw tables and reports
  drift         -> drift raw tables and reports

Each manifest records, per artifact, the repo-relative path, byte size and SHA-256, plus
the source commit (if git is available) and a generated timestamp. Every tracked file is
asserted below GitHub's hard 100 MiB file-size limit.
"""
import argparse
import glob
import hashlib
import json
import os
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MAX_BYTES = 100 * 1024 * 1024


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def source_commit():
    try:
        return subprocess.check_output(["git", "-C", ROOT, "rev-parse", "HEAD"],
                                       text=True).strip()
    except Exception:
        return None


def collect(patterns):
    files = []
    for pat in patterns:
        for p in sorted(glob.glob(os.path.join(HERE, pat), recursive=True)):
            if os.path.isfile(p) and "__pycache__" not in p and "/_partial/" not in p:
                files.append(p)
    return files


def build(scope, patterns):
    files = collect(patterns)
    artifacts = []
    for p in files:
        b = os.path.getsize(p)
        assert b < MAX_BYTES, "artifact exceeds GitHub file-size limit: %s (%d bytes)" % (p, b)
        artifacts.append({"path": os.path.relpath(p, ROOT), "bytes": b, "sha256": sha256(p)})
    manifest = {"scope": scope, "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "source_commit": source_commit(), "n_artifacts": len(artifacts), "artifacts": artifacts}
    return manifest


SCOPES = {
    "source": ["oqlib/*.py", "config/*.json", "tests/*.py", "run_architecture.py",
               "run_declaration_drift.py", "select_drift_carrier.py", "make_oq_analysis.py",
               "make_oq_manifest.py", "make_comparator_audit.py", "validate_distributed.py",
               "run_original_question_closure.py", "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md",
               "COMPARATOR_AUDIT.md", "DISTRIBUTED_SOLVER.md", "comparator_audit.json",
               "distributed_validation.json", "SCHEMA.md", "CHANGELOG.md", "REPRODUCIBILITY.md",
               "CONFIRMATORY_SEED_MANIFEST.json"],
    "architecture": ["results/architecture_v1/raw/*.csv", "results/architecture_v1/summary.json",
                     "results/architecture_v1/architecture_headline.json",
                     "results/architecture_v1/tables/*.csv", "DRIFT_CARRIER_DECISION.json"],
    "drift": ["results/drift_v1/raw/*.csv", "results/drift_v1/summary.json",
              "results/drift_v1/drift_headline.json", "results/drift_v1/tables/*.csv"],
}
OUT = {"source": "MANIFEST_SOURCE.json", "architecture": "results/architecture_v1/EXPERIMENT_MANIFEST.json",
       "drift": "results/drift_v1/EXPERIMENT_MANIFEST.json"}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("scope", choices=list(SCOPES))
    args = ap.parse_args(argv)
    manifest = build(args.scope, SCOPES[args.scope])
    out = os.path.join(HERE, OUT[args.scope])
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(manifest, f, indent=2)
    print("%s manifest: %d artifacts -> %s" % (args.scope, manifest["n_artifacts"], os.path.relpath(out, ROOT)))


def verify(manifest_path):
    """Return list of (path, problem) for any missing or hash-mismatched artifact."""
    manifest = json.load(open(manifest_path))
    problems = []
    for art in manifest["artifacts"]:
        p = os.path.join(ROOT, art["path"])
        if not os.path.exists(p):
            problems.append((art["path"], "missing"))
        elif sha256(p) != art["sha256"]:
            problems.append((art["path"], "hash mismatch"))
    return problems


if __name__ == "__main__":
    main()
