#!/usr/bin/env python3
"""Build a provenance and SHA-256 manifest of the heterogeneity-pilot artifacts.

Hashes only files under ``experiments/platform_mediation_heterogeneity/``; it does
not touch or reference the canonical evaluation bundle."""
import argparse
import glob
import hashlib
import json
import os
import platform
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
REL = os.path.relpath(HERE, ROOT)
# Immutable canonical-evaluation base commit (former platform-evaluation tip; reachable in
# main's history) so this provenance utility keeps working in a fresh clone after that branch
# is deleted. Overridable via --canonical-base-commit or CANONICAL_BASE_COMMIT.
CANONICAL_BASE_DEFAULT = "bfab534bba977d5f7c40b0407b83036b38dfbf4a"

ARTIFACT_GLOBS = [
    "config/*.json",
    "pilotlib/*.py",
    "*.py",
    "tests/*.py",
    "results/*.json",
    "results/*.md",
    "results/raw/*.csv",
    "tables/*.csv",
    "logs/*.log",
    "BASELINE_DIAGNOSTIC.md",
    "PILOT_RESULTS.md",
    "NEXT_EXPERIMENT_DECISION.md",
    "DECLARATION_STALENESS_DESIGN.md",
    "REPRODUCIBILITY.md",
    "CHANGELOG.md",
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, **kw):
    kw.setdefault("stderr", subprocess.DEVNULL)
    try:
        return subprocess.check_output(cmd, cwd=ROOT, **kw).decode().strip()
    except Exception:
        return None


def dep_versions(python):
    code = ("import json, importlib.metadata as m;"
            "print(json.dumps({p: m.version(p) for p in "
            "['cvxpy','clarabel','numpy','scipy','pandas']}))")
    out = run([python, "-c", code])
    try:
        return json.loads(out)
    except Exception:
        return {}


def load_json(rel):
    fp = os.path.join(HERE, rel)
    if os.path.exists(fp):
        with open(fp) as f:
            return json.load(f)
    return None


def resolve_canonical_base(explicit=None):
    """Resolve the canonical-evaluation base commit without depending on the
    ``platform-evaluation`` branch (removed after consolidation). Priority: explicit
    arg / ``CANONICAL_BASE_COMMIT`` env; the immutable default commit if reachable; the branch
    if it still resolves; else the default string."""
    v = explicit or os.environ.get("CANONICAL_BASE_COMMIT")
    if v:
        return v
    if run(["git", "rev-parse", "--verify", "--quiet", CANONICAL_BASE_DEFAULT + "^{commit}"]):
        return CANONICAL_BASE_DEFAULT
    return run(["git", "rev-parse", "origin/platform-evaluation"]) or CANONICAL_BASE_DEFAULT


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    ap.add_argument("--source-commit", default=os.environ.get("SOURCE_COMMIT"))
    ap.add_argument("--canonical-base-commit", default=None,
                    help="canonical-evaluation base commit; defaults to the immutable recorded base "
                         "so this works after the platform-evaluation branch is deleted")
    args = ap.parse_args()

    source_commit = args.source_commit or run(["git", "rev-parse", "HEAD"])
    canonical_base = resolve_canonical_base(args.canonical_base_commit)

    seen = set()
    files = []
    for pattern in ARTIFACT_GLOBS:
        for p in sorted(glob.glob(os.path.join(HERE, pattern))):
            rp = os.path.abspath(p)
            if rp in seen or not os.path.isfile(rp):
                continue
            seen.add(rp)
            files.append({"path": os.path.relpath(rp, ROOT), "sha256": sha256(rp),
                          "bytes": os.path.getsize(rp)})

    manifest = {
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "pilot": "platform_mediation_heterogeneity",
        "source_commit": source_commit,
        "canonical_base_commit": canonical_base,
        "config_hash": sha256(os.path.join(HERE, "config", "pilot.json")),
        "environment": {
            "python_version": run([args.solver_python, "-c", "import platform;print(platform.python_version())"]),
            "dependencies": dep_versions(args.solver_python),
            "java_version": run(["java", "-version"], stderr=subprocess.STDOUT),
            "maven_version": (run(["mvn", "-version"]) or "").splitlines()[0]
                             if run(["mvn", "-version"]) else None,
            "operating_system": platform.platform(),
        },
        "seed_derivation": "SHA-256 of pipe-joined labels, low 64 bits mod 2**32; 30 development "
                           "workload seeds from label 'heterogeneity_pilot' (disjoint from the "
                           "canonical '*__* test' seeds); per-agent numpy Generators for Dirichlet "
                           "draws; the same task workload is used at both contention levels",
        "declaration_source": "exact_pending_queue",
        "canonical_runtime_entrypoint": "org.carma.arbitration.experiment.PlatformMediationHarness",
        "summaries": {
            "workload": load_json("results/summary_workload.json"),
            "floor": load_json("results/summary_floor.json"),
        },
        "artifact_count": len(files),
        "artifacts": files,
    }
    out = os.path.join(HERE, "EXPERIMENT_MANIFEST.json")
    with open(out, "w") as f:
        json.dump(manifest, f, indent=2)
    print("wrote", out, "with", len(files), "artifacts; source_commit=", source_commit)


if __name__ == "__main__":
    main()
