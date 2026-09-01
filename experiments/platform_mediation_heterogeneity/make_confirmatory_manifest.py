#!/usr/bin/env python3
"""SHA-256 provenance manifest for the confirmatory-v1 artifacts.

Hashes the confirmatory configuration, driver/analysis code, raw records, tables,
headline, and reports. Does not touch the canonical evaluation bundle or the
exploratory pilot manifest."""
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
    "config/confirmatory_v1.json",
    "run_confirmatory.py",
    "make_confirmatory_analysis.py",
    "make_confirmatory_manifest.py",
    "pilotlib/local_opt.py",
    "CONFIRMATORY_PROTOCOL.md",
    "CONFIRMATORY_RESULTS.md",
    "CONFIRMATORY_DECISION.md",
    "results/confirmatory_v1/*.json",
    "results/confirmatory_v1/raw/*.csv",
    "results/confirmatory_v1/tables/*.csv",
    "logs/run_confirmatory_v1.log",
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
            "print(json.dumps({p: m.version(p) for p in ['cvxpy','clarabel','numpy','scipy','pandas']}))")
    out = run([python, "-c", code])
    try:
        return json.loads(out)
    except Exception:
        return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solver-python", default=os.environ.get("SOLVER_PYTHON", "python3"))
    ap.add_argument("--source-commit", default=os.environ.get("SOURCE_COMMIT"))
    ap.add_argument("--prereg-commit", default=os.environ.get("PREREG_COMMIT"))
    args = ap.parse_args()

    source_commit = args.source_commit or run(["git", "rev-parse", "HEAD"])
    summary_path = os.path.join(HERE, "results", "confirmatory_v1", "summary.json")
    summary = json.load(open(summary_path)) if os.path.exists(summary_path) else None

    seen, files = set(), []
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
        "experiment": "platform_mediation_heterogeneity/confirmatory_v1",
        "source_commit": source_commit,
        "preregistration_commit": args.prereg_commit,
        "canonical_base_commit": run(["git", "rev-parse", "origin/platform-evaluation"]),
        "config_hash": sha256(os.path.join(HERE, "config", "confirmatory_v1.json")),
        "environment": {
            "python_version": run([args.solver_python, "-c", "import platform;print(platform.python_version())"]),
            "dependencies": dep_versions(args.solver_python),
            "java_version": run(["java", "-version"], stderr=subprocess.STDOUT),
            "operating_system": platform.platform(),
        },
        "seed_derivation": "SHA-256 of pipe-joined labels, low 64 bits mod 2**32; 200 confirmatory "
                           "seeds from label 'heterogeneity_confirmatory_v1' (disjoint from canonical "
                           "and exploratory seeds and task-workload hashes; asserted in the driver)",
        "canonical_runtime_entrypoint": "org.carma.arbitration.experiment.PlatformMediationHarness",
        "summary": summary,
        "artifact_count": len(files),
        "artifacts": files,
    }
    out = os.path.join(HERE, "results", "confirmatory_v1", "EXPERIMENT_MANIFEST.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(manifest, f, indent=2)
    print("wrote", out, "with", len(files), "artifacts; source_commit=", source_commit,
          "prereg=", args.prereg_commit)


if __name__ == "__main__":
    main()
