#!/usr/bin/env python3
"""Build the platform-evaluation results bundle from the clean committed tree.

The bundle packages the committed source, tests, docs, configuration, the
convex solver, the experiment harnesses, and the canonical result artifacts
(raw data, tables, figures, headline, manifest, machine-readable test report and
run-completion record). It excludes version-control, build outputs, virtual
environments, caches, credential/env files, the historical noncanonical
joint-allocation results, and any nested archive (including a prior bundle).

Provenance files (SOURCE_COMMIT.txt, RESULTS_COMMIT.txt, REPOSITORY_COMMIT.txt,
BUNDLE_CONTENTS.md, LICENSE_STATUS.md) are generated here from defined inputs:
the repository HEAD, the manifest's recorded source commit, and the results
commit constant below. The zip is written deterministically (sorted entries,
commit-dated timestamps) and a sibling SHA-256 is emitted next to it.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

BUNDLE_ZIP = "platform_evaluation_results_bundle.zip"
BUNDLE_SHA = "platform_evaluation_results_bundle.sha256"
MANIFEST_REL = "experiments/platform_mediation/EXPERIMENT_MANIFEST.json"

# The revision that added the canonical generated results. Fixed provenance fact.
DEFAULT_RESULTS_COMMIT = "5f48135578682168b5ec5cbd1e1d048de2527475"

GENERATED = ("SOURCE_COMMIT.txt", "RESULTS_COMMIT.txt", "REPOSITORY_COMMIT.txt",
             "BUNDLE_CONTENTS.md", "LICENSE_STATUS.md")

# Tracked paths excluded from the bundle.
EXCLUDE_EXACT = {BUNDLE_ZIP, BUNDLE_SHA, ".env.example"}
EXCLUDE_PREFIX = (
    "experiments/joint_allocation/results/",
    "experiments/joint_allocation/tables/",
    "experiments/joint_allocation/figures/",
)
VCS_BUILD_SEGMENTS = {".git", "target", "__pycache__", ".venv", "venv",
                      "node_modules", "experiments_venv_tmp"}


def excluded(rel):
    if rel in EXCLUDE_EXACT or rel in GENERATED:
        return True
    if rel.endswith(".zip") or rel.endswith(".sha256"):
        return True
    if rel.startswith(".env"):
        return True
    if any(rel.startswith(p) for p in EXCLUDE_PREFIX):
        return True
    if any(seg in VCS_BUILD_SEGMENTS for seg in rel.split("/")):
        return True
    return False


def git(*args):
    return subprocess.check_output(["git", "-C", ROOT, *args])


def committed_files():
    out = git("ls-tree", "-r", "--name-only", "-z", "HEAD").decode()
    return [p for p in out.split("\0") if p]


def worktree_files():
    out = git("ls-files", "-z").decode()
    return [p for p in out.split("\0") if p]


def make_reader(mode):
    if mode == "worktree":
        def read(rel):
            with open(os.path.join(ROOT, rel), "rb") as f:
                return f.read()
        return read
    def read(rel):
        return git("show", "HEAD:" + rel)
    return read


def license_status():
    return (
        "# License status\n\n"
        "No open-source license is currently declared for this repository. "
        "Publication of the source and evaluation bundle does not itself grant "
        "an open-source license. Contact the repository owner for permitted "
        "uses.\n"
    )


def bundle_contents(source_commit, results_commit, repository_commit):
    return f"""# Platform Evaluation Results Bundle

A self-contained snapshot of the platform-mediation evaluation together with the
current repository source and documentation.

## The three recorded commits

- `SOURCE_COMMIT.txt` ({source_commit}) — the source revision that generated the
  canonical experiment raw data (the 2,800-run primary sweep and 40,000-row
  dynamic simulation). The manifest's `source_commit` points here.
- `RESULTS_COMMIT.txt` ({results_commit}) — the revision that added those
  generated results.
- `REPOSITORY_COMMIT.txt` ({repository_commit}) — the current cleanup revision
  this bundle is built from. It aligns legacy demos and repository claims with
  what the code and data support; it does not rerun or change the canonical raw
  results. The canonical raw data, result tables, figures, and summaries are
  byte-identical to `RESULTS_COMMIT`; only generated provenance metadata (the
  machine-readable test report and the manifest that hashes it) differ.

## Included

- `README.md`, `docs/`, and `LICENSE_STATUS.md` (a status notice, not a license
  grant).
- `src/main`, `src/test` — the runtime, contracts, services, arbitrator, the
  exact bounded-log decomposed Cobb-Douglas allocator and its tool, the
  experiment harnesses, and the full test suite.
- `scripts/joint_solver.py` — the convex solver for the four supported families.
- `experiments/platform_mediation/` — sweep driver, scenario, archetype,
  capacity-rounding and analysis code, configuration, raw per-run and per-agent
  CSVs (including `infeasible_runs.csv`), aggregate tables, figures, logs, the
  machine-readable headline, decomposition validation, the generated memo, the
  test report, the run-completion record, the manifest, and the headline, memo,
  validation, completion, claim-scan, bundle, and consistency generators.
- `experiments/dynamic_allocation/` — the secondary solver-level simulation with
  full-precision 40,000-row epoch data.
- `experiments/enforcement/` — the fault-injection driver, fake solvers, and the
  per-case results with explicit trial and operation denominators.
- `experiments/joint_allocation/` — historical experiment code only; its result
  tables, figures, summaries, and logs are excluded and are explicitly
  noncanonical. Its early constraint-saturation results motivated the narrower
  current framing but are not canonical evidence for a general joint advantage.
- `tests/python/`, `pom.xml`, `.github/` CI.

## Excluded

`.git`, `.env` and credentials, virtual environments, build directories,
`target`, compiled classes, `__pycache__`, caches, nested archives, historical
joint-allocation results, smoke and temporary outputs, and any prior bundle. The
manifest enumerates and hashes the result artifacts it lists, not every file in
this bundle.

## Reproduction

Requirements: Java 21, Maven 3.9, and a Python 3.12 interpreter with cvxpy 1.5.3,
clarabel 0.9.0, numpy 1.26.4, scipy 1.13.1, pandas 2.2.2, matplotlib 3.9.2. Point
`SOLVER_PYTHON` at that interpreter.

    export SOLVER_PYTHON=/path/to/python-with-cvxpy
    mvn -o clean test
    $SOLVER_PYTHON -m pytest tests/python -q
    mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
    # Primary sweep alone (no other solver-heavy work concurrent) for clean latency
    cd experiments/platform_mediation && python3 run_sweep.py --full
    cd ../enforcement && python3 run_enforcement.py --reps 100
    cd ../dynamic_allocation && python3 run_dynamic.py --full
    cd ../platform_mediation
    python3 validate_decomposition.py
    python3 make_headline.py && python3 make_memo.py && python3 figures.py
    python3 make_test_report.py && python3 make_completion_record.py
    python3 make_manifest.py --source-commit "$(cat ../../SOURCE_COMMIT.txt)"
    python3 claim_scan.py
    python3 check_consistency.py --with-manifest

## Headline finding

Each seed is an independent workload draw. Mixed-bundle completion: a linear
declaration completes far less bundle-structured work (about 0.05-0.10) than
Cobb-Douglas, CES, or Leontief (about 0.52-0.76). The nonlinear joint policies
beat linear by about +0.57, and beat equal quotas and standard DRF by small
margins (about +0.03 and +0.005 to +0.009 respectively); equal quotas and DRF are
strong comparators. The homogeneous composition is a symmetry check with a small
tie-breaking spread. The exact decomposed Cobb-Douglas solver matches the joint
solver's continuous solution up to the solver's numerical accuracy; installed
integers can differ by a unit from independent rounding, and few run-level
completion outcomes differ. Some individual agents do worse than under equal
quotas; no Pareto improvement is claimed. Full statement is in
`experiments/platform_mediation/RESULTS_FOR_PAPER.md`.
"""


def build(mode, results_commit):
    read = make_reader(mode)
    repo_commit = git("rev-parse", "HEAD").decode().strip()
    files = committed_files() if mode == "head" else worktree_files()

    source_commit = json.loads(read(MANIFEST_REL).decode())["source_commit"]

    payload = {}
    for rel in files:
        if excluded(rel):
            continue
        payload[rel] = read(rel)

    payload["SOURCE_COMMIT.txt"] = (source_commit + "\n").encode()
    payload["RESULTS_COMMIT.txt"] = (results_commit + "\n").encode()
    payload["REPOSITORY_COMMIT.txt"] = (repo_commit + "\n").encode()
    payload["LICENSE_STATUS.md"] = license_status().encode()
    payload["BUNDLE_CONTENTS.md"] = bundle_contents(
        source_commit, results_commit, repo_commit).encode()

    # Deterministic zip: sorted entries, commit-dated timestamps.
    commit_epoch = int(git("show", "-s", "--format=%ct", "HEAD").decode().strip())
    import time
    dt = time.gmtime(commit_epoch)
    date_time = (dt.tm_year, dt.tm_mon, dt.tm_mday, dt.tm_hour, dt.tm_min, dt.tm_sec)

    zip_path = os.path.join(ROOT, BUNDLE_ZIP)
    sha_path = os.path.join(ROOT, BUNDLE_SHA)
    for p in (zip_path, sha_path):
        if os.path.exists(p):
            os.remove(p)

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel in sorted(payload):
            info = zipfile.ZipInfo(rel, date_time=date_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, payload[rel])

    digest = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
    with open(sha_path, "w") as f:
        f.write("%s  %s\n" % (digest, BUNDLE_ZIP))

    return zip_path, sha_path, digest, len(payload)


def validate(zip_path, sha_path):
    subprocess.check_call(["unzip", "-tqq", zip_path])
    tracked = git("ls-files").decode().split("\n")
    zips = [t for t in tracked if t.endswith(".zip")]
    shas = [t for t in tracked if t.endswith(".sha256")]
    # After the replacement commit exactly one of each must be tracked; before it
    # the old pair is still tracked. Either way there must be no more than one.
    if len(zips) > 1 or len(shas) > 1:
        raise SystemExit("multiple tracked archives/checksums: %s %s" % (zips, shas))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="mode", choices=("head", "worktree"),
                    default="head", help="build from committed HEAD (default) or working tree")
    ap.add_argument("--results-commit", default=DEFAULT_RESULTS_COMMIT)
    args = ap.parse_args()

    zip_path, sha_path, digest, n = build(args.mode, args.results_commit)
    validate(zip_path, sha_path)
    size = os.path.getsize(zip_path)
    print("bundle: %s" % zip_path)
    print("entries: %d" % n)
    print("bytes: %d" % size)
    print("sha256: %s" % digest)
    print("checksum file: %s" % sha_path)


if __name__ == "__main__":
    main()
