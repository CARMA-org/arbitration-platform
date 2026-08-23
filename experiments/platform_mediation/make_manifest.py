#!/usr/bin/env python3
"""Build a provenance and SHA-256 manifest of all platform-evaluation artifacts."""
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

ARTIFACT_GLOBS = [
    "experiments/platform_mediation/results/**/*.csv",
    "experiments/platform_mediation/results/*.json",
    "experiments/platform_mediation/tables/*.csv",
    "experiments/platform_mediation/figures/*.png",
    "experiments/platform_mediation/config/*.json",
    "experiments/platform_mediation/logs/*.log",
    "experiments/platform_mediation/test_report.json",
    "experiments/platform_mediation/RESULTS_FOR_PAPER.md",
    "experiments/dynamic_allocation/results/**/*.csv",
    "experiments/dynamic_allocation/results/*.json",
    "experiments/dynamic_allocation/tables/*.csv",
    "experiments/dynamic_allocation/logs/*.log",
    "experiments/enforcement/results/*.json",
    "experiments/enforcement/results/*.csv",
]

CONFIG_FILES = [
    "experiments/platform_mediation/config/experiment.json",
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
    code = ("import json;"
            "import importlib.metadata as m;"
            "print(json.dumps({p: m.version(p) for p in "
            "['cvxpy','clarabel','numpy','scipy','pandas']}))")
    out = run([python, "-c", code])
    try:
        return json.loads(out)
    except Exception:
        return {}


def load_json(rel):
    fp = os.path.join(ROOT, "experiments", rel)
    if os.path.exists(fp):
        with open(fp) as f:
            return json.load(f)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    ap.add_argument("--source-commit", default=os.environ.get("SOURCE_COMMIT"))
    args = ap.parse_args()

    source_commit = args.source_commit or run(["git", "rev-parse", "HEAD"])
    source_tree = run(["git", "rev-parse", source_commit + "^{tree}"]) if source_commit else None

    files = []
    for pattern in ARTIFACT_GLOBS:
        for p in sorted(glob.glob(os.path.join(ROOT, pattern), recursive=True)):
            rel = os.path.relpath(p, ROOT)
            files.append({"path": rel, "sha256": sha256(p), "bytes": os.path.getsize(p)})

    config_hashes = {}
    for rel in CONFIG_FILES:
        fp = os.path.join(ROOT, rel)
        if os.path.exists(fp):
            config_hashes[rel] = sha256(fp)

    platform_summary = load_json("platform_mediation/results/summary_full.json")
    dynamic_summary = load_json("dynamic_allocation/results/summary_full.json")
    enforcement = load_json("enforcement/results/enforcement_report_full.json")
    test_report = None
    trp = os.path.join(HERE, "test_report.json")
    if os.path.exists(trp):
        with open(trp) as f:
            test_report = json.load(f)

    python_exec = run([args.solver_python, "-c", "import sys;print(sys.executable)"])

    run_counts = {
        "platform_mediation_test_runs": (platform_summary or {}).get("total_test_runs"),
        "platform_mediation_infeasible_runs": (platform_summary or {}).get("infeasible_runs"),
        "platform_mediation_seeds_per_cell": (platform_summary or {}).get("n_test_seeds_per_cell"),
        "dynamic_seeds": (dynamic_summary or {}).get("seeds"),
        "dynamic_epochs": (dynamic_summary or {}).get("epochs"),
        "enforcement_repeated_case_trials": (enforcement or {}).get("repeated_case_trials"),
    }
    test_counts = {
        "java": (test_report or {}).get("java", {}).get("totals"),
        "python": (test_report or {}).get("python"),
        "all_green": (test_report or {}).get("all_green"),
    }

    manifest = {
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source_commit": source_commit,
        "source_tree_sha1": source_tree,
        "config_hashes": config_hashes,
        "environment": {
            "python_executable": python_exec,
            "python_version": run([args.solver_python, "-c",
                                   "import platform;print(platform.python_version())"]),
            "dependencies": dep_versions(args.solver_python),
            "java_version": run(["java", "-version"], stderr=subprocess.STDOUT),
            "maven_version": (run(["mvn", "-version"]) or "").splitlines()[0] if run(["mvn", "-version"]) else None,
            "operating_system": platform.platform(),
        },
        "seed_derivation": "SHA-256 of pipe-joined string labels, low 64 bits mod 2**32; "
                           "each test seed is an independent workload draw (no calibration phase); "
                           "all seven policies share one scenario per cell and seed",
        "run_counts": run_counts,
        "test_counts": test_counts,
        "canonical_runtime_entrypoints": [
            "org.carma.arbitration.experiment.PlatformMediationHarness",
            "org.carma.arbitration.experiment.EnforcementFaultInjection",
        ],
        "experiments": {
            "platform_mediation": platform_summary,
            "dynamic_allocation": dynamic_summary,
            "enforcement": (enforcement or {}).get("totals"),
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
