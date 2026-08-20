#!/usr/bin/env python3
"""Build a SHA-256 manifest of all platform-evaluation artifacts."""
import argparse
import glob
import hashlib
import json
import os
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
    "experiments/dynamic_allocation/results/**/*.csv",
    "experiments/dynamic_allocation/results/*.json",
    "experiments/dynamic_allocation/tables/*.csv",
    "experiments/dynamic_allocation/logs/*.log",
    "experiments/enforcement/results/*.json",
    "experiments/enforcement/results/*.csv",
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def git_commit():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    args = ap.parse_args()

    files = []
    for pattern in ARTIFACT_GLOBS:
        for p in sorted(glob.glob(os.path.join(ROOT, pattern), recursive=True)):
            rel = os.path.relpath(p, ROOT)
            files.append({"path": rel, "sha256": sha256(p), "bytes": os.path.getsize(p)})

    summaries = {}
    for name in ("platform_mediation/results/summary_full.json",
                 "dynamic_allocation/results/summary_full.json",
                 "enforcement/results/enforcement_report_full.json"):
        fp = os.path.join(ROOT, "experiments", name)
        if os.path.exists(fp):
            with open(fp) as f:
                summaries[name] = json.load(f)

    manifest = {
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "git_commit": git_commit(),
        "solver_python": args.solver_python,
        "canonical_runtime_entrypoints": [
            "org.carma.arbitration.experiment.PlatformMediationHarness",
            "org.carma.arbitration.experiment.EnforcementFaultInjection",
        ],
        "experiments": {
            "platform_mediation": summaries.get("platform_mediation/results/summary_full.json"),
            "dynamic_allocation": summaries.get("dynamic_allocation/results/summary_full.json"),
            "enforcement": (summaries.get("enforcement/results/enforcement_report_full.json") or {}).get("totals"),
        },
        "artifact_count": len(files),
        "artifacts": files,
    }
    out = os.path.join(HERE, "EXPERIMENT_MANIFEST.json")
    with open(out, "w") as f:
        json.dump(manifest, f, indent=2)
    print("wrote", out, "with", len(files), "artifacts")


if __name__ == "__main__":
    main()
