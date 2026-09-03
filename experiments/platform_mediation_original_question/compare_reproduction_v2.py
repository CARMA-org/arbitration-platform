#!/usr/bin/env python3
"""Deterministic-analysis reproduction for verification v2.

Rather than claim a second full clean experiment rerun (the first verification already
supplies a full clean-clone rerun with exact equality of all non-timing raw fields), this
strengthens the OUTPUT comparison: it copies the frozen raw data into a temporary tree and
reruns ONLY the deterministic analyses over it -- the architecture and drift analyses, the
carrier selection, the preregistered secondary drift completion, and the manifests -- then
compares the regenerated

    * headline JSON            (architecture_headline.json, drift_headline.json)
    * result tables            (results/*/tables/*.csv)
    * summaries                (results/*/summary.json)
    * carrier decision         (DRIFT_CARRIER_DECISION.json)
    * supplemental secondary   (results/drift_v1/preregistered_secondary_completion/*)
    * manifests                (EXPERIMENT_MANIFEST.json x2, CORRECTION_MANIFEST.json)

against the committed frozen outputs.

Documented, explicitly-excluded environment-dependent fields (values still reproduce
exactly; only serialization/environment metadata differs):
  * JSON headlines are compared SEMANTICALLY (parsed equality). ``drift_headline.json`` is
    not byte-identical because its ``arm_tasks_per_run`` block is built by iterating a Python
    ``set`` of arm names, whose order depends on ``PYTHONHASHSEED``; every value is identical.
    ``architecture_headline.json`` is additionally byte-identical.
  * manifest ``generated_utc`` and ``source_commit`` (a timestamp and the git HEAD);
  * within a regenerated manifest's artifact list, the entries for the two
    ``EXPERIMENT_MANIFEST.json`` files (each carries a fresh ``generated_utc``) and for
    ``drift_headline.json`` (the hash-seed key order above); and the correction manifest's
    git-derived invariance recompute (which needs a git checkout).

If an original clean clone from the first verification still exists on disk, its raw tables
are additionally diffed (non-timing fields only). If it does not, v2 relies on the preserved
exact-raw reproduction evidence from the first verification plus this fresh deterministic
analysis reconstruction. No experiment or Java run is performed here.

Usage: compare_reproduction_v2.py [--clone DIR]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
EXPERIMENTS = os.path.abspath(os.path.join(HERE, ".."))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FROZEN_PREREG_COMMIT = "7ebf8b70366b8b68a90554a722f097d8acea3f01"
PY = os.environ.get("SOLVER_PYTHON", sys.executable)

# artifact paths (basenames) excluded from regenerated-manifest artifact comparison, because
# their serialization is timestamp- or hash-seed-dependent (values reproduce exactly).
MANIFEST_ARTIFACT_EXCLUDE_BASENAMES = {"EXPERIMENT_MANIFEST.json", "drift_headline.json"}


def run(cmd, cwd, env):
    subprocess.check_call(cmd, cwd=cwd, env=env, stdout=subprocess.DEVNULL)


def byte_equal(a, b):
    return open(a, "rb").read() == open(b, "rb").read()


def json_semantic_equal(a, b):
    return json.load(open(a)) == json.load(open(b))


def manifest_artifacts_equal(a, b):
    """Compare two manifests' artifact lists by path, on (bytes, sha256), excluding entries
    whose serialization is environment-dependent (see module docstring)."""
    def idx(p):
        m = json.load(open(p))
        return {art["path"]: (art["bytes"], art["sha256"]) for art in m["artifacts"]}
    ia, ib = idx(a), idx(b)
    if set(ia) != set(ib):
        return False
    for path in ia:
        if os.path.basename(path) in MANIFEST_ARTIFACT_EXCLUDE_BASENAMES:
            continue
        if ia[path] != ib[path]:
            return False
    return True


def build_temp_and_reanalyze():
    tmp = tempfile.mkdtemp(prefix="oq_repro_v2_")
    dst = os.path.join(tmp, "experiments", "platform_mediation_original_question")
    shutil.copytree(HERE, dst, ignore=shutil.ignore_patterns("__pycache__", ".pytest_cache", "*.pyc"))
    # make lib/pilotlib importable exactly as oqlib expects (siblings under experiments/)
    os.symlink(os.path.join(EXPERIMENTS, "platform_mediation"),
               os.path.join(tmp, "experiments", "platform_mediation"))
    os.symlink(os.path.join(EXPERIMENTS, "platform_mediation_heterogeneity"),
               os.path.join(tmp, "experiments", "platform_mediation_heterogeneity"))
    env = dict(os.environ, OQ_PREREG_COMMIT=FROZEN_PREREG_COMMIT, PYTHONDONTWRITEBYTECODE="1")
    # rerun the deterministic analyses (no experiment/Java run)
    run([PY, "make_oq_analysis.py", "architecture"], dst, env)
    run([PY, "make_oq_analysis.py", "drift"], dst, env)
    run([PY, "select_drift_carrier.py"], dst, env)
    run([PY, "complete_drift_secondary.py"], dst, env)
    run([PY, "make_oq_manifest.py", "architecture"], dst, env)
    run([PY, "make_oq_manifest.py", "drift"], dst, env)
    run([PY, "make_correction_manifest.py"], dst, env)
    return tmp, dst


def compare(dst):
    results = []
    # JSON analysis outputs: semantic (parsed) equality
    SEMANTIC = [
        "results/architecture_v1/architecture_headline.json",
        "results/drift_v1/drift_headline.json",
        "results/architecture_v1/summary.json",
        "results/drift_v1/summary.json",
        "DRIFT_CARRIER_DECISION.json",
        "results/drift_v1/preregistered_secondary_completion/drift_secondary_completion.json",
    ]
    for rel in SEMANTIC:
        a, b = os.path.join(HERE, rel), os.path.join(dst, rel)
        ok = os.path.exists(b) and json_semantic_equal(a, b)
        note = "semantic-equal" + (", byte-identical" if ok and byte_equal(a, b) else "")
        results.append((rel, note, ok))
    # CSV / textual outputs: byte-identical
    BYTE = [
        "results/architecture_v1/tables/cell_arm_means.csv",
        "results/architecture_v1/tables/paired_comparisons.csv",
        "results/drift_v1/tables/drift_response.csv",
        "results/drift_v1/preregistered_secondary_completion/drift_secondary_completion.csv",
    ]
    for rel in BYTE:
        a, b = os.path.join(HERE, rel), os.path.join(dst, rel)
        ok = os.path.exists(b) and byte_equal(a, b)
        results.append((rel, "byte-identical", ok))
    # manifests: artifact (path, bytes, sha256) identical except documented env-dependent entries
    for rel in ("results/architecture_v1/EXPERIMENT_MANIFEST.json", "results/drift_v1/EXPERIMENT_MANIFEST.json",
                "CORRECTION_MANIFEST.json"):
        a, b = os.path.join(HERE, rel), os.path.join(dst, rel)
        ok = os.path.exists(b) and manifest_artifacts_equal(a, b)
        results.append((rel, "artifacts-identical (excl generated_utc/source_commit, EXPERIMENT_MANIFEST + drift_headline entries)", ok))
    return results


def compare_clone_raw(clone):
    """Optional: diff raw tables of a leftover clean clone (non-timing fields only)."""
    TIMING = {"runtime_ms", "alloc_latency_ms"}
    import csv
    tables = {
        "results/architecture_v1/raw": ["scenarios.csv", "runs.csv", "agents.csv", "distributed.csv", "infeasible.csv"],
        "results/drift_v1/raw": ["scenarios.csv", "runs.csv", "agents.csv", "declarations.csv", "distributed.csv", "infeasible.csv"],
    }
    out = []
    coq = os.path.join(clone, "experiments", "platform_mediation_original_question")
    for sub, files in tables.items():
        for t in files:
            pa, pb = os.path.join(HERE, sub, t), os.path.join(coq, sub, t)
            if not (os.path.exists(pa) and os.path.exists(pb)):
                out.append(("%s/%s" % (sub, t), "clone-raw", None))
                continue
            ra = list(csv.DictReader(open(pa)))
            rb = list(csv.DictReader(open(pb)))
            ok = len(ra) == len(rb) and all(
                all(x.get(k) == y.get(k) for k in x if k not in TIMING) for x, y in zip(ra, rb))
            out.append(("%s/%s" % (sub, t), "clone-raw (non-timing)", ok))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--clone", default=None, help="path to a leftover clean clone repo root")
    args = ap.parse_args(argv)
    tmp, dst = build_temp_and_reanalyze()
    try:
        results = compare(dst)
        clone_note = "no clean clone supplied; relying on preserved v1 exact-raw reproduction + this deterministic reconstruction"
        if args.clone and os.path.isdir(args.clone):
            results += compare_clone_raw(args.clone)
            clone_note = "compared against clean clone at %s" % args.clone
        ok = all(r[2] for r in results if r[2] is not None)
        print("Deterministic-analysis reproduction (v2)")
        print("  temp tree:", dst)
        print("  clone:", clone_note)
        for rel, kind, res in results:
            tag = "OK  " if res else ("--  " if res is None else "FAIL")
            print("  [%s] %s  (%s)" % (tag, rel, kind))
        print("RESULT:", "REPRODUCED (all compared outputs match)" if ok else "MISMATCH")
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
